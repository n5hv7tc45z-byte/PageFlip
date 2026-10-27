package com.pageflip.remote;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指令分发器：单例，串行发送，UDP 优先、HTTP 回退。
 *
 * 三个不变量：
 * 1. **会话代次**：每次 start/stop 递增 generation，排队任务在执行前比对代次，
 *    代次不符直接丢弃 —— 停止后不会有旧指令继续发出。
 * 2. **命令唯一**：sessionId（每次进程启动随机）+ 递增 seq 构成命令 ID，
 *    进程重启后 ID 不会与上一轮的记录碰撞。
 * 3. **一次执行**：UDP 失败后用同一个命令 ID 走 HTTP，服务端按 ID 去重并回放结果。
 */
public final class Dispatcher {

    public interface Listener {
        /** via = "UDP" / "HTTP" / "AUTH"；ok=false 表示未送达、被拒或注入失败 */
        void onResult(String action, boolean ok, double rttMs, String via);
    }

    private static final long DEDUP_MS = 120;
    private static final int UDP_TIMEOUT_MS = 150;
    private static final int MAX_PENDING = 8;
    private static final long CMD_TTL_MS = 1500;
    private static final long LOCK_MINUTES = 30;

    private static Dispatcher instance;

    public static synchronized Dispatcher get(Context ctx) {
        if (instance == null) {
            instance = new Dispatcher(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetForTest() {
        instance = null;
    }

    private final Context app;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();

    /** 每次进程启动都不同：进程重启后不会与上一轮的去重记录碰撞。 */
    private final String sessionId;

    /** 会话代次：start/stop 时递增，让所有已排队任务作废。 */
    private volatile int generation = 0;
    private volatile boolean closed = false;

    private String host = "";
    private int port = 8000;
    private String token = "";

    private DatagramSocket udpSock;
    private volatile HttpURLConnection inflight;
    private PowerManager.WakeLock cpuLock;

    private volatile Listener listener;
    private String lastAction = "";
    private long lastAt = 0;

    private Dispatcher(Context c) {
        this.app = c;
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    // ---- 配置读写（Activity 与无障碍服务共用） ----------------------------

    public static final String PREFS = "pageflip";
    public static final String KEY_SERVER = "server";
    public static final String KEY_FALLBACK = "fallback";
    public static final String KEY_TOKEN = "pair_token";
    public static final String KEY_CLIENT = "client_id";
    public static final String KEY_ACTIVE = "remote_active";
    public static final String KEY_LOCKSCREEN = "lock_screen_want";

    public static String savedServer(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVER, "");
        return s == null ? "" : s;
    }

    public static void saveServer(Context c, String value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SERVER, value).apply();
    }

    public static String savedToken(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "");
        return s == null ? "" : s;
    }

    public static void saveToken(Context c, String value) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, value).apply();
    }

    public static boolean fallbackEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FALLBACK, true);
    }

    public static void setFallback(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FALLBACK, on).apply();
    }

    /** 遥控会话是否进行中。按键处理、后台服务都以此为准。 */
    public static boolean isActive(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false);
    }

    public static void setActive(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, on).apply();
    }

    /**
     * 用户是否希望启用「锁屏遥控」。
     * 与 isActive 分开：正在遥控 ≠ 希望开无障碍。用户主动关掉后不得被自愈重开。
     */
    public static boolean lockScreenWanted(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCKSCREEN, false);
    }

    public static void setLockScreenWanted(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LOCKSCREEN, on).apply();
    }

    /** 安装级标识，仅用于日志；去重键用的是 sessionId。 */
    public static String clientId(Context c) {
        String id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLIENT, "");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CLIENT, id).apply();
        }
        return id;
    }

    /** "http://1.2.3.4:8000" -> {"1.2.3.4", "8000"} */
    public static String[] parseServer(String url) {
        String h = url == null ? "" : url.trim();
        int p = 8000;
        if (h.startsWith("http://")) h = h.substring(7);
        else if (h.startsWith("https://")) h = h.substring(8);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.lastIndexOf(':');
        if (colon > 0) {
            try {
                p = Integer.parseInt(h.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
            h = h.substring(0, colon);
        }
        return new String[]{h, String.valueOf(p)};
    }

    public static void applySaved(Context c) {
        String saved = savedServer(c);
        if (saved.isEmpty()) return;
        String[] hp = parseServer(saved);
        get(c).configure(hp[0], Integer.parseInt(hp[1]), savedToken(c));
    }

    // ---- 运行时配置 ------------------------------------------------------

    public synchronized void configure(String h, int p, String tk) {
        String newToken = tk == null ? "" : tk;
        if (!h.equals(host) || p != port || !newToken.equals(token)) {
            generation++; // 切换目标时，旧配置排队或正在解析 DNS 的命令也必须作废。
            this.host = h;
            this.port = p;
            this.token = newToken;
            closeSocket();
        }
    }

    public boolean isConfigured() {
        return host != null && !host.isEmpty();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String sessionId() {
        return sessionId;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ---- 发送 ------------------------------------------------------------

    public boolean send(final String action) {
        if (!isConfigured() || closed) return false;
        if (!isActive(app)) return false;

        long now = System.currentTimeMillis();
        if (action.equals(lastAction) && now - lastAt < DEDUP_MS) {
            return false;
        }
        lastAction = action;
        lastAt = now;

        final int mySeq = seq.incrementAndGet();
        final int myGen = generation;
        final long born = now;

        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            return false;
        }
        try {
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 停止会话后，排队中的旧指令一律丢弃
                        if (myGen != generation || closed) return;
                        if (!isActive(app)) return;
                        if (System.currentTimeMillis() - born > CMD_TTL_MS) return;
                        dispatch(action, mySeq, myGen);
                    } finally {
                        decPending();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            decPending();
            return false;
        }
        return true;
    }

    /** 计数只由任务自身维护平衡，任何路径都不做「强制归零」。 */
    private void decPending() {
        while (true) {
            int cur = pending.get();
            int next = cur > 0 ? cur - 1 : 0;
            if (pending.compareAndSet(cur, next)) return;
        }
    }

    private void dispatch(String action, int mySeq, int myGen) {
        long t0 = System.nanoTime();
        String status = sendUdp(action, mySeq, myGen);

        if (myGen != generation) return;   // UDP 期间被停止，不再回退

        if ("ok".equals(status) || "dup".equals(status)) {
            emit(action, true, (System.nanoTime() - t0) / 1_000_000.0, "UDP");
            return;
        }
        if ("auth".equals(status)) {
            emit(action, false, 0, "AUTH");
            return;
        }

        // 用同一个命令 ID 走 HTTP：服务端会回放这次执行的真实结果
        int r = sendHttp(action, mySeq, myGen);   // 1=成功 0=失败 -1=未确定/已取消
        if (myGen != generation) return;
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        if (r < 0) {
            emit(action, false, ms, "PENDING");   // 服务端仍在执行，不能算失败
        } else {
            emit(action, r > 0, ms, "HTTP");
        }
    }

    private void emit(String action, boolean ok, double ms, String via) {
        Listener l = listener;
        if (l != null) l.onResult(action, ok, ms, via);
    }

    private InetAddress resolve(String targetHost) {
        try {
            // InetAddress 本身已有 DNS 缓存，避免旧会话解析结果污染新目标的缓存。
            return InetAddress.getByName(targetHost);
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回 "ok" / "dup" / "auth" / "err" */
    private String sendUdp(String action, int mySeq, int myGen) {
        DatagramSocket socket = null;
        try {
            final String targetHost;
            final int targetPort;
            final String targetToken;
            synchronized (this) {
                if (closed || myGen != generation) return "err";
                targetHost = host;
                targetPort = port;
                targetToken = token;
            }
            InetAddress a = resolve(targetHost);
            if (a == null) return "err";
            synchronized (this) {
                // DNS 可能阻塞超过 shutdown() 的等待上限；恢复后不能再发旧命令。
                // 与 shutdown/configure 共用锁，检查和发送之间不能插入会话终止。
                if (closed || myGen != generation) return "err";
                if (udpSock == null || udpSock.isClosed()) {
                    udpSock = new DatagramSocket();
                    udpSock.setSoTimeout(UDP_TIMEOUT_MS);
                }
                socket = udpSock;
                String tk = (targetToken == null || targetToken.isEmpty()) ? "-" : targetToken;
                byte[] out = ("PFD1 " + tk + " " + sessionId + " " + mySeq + " " + action)
                        .getBytes("US-ASCII");
                socket.send(new DatagramPacket(out, out.length, a, targetPort));
            }

            byte[] buf = new byte[128];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);

            // 回执必须来自我们瞄准的那台主机和端口，且序号一致
            if (in.getPort() != targetPort || !in.getAddress().equals(a)) return "err";
            String[] parts = new String(in.getData(), 0, in.getLength(), "US-ASCII")
                    .trim().split("\\s+");
            if (parts.length < 4 || !"PFD1".equals(parts[0]) || !"ack".equals(parts[1])) {
                return "err";
            }
            if (Integer.parseInt(parts[2]) != mySeq) return "err";
            return parts[3];
        } catch (Exception e) {
            synchronized (this) {
                if (udpSock == socket) closeSocket();
            }
            return "err";
        }
    }

    /** 返回 1=成功 / 0=失败 / -1=服务端仍在处理或本次已取消 */
    private int sendHttp(String action, int mySeq, int myGen) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://" + host + ":" + port + "/api/cmd");
            c = (HttpURLConnection) url.openConnection();
            inflight = c;                      // 供 shutdown() 主动打断
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-PF-Token", token);
            byte[] body = ("{\"action\":\"" + action + "\",\"c\":\"" + sessionId
                    + "\",\"s\":" + mySeq + "}").getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);

            // 登记之后、开始建连之前：会话已结束就根本不发起
            if (closed || myGen != generation) return -1;

            OutputStream os = c.getOutputStream();   // 这一步才真正建立连接

            // 建连可能耗时，期间可能被 stop。disconnect() 覆盖不到「已登记未建连」的窗口，
            // 所以写正文前必须再确认一次，已取消就一个字节都不发。
            if (closed || myGen != generation) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
                return -1;
            }

            os.write(body);
            os.close();

            if (c.getResponseCode() != 200) return 0;
            InputStream is = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[256];
            int n;
            while ((n = is.read(buf)) > 0 && sb.length() < 512) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            is.close();
            String resp = sb.toString();
            if (resp.contains("\"pending\": true") || resp.contains("\"pending\":true")) {
                return -1;
            }
            return (resp.contains("\"ok\": true") || resp.contains("\"ok\":true")) ? 1 : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            inflight = null;
            if (c != null) c.disconnect();
        }
    }

    private void closeSocket() {
        try {
            if (udpSock != null) udpSock.close();
        } catch (Exception ignored) {
        }
        udpSock = null;
    }

    // ---- 资源 ------------------------------------------------------------

    public void acquireCpu() {
        try {
            if (cpuLock == null) {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pageflip:cpu");
                    cpuLock.setReferenceCounted(false);
                }
            }
            if (cpuLock != null && !cpuLock.isHeld()) {
                cpuLock.acquire(LOCK_MINUTES * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
    }

    public void releaseAll() {
        try {
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        } catch (Exception ignored) {
        }
        closeSocket();
    }

    /**
     * 停止遥控：递增代次让所有排队任务作废，等在途任务自然结束，
     * 不做 pending=0 那种暴力归零（会导致计数变负、破坏后续背压）。
     */
    public void shutdown() {
        synchronized (this) {
            closed = true;
            generation++;
            closeSocket(); // 立即打断等待 UDP 回执的任务。
        }

        // 打断卡在建连/等响应阶段的 HTTP 请求，否则恢复网络后仍会提交正文
        HttpURLConnection c = inflight;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }

        long deadline = System.currentTimeMillis() + 500;
        while (pending.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        releaseAll();
    }

    /**
     * 新会话开始。
     * 注意：不重置 pending —— 每个任务的 increment 与 finally 里的 decrement 自成平衡，
     * 强行归零会让上一轮未结束的任务随后递减，把计数打成负数。
     */
    public synchronized void reopen() {
        generation++;
        closed = false;
    }
}
