package com.tj.skyrim.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Locale;

/**
 * Checks the device before the game starts, so an unsupported GPU or a phone
 * without Vulkan 1.1 gets a sentence instead of a crash at the Bethesda logo.
 *
 * <p>The Vulkan check is the platform feature the manifest already asks for;
 * the RAM number comes from the system. The Mali warning exists because the
 * stock driver of those GPUs crashes in its shader compiler: the port needs a
 * custom driver (PanVK / Mr. Purple) installed through the Driver tab.
 */
public final class Compatibility {

    /** 2.5 GiB: below this the 8.4 GB data and the guest memory do not fit. */
    private static final long MIN_RAM = 2560L << 20;

    private Compatibility() {
    }

    /** A blocking problem, a warning, or null when the device is fine. */
    public static Problem check(Context context) {
        if (!hasVulkan11(context)) {
            return new Problem("Vulkan 1.1 is required",
                    "This device does not report Vulkan 1.1, which the port needs "
                            + "to draw anything. Update the system and the GPU driver, "
                            + "or try a device with a Vulkan 1.1 driver.", true);
        }
        long ram = totalRam(context);
        if (ram > 0 && ram < MIN_RAM) {
            return new Problem("Low memory",
                    "The game needs about 3 GB of RAM; this device reports "
                            + (ram >> 20) + " MB. It may still start, but expect it to be "
                            + "closed while loading.", false);
        }
        return null;
    }

    /**
     * True when the GPU is a Mali/Immortalis on the system driver, the case
     * that crashes in the shader compiler without a custom driver.
     */
    public static boolean needsCustomDriver(Context context, LauncherSettings settings) {
        if (settings.driver() != null) return false;
        if (Build.VERSION.SDK_INT >= 31) {
            String soc = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL)
                    .toLowerCase(Locale.ROOT);
            if (soc.contains("mediatek") || soc.contains("mali")
                    || soc.contains("immortalis")) return true;
        }
        String hardware = (Build.HARDWARE + " " + Build.BOARD + " " + Build.DEVICE)
                .toLowerCase(Locale.ROOT);
        return hardware.startsWith("mt") || hardware.contains("mediatek")
                || hardware.contains("mali") || hardware.contains("exynos");
    }

    /** A one-line summary for the launcher's corner. */
    public static String summary(Context context) {
        return (Build.SOC_MODEL == null || Build.SOC_MODEL.isEmpty()
                ? Build.HARDWARE : Build.SOC_MODEL) + ", " + (totalRam(context) >> 20) + " MB RAM";
    }

    private static boolean hasVulkan11(Context context) {
        try {
            /* 0x401000 == Vulkan 1.1 as the platform feature reports it */
            return context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long totalRam(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return info.totalMem;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** A device message the launcher shows in a dialog. */
    public static final class Problem {
        public final String title;
        public final String message;
        public final boolean blocking;

        Problem(String title, String message, boolean blocking) {
            this.title = title;
            this.message = message;
            this.blocking = blocking;
        }
    }
}
