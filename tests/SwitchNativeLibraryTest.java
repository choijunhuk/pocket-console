import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.yuzu.yuzu_emu.NativeLibrary;

public final class SwitchNativeLibraryTest {
    public static void main(String[] args) throws Exception {
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
