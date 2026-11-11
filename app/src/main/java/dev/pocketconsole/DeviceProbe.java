package dev.pocketconsole;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Build;

public final class DeviceProbe {
    private DeviceProbe() { }

    public static DeviceCapabilities read(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Integer glEsVersion = null;
        long totalRamBytes = -1;
        if (manager != null) {
            if (manager.getDeviceConfigurationInfo() != null && manager.getDeviceConfigurationInfo().reqGlEsVersion > 0) {
                glEsVersion = manager.getDeviceConfigurationInfo().reqGlEsVersion;
            }
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            totalRamBytes = memory.totalMem;
        }

        Integer vulkanVersion = null;
        PackageManager packages = context.getPackageManager();
        if (packages != null) {
            FeatureInfo[] features = packages.getSystemAvailableFeatures();
            if (features != null) {
                vulkanVersion = 0;
                for (FeatureInfo feature : features) {
                    if (feature != null && PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(feature.name)) {
                        vulkanVersion = feature.version;
                        break;
                    }
                }
            }
        }
        return new DeviceCapabilities(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS,
                glEsVersion, vulkanVersion, totalRamBytes, Build.MODEL);
    }
}
