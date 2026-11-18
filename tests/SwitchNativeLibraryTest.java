import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.yuzu.yuzu_emu.NativeLibrary;
import org.yuzu.yuzu_emu.applets.keyboard.SoftwareKeyboard;

public final class SwitchNativeLibraryTest {
    public static void main(String[] args) throws Exception {
        Method warning = NativeLibrary.class.getDeclaredMethod("formatVulkanWarning", String.class);
        warning.setAccessible(true);
        if (!"".equals(warning.invoke(null, "")) || !"".equals(warning.invoke(null, (Object) null)))
            throw new AssertionError("Supported GPU must not show a missing-feature warning");
        String note = (String) warning.invoke(null, "geometryShader, logicOp, wideLines, timelineSemaphore");
        if (!note.contains("geometryShader, logicOp, wideLines 외 1개")
                || !note.contains("게임이 시작되지 않을 수 있어요."))
            throw new AssertionError("GPU warning must name missing features and explain the risk");
        if (((String) warning.invoke(null, "logicOp")).contains("외 "))
            throw new AssertionError("Single missing feature must not invent extra features");
        Field loaded = NativeLibrary.class.getDeclaredField("loadedBackend");
        loaded.setAccessible(true);
        loaded.set(null, "pocket_switch");
        try {
            if (!NativeLibrary.switchVulkanDiagnostic().contains("다른 Switch 엔진"))
                throw new AssertionError("GPU probe ignored the already loaded backend");
        } finally {
            loaded.set(null, null);
        }
        NativeLibrary.Listener owner = (NativeLibrary.Listener) Proxy.newProxyInstance(
                NativeLibrary.Listener.class.getClassLoader(),
                new Class<?>[]{NativeLibrary.Listener.class}, (proxy, method, values) -> null);
        Field listener = NativeLibrary.class.getDeclaredField("listener");
        listener.setAccessible(true);
        listener.set(null, new WeakReference<>(owner));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch detached = new CountDownLatch(1);
        Thread nativeCall = new Thread(() -> {
            synchronized (NativeLibrary.class) {
                locked.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        });
        nativeCall.start();
        if (!locked.await(1, TimeUnit.SECONDS)) throw new AssertionError("Could not hold native monitor");
        Thread uiDetach = new Thread(() -> { NativeLibrary.detach(owner); detached.countDown(); });
        uiDetach.start();
        boolean responsive = detached.await(300, TimeUnit.MILLISECONDS);
        release.countDown();
        nativeCall.join(1_000);
        uiDetach.join(1_000);
        if (!responsive) throw new AssertionError("UI detach blocked behind native diagnostic");
        listener.set(null, new WeakReference<>(null));
        File parent = Files.createTempDirectory("pocket-switch-path-").toFile();
        File files = new File(parent, "files");
        File sibling = new File(parent, "files-other");
        File outside = new File(parent, "outside");
        File link = new File(files, "outside-link");
        try {
            if (!files.mkdir() || !sibling.mkdir() || !outside.mkdir()) throw new AssertionError();
            Method within = NativeLibrary.class.getDeclaredMethod("within", File.class, File.class);
            within.setAccessible(true);
            if (!(Boolean) within.invoke(null, files, files)) throw new AssertionError("base is private");
            if (!(Boolean) within.invoke(null, new File(files, "game.nro"), files))
                throw new AssertionError("child is private");
            if ((Boolean) within.invoke(null, sibling, files))
                throw new AssertionError("prefix sibling escaped");
            Files.createSymbolicLink(link.toPath(), outside.toPath());
            if ((Boolean) within.invoke(null, new File(link, "secret"), files))
                throw new AssertionError("symlink escaped");
            if (NativeLibrary.getParentDirectory("relative-file") != null)
                throw new AssertionError("relative native path accepted");
            if (NativeLibrary.class.getMethod("probeNativeBridge").getReturnType() != int.class)
                throw new AssertionError("Native bridge probe ABI changed");
            Class<?> config = SoftwareKeyboard.KeyboardConfig.class;
            Class<?> data = SoftwareKeyboard.KeyboardData.class;
            if (config.getField("max_text_length").getType() != int.class
                    || config.getField("disable_cancel_button").getType() != boolean.class
                    || config.getField("initial_text").getType() != String.class
                    || data.getField("result").getType() != int.class
                    || data.getField("text").getType() != String.class
                    || SoftwareKeyboard.class.getMethod("executeNormal", config).getReturnType() != data) {
                throw new AssertionError("Pinned native keyboard ABI changed");
            }
            System.out.println("SWITCH_JAVA_PATH_PASS");
        } finally {
            Files.deleteIfExists(link.toPath());
            Files.deleteIfExists(outside.toPath());
            Files.deleteIfExists(sibling.toPath());
            Files.deleteIfExists(files.toPath());
            Files.deleteIfExists(parent.toPath());
        }
    }
}
