package dev.pocketconsole;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.yuzu.yuzu_emu.NativeLibrary;

/**
 * Installs and removes user-supplied Switch keys and firmware in the expendable :switch2 process,
 * the only process that may load the engine. Key contents are never read back, logged or sent out.
 */
public final class SwitchContentService extends Service {
    public static final String INSTALL_KEYS = "install_keys";
    public static final String INSTALL_FIRMWARE = "install_firmware";
    public static final String DELETE_KEYS = "delete_keys";
    public static final String DELETE_FIRMWARE = "delete_firmware";
    public static final String MESSAGE = "message";
    public static final String KEYS = "keys";
    public static final String FIRMWARE_FILES = "firmware_files";
    public static final String VERSION = "version";
    private static final String EXTRA_RECEIVER = "receiver";
    private static final String EXTRA_ACTION = "action";
    private static final String EXTRA_PATHS = "paths";
    private static final long SHORT_DEADLINE_MS = 60_000;
    private static final long FIRMWARE_DEADLINE_MS = 15 * 60_000;
    private static final long KILL_DELAY_MS = 700;
    private static final AtomicInteger BUSY = new AtomicInteger();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());

    /** True while keys or firmware are being changed; the player must not start meanwhile. */
    static boolean isBusy() { return BUSY.get() > 0; }

    /** {@code paths} are private cache files the library copied from the picked documents. */
    public static void start(Context context, String action, String[] paths, ResultReceiver receiver) {
        context.startService(new Intent(context, SwitchContentService.class)
                .putExtra(EXTRA_ACTION, action).putExtra(EXTRA_PATHS, paths)
                .putExtra(EXTRA_RECEIVER, receiver));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver receiver = intent == null ? null : Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class)
                : intent.getParcelableExtra(EXTRA_RECEIVER);
        String action = intent == null ? null : intent.getStringExtra(EXTRA_ACTION);
        String[] paths = intent == null ? null : intent.getStringArrayExtra(EXTRA_PATHS);
        if (receiver == null || action == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (paths == null) paths = new String[0];
        File cache = getCacheDir();
        File root = SwitchContentStatus.userRoot(getFilesDir());
        if (Switch2PlayerActivity.isProcessSessionActive()) {
            deleteTemps(cache, paths);
            receiver.send(1, status(root, false,
                    "Switch 실행 중에는 키와 펌웨어를 바꿀 수 없어요. 게임을 끝낸 뒤 다시 시도하세요.", null));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        BUSY.incrementAndGet();
        final String[] picked = paths;
        final boolean[] done = {false};
        boolean firmware = INSTALL_FIRMWARE.equals(action);
        boolean purge = DELETE_KEYS.equals(action) || DELETE_FIRMWARE.equals(action)
                || INSTALL_KEYS.equals(action);
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            android.util.Log.w("PocketSwitchContent", "SWITCH_CONTENT_TIMEOUT");
            deleteTemps(cache, picked);
            receiver.send(1, status(root, false, "작업 시간이 초과돼 중단했어요. 다시 시도하세요.", null));
            stopSelf(startId);
            release(true);
        };
        main.postDelayed(timeout, firmware ? FIRMWARE_DEADLINE_MS : SHORT_DEADLINE_MS);
        WORK.execute(() -> {
            Bundle result;
            try {
                result = run(action, picked, root);
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                // The class name only: exception text could carry file names from a key import.
                android.util.Log.w("PocketSwitchContent", "failed " + error.getClass().getSimpleName());
                result = status(root, false, "처리하지 못했어요. 파일과 기기 상태를 확인하세요.", null);
            } finally {
                deleteTemps(cache, picked);
            }
            Bundle sent = result;
            main.post(() -> {
                main.removeCallbacks(timeout);
                if (done[0]) return;
                done[0] = true;
                receiver.send(sent.getBoolean("ok") ? 0 : 1, sent);
                stopSelf(startId);
                // Key changes drop any key material the engine kept in memory.
                release(purge);
            });
        });
        return START_NOT_STICKY;
    }

    /**
     * Ends one counted request. When the process is to be killed the request stays counted until the
     * kill decision, so a player cannot start inside the delay and be killed with it.
     */
    private void release(boolean kill) {
        if (!kill) {
            BUSY.decrementAndGet();
            return;
        }
        main.postDelayed(() -> {
            if (Switch2PlayerActivity.isProcessSessionActive()) BUSY.decrementAndGet();
            else android.os.Process.killProcess(android.os.Process.myPid());
        }, KILL_DELAY_MS);
    }

    private static Bundle status(File root, boolean ok, String message, String version) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("ok", ok);
        bundle.putString(MESSAGE, message);
        bundle.putBoolean(KEYS, SwitchContentStatus.keysPresent(root));
        bundle.putInt(FIRMWARE_FILES, SwitchContentStatus.firmwareFileCount(root));
        if (version != null) bundle.putString(VERSION, version);
        return bundle;
    }

    private Bundle run(String action, String[] paths, File root) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) return status(root, false, "Switch 저장 공간을 준비하지 못했어요.", null);
        // A killed or timed-out earlier request may have left a half-finished swap.
        recoverKeys(SwitchContentStatus.keysDir(root));
        recoverFirmware(SwitchContentStatus.registeredDir(root).getParentFile());
        switch (action) {
            case INSTALL_KEYS: return installKeys(paths, root);
            case INSTALL_FIRMWARE: return installFirmware(paths, root);
            case DELETE_KEYS:
                deleteContents(SwitchContentStatus.keysDir(root));
                return status(root, true, "키를 삭제했어요.", "");
            case DELETE_FIRMWARE:
                deleteTree(SwitchContentStatus.registeredDir(root));
                for (String leftover : new String[]{"registered.prev", "registered.staging", "registered.bad"})
                    deleteQuietly(new File(SwitchContentStatus.registeredDir(root).getParentFile(), leftover));
                SwitchContentStatus.saveFirmwareVersion(root, null);
                return status(root, true, "펌웨어를 삭제했어요.", "");
            default: return status(root, false, "알 수 없는 요청이에요.", null);
        }
    }

    interface KeyInstaller { int install(File source) throws Exception; }

    static final class KeyResult {
        final int code;
        final String name;
        KeyResult(int code, String name) { this.code = code; this.name = name; }
    }

    private static final String KEY_MARKER = ".installing";

    /**
     * All-or-nothing key install. The marker lists the targets of this import; until it is removed
     * (the commit point) any failure, exception or crash restores the previous copies.
     */
    static KeyResult installKeyFiles(File keys, java.util.List<File> sources, KeyInstaller installer) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (File source : sources) names.add(source.getName().toLowerCase(Locale.ROOT));
        File marker = new File(keys, KEY_MARKER);
        // Never stack a new import on an unsettled one: that could bury the only good copy.
        if (!recoverKeys(keys) || marker.exists()) throw new IOException("key recovery incomplete");
        boolean committed = false;
        try {
            // Back up EVERY existing target first, so "no .prev" always means "did not exist before".
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String name : names) {
                File target = new File(keys, name);
                File backup = new File(keys, name + ".prev");
                boolean existed = false;
                if (target.isFile()) {
                    // Recovery already ran, so a backup next to a present target is stale.
                    if (backup.exists()) Files.delete(backup.toPath());
                    Files.move(target.toPath(), backup.toPath());
                    existed = true;
                } else if (backup.exists()) {
                    // A backup is the only copy when its target is missing; it stays the backup.
                    existed = true;
                }
                lines.add((existed ? "E:" : "N:") + name);
            }
            // Written only after every backup exists, atomically so it is never half-read.
            File temp = new File(keys, KEY_MARKER + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                out.write(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync(); // Durable before the rename, so power loss cannot leave an empty marker.
            }
            Files.move(temp.toPath(), marker.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            for (int i = 0; i < sources.size(); i++) {
                int code = installer.install(sources.get(i));
                if (code != 0) return new KeyResult(code, names.get(i));
            }
            Files.delete(marker.toPath());
            committed = true;
            for (String name : names) Files.deleteIfExists(new File(keys, name + ".prev").toPath());
            return new KeyResult(0, "");
        } finally {
            if (!committed) recoverKeys(keys);
        }
    }

    private static boolean validKeyName(String name) {
        return name.equals("prod.keys") || name.equals("title.keys");
    }

    /**
     * Restores or settles an interrupted key install and is safe to run again after any failure.
     * The marker records, per name, whether the key existed before ("E:", backed up) or not ("N:").
     * Returns false when it could not finish; callers must then refuse to start another import.
     */
    static boolean recoverKeys(File keys) {
        if (!keys.exists()) return true;
        if (!keys.isDirectory()) return false;
        File marker = new File(keys, KEY_MARKER);
        try {
            Files.deleteIfExists(new File(keys, KEY_MARKER + ".tmp").toPath());
            if (marker.isFile()) {
                java.util.List<String> lines = Files.readAllLines(marker.toPath(),
                        java.nio.charset.StandardCharsets.UTF_8);
                // Parse everything first: on any doubt change nothing and keep the marker.
                // installKeyFiles never writes an empty marker, so an empty one is damage, not "nothing to do".
                if (lines.isEmpty()) return false;
                for (String line : lines) {
                    if (line.length() < 3 || (line.charAt(0) != 'E' && line.charAt(0) != 'N')
                            || line.charAt(1) != ':' || !validKeyName(line.substring(2))) return false;
                }
                for (String line : lines) {
                    File target = new File(keys, line.substring(2));
                    File backup = new File(keys, line.substring(2) + ".prev");
                    if (line.charAt(0) == 'E') {
                        // Backup gone means an earlier recovery already put it back: leave the target.
                        if (backup.isFile()) Files.move(backup.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.deleteIfExists(target.toPath());
                    }
                }
                Files.delete(marker.toPath());
            }
            // No marker: the import committed or never began. A backup is stale unless its key is missing.
            File[] rest = keys.listFiles((dir, name) -> name.endsWith(".prev"));
            if (rest != null) for (File backup : rest) {
                String name = backup.getName();
                File target = new File(keys, name.substring(0, name.length() - 5));
                if (target.isFile()) Files.deleteIfExists(backup.toPath());
                else Files.move(backup.toPath(), target.toPath());
            }
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private Bundle installKeys(String[] paths, File root) throws Exception {
        if (paths.length == 0) return status(root, false, "선택한 키 파일이 없어요.", null);
        File keys = SwitchContentStatus.keysDir(root);
        if (!keys.isDirectory() && !keys.mkdirs()) return status(root, false, "키 폴더를 만들지 못했어요.", null);
        File cache = getCacheDir();
        // prod.keys first: title.keys alone never satisfies the engine's key check.
        java.util.Arrays.sort(paths, (a, b) -> Boolean.compare(!a.endsWith("prod.keys"), !b.endsWith("prod.keys")));
        java.util.List<File> sources = new java.util.ArrayList<>();
        for (String path : paths) {
            File file = NativeLibrary.privatePath(this, path);
            if (file == null || !file.getParentFile().getParentFile().equals(cache.getCanonicalFile())
                    || !SwitchKeyFile.isAcceptedName(file.getName()))
                return status(root, false, "prod.keys 또는 title.keys 파일만 가져올 수 있어요.", null);
            try (InputStream in = new FileInputStream(file)) {
                SwitchKeyFile.Check check = SwitchKeyFile.check(in, file.getName());
                if (!check.ok()) return status(root, false, check.message(), null);
            }
            sources.add(file);
        }
        ensureLibrary(root);
        KeyResult result = installKeyFiles(keys, sources,
                source -> NativeLibrary.installKeys(source.getAbsolutePath(), "keys"));
        if (result.code != 0) return status(root, false, describeKeyResult(result.code, result.name), null);
        return status(root, true, "키를 설치했어요. 이 키로 연 게임은 실험적이며 호환성은 확인되지 않았어요.", null);
    }

    /** Maps FirmwareManager::KeyInstallResult. */
    static String describeKeyResult(int code, String fileName) {
        switch (code) {
            case 0: return "키를 설치했어요.";
            case 1: return "키 폴더를 열 수 없어요. 저장 공간을 확인하세요.";
            case 2: return "키 파일을 복사하지 못했어요. 저장 공간을 확인하세요.";
            case 3: return "prod.keys 또는 title.keys 파일이 아니에요.";
            case 4: return "title.keys".equals(fileName)
                    ? "prod.keys가 먼저 필요해요. 본인 콘솔에서 덤프한 prod.keys를 먼저 가져오세요."
                    : "키는 읽었지만 필요한 키가 모자라요. 본인 콘솔에서 덤프한 완전한 prod.keys가 필요해요.";
            default: return "알 수 없는 키 설치 결과예요 (" + code + ").";
        }
    }

    private Bundle installFirmware(String[] paths, File root) throws Exception {
        if (!SwitchContentStatus.keysPresent(root))
            return status(root, false, "펌웨어를 확인하려면 먼저 키를 가져와야 해요.", null);
        File zip = paths.length == 1 ? NativeLibrary.privatePath(this, paths[0]) : null;
        if (zip == null || !zip.isFile()
                || !zip.getParentFile().getParentFile().equals(getCacheDir().getCanonicalFile()))
            return status(root, false, "펌웨어 zip을 준비하지 못했어요.", null);
        File registered = SwitchContentStatus.registeredDir(root);
        File contents = registered.getParentFile();
        File staging = new File(contents, "registered.staging");
        if (!contents.isDirectory() && !contents.mkdirs()) return status(root, false, "펌웨어 폴더를 만들지 못했어요.", null);
        try {
            SwitchFirmwareZip.extract(zip, staging);
        } catch (IOException | RuntimeException error) {
            deleteQuietly(staging);
            return status(root, false, error instanceof SwitchFirmwareZip.Rejected
                    ? error.getMessage() : "펌웨어 zip을 풀지 못했어요. 저장 공간을 확인하세요.", null);
        }
        File swapping = new File(contents, FW_SWAPPING);
        Files.write(swapping.toPath(), new byte[0]);
        String version = "";
        boolean verified = false;
        try {
            if (registered.exists()) Files.move(registered.toPath(), new File(contents, "registered.prev").toPath());
            Files.move(staging.toPath(), registered.toPath());
            ensureLibrary(root);
            ensureSystem();
            verified = NativeLibrary.isFirmwareAvailable();
            if (verified) {
                version = nonNull(NativeLibrary.firmwareVersion());
                Files.write(new File(contents, FW_VERIFIED).toPath(), new byte[0]);
            }
        } finally {
            // Verified: this commits (drops the old copy). Otherwise: rolls the old copy back.
            recoverFirmware(contents);
            if (!verified) {
                deleteQuietly(staging);
                try { ensureSystem(); } catch (RuntimeException | LinkageError ignored) {}
            }
        }
        if (!verified) {
            return status(root, false, "펌웨어를 엔진이 읽지 못했어요. 키와 펌웨어 버전이 맞는지, zip이 온전한지 확인하세요. 기존 펌웨어는 그대로 두었어요.", null);
        }
        SwitchContentStatus.saveFirmwareVersion(root, version);
        return status(root, true, "펌웨어를 설치했어요.", version);
    }

    private static final String FW_SWAPPING = "registered.swapping";
    private static final String FW_VERIFIED = "registered.verified";

    private static void deleteQuietly(File file) {
        try { deleteTree(file); } catch (IOException | RuntimeException ignored) {}
    }

    /**
     * Settles an interrupted firmware swap: verified means commit (drop the old copy), anything else
     * rolls back to the old copy. Each step is independent so one failure cannot skip the restore.
     */
    static void recoverFirmware(File contents) {
        File registered = new File(contents, "registered");
        File previous = new File(contents, "registered.prev");
        File bad = new File(contents, "registered.bad");
        File swapping = new File(contents, FW_SWAPPING);
        File verified = new File(contents, FW_VERIFIED);
        deleteQuietly(new File(contents, "registered.staging"));
        deleteQuietly(bad);
        if (swapping.exists() && !verified.exists()) {
            try {
                if (registered.exists()) Files.move(registered.toPath(), bad.toPath());
            } catch (IOException ignored) {}
            try {
                if (previous.exists() && !registered.exists()) Files.move(previous.toPath(), registered.toPath());
            } catch (IOException ignored) {}
            deleteQuietly(bad);
        } else {
            deleteQuietly(previous);
        }
        if (!registered.exists() && previous.exists()) return; // could not restore; keep it for next time
        try {
            Files.deleteIfExists(swapping.toPath());
            Files.deleteIfExists(verified.toPath());
        } catch (IOException ignored) {}
    }

    private static String nonNull(String value) { return value == null ? "" : value; }

    /** NativeLibrary keeps only a weak reference, so the owner lives in a static field. */
    private static final class Owner implements NativeLibrary.Listener {
        private final Context app;
        Owner(Context app) { this.app = app; }
        @Override public Context context() { return app; }
        @Override public void onLoaded() {}
        @Override public void onFirstFramePresented() {}
        @Override public void onStopped(int result) {}
        @Override public void onProgramChanged(int index) {}
        @Override public void onShaderProgress(int stage, int progress, int maximum) {}
        @Override public void onUnsupportedApplet(String reason) {}
        @Override public void onExternalUrl(String url) {}
    }

    private static Owner owner;

    private synchronized void ensureLibrary(File root) throws ReflectiveOperationException {
        if (owner == null) owner = new Owner(getApplicationContext());
        NativeLibrary.initialize(owner, "pocket_switch2");
        NativeLibrary.setAppDirectory(root.getAbsolutePath());
    }

    private void ensureSystem() {
        // Same gate as a game: no GPU driver setup on a device the Vulkan check rejected.
        if (!NativeLibrary.switchVulkanDiagnostic().isEmpty()) throw new IllegalStateException("vulkan");
        Switch2PlayerActivity.initializeEngineSystem(getApplicationContext());
    }

    private static void deleteTemps(File cache, String[] paths) {
        for (String path : paths) {
            File file = new File(path);
            File parent = file.getParentFile();
            // Only the per-import directories this feature created: cache/switch-import-*/name.
            if (parent == null || !parent.getName().startsWith("switch-import-")
                    || !cache.equals(parent.getParentFile())) continue;
            try { deleteTree(parent); } catch (IOException ignored) {}
        }
    }

    private static void deleteContents(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
    }

    /** Deletes without following symlinks. */
    private static void deleteTree(File file) throws IOException {
        Path path = file.toPath();
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path item, BasicFileAttributes attributes) throws IOException {
                Files.delete(item);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path item, IOException error) throws IOException {
                Files.delete(item);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
