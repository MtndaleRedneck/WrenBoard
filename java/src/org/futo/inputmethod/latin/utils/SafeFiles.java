package org.futo.inputmethod.latin.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * WrenBoard: path containment and bounded copy helpers for imported archives and files.
 * Plain JDK only, so the logic can be tested off-device.
 */
public final class SafeFiles {
    private SafeFiles() {}

    /** Free space that must remain on the target volume while importing. */
    public static final long FREE_SPACE_FLOOR_BYTES = 32L * 1024 * 1024;
    private static final long CHECK_INTERVAL_BYTES = 1024 * 1024;

    private static final Pattern THEME_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,}$");

    public static boolean isValidThemeId(String id) {
        return id != null && id.length() <= 128 && THEME_ID.matcher(id).matches();
    }

    /**
     * Resolves {@code relative} under {@code base} and verifies the canonical result is strictly
     * inside the canonical base (symlinks resolved, "..", absolute and sibling-prefix paths rejected).
     * @throws SecurityException if the result is not strictly inside base.
     */
    public static File resolveContained(File base, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.indexOf('\0') >= 0
                || relative.startsWith("/") || relative.startsWith("\\") || relative.indexOf('\\') >= 0) {
            throw new SecurityException("Unsafe path: " + relative);
        }
        final File canonicalBase = base.getCanonicalFile();
        final File out = new File(canonicalBase, relative).getCanonicalFile();
        if (!out.getPath().startsWith(canonicalBase.getPath() + File.separator)) {
            throw new SecurityException("Path is outside of the target directory: " + relative);
        }
        return out;
    }

    /** Copies with a free-space floor on {@code spaceDir}'s volume. Throws IOException when below the floor. */
    public static long copyBounded(InputStream in, OutputStream out, File spaceDir) throws IOException {
        final byte[] buf = new byte[16384];
        long total = 0, sinceCheck = CHECK_INTERVAL_BYTES; // check before first write
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (sinceCheck >= CHECK_INTERVAL_BYTES) {
                if (spaceDir.getUsableSpace() < FREE_SPACE_FLOOR_BYTES) {
                    throw new IOException("Not enough free storage space to continue");
                }
                sinceCheck = 0;
            }
            out.write(buf, 0, n);
            total += n;
            sinceCheck += n;
        }
        return total;
    }

    /** Writes to a temporary file next to dest, then moves it into place; the temp file is removed on failure. */
    public static void copyToFileAtomic(InputStream in, File dest) throws IOException {
        final File dir = dest.getParentFile();
        if (dir == null) throw new IOException("No parent directory");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File tmp = new File(dir, dest.getName() + ".part");
        boolean ok = false;
        try (OutputStream out = new java.io.FileOutputStream(tmp)) {
            copyBounded(in, out, dir);
            out.flush();
            ok = true;
        } finally {
            if (!ok) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
        // rename(2) replaces an existing file atomically. Never delete dest first: if the move
        // fails, the original must stay intact.
        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not move imported file into place");
        }
    }
}
