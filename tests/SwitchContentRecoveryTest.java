package dev.pocketconsole;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class SwitchContentRecoveryTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static File write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Stands in for the engine: copies the source into the keys directory like installKeys does. */
    private static SwitchContentService.KeyInstaller copying(File keys, int code, boolean throwing) {
        return source -> {
            Files.copy(source.toPath(), new File(keys, source.getName()).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (throwing) throw new IllegalStateException("engine crashed");
            return code;
        };
    }

    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("pocket-recovery-test-").toFile();
        File keys = new File(base, "keys");
        File incoming = new File(base, "incoming");
        File prod = write(new File(incoming, "prod.keys"), "NEW-PROD");
        File title = write(new File(incoming, "title.keys"), "NEW-TITLE");
        List<File> sources = Arrays.asList(prod, title);

        // Success commits: no backups, no marker.
        write(new File(keys, "prod.keys"), "OLD-PROD");
        SwitchContentService.KeyResult ok = SwitchContentService.installKeyFiles(keys, sources, copying(keys, 0, false));
        check(ok.code == 0 && read(new File(keys, "prod.keys")).equals("NEW-PROD"), "commit installs new keys");
        check(new File(keys, "title.keys").isFile(), "title.keys installed");
        check(!new File(keys, "prod.keys.prev").exists() && !new File(keys, ".installing").exists(), "commit cleans up");

        // Engine failure on the second file: everything from this import is rolled back.
        write(new File(keys, "prod.keys"), "OLD-PROD");
        new File(keys, "title.keys").delete();
        SwitchContentService.KeyInstaller failSecond = source -> {
            Files.copy(source.toPath(), new File(keys, source.getName()).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return source.getName().equals("title.keys") ? 4 : 0;
        };
        SwitchContentService.KeyResult failed = SwitchContentService.installKeyFiles(keys, sources, failSecond);
        check(failed.code == 4 && failed.name.equals("title.keys"), "failure code and file name");
        check(read(new File(keys, "prod.keys")).equals("OLD-PROD"), "old prod.keys restored after failure");
        check(!new File(keys, "title.keys").exists(), "new title.keys removed after failure");
        check(!new File(keys, ".installing").exists() && !new File(keys, "prod.keys.prev").exists(), "no leftovers");

        // An exception mid-install also restores before propagating.
        boolean thrown = false;
        try {
            SwitchContentService.installKeyFiles(keys, sources, copying(keys, 0, true));
        } catch (IllegalStateException expected) {
            thrown = true;
        }
        check(thrown, "exception propagates");
        check(read(new File(keys, "prod.keys")).equals("OLD-PROD"), "old prod.keys restored after exception");
        check(!new File(keys, "title.keys").exists(), "new files removed after exception");

        // Multi-select where the FIRST file fails: the untouched existing title.keys must survive intact.
        for (boolean throwing : new boolean[]{false, true}) {
            write(new File(keys, "prod.keys"), "ORIG-PROD");
            write(new File(keys, "title.keys"), "ORIG-TITLE");
            SwitchContentService.KeyInstaller failFirst = source -> {
                if (throwing) throw new IllegalStateException("engine crashed");
                return 4;
            };
            try {
                SwitchContentService.installKeyFiles(keys, sources, failFirst);
            } catch (IllegalStateException expected) {
                check(throwing, "unexpected exception");
            }
            check(read(new File(keys, "prod.keys")).equals("ORIG-PROD"), "prod.keys byte-identical after first-file failure");
            check(read(new File(keys, "title.keys")).equals("ORIG-TITLE"), "untouched title.keys survives (throwing=" + throwing + ")");
            check(!new File(keys, ".installing").exists() && !new File(keys, "title.keys.prev").exists()
                    && !new File(keys, "prod.keys.prev").exists(), "no leftovers");
        }
        // Same, but only prod.keys existed before: title.keys must not appear after a failure.
        new File(keys, "title.keys").delete();
        write(new File(keys, "prod.keys"), "ORIG-PROD");
        SwitchContentService.installKeyFiles(keys, sources, source -> source.getName().equals("prod.keys") ? 0 : 4);
        check(read(new File(keys, "prod.keys")).equals("ORIG-PROD") && !new File(keys, "title.keys").exists(),
                "later failure restores exactly the earlier state");
        // A backup whose target is missing is moved back, never deleted.
        new File(keys, "prod.keys").delete();
        write(new File(keys, "prod.keys.prev"), "ONLY-GOOD");
        SwitchContentService.installKeyFiles(keys, sources, source -> 4);
        check(read(new File(keys, "prod.keys")).equals("ONLY-GOOD"), "orphan backup restored, not deleted");
        new File(keys, "prod.keys").delete();

        // A killed process: marker present, new file written, backup holds the only good copy.
        File crash = new File(base, "crash-keys");
        write(new File(crash, "prod.keys"), "HALF-NEW");
        write(new File(crash, "prod.keys.prev"), "GOOD");
        write(new File(crash, ".installing"), "E:prod.keys\nN:title.keys");
        write(new File(crash, "title.keys"), "HALF-TITLE");
        SwitchContentService.recoverKeys(crash);
        check(read(new File(crash, "prod.keys")).equals("GOOD"), "crash restores the backup");
        check(!new File(crash, "title.keys").exists() && !new File(crash, ".installing").exists(), "crash cleans new file");
        // Stale backup after a committed install is dropped; a missing key is restored from it.
        write(new File(crash, "title.keys.prev"), "STALE");
        write(new File(crash, "title.keys"), "CURRENT");
        write(new File(crash, "prod.keys.prev"), "STALE");
        SwitchContentService.recoverKeys(crash);
        check(read(new File(crash, "title.keys")).equals("CURRENT") && !new File(crash, "title.keys.prev").exists(),
                "committed target wins over a stale backup");
        new File(crash, "prod.keys").delete();
        write(new File(crash, "prod.keys.prev"), "ONLY-COPY");
        SwitchContentService.recoverKeys(crash);
        check(read(new File(crash, "prod.keys")).equals("ONLY-COPY"), "the only copy is never deleted");

        // Recovery is idempotent: running it again on the same marker state loses nothing.
        File idem = new File(base, "idem-keys");
        write(new File(idem, "prod.keys"), "HALF-NEW");
        write(new File(idem, "prod.keys.prev"), "ORIGINAL");
        write(new File(idem, "title.keys"), "NEW-TITLE");
        write(new File(idem, ".installing"), "E:prod.keys\nN:title.keys");
        check(SwitchContentService.recoverKeys(idem), "first recovery");
        check(SwitchContentService.recoverKeys(idem), "second recovery");
        check(read(new File(idem, "prod.keys")).equals("ORIGINAL") && !new File(idem, "title.keys").exists(),
                "idempotent recovery keeps the restored original");
        // Crash mid-recovery: backup already moved back but the marker is still there.
        write(new File(idem, ".installing"), "E:prod.keys\nN:title.keys");
        check(SwitchContentService.recoverKeys(idem), "recovery with the backup already moved back");
        check(read(new File(idem, "prod.keys")).equals("ORIGINAL"), "restored original is not deleted by a repeat");
        check(!new File(idem, ".installing").exists(), "marker removed last");
        // An unparseable marker changes nothing and reports failure.
        write(new File(idem, ".installing"), "E:prod.keys\ngarbage");
        write(new File(idem, "title.keys"), "KEEP-ME");
        check(!SwitchContentService.recoverKeys(idem), "unparseable marker is a failure");
        check(read(new File(idem, "prod.keys")).equals("ORIGINAL") && read(new File(idem, "title.keys")).equals("KEEP-ME"),
                "never delete on doubt");
        // ...and the next import refuses rather than burying the good copy.
        write(new File(idem, "prod.keys.prev"), "ONLY-GOOD");
        write(new File(idem, "prod.keys"), "HALF-NEW");
        boolean refused = false;
        try {
            SwitchContentService.installKeyFiles(idem, Arrays.asList(prod), source -> 0);
        } catch (IOException expected) {
            refused = true;
        }
        check(refused, "import refuses while recovery is incomplete");
        check(read(new File(idem, "prod.keys.prev")).equals("ONLY-GOOD"), "the good backup was not overwritten");
        new File(idem, ".installing").delete();
        new File(idem, "prod.keys.prev").delete();

        // Read-only keys directory: recovery fails, the import refuses, nothing is lost.
        File locked = new File(base, "locked-keys");
        write(new File(locked, "prod.keys"), "HALF-NEW");
        write(new File(locked, "prod.keys.prev"), "GOOD");
        write(new File(locked, ".installing"), "E:prod.keys");
        if (locked.setWritable(false) && !Files.isWritable(locked.toPath())) {
            check(!SwitchContentService.recoverKeys(locked), "read-only directory fails recovery");
            boolean blocked = false;
            try {
                SwitchContentService.installKeyFiles(locked, Arrays.asList(prod), source -> 0);
            } catch (IOException expected) {
                blocked = true;
            }
            locked.setWritable(true);
            check(blocked, "import refuses on a read-only directory");
            check(read(new File(locked, "prod.keys.prev")).equals("GOOD"), "backup survives a failed recovery");
            check(SwitchContentService.recoverKeys(locked) && read(new File(locked, "prod.keys")).equals("GOOD"),
                    "recovery completes once the directory is writable");
        } else {
            locked.setWritable(true);
        }

        // Two imports in a row with a stale backup left by a committed one.
        File chain = new File(base, "chain-keys");
        write(new File(chain, "prod.keys"), "V1");
        write(new File(chain, "prod.keys.prev"), "STALE-V0");
        SwitchContentService.installKeyFiles(chain, Arrays.asList(prod), copying(chain, 0, false));
        check(read(new File(chain, "prod.keys")).equals("NEW-PROD") && !new File(chain, "prod.keys.prev").exists(),
                "stale backup replaced by the new import");
        write(new File(incoming, "prod.keys"), "V3");
        SwitchContentService.installKeyFiles(chain, Arrays.asList(prod), source -> 4);
        check(read(new File(chain, "prod.keys")).equals("NEW-PROD"), "second import failure restores the first import");
        write(new File(incoming, "prod.keys"), "NEW-PROD");

        // Firmware: interrupted before verification rolls back to the previous firmware.
        File contents = new File(base, "Contents");
        write(new File(contents, "registered/new.nca"), "n");
        write(new File(contents, "registered.prev/old.nca"), "o");
        write(new File(contents, "registered.swapping"), "");
        write(new File(contents, "registered.staging/x.nca"), "x");
        SwitchContentService.recoverFirmware(contents);
        check(new File(contents, "registered/old.nca").isFile() && !new File(contents, "registered/new.nca").exists(),
                "unverified swap is rolled back");
        check(!new File(contents, "registered.prev").exists() && !new File(contents, "registered.swapping").exists()
                && !new File(contents, "registered.staging").exists(), "rollback leaves no markers");
        // Verified: commit and drop the old copy.
        write(new File(contents, "registered.prev/old2.nca"), "o");
        write(new File(contents, "registered.swapping"), "");
        write(new File(contents, "registered.verified"), "");
        new File(contents, "registered/old.nca").renameTo(new File(contents, "registered/new2.nca"));
        SwitchContentService.recoverFirmware(contents);
        check(new File(contents, "registered/new2.nca").isFile() && !new File(contents, "registered.prev").exists()
                && !new File(contents, "registered.verified").exists(), "verified swap commits");
        // First install, never verified: nothing to restore, so the unverified copy goes.
        File fresh = new File(base, "Fresh");
        write(new File(fresh, "registered/a.nca"), "a");
        write(new File(fresh, "registered.swapping"), "");
        SwitchContentService.recoverFirmware(fresh);
        check(!new File(fresh, "registered").exists(), "unverified first install removed");
        System.out.println("SWITCH_CONTENT_RECOVERY_PASS");
    }
}
