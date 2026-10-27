package com.pageflip.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 锁屏可用音量键的关键。
 *
 * 普通 Activity 在锁屏后收不到任何按键事件，普通 Service 又没有窗口级别的按键流。
 * 只有开启 FLAG_REQUEST_FILTER_KEY_EVENTS 的无障碍服务能在系统派发前拦到按键，
 * 并且可以返回 true 把它消费掉（音量因此不会变化）。
 *
 * 兼容兜底：部分 ROM 在锁屏时不给无障碍服务派发音量键，此时音量会真的变化，
 * 我们监听 VOLUME_CHANGED 广播捕捉这一变化，翻页后把音量还原。
 */
public class VolumeKeyService extends AccessibilityService {

    private static final String TAG = "PageFlipKey";

    private AudioManager am;
    private BroadcastReceiver volumeReceiver;
    private ContentObserver volumeObserver;
    private boolean restoring;
    private int lastMusic = -1;
    private int lastRing = -1;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.notificationTimeout = 0;
        setServiceInfo(info);
        Log.i(TAG, "onServiceConnected, flags=" + info.flags);

        Dispatcher.applySaved(this);
        KeepAliveService.start(this);

        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            lastMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            lastRing = am.getStreamVolume(AudioManager.STREAM_RING);
        }
        registerFallback();
    }

    // ------------------------------------------------------------ 主通道：拦截按键

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event);
        }

        // 遥控未进行时，把音量键完整交还系统：不消费、不发送
        if (!Dispatcher.isActive(this)) {
            return super.onKeyEvent(event);
        }
        // 应用任一界面在前台时由 Activity 自己处理，无障碍不要抢（否则设置页/扫码页也会吞键）
        if (MainActivity.isAppForeground()) {
            return super.onKeyEvent(event);
        }
        // 用户没打算用锁屏遥控，锁屏时就不该吞掉音量键
        if (!Dispatcher.lockScreenWanted(this)) {
            return super.onKeyEvent(event);
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            String act = (code == KeyEvent.KEYCODE_VOLUME_UP) ? "prev" : "next";
            int repeat = event.getRepeatCount();
            if (repeat == 0 || repeat % 2 == 0) {
                Dispatcher.get(this).send(act);
                if (repeat == 0) buzz();   // 盲按反馈（此时 Activity 收不到该事件）
            }
        }
        return true; // 消费：音量不变，也不再传给 Activity
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(
                            12, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(12);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 兜底：音量变化广播

    private void registerFallback() {
        // 路径一：音量变化广播（老系统可靠，新系统上可能收不到）
        if (volumeReceiver == null) {
            volumeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                    int val = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                    applyChange(stream, val, "broadcast");
                }
            };
            try {
                IntentFilter f = new IntentFilter();
                f.addAction("android.media.VOLUME_CHANGED_ACTION");
                f.addAction("android.media.STREAM_VOLUME_CHANGED_ACTION");
                registerReceiver(volumeReceiver, f);
            } catch (Exception e) {
                volumeReceiver = null;
            }
        }

        // 路径二：直接监听系统设置项（不依赖广播，锁屏同样有效）
        if (volumeObserver == null) {
            volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    pollVolumes();
                }

                @Override
                public void onChange(boolean selfChange) {
                    pollVolumes();
                }
            };
            try {
                getContentResolver().registerContentObserver(
                        Settings.System.CONTENT_URI, true, volumeObserver);
            } catch (Exception e) {
                volumeObserver = null;
            }
        }
        Log.i(TAG, "fallback registered: broadcast=" + (volumeReceiver != null)
                + " observer=" + (volumeObserver != null));
    }

    private void pollVolumes() {
        if (am == null) return;
        if (!Dispatcher.isActive(this)) {
            // 非遥控期间用户正常调音量：只更新基线，不触发翻页，
            // 避免下次开始遥控时把旧基线当成新按键、还原到过期音量
            lastMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            lastRing = am.getStreamVolume(AudioManager.STREAM_RING);
            return;
        }
        applyChange(AudioManager.STREAM_MUSIC, am.getStreamVolume(AudioManager.STREAM_MUSIC), "observer");
        applyChange(AudioManager.STREAM_RING, am.getStreamVolume(AudioManager.STREAM_RING), "observer");
    }

    private synchronized void applyChange(int stream, int val, String from) {
        if (am == null || val < 0) return;

        // 先取旧值并更新基线：无论这次是否处理，基线都必须保持最新。
        // 否则在前台调过音量后转到后台，会把旧基线当成新按键而误翻页。
        int prev;
        if (stream == AudioManager.STREAM_MUSIC) {
            prev = lastMusic;
            lastMusic = val;
        } else if (stream == AudioManager.STREAM_RING) {
            prev = lastRing;
            lastRing = val;
        } else {
            return;
        }

        if (!Dispatcher.isActive(this)) return;          // 遥控已停止
        if (MainActivity.isAppForeground()) return;      // 前台（含扫码页）由 Activity 处理
        if (!Dispatcher.lockScreenWanted(this)) return;  // 用户不要锁屏模式
        if (!Dispatcher.fallbackEnabled(this)) return;
        if (restoring) return;

        if (prev < 0 || val == prev) return;

        Log.i(TAG, "volume " + from + " stream=" + stream + " " + prev + " -> " + val);
        // 音量变大 = 按了音量上 = 上一页
        String act = (val > prev) ? "prev" : "next";
        Dispatcher.get(this).send(act);

        // 立刻把音量还原，用户感知不到音量被改过
        restoring = true;
        try {
            am.setStreamVolume(stream, prev, 0);
        } catch (Exception ignored) {
        } finally {
            restoring = false;
        }
        if (stream == AudioManager.STREAM_MUSIC) lastMusic = prev;
        else lastRing = prev;
    }

    // ------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只做按键拦截，不关心界面事件
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.w(TAG, "onUnbind —— 无障碍服务被系统解绑");
        Dispatcher.get(this).releaseAll();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        // 服务销毁是终止路径，必须成对释放锁，否则 CPU 无法进入低功耗状态
        Dispatcher.get(this).releaseAll();
        if (volumeReceiver != null) {
            try {
                unregisterReceiver(volumeReceiver);
            } catch (Exception ignored) {
            }
            volumeReceiver = null;
        }
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
            }
            volumeObserver = null;
        }
        super.onDestroy();
    }
}
