package org.futo.inputmethod.latin.content

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.actions.fonttyper.ImageTyperState
import java.io.File
import java.util.UUID


val FONT_AUTHORITY = BuildConfig.APPLICATION_ID + ".font"
private val CODE_RENDERED_FONT_IMAGE = 1
private val CODE_KLIPY_GIF = 2

private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
    addURI(FONT_AUTHORITY, "render/*", CODE_RENDERED_FONT_IMAGE)
    // WrenBoard 1.5 (item 1): in-memory piped KLIPY GIFs, nothing kept on disk
    // WrenBoard 1.7: MP4 insertions add a format segment (gif/mp4/<uuid>) so getType can
    // report video/mp4; GIF keeps the original gif/<uuid> shape (see KlipyGifPipe).
    addURI(FONT_AUTHORITY, "gif/*", CODE_KLIPY_GIF)
    addURI(FONT_AUTHORITY, "gif/mp4/*", CODE_KLIPY_GIF)
}

class KbContentProvider: ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String?>?,
        selection: String?,
        selectionArgs: Array<out String?>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    private val fileCache = HashMap<Uri, File>()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if(fileCache.containsKey(uri)) return ParcelFileDescriptor.open(
            fileCache[uri],
            ParcelFileDescriptor.MODE_READ_ONLY
        )

        val match = URI_MATCHER.match(uri)
        if (match != CODE_RENDERED_FONT_IMAGE && match != CODE_KLIPY_GIF) {
            throw IllegalArgumentException("Unsupported URI: $uri")
        }

        val id = uri.lastPathSegment ?: throw IllegalArgumentException("Invalid URI")
        if (match == CODE_KLIPY_GIF) {
            return org.futo.inputmethod.latin.uix.gif.KlipyGifPipe.openStream(UUID.fromString(id))
        }
        return ImageTyperState.renderRequest(context!!, UUID.fromString(id))
    }


    override fun getType(uri: Uri): String =
        when(URI_MATCHER.match(uri)) {
            CODE_RENDERED_FONT_IMAGE -> "image/png"
            CODE_KLIPY_GIF -> org.futo.inputmethod.latin.uix.gif.KlipyGifPipe.mimeTypeFor(uri)
            else -> throw IllegalArgumentException("Unsupported URI: $uri")
        }

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = throw UnsupportedOperationException("Provider is read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String?>?
    ): Int = throw UnsupportedOperationException("Provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String?>?
    ): Int = throw UnsupportedOperationException("Provider is read-only")
}
