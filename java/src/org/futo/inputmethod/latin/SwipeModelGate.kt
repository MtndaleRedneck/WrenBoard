package org.futo.inputmethod.latin

import java.io.InputStream

/**
 * WrenBoard: decides whether the bundled FUTO Swipe model files are usable before the swipe engine
 * touches them. A missing asset, an empty file, or a Git LFS pointer left in place of the weights
 * (a "corrupt" build) all mean "not usable": swipe then quietly falls back instead of crashing.
 */
internal object SwipeModelGate {
    private const val LFS_POINTER_PREFIX = "version https://git-lfs"

    /** Smallest size accepted for a .pte model; real FUTO Swipe weights are over a megabyte. */
    const val MIN_MODEL_BYTES = 64 * 1024

    fun isUsable(open: (String) -> InputStream, asset: String, minBytes: Int): Boolean {
        if (asset.isEmpty()) return false
        return try {
            open(asset).use { stream ->
                val head = ByteArray(LFS_POINTER_PREFIX.length)
                var read = 0
                while (read < head.size) {
                    val n = stream.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read >= LFS_POINTER_PREFIX.length && String(head, Charsets.US_ASCII) == LFS_POINTER_PREFIX) {
                    return false
                }
                var total = read.toLong()
                val buf = ByteArray(16 * 1024)
                while (total < minBytes) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                }
                total >= minBytes
            }
        } catch (e: Exception) {
            false
        }
    }

    /** True only when every required asset is usable. */
    fun allUsable(open: (String) -> InputStream, assets: List<Pair<String, Int>>): Boolean =
        assets.all { (name, min) -> isUsable(open, name, min) }
}
