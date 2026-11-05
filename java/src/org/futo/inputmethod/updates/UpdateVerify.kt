package org.futo.inputmethod.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * WrenBoard 1.6 (item 11): the download and install checks from BingeMote's Updater.kt (MtndaleRedneck/BingeMote
 * main @6b60b13), adapted for WrenBoard. The APK must come from WrenBoard's own GitHub releases, over HTTPS, with
 * redirects only inside github.com / githubusercontent.com; it must match the published size and checksum; and it
 * must be WrenBoard, newer, and signed by the same key as the running app. Android still makes the final call.
 */
object UpdateVerify {
    class Failure(val msg: String) : Exception(msg)

    fun hostAllowed(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && (u.host == "github.com" || u.host == "githubusercontent.com" || u.host.endsWith(".githubusercontent.com"))
    } catch (e: Exception) { false }

    fun updateDir(ctx: Context) = File(ctx.cacheDir, "updates")
    fun clean(ctx: Context) { updateDir(ctx).listFiles()?.forEach { it.delete() } }

    /** Downloads the APK to the app's cache, following only HTTPS redirects inside GitHub. Throws [Failure]. */
    fun download(ctx: Context, info: UpdateResult, progress: (Float) -> Unit): File {
        if (!info.apkUrl.startsWith(UPDATE_DOWNLOAD_PREFIX)) throw Failure("The download address was not trusted, so it was stopped.")
        clean(ctx)
        val dir = updateDir(ctx); dir.mkdirs()
        val out = File(dir, "WrenBoard-update.apk")
        try {
            var url = info.apkUrl
            var conn: HttpURLConnection? = null
            for (hop in 0..5) {
                if (!hostAllowed(url)) throw Failure("The download address was not trusted, so it was stopped.")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "WrenBoard-Update")
                c.setRequestProperty("Accept", "application/octet-stream")
                val code = c.responseCode
                if (code in 301..308) {
                    val next = c.getHeaderField("Location") ?: throw Failure("The download could not start.")
                    url = URL(URL(url), next).toString(); c.disconnect(); continue
                }
                if (code != 200) { c.disconnect(); throw Failure("The download failed (error $code).") }
                conn = c; break
            }
            val c = conn ?: throw Failure("The download was redirected too many times.")
            val expected = if (info.size > 0) info.size else c.contentLengthLong
            if (expected <= 0L || expected > UPDATE_MAX_APK_BYTES) { c.disconnect(); throw Failure("The download size was not as expected.") }
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                c.inputStream.use { ins -> out.outputStream().use { os ->
                    val buf = ByteArray(32 * 1024); var n = ins.read(buf)
                    while (n >= 0) {
                        total += n; if (total > expected) throw Failure("The file was larger than expected.")
                        md.update(buf, 0, n); os.write(buf, 0, n); progress(total.toFloat() / expected)
                        n = ins.read(buf)
                    }
                } }
            } finally { c.disconnect() }
            if (total != expected) throw Failure("The download was incomplete.")
            if (info.sha256 != null && toHex(md.digest()) != info.sha256) throw Failure("The file did not match the published checksum, so it was deleted.")
            return out
        } catch (e: Failure) { out.delete(); throw e }
        catch (e: Exception) { out.delete(); throw Failure("The download failed. Check your connection and try again.") }
    }

    /** Same signer set, or a key rotation: the installed signer appears in the new file's signing history. */
    fun signersAcceptable(installed: Set<List<Byte>>, incoming: Set<List<Byte>>, incomingHistory: Set<List<Byte>>): Boolean {
        if (installed.isEmpty() || incoming.isEmpty()) return false
        if (installed == incoming) return true
        return installed.size == 1 && incoming.size == 1 && incomingHistory.containsAll(installed)
    }

    fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Confirms the file is WrenBoard, newer, and signed with the same key as the running app. Throws [Failure]. */
    fun verify(ctx: Context, f: File) {
        try { verifyInner(ctx, f) }
        catch (e: Failure) { throw e }
        catch (e: Exception) { throw Failure("The downloaded file could not be checked, so it was not installed.") }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Pair<Set<List<Byte>>, Set<List<Byte>>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo
            val current = s?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
            val history = s?.signingCertificateHistory?.map { it.toByteArray().toList() }?.toSet().orEmpty()
            current to history
        } else {
            val current = info.signatures?.map { it.toByteArray().toList() }?.toSet().orEmpty()
            current to current
        }

    @Suppress("DEPRECATION")
    private fun verifyInner(ctx: Context, f: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val arch = pm.getPackageArchiveInfo(f.path, flags) ?: throw Failure("The downloaded file is not a valid app.")
        if (arch.packageName != ctx.packageName) throw Failure("The downloaded file is a different app.")
        arch.applicationInfo?.let { it.sourceDir = f.path; it.publicSourceDir = f.path }
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        val (cur, _) = signers(installed)
        val (next, history) = signers(arch)
        if (!signersAcceptable(cur, next, history)) throw Failure("The file is not signed by the same key as your installed WrenBoard, so it was not installed.")
        val archCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) arch.longVersionCode else arch.versionCode.toLong()
        val curCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode else installed.versionCode.toLong()
        if (archCode <= curCode) throw Failure("The downloaded file is not newer than this version.")
    }
}
