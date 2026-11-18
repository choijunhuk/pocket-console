package org.yuzu.yuzu_emu;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** The Pocket Console callbacks expected by the pinned native Switch backend. */
public final class NativeLibrary {
    public interface Listener {
        Context context();
        default boolean isEnding() { return false; }
        void onLoaded();
        void onFirstFramePresented();
        void onStopped(int result);
        void onProgramChanged(int index);
        void onShaderProgress(int stage, int progress, int maximum);
        void onUnsupportedApplet(String reason);
        void onExternalUrl(String url);
    }

    private static volatile WeakReference<Listener> listener = new WeakReference<>(null);
    private static final Object listenerLock = new Object();
    private static volatile boolean initialized;
    private static String loadedBackend;

    private NativeLibrary() {}

    public static native int probeNativeBridge();
    public static native String pocketVulkanDiagnostic();
    public static native String pocketVulkanWarning();

    public static String switchVulkanWarning() {
        try {
            String missing = pocketVulkanWarning();
            return formatVulkanWarning(missing);
        } catch (LinkageError error) {
            return "GPU 호환성을 확인하지 못했어요. 게임이 시작되지 않을 수 있어요.";
        }
    }

    private static String formatVulkanWarning(String missing) {
        if (missing == null || missing.isEmpty()) return "";
        String[] items = missing.split(", ");
        String names = String.join(", ", java.util.Arrays.copyOf(items, Math.min(3, items.length)));
        if (items.length > 3) names += " 외 " + (items.length - 3) + "개";
        return "GPU 기능 부족: " + names
                + ". 그래픽이 잘못 표시되거나 게임이 시작되지 않을 수 있어요.";
    }

    public static synchronized String switchVulkanDiagnostic() {
        if (loadedBackend != null && !"pocket_switch2".equals(loadedBackend))
            return "다른 Switch 엔진이 이미 실행 중이에요.";
        try {
            if (loadedBackend == null) {
                System.loadLibrary("pocket_switch2");
                loadedBackend = "pocket_switch2";
            }
            String diagnostic = pocketVulkanDiagnostic();
            return diagnostic == null ? "Vulkan 기능 확인에 실패했어요." : diagnostic;
        } catch (LinkageError error) {
            return "Switch 엔진 또는 Vulkan 진단 기능을 사용할 수 없어요.";
        }
    }
    public static native long resetPocketStopRequest();
    public static native void requestPocketStop(long generation);
    public static native void haltPocketSession(long generation);
    public static native void pausePocketSession(long generation);
    public static native void unpausePocketSession(long generation);
    public static native void refreshPocketSurface(long generation);

    public static synchronized void initialize(Listener owner, String backend)
            throws ReflectiveOperationException {
        if (!"pocket_switch2".equals(backend) && !"pocket_switch".equals(backend)) {
            throw new IllegalArgumentException("Unexpected native backend");
        }
        if (loadedBackend != null && !loadedBackend.equals(backend)) {
            throw new IllegalStateException("Another Switch backend is loaded in this process");
        }
        synchronized (listenerLock) { listener = new WeakReference<>(owner); }
        if (initialized) return;
        if (loadedBackend == null) {
            System.loadLibrary(backend);
            loadedBackend = backend;
        }
        Class<?> sdl = Class.forName("org.libsdl.app.SDL");
        sdl.getMethod("setupJNI").invoke(null);
        initJvm();
        initialized = true;
    }

    public static void detach(Listener owner) {
        synchronized (listenerLock) {
            if (listener.get() == owner) listener = new WeakReference<>(null);
        }
    }

    private static Listener current() {
        return listener.get();
    }

    public static Listener activeListener() {
        return current();
    }

    public static void unsupportedApplet(String reason) {
        Listener owner = current();
        if (owner != null) owner.onUnsupportedApplet(reason);
    }

    public static void onEmulationStarted() {
        Listener owner = current();
        if (owner != null) owner.onLoaded();
    }

    public static void onFirstFramePresented() {
        Listener owner = current();
        if (owner != null) owner.onFirstFramePresented();
    }

    public static void onEmulationStopped(int result) {
        Listener owner = current();
        if (owner != null) owner.onStopped(result);
    }

    public static void onProgramChanged(int index) {
        Listener owner = current();
        if (owner != null) owner.onProgramChanged(index);
    }

    public static void exitEmulationActivity(int result) {
        onEmulationStopped(result);
    }

    public static void loadProgress(int stage, int progress, int maximum) {
        Listener owner = current();
        if (owner != null) owner.onShaderProgress(stage, progress, maximum);
    }

    public static void openExternalUrl(String url) {
        Listener owner = current();
        if (owner != null) owner.onExternalUrl(url);
    }

    private static boolean within(File file, File root) throws IOException {
        String base = root.getCanonicalPath();
        String value = file.getCanonicalPath();
        return value.equals(base) || value.startsWith(base + File.separator);
    }

    private static File privatePath(String path) {
        Listener owner = current();
        return owner == null ? null : privatePath(owner.context(), path);
    }

    public static File privatePath(Context context, String path) {
        if (context == null || path == null) return null;
        File file = new File(path);
        if (!file.isAbsolute()) return null;
        try {
            if (within(file, context.getFilesDir())
                    || within(file, context.getCacheDir())) return file.getCanonicalFile();
        } catch (IOException ignored) {}
        return null;
    }

    public static boolean copyFileToStorage(String source, String destinationDirectory) {
        Listener owner = current();
        if (owner == null || source == null || destinationDirectory == null) return false;
        File files = owner.context().getFilesDir();
        File cache = owner.context().getCacheDir();
        File from = new File(source);
        File destination = new File(destinationDirectory);
        if (!from.isAbsolute() || !destination.isAbsolute()) return false;
        Path temporary = null;
        try {
            if (!from.isFile() || !(within(from, files) || within(from, cache))
                    || !within(destination, files) || !destination.isDirectory()) return false;
            File target = new File(destination, from.getName());
            if (!within(target, files)) return false;
            temporary = Files.createTempFile(destination.toPath(), ".pocket-copy-", ".tmp");
            Files.copy(from.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    public static boolean exists(String path) {
        File file = privatePath(path);
        return file != null && file.exists();
    }

    public static String getFileExtension(String path) {
        if (path == null) return "";
        String name = new File(path).getName();
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    public static String getParentDirectory(String path) {
        File file = privatePath(path);
        if (file == null) return null;
        String parent = file.getParent();
        return parent == null || privatePath(parent) == null ? null : parent;
    }

    public static String getFilename(String path) {
        File file = privatePath(path);
        return file == null ? "" : file.getName();
    }

    public static long getSize(String path) {
        File file = privatePath(path);
        return file == null ? 0 : file.length();
    }

    public static boolean isDirectory(String path) {
        File file = privatePath(path);
        return file != null && file.isDirectory();
    }

    public static int openContentUri(String path, String mode) {
        // shortcut: content URIs are unsupported, add a validated SAF bridge before direct provider loading.
        return -1;
    }

    private static native void initJvm();
    public static native void setAppDirectory(String path);
    public static native void initializeGpuDriver(
            String libraryDirectory, String customDirectory, String customDriver, String redirectDirectory);
    public static native void initializeSystem(boolean reload);
    public static native void surfaceChanged(android.view.Surface surface);
    public static native void surfaceDestroyed();
    public static native void run(String path, int programIndex, boolean frontendInitiated);
    public static native boolean isRunning();
}
