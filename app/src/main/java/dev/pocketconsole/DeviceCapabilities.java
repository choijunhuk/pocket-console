package dev.pocketconsole;

import java.util.Arrays;

public final class DeviceCapabilities {
    public final int sdkVersion;
    public final String[] supportedAbis;
    public final Integer glEsVersion;
    public final Integer vulkanVersion;
    public final long totalRamBytes;
    public final String deviceModel;

    public DeviceCapabilities(int sdkVersion, String[] supportedAbis, Integer glEsVersion,
                              Integer vulkanVersion, long totalRamBytes) {
        this(sdkVersion, supportedAbis, glEsVersion, vulkanVersion, totalRamBytes, null);
    }

    public DeviceCapabilities(int sdkVersion, String[] supportedAbis, Integer glEsVersion,
                              Integer vulkanVersion, long totalRamBytes, String deviceModel) {
        this.sdkVersion = sdkVersion;
        this.supportedAbis = supportedAbis == null ? null : supportedAbis.clone();
        this.glEsVersion = glEsVersion;
        this.vulkanVersion = vulkanVersion;
        this.totalRamBytes = totalRamBytes;
        this.deviceModel = deviceModel;
    }

    public String summary() {
        return (deviceModel == null || deviceModel.isEmpty() ? "Android device" : deviceModel)
                + " · API " + (sdkVersion > 0 ? sdkVersion : "unknown")
                + " · ABI " + (supportedAbis == null || supportedAbis.length == 0 ? "unknown" : Arrays.toString(supportedAbis))
                + " · GLES " + version(glEsVersion, 16)
                + " · Vulkan " + version(vulkanVersion, 22)
                + " · RAM " + (totalRamBytes > 0 ? totalRamBytes / (1024 * 1024) + " MiB" : "unknown");
    }

    private static String version(Integer encoded, int majorShift) {
        if (encoded == null) return "unknown";
        int minorShift = majorShift == 16 ? 0 : 12;
        int minorMask = majorShift == 16 ? 0xffff : 0x3ff;
        return (encoded >>> majorShift) + "." + ((encoded >>> minorShift) & minorMask);
    }

    public Compatibility check(ConsoleType console) {
        String advice = "실제 기기 성능과 게임 호환성은 실행 시험이 필요합니다.";
        if (console == null) return result(Compatibility.State.UNKNOWN, "기종을 선택하지 않았습니다.", advice);
        if (sdkVersion <= 0) return result(Compatibility.State.UNKNOWN, "Android API 버전을 확인할 수 없습니다.", advice);
        if (sdkVersion < 26) return result(Compatibility.State.UNSUPPORTED, "Android 8 (API 26) 이상이 필요합니다.", advice);
        if (supportedAbis == null || supportedAbis.length == 0) return result(Compatibility.State.UNKNOWN, "64비트 ABI를 확인할 수 없습니다.", advice);
        boolean has64BitAbi = false;
        boolean hasArm64Abi = false;
        for (String abi : supportedAbis) {
            if (abi == null) continue;
            if (abi.contains("64")) has64BitAbi = true;
            if (abi.equals("arm64-v8a")) hasArm64Abi = true;
        }
        if (!has64BitAbi) return result(Compatibility.State.UNSUPPORTED, "64비트 ABI가 필요합니다.", advice);
        if (console == ConsoleType.THREE_DS) {
            if (glEsVersion == null) return result(Compatibility.State.UNKNOWN, "OpenGL ES 버전을 확인할 수 없습니다.", advice);
            if (glEsVersion < 0x30002) return result(Compatibility.State.UNSUPPORTED, "OpenGL ES 3.2 이상이 필요합니다.", advice);
            return result(Compatibility.State.READY, "3DS 기기 전제 조건을 충족합니다. 실제 EGL 생성은 실행 중 확인합니다.", advice);
        }
        if (console == ConsoleType.SWITCH) {
            if (sdkVersion < 33) return result(Compatibility.State.UNSUPPORTED, "Android 13 (API 33) 이상이 필요합니다.", advice);
            if (!hasArm64Abi) return result(Compatibility.State.UNSUPPORTED, "Switch 홈브루는 ARM64 기기가 필요합니다.", advice);
            // PackageManager's advertised version can differ from the physical Vulkan driver.
            return result(Compatibility.State.READY, "실제 Vulkan 기능을 확인합니다.", advice);
        }
        if (console == ConsoleType.SWITCH_TWO) {
            if (sdkVersion < 33) return result(Compatibility.State.UNSUPPORTED, "Android 13 (API 33) 이상이 필요합니다.", advice);
            if (!hasArm64Abi) return result(Compatibility.State.UNSUPPORTED, "Switch 2 후보 코어는 ARM64 ABI가 필요합니다.", advice);
            if (vulkanVersion == null) return result(Compatibility.State.UNKNOWN, "Vulkan 버전을 확인할 수 없습니다.", advice);
            if (vulkanVersion < 0x403000) return result(Compatibility.State.UNSUPPORTED, "Vulkan 1.3 이상이 필요합니다.", advice);
            return result(Compatibility.State.READY, "Switch 2 후보 코어의 기기 전제 조건을 충족합니다.", advice);
        }
        return result(Compatibility.State.READY, "DS 기기 전제 조건을 충족합니다.", advice);
    }

    private static Compatibility result(Compatibility.State state, String reason, String advice) {
        return new Compatibility(state, reason, advice);
    }

    public static final class Compatibility {
        public enum State { READY, UNSUPPORTED, UNKNOWN }

        public final State state;
        public final String reason;
        public final String advice;

        private Compatibility(State state, String reason, String advice) {
            this.state = state;
            this.reason = reason;
            this.advice = advice;
        }
    }
}
