package com.rc2.toolbox;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Wrapper around the handful of {@code Settings.System} keys that are actually
 * useful on the RC 2 and that a normal (non-root) app is allowed to change once
 * the user grants WRITE_SETTINGS.
 *
 * <p>目前只管屏幕：熄屏时间、自动旋转、强制方向、亮度。
 */
public final class SysSettings {

    private SysSettings() {}

    /** Screen never times out while a value this large is set. */
    public static final int TIMEOUT_NEVER = 30 * 60 * 1000;

    public static boolean canWrite(Context c) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                return Settings.System.canWrite(c);
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    /** Send the user to the one system screen that can grant WRITE_SETTINGS. */
    public static void requestWrite(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable t) {
            // fall through to the un-scoped variant
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            // caller reports the failure; nothing else we can do here
        }
    }

    /** The adb one-liner that grants WRITE_SETTINGS without any UI. */
    public static String adbGrant(Context c) {
        return "adb shell appops set " + c.getPackageName()
                + " WRITE_SETTINGS allow";
    }

    // ------------------------------------------------------------------ raw io

    public static int getInt(Context c, String key, int def) {
        try {
            return Settings.System.getInt(c.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static boolean putInt(Context c, String key, int v) {
        try {
            return Settings.System.putInt(c.getContentResolver(), key, v);
        } catch (Throwable t) {
            return false;
        }
    }

    public static float getFloat(Context c, String key, float def) {
        try {
            return Settings.System.getFloat(c.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------ screen

    public static int timeout(Context c) {
        return getInt(c, Settings.System.SCREEN_OFF_TIMEOUT, 30000);
    }

    public static boolean setTimeout(Context c, int ms) {
        return putInt(c, Settings.System.SCREEN_OFF_TIMEOUT, ms);
    }

    public static String describeTimeout(int ms) {
        if (ms >= TIMEOUT_NEVER) return "永不熄屏";
        if (ms % 60000 == 0) return (ms / 60000) + " 分钟";
        return (ms / 1000) + " 秒";
    }

    public static boolean autoRotate(Context c) {
        return getInt(c, Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
    }

    public static boolean setAutoRotate(Context c, boolean on) {
        return putInt(c, Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
    }

    /** 当前 user_rotation 档位：0=0°、1=90°、2=180°、3=270°。 */
    public static int rotation(Context c) {
        return getInt(c, Settings.System.USER_ROTATION, 0);
    }

    public static String describeRotation(Context c) {
        switch (((rotation(c) % 4) + 4) % 4) {
            case 1:  return "已锁定横屏（90°）";
            case 2:  return "已锁定倒置（180°）";
            case 3:  return "已锁定反向横屏（270°）";
            default: return "已锁定竖屏（0°）";
        }
    }

    /**
     * 强制屏幕方向 —— 用户要的「翻转屏幕」。
     *
     * 关键顺序：必须先关自动旋转，再写 user_rotation。
     * 只写 user_rotation 而不关加速度计旋转，传感器下一次上报就会立刻把方向覆盖回去，
     * 表现出来就是「点了没反应」。
     */
    public static boolean forceRotation(Context c, int degrees) {
        int idx = (((degrees % 360) + 360) % 360) / 90;
        boolean ok = setAutoRotate(c, false);
        if (!putInt(c, Settings.System.USER_ROTATION, idx)) ok = false;
        return ok;
    }

    /** 把方向交还给重力感应。 */
    public static boolean releaseRotation(Context c) {
        return setAutoRotate(c, true);
    }

    public static int brightness(Context c) {
        return getInt(c, Settings.System.SCREEN_BRIGHTNESS, 128);
    }

    public static int brightnessMode(Context c) {
        return getInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
    }

    /** Manual brightness needs the mode pin first, or the auto curve wins. */
    public static boolean setBrightness(Context c, int value) {
        if (value < 1) value = 1;
        if (value > 255) value = 255;
        putInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        return putInt(c, Settings.System.SCREEN_BRIGHTNESS, value);
    }

    public static boolean setBrightnessAuto(Context c) {
        return putInt(c, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
    }

    // -------------------------------------------------------- touch feedback

    public static boolean hapticFeedback(Context c) {
        return getInt(c, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1;
    }

    public static boolean setHapticFeedback(Context c, boolean on) {
        return putInt(c, Settings.System.HAPTIC_FEEDBACK_ENABLED, on ? 1 : 0);
    }

    public static boolean vibrateOn(Context c) {
        return getInt(c, Settings.System.VIBRATE_ON, 1) == 1;
    }

    public static boolean setVibrateOn(Context c, boolean on) {
        return putInt(c, Settings.System.VIBRATE_ON, on ? 1 : 0);
    }
}
