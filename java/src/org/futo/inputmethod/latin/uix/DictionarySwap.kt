package org.futo.inputmethod.latin.uix

import java.io.File
import java.io.IOException

/**
 * Swaps restored learned-word dictionary directories into place. Plain java.io only, so the
 * host test in tools/restore-swap-test compiles this exact file.
 *
 * Each live directory is renamed to ".bak", the staged one renamed into place, then the backup
 * removed. On a failed rename the old directory is put back. A kill between the two renames
 * leaves the ".bak", which ExpandableBinaryDictionary restores on the next open.
 * This is rename-based; it makes no power-loss guarantee.
 */
object DictionarySwap {
    /** Throws before anything is touched if a staged directory lacks its header or body. */
    fun validate(staged: List<File>) {
        for(dir in staged) {
            val files = dir.list()?.toSet() ?: emptySet()
            if(!files.contains("${dir.name}.header") || !files.contains("${dir.name}.body")) {
                throw IOException("Backup learned-word data for ${dir.name} is incomplete; existing data unchanged")
            }
        }
    }

    fun swap(
        filesDir: File,
        staged: List<File>,
        destructive: Boolean,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        validate(staged)
        for(dir in staged) {
            val live = File(filesDir, dir.name)
            val backup = File(filesDir, "${dir.name}.bak")
            if(backup.exists()) backup.deleteRecursively()
            val hadOld = live.exists()
            if(hadOld && !rename(live, backup)) {
                throw IOException("Could not replace learned words for ${dir.name}; old data kept")
            }
            if(!rename(dir, live)) {
                if(hadOld) rename(backup, live)
                throw IOException("Could not move restored learned words into place for ${dir.name}; old data kept")
            }
            if(hadOld) backup.deleteRecursively()
        }
        if(destructive) {
            // A destructive restore replaces everything: drop dictionaries the backup did not contain
            val restored = staged.map { it.name }.toSet()
            filesDir.listFiles()?.forEach {
                if(it.name.startsWith("UserHistoryDictionary") && !it.name.endsWith(".bak") && it.name !in restored) {
                    it.deleteRecursively()
                }
            }
        }
    }
}
