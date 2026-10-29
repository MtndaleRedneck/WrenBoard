package org.futo.inputmethod.latin.uix

import java.io.File
import java.io.IOException

/**
 * Swaps restored learned-word dictionary directories into place. Plain java.io only, so the
 * host test in tools/restore-swap-test compiles this exact file.
 *
 * Each live directory is renamed to ".bak" and the staged one renamed into place. The ".bak"
 * copy is kept as a fallback: ExpandableBinaryDictionary drops it after the restored dictionary
 * opens cleanly, and puts it back if the restored one does not open. On a failed rename the old
 * directory is put back. A kill between the two renames leaves the ".bak", which
 * ExpandableBinaryDictionary restores on the next open.
 * This is rename-based; it makes no power-loss guarantee.
 */
object DictionarySwap {
    /**
     * Read-only check of backup archive entry names, meant to run while the archive is still
     * being validated, before anything is deleted or written. Every learned-word directory in
     * the archive must bring both its header and its body file, named as the restore staging
     * lays them out ("userdict/<dir>/<dir>.header" and "userdict/<dir>/<dir>.body"); an
     * incomplete or unexpected backup is rejected up front, while existing data is untouched.
     */
    fun validateEntryNames(entryNames: List<String>) {
        val filesByDir = sortedMapOf<String, MutableSet<String>>()
        for(name in entryNames) {
            if(!name.startsWith("userdict/")) continue
            val parts = name.split('/')
            if(parts.size != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw IOException("Backup holds a learned-word entry with an unexpected name; existing data unchanged")
            }
            val dir = parts[1]
            val file = parts[2]
            if(file != "$dir.header" && file != "$dir.body") {
                throw IOException("Backup holds a learned-word entry with an unexpected name; existing data unchanged")
            }
            filesByDir.getOrPut(dir) { mutableSetOf() }.add(file)
        }
        for((dir, files) in filesByDir) {
            if(files != setOf("$dir.header", "$dir.body")) {
                throw IOException("Backup learned-word data for $dir is incomplete; existing data unchanged")
            }
        }
    }

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
            // Keep the ".bak" copy of the previous dictionary as the fallback noted above.
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
