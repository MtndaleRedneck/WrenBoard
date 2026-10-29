import org.futo.inputmethod.latin.uix.DictionarySwap
import java.io.File
import java.io.IOException
import java.nio.file.Files

var failures = 0
fun check(name: String, ok: Boolean) { println((if(ok) "PASS " else "FAIL ") + name); if(!ok) failures++ }

fun mkdict(root: File, name: String, tag: String, header: Boolean = true, body: Boolean = true): File {
    val d = File(root, name); d.mkdirs()
    if(header) File(d, "$name.header").writeText("H-$tag")
    if(body) File(d, "$name.body").writeText("B-$tag")
    return d
}
fun tag(root: File, name: String): String? = File(root, "$name/$name.body").takeIf { it.exists() }?.readText()
fun fresh(): Pair<File, File> {
    val base = Files.createTempDirectory("swap").toFile()
    return File(base, "files").also { it.mkdirs() } to File(base, "files/restore-dict-stage").also { it.mkdirs() }
}
fun staged(stage: File) = stage.listFiles()!!.filter { it.isDirectory }
val N = "UserHistoryDictionary.en_US.dict"
val M = "UserHistoryDictionary.fr.dict"

fun main() {
    run { // normal replace
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(st, N, "new")
        DictionarySwap.swap(fd, staged(st), false)
        check("replace: live has new", tag(fd, N) == "B-new")
        check("replace: no .bak left", !File(fd, "$N.bak").exists())
    }
    run { // incomplete staged dir leaves old data untouched
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(st, N, "new", body = false)
        val threw = try { DictionarySwap.swap(fd, staged(st), false); false } catch(e: IOException) { true }
        check("missing body: rejected", threw)
        check("missing body: old data intact", tag(fd, N) == "B-old" && !File(fd, "$N.bak").exists())
    }
    run { // second dir incomplete: first dir must not be swapped either
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(fd, M, "oldM")
        mkdict(st, N, "new"); mkdict(st, M, "newM", header = false)
        val threw = try { DictionarySwap.swap(fd, staged(st), false); false } catch(e: IOException) { true }
        check("one bad of two: rejected before any swap", threw && tag(fd, N) == "B-old" && tag(fd, M) == "B-oldM")
    }
    run { // fail moving live -> .bak
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(st, N, "new")
        val threw = try { DictionarySwap.swap(fd, staged(st), false) { _, _ -> false }; false } catch(e: IOException) { true }
        check("inject fail live->bak: throws", threw)
        check("inject fail live->bak: old intact", tag(fd, N) == "B-old")
    }
    run { // fail moving staged -> live: old restored
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(st, N, "new")
        val threw = try {
            DictionarySwap.swap(fd, staged(st), false) { a, b -> if(a.parentFile == st) false else a.renameTo(b) }; false
        } catch(e: IOException) { true }
        check("inject fail new->live: throws", threw)
        check("inject fail new->live: old restored, no torn state", tag(fd, N) == "B-old" && File(fd, "$N/$N.header").readText() == "H-old")
    }
    run { // second dir fails after first swapped: first is new, second is old (each dir whole)
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(fd, M, "oldM"); mkdict(st, N, "new"); mkdict(st, M, "newM")
        val order = staged(st).sortedBy { it.name }
        val failName = order[1].name
        try { DictionarySwap.swap(fd, order, false) { a, b -> if(a.name == failName && a.parentFile == st) false else a.renameTo(b) } } catch(e: IOException) {}
        val a = tag(fd, order[0].name); val b = tag(fd, order[1].name)
        check("second dir fails: every live dir is wholly old or wholly new", (a == "B-new" || a == "B-old" || a == "B-oldM" || a == "B-newM") && b != null)
        check("second dir fails: failed dir kept old", b!!.contains("old"))
    }
    run { // simulated kill between the renames: only .bak exists
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(st, N, "new")
        class Kill : RuntimeException()
        try { DictionarySwap.swap(fd, staged(st), false) { a, b -> if(a.parentFile == st) throw Kill() else a.renameTo(b) } } catch(e: Kill) {}
        check("kill between renames: live missing, .bak holds old", !File(fd, N).exists() && File(fd, "$N.bak/$N.body").readText() == "B-old")
    }
    run { // stale .bak
        val (fd, st) = fresh(); mkdict(fd, N, "old"); mkdict(fd, "$N.bak", "stale"); mkdict(st, N, "new")
        DictionarySwap.swap(fd, staged(st), false)
        check("stale .bak: replaced cleanly", tag(fd, N) == "B-new" && !File(fd, "$N.bak").exists())
    }
    run { // first write, destructive removes dicts not in backup
        val (fd, st) = fresh(); mkdict(fd, M, "extra"); mkdict(st, N, "new")
        DictionarySwap.swap(fd, staged(st), true)
        check("destructive: restored present, absent-in-backup removed", tag(fd, N) == "B-new" && !File(fd, M).exists())
    }
    run { // non destructive keeps others
        val (fd, st) = fresh(); mkdict(fd, M, "keep"); mkdict(st, N, "new")
        DictionarySwap.swap(fd, staged(st), false)
        check("merge: other dict kept", tag(fd, M) == "B-keep")
    }
    println(if(failures == 0) "RESULT OK: 0 failing" else "RESULT FAIL: $failures")
    if(failures != 0) System.exit(1)
}
