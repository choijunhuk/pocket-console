package dev.pocketconsole;

public final class DeviceCapabilitiesTest {
    private static int checks;

    public static void main(String[] args) {
        DeviceCapabilities high = new DeviceCapabilities(33, new String[]{"arm64-v8a", "armeabi-v7a"}, 0x30002, 0x403000, 8_000_000_000L);
        expect(high, ConsoleType.DS, DeviceCapabilities.Compatibility.State.READY);
        expect(high, ConsoleType.THREE_DS, DeviceCapabilities.Compatibility.State.READY);
        expect(high, ConsoleType.SWITCH, DeviceCapabilities.Compatibility.State.UNKNOWN);
        expect(high, ConsoleType.SWITCH_TWO, DeviceCapabilities.Compatibility.State.READY);
        expect(new DeviceCapabilities(26, new String[]{"x86_64"}, 0x30000, null, 2_000_000_000L), ConsoleType.THREE_DS, DeviceCapabilities.Compatibility.State.UNSUPPORTED);
        expect(new DeviceCapabilities(25, new String[]{"arm64-v8a"}, 0x30002, 0x403000, 8_000_000_000L), ConsoleType.DS, DeviceCapabilities.Compatibility.State.UNSUPPORTED);
        expect(new DeviceCapabilities(33, new String[]{"armeabi-v7a"}, 0x30002, 0x403000, 8_000_000_000L), ConsoleType.SWITCH_TWO, DeviceCapabilities.Compatibility.State.UNSUPPORTED);
        expect(new DeviceCapabilities(33, new String[]{"x86_64"}, 0x30002, 0x403000, 8_000_000_000L), ConsoleType.SWITCH_TWO, DeviceCapabilities.Compatibility.State.UNSUPPORTED);
        expect(new DeviceCapabilities(32, new String[]{"arm64-v8a"}, 0x30002, 0x403000, 8_000_000_000L), ConsoleType.SWITCH_TWO, DeviceCapabilities.Compatibility.State.UNSUPPORTED);
        expect(new DeviceCapabilities(33, new String[]{"arm64-v8a"}, 0x30002, 0x402000, 8_000_000_000L), ConsoleType.SWITCH_TWO, DeviceCapabilities.Compatibility.State.UNSUPPORTED);
        expect(new DeviceCapabilities(33, new String[]{"arm64-v8a"}, null, 0x403000, -1), ConsoleType.THREE_DS, DeviceCapabilities.Compatibility.State.UNKNOWN);
        expect(new DeviceCapabilities(33, new String[]{"arm64-v8a"}, 0x30002, null, -1), ConsoleType.SWITCH_TWO, DeviceCapabilities.Compatibility.State.UNKNOWN);
        expect(new DeviceCapabilities(33, null, 0x30002, 0x403000, -1), ConsoleType.DS, DeviceCapabilities.Compatibility.State.UNKNOWN);
        expect(new DeviceCapabilities(33, new String[]{"arm64-v8a"}, 0x30002, 0x403000, -1), ConsoleType.DS, DeviceCapabilities.Compatibility.State.READY);
        System.out.println("PASS: " + checks + " device capability checks (assumed profiles, not device measurements)");
    }

    private static void expect(DeviceCapabilities device, ConsoleType type, DeviceCapabilities.Compatibility.State expected) {
        DeviceCapabilities.Compatibility actual = device.check(type);
        if (actual.state != expected) throw new AssertionError(type + ": expected " + expected + ", got " + actual.state + " (" + actual.reason + ")");
        if (actual.reason == null || actual.reason.isEmpty() || actual.advice == null || actual.advice.isEmpty()) {
            throw new AssertionError("Capability result needs reason and advice");
        }
        checks++;
    }
}
