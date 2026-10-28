import org.futo.inputmethod.latin.utils.SafeFiles;
import java.io.*;
import java.nio.file.*;

public class SafeFilesTest {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static boolean rejects(File base, String rel) {
        try { SafeFiles.resolveContained(base, rel); return false; }
        catch (SecurityException e) { return true; } catch (IOException e) { return false; }
    }
    public static void main(String[] a) throws Exception {
        Path root = Files.createTempDirectory("sf");
        File base = new File(root.toFile(), "themes"); base.mkdirs();
        File sibling = new File(root.toFile(), "themes_evil"); sibling.mkdirs();
        File outside = new File(root.toFile(), "secret.txt"); Files.write(outside.toPath(), "s".getBytes());
        Files.createSymbolicLink(new File(base, "link").toPath(), sibling.toPath());
        for (String bad : new String[]{"../secret.txt", "a/../../secret.txt", "/etc/passwd", "../themes_evil/x", "..", ".", "", "link/x", "a\\..\\b", "x\0y", "../themes/../secret.txt"})
            check("reject [" + bad.replace("\0","NUL") + "]", rejects(base, bad));
        for (String good : new String[]{"abc.zip", "sub/dir/file.txt", "a..b.zip", "UserHistoryDictionary.en_US.1/x"})
            { try { File f = SafeFiles.resolveContained(base, good); check("accept [" + good + "]", f.getPath().startsWith(base.getCanonicalPath() + File.separator)); } catch (Exception e) { check("accept [" + good + "]", false); } }
        for (String id : new String[]{"../../x", "a/b", "ab", "abc/", ".abc", "a b c", "..zip", "x\0yz", ""})
            check("themeid reject [" + id.replace("\0","NUL") + "]", !SafeFiles.isValidThemeId(id));
        for (String id : new String[]{"Orange-Black", "my_theme.v2", "abc", "Theme123"})
            check("themeid accept [" + id + "]", SafeFiles.isValidThemeId(id));
        // atomic copy keeps existing file when input fails midway
        File dest = new File(base, "keep.zip"); Files.write(dest.toPath(), "old".getBytes());
        InputStream bad = new InputStream() { int n=0; public int read() throws IOException { throw new IOException("x"); }
            public int read(byte[] b) throws IOException { if (n++ == 0) { b[0]=1; return 1; } throw new IOException("boom"); } };
        boolean threw = false; try { SafeFiles.copyToFileAtomic(bad, dest); } catch (IOException e) { threw = true; }
        check("failed copy throws", threw);
        check("failed copy leaves original intact", new String(Files.readAllBytes(dest.toPath())).equals("old"));
        check("failed copy leaves no .part", !new File(base, "keep.zip.part").exists());
        SafeFiles.copyToFileAtomic(new ByteArrayInputStream("new".getBytes()), dest);
        check("good copy replaces", new String(Files.readAllBytes(dest.toPath())).equals("new") && !new File(base,"keep.zip.part").exists());
        check("outside file untouched", new String(Files.readAllBytes(outside.toPath())).equals("s"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
