package com.pageflip.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * 翻页器 · 手机端
 *
 * 音量上 = 上一页，音量下 = 下一页。
 * 开启无障碍服务后，锁屏状态下同样生效。
 */
public class MainActivity extends Activity implements Dispatcher.Listener {

    private static final int BG = 0xFF0A0D13;
    private static final int CARD = 0xFF16202F;
    private static final int TXT = 0xFFE8EDF7;
    private static final int DIM = 0xFF6B7788;
    private static final int ACCENT = 0xFF4D8DFF;
    private static final int OK = 0xFF2FBF71;
    private static final int BAD = 0xFFFF5D5D;
    private static final int WARN = 0xFFFFB84D;
    private static final int REQ_SCAN = 2002;

    private WebView web;
    private TextView dot;
    private TextView statusText;
    private TextView statusRow;
    private EditText inputAddr;
    private EditText inputToken;
    private FrameLayout remoteRoot;
    private View blackoutView;
    private boolean blackout = false;
    private long blackoutTapAt = 0;
    private String server = "";
    private boolean remoteShown = false;

    private String lastVia;
    private double lastRtt;

    /** 供无障碍服务判断：主界面在前台时不要抢音量键。 */
    private static volatile boolean foreground = false;

    public static boolean isForeground() {
        return foreground;
    }

    /** 应用内任一界面（主界面或扫码页）在前台。无障碍与兼容监听都以它为准，否则扫码页会被当成后台遥控场景。 */
    public static boolean isAppForeground() {
        return foreground || ScanActivity.isResumed();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestNotifPermission();
        if (Dispatcher.isActive(this)) {
            KeepAliveService.start(this);
        }
        Dispatcher.applySaved(this);
        server = Dispatcher.savedServer(this);
        // 有地址但会话已停止时，回到设置页让用户重新开始，而不是默默继续遥控
        if (server.isEmpty() || !Dispatcher.isActive(this)) {
            showSetup();
        } else {
            loadRemote();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        Dispatcher.get(this).setListener(this);
        if (Dispatcher.isActive(this)) {
            KeepAliveService.start(this);
        }
        tryRestoreAccessibility();
        refreshStatusRow();
        if (blackout) {
            // 从后台回来，重新压低亮度并隐藏系统栏
            setBrightness(0.01f);
            hideSystemBars();
        }
        if (remoteShown) {
            updateStatus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        Dispatcher.get(this).setListener(null);
    }

    // ------------------------------------------------------------ 工具

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable roundRectStroke(int color, float radius, int stroke, int strokeColor) {
        GradientDrawable d = roundRect(color, radius);
        d.setStroke(dp(stroke), strokeColor);
        return d;
    }

    private boolean accessibilityOn() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String full = getPackageName() + "/" + VolumeKeyService.class.getName();
            String shortForm = getPackageName() + "/." + VolumeKeyService.class.getSimpleName();
            return enabled.contains(full) || enabled.contains(shortForm);
        } catch (Exception e) {
            return false;
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
            }
        } catch (Exception ignored) {
        }
    }

    /** 需要 adb 授权：pm grant com.pageflip.remote android.permission.WRITE_SECURE_SETTINGS */
    private boolean canWriteSecure() {
        try {
            return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** ColorOS 偶发会关掉无障碍服务，拿到写设置权限后可以自己重新打开。 */
    private boolean tryRestoreAccessibility() {
        if (!Dispatcher.isActive(this)) return false;          // 会话已结束
        if (!Dispatcher.lockScreenWanted(this)) return false;  // 用户不想用锁屏模式
        if (!canWriteSecure() || accessibilityOn()) return false;
        try {
            String me = getPackageName() + "/" + VolumeKeyService.class.getName();
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String merged = (cur == null || cur.trim().isEmpty()) ? me : cur + ":" + me;
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 设置页

    private void showSetup() {
        remoteShown = false;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int p = dp(24);
        root.setPadding(p, dp(36), p, dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("翻 页 器");
        title.setTextColor(TXT);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(0.16f);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("音量上 = 上一页　　音量下 = 下一页");
        sub.setTextColor(DIM);
        sub.setTextSize(12.5f);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        subLp.bottomMargin = dp(26);
        root.addView(sub, subLp);

        // ---- 扫码：一次拿到地址 + 配对码 ----
        Button scan = new Button(this);
        scan.setText("扫 码 填 写");
        scan.setTextSize(15);
        scan.setAllCaps(false);
        scan.setTextColor(0xFFCFE0FF);
        scan.setBackground(roundRect(0xFF1B2A3D, 12));
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scanLp.bottomMargin = dp(16);
        root.addView(scan, scanLp);
        scan.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_SCAN);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开相机，请手动输入", Toast.LENGTH_SHORT).show();
            }
        });

        // ---- 服务器地址 ----
        inputAddr = new EditText(this);
        inputAddr.setHint("192.168.1.100:8000");
        inputAddr.setText(server);
        inputAddr.setSingleLine(true);
        inputAddr.setTextColor(TXT);
        inputAddr.setHintTextColor(0xFF3E4A5C);
        inputAddr.setTextSize(16);
        inputAddr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        inputAddr.setBackground(roundRect(CARD, 12));
        inputAddr.setPadding(dp(16), dp(16), dp(16), dp(16));
        inputAddr.setSelectAllOnFocus(true);
        root.addView(inputAddr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 配对码：电脑控制台 / 启动横幅里显示
        inputToken = new EditText(this);
        inputToken.setHint("配对码（6 位数字，见电脑控制台）");
        inputToken.setText(Dispatcher.savedToken(this));
        inputToken.setSingleLine(true);
        inputToken.setTextColor(TXT);
        inputToken.setHintTextColor(0xFF3E4A5C);
        inputToken.setTextSize(15);
        inputToken.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputToken.setBackground(roundRect(CARD, 12));
        inputToken.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams tkLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tkLp.topMargin = dp(10);
        root.addView(inputToken, tkLp);

        Button go = new Button(this);
        go.setText("连 接");
        go.setTextSize(16);
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setBackground(roundRect(ACCENT, 12));
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        goLp.topMargin = dp(14);
        root.addView(go, goLp);

        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doConnect(inputAddr.getText().toString(), inputToken.getText().toString());
            }
        });

        // ---- 锁屏翻页（无障碍） ----
        root.addView(buildAccessibilityCard());
        root.addView(buildBatteryRow());
        root.addView(buildStatusRow());

        // ---- 兼容模式开关 ----
        final CheckBox fb = new CheckBox(this);
        fb.setText("音量键兼容模式");
        fb.setTextColor(DIM);
        fb.setTextSize(12.5f);
        fb.setChecked(Dispatcher.fallbackEnabled(this));
        fb.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fbLp.topMargin = dp(18);
        root.addView(fb, fbLp);

        TextView fbHint = new TextView(this);
        fbHint.setText("部分手机（小米 / 华为 / OPPO 等）在锁屏时不会把音量键交给无障碍服务，"
                + "开启此模式后改为监听音量变化，翻页同时自动把音量还原。若发现手动调音量会误翻页，可关闭。");
        fbHint.setTextColor(0xFF55606F);
        fbHint.setTextSize(11);
        fbHint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams fhLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fhLp.topMargin = dp(4);
        root.addView(fbHint, fhLp);

        fb.setOnCheckedChangeListener((buttonView, isChecked) ->
                Dispatcher.setFallback(MainActivity.this, isChecked));

        setContentView(scroll);
        if (server.isEmpty()) {
            inputAddr.requestFocus();
        }
    }

    /** 运行状态一览：后台常驻是否在跑、自愈权限有没有。 */
    private View buildStatusRow() {
        statusRow = new TextView(this);
        statusRow.setTextSize(11f);
        statusRow.setPadding(dp(4), dp(2), dp(4), dp(10));
        statusRow.setOnClickListener(v -> showStatusDialog());
        refreshStatusRow();
        // 服务是异步拉起的，稍后再刷一次真实状态
        statusRow.postDelayed(this::refreshStatusRow, 900);
        return statusRow;
    }

    private void refreshStatusRow() {
        if (statusRow == null) return;
        boolean alive = KeepAliveService.isRunning();
        boolean heal = canWriteSecure();
        statusRow.setText("后台常驻 " + (alive ? "运行中" : "未运行")
                + "　·　失联自愈 " + (heal ? "已授权" : "未授权"));
        statusRow.setTextColor(alive && heal ? 0xFF5E7A68 : 0xFF8A6A3A);
    }

    private void showStatusDialog() {
        boolean heal = canWriteSecure();
        new AlertDialog.Builder(this)
                .setTitle(heal ? "后台常驻与自愈" : "开启失联自愈（可选）")
                .setMessage(heal
                        ? "后台常驻已生效：进程被系统冻结的概率大幅降低。\n\n"
                          + "失联自愈已授权：万一系统关掉了无障碍服务，App 会自动重新打开。"
                        : "某些系统（尤其 ColorOS / MIUI）会在一段时间后偷偷关掉无障碍服务，"
                          + "导致音量键突然失灵。\n\n"
                          + "授权后 App 可以自己把服务重新打开。需要用电脑执行一次：\n\n"
                          + "adb shell pm grant com.pageflip.remote "
                          + "android.permission.WRITE_SECURE_SETTINGS\n\n"
                          + "不授权也能用，只是失灵时需要手动重开无障碍。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 电池优化白名单：不让系统在锁屏后把无障碍服务杀掉。 */
    private View buildBatteryRow() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ok = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        TextView tv = new TextView(this);
        tv.setText(ok ? "· 电池优化已关闭，后台常驻正常"
                      : "· 电池优化未关闭，点击设置（避免锁屏后被系统杀掉）");
        tv.setTextColor(ok ? 0xFF5E7A68 : 0xFFB08A4A);
        tv.setTextSize(11.5f);
        tv.setPadding(dp(4), dp(12), dp(4), dp(4));
        tv.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });
        return tv;
    }

    private View buildAccessibilityCard() {
        boolean on = accessibilityOn();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRectStroke(
                on ? 0xFF12271C : 0xFF1C1710, 14, 1, on ? 0xFF2A5F42 : 0xFF4A3A18));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(24);

        TextView head = new TextView(this);
        head.setText(on ? "锁屏翻页　已开启" : "锁屏翻页　未开启");
        head.setTextColor(on ? OK : WARN);
        head.setTextSize(14);
        card.addView(head);

        TextView desc = new TextView(this);
        desc.setText(on
                ? "锁屏后按音量键即可翻页，屏幕不需要点亮。"
                : "Android 锁屏后普通应用收不到音量键，必须开启无障碍服务才能拦截。"
                  + "开启后锁屏按音量键即可翻页。");
        desc.setTextColor(DIM);
        desc.setTextSize(11.5f);
        desc.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(6);
        card.addView(desc, descLp);

        Button btn = new Button(this);
        btn.setText(on ? "去查看设置" : "开启锁屏翻页");
        btn.setAllCaps(false);
        btn.setTextSize(13.5f);
        btn.setTextColor(on ? 0xFFBFD8FF : Color.WHITE);
        btn.setBackground(roundRect(on ? 0xFF1B2A3D : 0xFF3A2E14, 10));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(12);
        card.addView(btn, btnLp);

        btn.setOnClickListener(v -> {
            if (!on) {
                // 这条入口同样属于「开启锁屏翻页」，必须把用户意愿写下来，
                // 否则服务会因 lock_screen_want=false 而把音量键放行，锁屏翻页直接失效
                Dispatcher.setLockScreenWanted(this, true);
            }
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "在列表中找到「翻页器」并开启", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置，请手动进入：设置 → 无障碍", Toast.LENGTH_LONG).show();
            }
        });

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(card, cardLp);
        return wrapper;
    }

    private String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------ 遥控页

    // ------------------------------------------------------------ 遥控会话

    /** 地址与配对码都合法才进入遥控页。手动输入和扫码共用这一条路径。 */
    private void doConnect(String rawAddr, String rawToken) {
        String s = normalize(rawAddr);
        if (s.isEmpty()) {
            Toast.makeText(this, "请输入电脑地址，或点「扫码填写」", Toast.LENGTH_SHORT).show();
            return;
        }
        String tk = rawToken == null ? "" : rawToken.trim();
        if (tk.isEmpty()) {
            Toast.makeText(this, "请输入电脑上显示的配对码", Toast.LENGTH_SHORT).show();
            return;
        }
        server = s;
        if (inputAddr != null) inputAddr.setText(s);
        if (inputToken != null) inputToken.setText(tk);
        Dispatcher.saveServer(this, s);
        Dispatcher.saveToken(this, tk);
        Dispatcher.applySaved(this);
        startRemote();
        loadRemote();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK || data == null) return;

        String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
        if (text == null || text.isEmpty()) return;

        try {
            Uri u = Uri.parse(text.trim());
            String host = u.getHost();
            if (host == null || host.isEmpty()) {
                Toast.makeText(this, "二维码内容无法识别", Toast.LENGTH_LONG).show();
                return;
            }
            int port = u.getPort() > 0 ? u.getPort() : 8000;
            String tk = u.getQueryParameter("t");
            String addr = "http://" + host + ":" + port;

            if (inputAddr != null) inputAddr.setText(addr);
            if (inputToken != null) inputToken.setText(tk == null ? "" : tk);

            if (tk == null || tk.isEmpty()) {
                Toast.makeText(this, "二维码里没有配对码，请手动补上", Toast.LENGTH_LONG).show();
                return;
            }
            doConnect(addr, tk);
        } catch (Exception e) {
            Toast.makeText(this, "二维码内容无法识别", Toast.LENGTH_LONG).show();
        }
    }

    /** 开始遥控：会话标记为进行中，此后按键才会被处理与发送。 */
    private void startRemote() {
        Dispatcher.setActive(this, true);
        Dispatcher.get(this).reopen();
        KeepAliveService.start(this);
        refreshStatusRow();
    }

    /**
     * 停止遥控 —— 真正的终止路径。
     * 1) 会话标为结束，无障碍服务从此把音量键交还系统
     * 2) 丢弃排队指令、关闭 socket、释放所有锁
     * 3) 停掉后台常驻服务
     * 4) 若已授予写设置权限，直接把无障碍服务关掉
     */
    private void stopRemote() {
        Dispatcher.setActive(this, false);
        Dispatcher.get(this).shutdown();
        KeepAliveService.stop(this);
        if (canWriteSecure()) disableAccessibility();
        if (blackout) exitBlackout();
        finish();
    }

    private void disableAccessibility() {
        try {
            String me = getPackageName() + "/" + VolumeKeyService.class.getName();
            String shortForm = getPackageName() + "/." + VolumeKeyService.class.getSimpleName();
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) return;
            StringBuilder sb = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty()) continue;
                if (s.equals(me) || s.equals(shortForm)) continue;
                if (sb.length() > 0) sb.append(':');
                sb.append(s);
            }
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, sb.toString());
        } catch (Exception ignored) {
        }
    }

    private void loadRemote() {
        remoteShown = true;

        remoteRoot = new FrameLayout(this);
        remoteRoot.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶部状态条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF0D1219);
        bar.setPadding(dp(12), 0, dp(12), 0);

        dot = new TextView(this);
        dot.setBackgroundColor(DIM);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(9);
        bar.addView(dot, dotLp);

        statusText = new TextView(this);
        statusText.setTextColor(DIM);
        statusText.setTextSize(11.5f);
        statusText.setMaxLines(1);
        bar.addView(statusText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView sleep = new TextView(this);
        sleep.setText("熄屏");
        sleep.setTextColor(0xFFBFD8FF);
        sleep.setTextSize(12);
        sleep.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.addView(sleep);

        TextView gear = new TextView(this);
        gear.setText("设置");
        gear.setTextColor(0xFF7C89A0);
        gear.setTextSize(12);
        gear.setPadding(dp(6), dp(6), dp(2), dp(6));
        bar.addView(gear);

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        View.OnClickListener menu = v -> showMenu();
        dot.setOnClickListener(menu);
        statusText.setOnClickListener(menu);
        gear.setOnClickListener(menu);
        sleep.setOnClickListener(v -> enterBlackout());

        // 遥控网页
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(BG);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                setDot(true);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                setDot(false);
                if (!blackout) {
                    Toast.makeText(MainActivity.this,
                            "遥控网页加载失败：" + description, Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        remoteRoot.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(remoteRoot);
        // 原生已配对的 token 直接交给网页：网页端认识 URL 的 t 参数并会清理地址栏。
        // 否则首次安装时网页 localStorage 为空，会再弹一次配对门。
        String tk = Dispatcher.savedToken(this);
        web.loadUrl(server + "/" + (tk.isEmpty() ? "" : "?t=" + Uri.encode(tk)));
        updateStatus();
    }

    // ------------------------------------------------------------ 熄屏模式

    private void enterBlackout() {
        if (blackout || remoteRoot == null) return;
        blackout = true;
        blackoutTapAt = 0;

        // 停掉网页：既不渲染，也不再每 4 秒轮询
        if (web != null) {
            try {
                web.onPause();
                web.pauseTimers();
                web.setVisibility(View.INVISIBLE);
            } catch (Exception ignored) {
            }
        }

        setBrightness(0.01f);
        hideSystemBars();

        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0xFF000000);
        layer.setClickable(true);

        final TextView hint = new TextView(this);
        hint.setText("熄屏翻页中\n\n音量上 = 上一页　　音量下 = 下一页\n\n点一下屏显示提示，两秒内再点一次退出");
        hint.setTextColor(0xFF1F1F1F);
        hint.setTextSize(12.5f);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(6), 1f);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.CENTER;
        layer.addView(hint, hl);

        final Runnable fade = () -> hint.animate().alpha(0f).setDuration(400).start();

        layer.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (blackoutTapAt != 0 && now - blackoutTapAt < 2500) {
                exitBlackout();
                return;
            }
            blackoutTapAt = now;
            hint.setAlpha(1f);
            layer.removeCallbacks(fade);
            layer.postDelayed(fade, 2500);
        });

        remoteRoot.addView(layer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        blackoutView = layer;
    }

    private void exitBlackout() {
        if (!blackout) return;
        blackout = false;

        if (blackoutView != null && remoteRoot != null) {
            remoteRoot.removeView(blackoutView);
            blackoutView = null;
        }
        if (web != null) {
            try {
                web.setVisibility(View.VISIBLE);
                web.onResume();
                web.resumeTimers();
            } catch (Exception ignored) {
            }
        }
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        showSystemBars();
    }

    private void setBrightness(float v) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = v;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {
        }
    }

    private void hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Exception ignored) {
        }
    }

    private void showSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Exception ignored) {
        }
    }

    private void showMenu() {
        final boolean want = Dispatcher.lockScreenWanted(this);
        String acc = want ? "关闭锁屏翻页" : "开启锁屏翻页";
        new AlertDialog.Builder(this)
                .setTitle("翻页器")
                .setItems(new String[]{"停止遥控并退出", "重新设置地址与配对码", acc}, (d, which) -> {
                    if (which == 0) {
                        stopRemote();
                    } else if (which == 1) {
                        showSetup();
                    } else {
                        toggleLockScreen(!want);
                    }
                })
                .show();
    }

    /**
     * 锁屏意愿独立于会话状态：用户主动关闭后，自愈逻辑不得再把它打开。
     */
    private void toggleLockScreen(boolean want) {
        Dispatcher.setLockScreenWanted(this, want);
        if (want) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "在列表中找到「翻页器」并开启", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "请手动进入：设置 → 无障碍", Toast.LENGTH_LONG).show();
            }
        } else {
            if (canWriteSecure()) disableAccessibility();
            Toast.makeText(this, "已关闭锁屏翻页，不会自动重开", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatus() {
        if (statusText == null) return;
        boolean acc = accessibilityOn();
        String ch;
        if (lastVia == null) {
            ch = "等待按键";
        } else if ("PENDING".equals(lastVia)) {
            ch = "结果未确定";   // 服务端仍在执行，稍后以电脑实际画面为准
        } else {
            ch = String.format(Locale.US, "%s %.1f ms", lastVia, lastRtt);
        }
        statusText.setText((acc ? "锁屏可用" : "仅前台") + " · " + ch);
        statusText.setTextColor(acc ? 0xFF7FD3A2 : 0xFFE0A64D);
    }

    private void setDot(final boolean ok) {
        runOnUiThread(() -> {
            if (dot != null) dot.setBackgroundColor(ok ? OK : BAD);
        });
    }

    @Override
    public void onResult(String action, boolean ok, double rttMs, String via) {
        lastVia = via;
        lastRtt = rttMs;
        runOnUiThread(() -> {
            if (dot != null) dot.setBackgroundColor(ok ? OK : BAD);
            updateStatus();
        });
    }

    // ------------------------------------------------------------ 音量键（前台兜底）

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // 设置页 / 未开始遥控时，音量键必须交还系统，不能白白吞掉
            if (!remoteShown || !Dispatcher.isActive(this) || server.isEmpty()) {
                return super.dispatchKeyEvent(event);
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                String act = (code == KeyEvent.KEYCODE_VOLUME_UP) ? "prev" : "next";
                int repeat = event.getRepeatCount();
                if (repeat == 0 || repeat % 2 == 0) {
                    Dispatcher.get(this).send(act);
                    if (blackout) buzz(); // 全黑界面下的盲按反馈
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(
                            12, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(12);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (blackout) {
            exitBlackout();
            return;
        }
        showMenu();
    }
}
