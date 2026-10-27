package com.pageflip.remote;

/**
 * Y 平面旋转工具（纯 Java，无 Android 依赖，便于在电脑上直接做单元验证）。
 *
 * 相机的 NV21 预览帧是横向的，屏幕显示时会旋转，而送进解码器的帧数据并不会跟着转。
 * 解码前把 Y 平面按显示方向转正，识别率明显更高。
 */
public final class QrRotate {

    private QrRotate() {
    }

    /**
     * 把 Y 平面顺时针旋转 degrees 度（0 / 90 / 180 / 270）。
     *
     * @param src 源 Y 平面，宽 w 高 h
     * @return 旋转后的 Y 平面；90/270 时宽高互换
     */
    public static byte[] rotateY(byte[] src, int w, int h, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        if (d == 0) return src;

        byte[] out = new byte[w * h];
        if (d == 180) {
            for (int y = 0; y < h; y++) {
                int base = y * w;
                int obase = (h - 1 - y) * w;
                for (int x = 0; x < w; x++) {
                    out[obase + (w - 1 - x)] = src[base + x];
                }
            }
            return out;
        }
        if (d == 90) {
            // 顺时针 90：左上 -> 右上，目标宽 = h
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    out[x * h + (h - 1 - y)] = src[base + x];
                }
            }
            return out;
        }
        // 270：逆时针 90，左上 -> 左下，目标宽 = h
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                out[(w - 1 - x) * h + y] = src[base + x];
            }
        }
        return out;
    }

    /** 旋转后的宽高。 */
    public static int widthAfter(int w, int h, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return (d == 90 || d == 270) ? h : w;
    }

    public static int heightAfter(int w, int h, int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return (d == 90 || d == 270) ? w : h;
    }
}
