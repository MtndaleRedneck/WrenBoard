package com.wrenboard.imetarget;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// CI-only: serves test fixture files bundled in assets via content:// so the importer can be driven
// through its real VIEW intent. Not part of the released app.
public class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException(name);
        final InputStream in;
        InputStream opened;
        try {
            opened = getContext().getAssets().open(name);
        } catch (IOException e) {
            // fall back to a file placed in this app's private files dir by the test harness
            try {
                opened = new java.io.FileInputStream(new java.io.File(getContext().getFilesDir(), name));
            } catch (IOException e2) {
                throw new FileNotFoundException(name);
            }
        }
        in = opened;
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (InputStream i = in; OutputStream o = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = i.read(b)) >= 0) o.write(b, 0, n);
                } catch (IOException ignored) { }
            }).start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(name);
        }
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
