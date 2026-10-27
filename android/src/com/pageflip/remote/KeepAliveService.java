package com.pageflip.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/**
 * 后台常驻服务。
 *
 * 存在的唯一目的：把进程优先级抬到「前台服务」，避免 ColorOS / MIUI 这类 ROM
 * 在应用短暂无操作后把进程冻结（冻结后无障碍服务就再也收不到音量键）。
 *
 * 通知是低优先级、无声、不可划走的，对用户几乎无感。
 */
public class KeepAliveService extends Service {

    public static final String CHANNEL_ID = "pageflip_alive";
    public static final int NOTI_ID = 1001;

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    /** 前台启动；失败时退回普通服务，至少不被立刻回收。 */
    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        handler.postDelayed(healTask, 8000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTI_ID, buildNotification());
        } catch (Exception e) {
            // Android 14+ 若 FGS 类型不被接受会抛异常，此时退化为普通服务继续跑
        }
        // 没有遥控会话就不该常驻：立刻退场，避免无意义的常驻通知与进程优先级
        if (!Dispatcher.isActive(this)) {
            try {
                stopForeground(true);
            } catch (Exception ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(healTask);
        super.onDestroy();
    }

    // ---- 失联自愈：有些 ROM 会偷偷关掉无障碍服务 ----

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean lostNotified = false;

    private final Runnable healTask = new Runnable() {
        @Override
        public void run() {
            healAccessibility();
            handler.postDelayed(this, 15000);
        }
    };

    private boolean accessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String full = getPackageName() + "/" + VolumeKeyService.class.getName();
            String shortForm = getPackageName() + "/." + VolumeKeyService.class.getSimpleName();
            return enabled.contains(full) || enabled.contains(shortForm);
        } catch (Exception e) {
            return true; // 读不到就别误报
        }
    }

    private void healAccessibility() {
        // 会话进行中、且用户仍希望用锁屏模式，才允许自愈
        if (!Dispatcher.isActive(this) || !Dispatcher.lockScreenWanted(this)) {
            lostNotified = false;
            return;
        }

        if (accessibilityEnabled()) {
            lostNotified = false;
            return;
        }

        boolean canWrite;
        try {
            canWrite = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            canWrite = false;
        }

        if (canWrite) {
            try {
                String me = getPackageName() + "/" + VolumeKeyService.class.getName();
                String cur = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                String merged = (cur == null || cur.trim().isEmpty()) ? me : cur + ":" + me;
                Settings.Secure.putString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
                Settings.Secure.putInt(getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (Exception ignored) {
            }
            return;
        }

        // 没有自愈权限，至少告诉用户为什么失灵
        if (!lostNotified) {
            notifyLost();
            lostNotified = true;
        }
    }

    private void notifyLost() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent to = new Intent(this, MainActivity.class);
            to.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 1, to, piFlags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);
            b.setContentTitle("音量键翻页已停止")
                    .setContentText("无障碍服务被系统关闭了，点此重新开启")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            nm.notify(1002, b.build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "翻页器运行状态", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持音量键翻页服务在后台存活");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent to = new Intent(this, MainActivity.class);
        to.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, to, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("翻页器")
                .setContentText("音量键翻页已就绪，锁屏可用")
                .setSmallIcon(android.R.drawable.ic_media_next)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_SERVICE);
            b.setVisibility(Notification.VISIBILITY_SECRET);
        }
        return b.build();
    }
}
