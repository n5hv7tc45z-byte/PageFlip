package com.pageflip.remote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 扫码连接：扫描电脑控制台上的二维码，一次拿到地址与配对码。
 *
 * 二维码内容是 http://<ip>:<port>/?t=<配对码>，与 /desk 页面生成的完全一致。
 *
 * 关键点：相机原始帧是横向的，屏幕显示时会被旋转，而送进解码器的帧数据不受影响。
 * 这里在解码前把 Y 平面按显示方向旋转成正立，识别率会明显高于直接丢歪帧过去。
 */
public class ScanActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_RESULT = "scan_result";

    private static final String TAG = "PageFlipScan";
    private static final int REQ_CAMERA = 2001;
    private static final int REQ_PICK = 2002;

    private SurfaceView surface;
    private Camera camera;
    private boolean previewing;

    private final MultiFormatReader reader = new MultiFormatReader();
    private HandlerThread worker;
    private Handler workerHandler;

    private volatile boolean busy;
    private volatile boolean handled;
    private int frameSkip;

    private Camera.Size previewSize;   // 缓存，避免每帧 getParameters()
    private int displayDegrees = 90;   // 显示需要顺时针旋转的角度
    private int cameraId = -1;         // 实际使用的摄像头编号

    /** 扫码页是否处于前台。供 MainActivity.isAppForeground() 使用：否则扫码页会被当成后台遥控场景，音量键继续翻旧电脑。 */
    private static volatile boolean resumed;

    /** 权限申请待决标志。onResume 与 surfaceCreated 都可能走到申请入口，重复申请会让回调空结果。 */
    private boolean permissionRequestInFlight;

    public static boolean isResumed() {
        return resumed;
    }

    /**
     * 显式挑选后置摄像头。
     * Camera.open() 打开的是 ID 0，而 Android 并不保证 0 就是后置 ——
     * 不少设备上 ID 0 反而是前置，前置预览是镜像的，会让人完全对不上。
     */
    private int pickCameraId() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int count = Camera.getNumberOfCameras();
        int back = -1, front = -1;
        for (int i = 0; i < count; i++) {
            try {
                Camera.getCameraInfo(i, info);
            } catch (Exception e) {
                continue;
            }
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                if (back < 0) back = i;
            } else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                if (front < 0) front = i;
            }
        }
        if (back >= 0) return back;
        if (front >= 0) return front;
        return 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        worker = new HandlerThread("qr-decode");
        worker.start();
        workerHandler = new Handler(worker.getLooper());

        setContentView(buildUi());
    }

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surface.getHolder().addCallback(this);

        // 取景框
        int size = (int) (getResources().getDisplayMetrics().widthPixels * 0.66);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(size, size);
        boxLp.gravity = Gravity.CENTER;

        TextView box = new TextView(this);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(16));
        border.setStroke(dp(2), 0xFF4D8DFF);
        box.setBackground(border);
        root.addView(box, boxLp);

        // 提示
        TextView tip = new TextView(this);
        tip.setText("把电脑控制台上的二维码放进框内\n自动识别地址与配对码");
        tip.setTextColor(0xFFB9C6D8);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(dp(5), 1f);
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.gravity = Gravity.BOTTOM;
        tipLp.bottomMargin = dp(112);
        root.addView(tip, tipLp);

        // 备选方式：相册 / 粘贴链接
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        TextView fromAlbum = makeAction("从相册选", v -> pickImage());
        TextView fromPaste = makeAction("粘贴链接", v -> pasteLink());
        TextView torch = makeAction("手电筒", v -> toggleTorch());
        LinearLayout.LayoutParams alLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        alLp.bottomMargin = dp(58);
        actions.addView(fromAlbum, alLp);
        actions.addView(fromPaste, alLp);
        actions.addView(torch, alLp);
        root.addView(actions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cancel = new TextView(this);
        cancel.setText("取 消");
        cancel.setTextColor(0xFFE8EDF7);
        cancel.setTextSize(15);
        cancel.setPadding(dp(20), dp(10), dp(20), dp(10));
        cancel.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.gravity = Gravity.TOP | Gravity.START;
        cancelLp.topMargin = dp(36);
        cancelLp.leftMargin = dp(18);
        root.addView(cancel, cancelLp);

        return root;
    }

    private TextView makeAction(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFF9FC4FF);
        t.setTextSize(13.5f);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33204060);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        t.setOnClickListener(l);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(12);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 相机

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        maybeStartCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.startPreview();
                previewing = true;
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_CAMERA) return;
        permissionRequestInFlight = false;
        boolean granted;
        if (results != null && results.length > 0) {
            granted = results[0] == PackageManager.PERMISSION_GRANTED;
        } else {
            // 空结果 = 申请被并发调用打断，真实结果未知：按当前权限状态兜底，
            // 避免把「实际已授权」误判成拒绝而退出扫码页
            try {
                granted = checkSelfPermission("android.permission.CAMERA")
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                granted = false;
            }
        }
        if (granted) {
            maybeStartCamera();
        } else {
            Toast.makeText(this, "需要相机权限才能扫码，也可以用「粘贴链接」", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * 统一启动入口：Activity 恢复 + 权限已授权 + Surface 有效 + 相机未开，四者齐备才开相机。
     * onResume、surfaceCreated、权限回调共用，重复调用不会重复打开；
     * onPause 只释放不重开，恢复交给 onResume（Surface 保留时这里直接恢复，不依赖 surfaceCreated 再触发）。
     */
    private void maybeStartCamera() {
        if (!resumed || camera != null) return;
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            // 已有申请在途就只等回调。并发发起第二次申请时，系统会把第一次的
            // onRequestPermissionsResult 以空结果回调，曾导致扫码页被误判拒绝而直接退出
            if (permissionRequestInFlight) return;
            permissionRequestInFlight = true;
            requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAMERA);
            return;
        }
        SurfaceHolder h = surface.getHolder();
        if (h.getSurface() == null || !h.getSurface().isValid()) return;   // 等 surfaceCreated 回调
        startCamera(h);
    }

    private void startCamera(SurfaceHolder holder) {
        if (camera != null) return;
        cameraId = pickCameraId();
        try {
            camera = Camera.open(cameraId);
        } catch (Exception e) {
            Log.w(TAG, "open camera failed", e);
            Toast.makeText(this, "无法打开相机，请用「粘贴链接」或手动输入", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            // 720p 对二维码足够，且能明显降低单帧解码耗时
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width <= 1280 && s.height <= 720) {
                    if (best == null || s.width * s.height > best.width * best.height) best = s;
                }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            // 连续自动对焦：二维码随手一晃也能跟上，单次对焦很容易糊
            List<String> focusModes = p.getSupportedFocusModes();
            if (focusModes != null
                    && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(p);

            previewSize = camera.getParameters().getPreviewSize();
            displayDegrees = displayOrientation();

            camera.setDisplayOrientation(displayDegrees);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
            previewing = true;
            Log.i(TAG, "cameraId=" + cameraId + " preview=" + previewSize
                    + "x" + (previewSize != null ? previewSize.height : 0)
                    + " displayDegrees=" + displayDegrees);
        } catch (Exception e) {
            Log.w(TAG, "start preview failed", e);
            stopCamera();
            Toast.makeText(this, "相机启动失败，请用「粘贴链接」或手动输入", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private int displayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        try {
            Camera.getCameraInfo(cameraId >= 0 ? cameraId : 0, info);
        } catch (Exception e) {
            return 90;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees;
        switch (rotation) {
            case android.view.Surface.ROTATION_90: degrees = 90; break;
            case android.view.Surface.ROTATION_180: degrees = 180; break;
            case android.view.Surface.ROTATION_270: degrees = 270; break;
            default: degrees = 0; break;
        }
        int result = (info.orientation - degrees + 360) % 360;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (360 - result) % 360;
        }
        return result;
    }

    private void stopCamera() {
        previewing = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    // ------------------------------------------------------------ 实时解码

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (busy || handled || data == null) return;
        if ((frameSkip++ & 1) != 0) return;   // 隔帧解码，省电

        final Camera.Size size = previewSize;
        if (size == null) return;

        final byte[] frame = data.clone();   // 预览缓冲会复用，必须拷贝
        final int w = size.width;
        final int h = size.height;
        final int deg = displayDegrees;

        busy = true;
        workerHandler.post(() -> {
            try {
                Result r = decodeFrame(frame, w, h, deg);
                if (r != null && !handled) {
                    handled = true;
                    onScanned(r.getText());
                }
            } catch (Exception ignored) {
            } finally {
                busy = false;
            }
        });
    }

    private Result decodeFrame(byte[] nv21, int w, int h, int degrees) {
        // 先按显示方向转正，识别率明显更高
        Result r = decodeY(QrRotate.rotateY(nv21, w, h, degrees), w, h, degrees);
        if (r == null) {
            // 兜底：万一方向判断反了，用原始帧再试一次
            r = decodeY(nv21, w, h, 0);
        }
        return r;
    }

    private Result decodeY(byte[] yPlane, int w, int h, int degrees) {
        try {
            int iw = QrRotate.widthAfter(w, h, degrees);
            int ih = QrRotate.heightAfter(w, h, degrees);
            PlanarYUVLuminanceSource src =
                    new PlanarYUVLuminanceSource(yPlane, iw, ih, 0, 0, iw, ih, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
            return reader.decodeWithState(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 备选方式

    /** 开关手电筒：光线不足时二维码识别率会明显下降。 */
    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null) return;

            boolean on = Camera.Parameters.FLASH_MODE_TORCH.equals(p.getFlashMode());
            if (on) {
                if (!modes.contains(Camera.Parameters.FLASH_MODE_OFF)) return;
                p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
            } else {
                if (!modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                    Toast.makeText(this, "这台手机不支持手电筒", Toast.LENGTH_SHORT).show();
                    return;
                }
                p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            }
            camera.setParameters(p);
            camera.stopPreview();
            camera.startPreview();
            previewing = true;
            Toast.makeText(this, on ? "手电筒已关闭" : "手电筒已打开", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "手电筒不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickImage() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的相册应用", Toast.LENGTH_LONG).show();
        }
    }

    private void pasteLink() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            if (cs == null || cs.toString().trim().isEmpty()) {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show();
                return;
            }
            onScanned(cs.toString());
        } catch (Exception e) {
            Toast.makeText(this, "读取剪贴板失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        Toast.makeText(this, "正在识别图片…", Toast.LENGTH_SHORT).show();
        workerHandler.post(() -> {
            String text = null;
            String err = null;
            try {
                text = decodeAlbumText(uri);
            } catch (OutOfMemoryError e) {
                err = "图片太大，内存不够用";
                Log.w(TAG, "album decode OOM", e);
            } catch (Exception e) {
                Log.w(TAG, "album decode failed", e);
            }
            final String value = text;
            final String error = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (value != null) {
                    handled = true;   // 防止刚恢复的相机随后解出同一码再触发一次
                    onScanned(value);
                } else {
                    Toast.makeText(this,
                            error != null ? error : "这张图片里没有找到二维码",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * 相册识别：先只读尺寸，按最长边约 2000px 采样后再真正解码；
     * 解码、灰度转换、ZXing 识别都在解码线程，输入流显式关闭。
     * 48MP 原图若整幅解码，仅辅助数组就需约 240MB，采样后稳定在 20MB 量级。
     */
    private String decodeAlbumText(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = calcSampleSize(Math.max(bounds.outWidth, bounds.outHeight), 1000);
        InputStream in2 = getContentResolver().openInputStream(uri);
        if (in2 == null) return null;
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in2, null, o);
        } finally {
            try { in2.close(); } catch (Exception ignored) { }
        }
        if (bmp == null) return null;
        try {
            BinaryBitmap bin = new BinaryBitmap(new HybridBinarizer(new BitmapSource(bmp)));
            Result r = reader.decodeWithState(bin);
            return r == null ? null : r.getText();
        } finally {
            bmp.recycle();
        }
    }

    /** inSampleSize 只能取 2 的幂：让解码结果的最长边降到 target 以下但尽量贴近。 */
    private static int calcSampleSize(int maxSide, int target) {
        int sample = 1;
        while (maxSide / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    /** core 包里没有 BufferedImageLuminanceSource，这里自实现一个用于解码相册图片。 */
    private static class BitmapSource extends LuminanceSource {
        private final byte[] luminances;

        BitmapSource(Bitmap bmp) {
            super(bmp.getWidth(), bmp.getHeight());
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            luminances = new byte[w * h];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                luminances[i] = (byte) ((r + 2 * g + b) / 4);
            }
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < getWidth()) row = new byte[getWidth()];
            System.arraycopy(luminances, y * getWidth(), row, 0, getWidth());
            return row;
        }

        @Override
        public byte[] getMatrix() {
            return luminances;
        }
    }

    // ------------------------------------------------------------ 结果

    private void onScanned(String text) {
        if (text == null) return;
        text = text.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            runOnUiThread(() -> {
                handled = false;
                Toast.makeText(this, "这不是翻页器的二维码", Toast.LENGTH_SHORT).show();
            });
            return;
        }
        final String value = text;
        runOnUiThread(() -> {
            stopCamera();
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT, value);
            setResult(RESULT_OK, data);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        maybeStartCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (worker != null) {
            worker.quitSafely();
            worker = null;
        }
    }
}
