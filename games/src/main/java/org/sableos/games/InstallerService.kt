package org.sableos.games

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the installer shows for one app. */
internal class InstallerRow(
    val app: InstallerApp,
) {
    /** The installed version name, or null if the app is not installed. */
    var installed by mutableStateOf<String?>(null)
    var selected by mutableStateOf(false)

    /** A line about what is happening or what went wrong; empty when idle. */
    var note by mutableStateOf("")
    var busy by mutableStateOf(false)
}

/** Android calls this when a session finishes or needs the user's confirmation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) context.startActivity(confirm)
            return
        }
        InstallEvents.finish(pkg, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Hands install results from the receiver to the thread waiting for them. */
internal object InstallEvents {
    class Result(
        val status: Int,
        val message: String?,
    )

    private class Waiter(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var result: Result? = null,
    )

    private val waiters = ConcurrentHashMap<String, Waiter>()

    fun expect(pkg: String) {
        waiters[pkg] = Waiter()
    }

    fun finish(
        pkg: String,
        status: Int,
        message: String?,
    ) {
        waiters[pkg]?.let {
            it.result = Result(status, message)
            it.latch.countDown()
        }
    }

    /** Waits for the result of the install of [pkg]; null if it timed out. */
    fun await(
        pkg: String,
        minutes: Long,
    ): Result? {
        val w = waiters[pkg] ?: return null
        val arrived = w.latch.await(minutes, TimeUnit.MINUTES)
        waiters.remove(pkg)
        return if (arrived) w.result else null
    }
}

/** Looks up the latest release, downloads the chosen APKs, checks them and hands them to Android's installer. */
internal class InstallerModel(
    private val context: Context,
) {
    val rows = InstallerCatalog.apps.map { InstallerRow(it) }

    /** A line at the top: the release found, or what went wrong. */
    var headline by mutableStateOf("Looking for the latest release…")
    var working by mutableStateOf(false)

    /** The short release notes shown under the headline. */
    var notes by mutableStateOf("")

    private val prefs = context.getSharedPreferences("installer", Context.MODE_PRIVATE)

    /** When true, nothing is downloaded over a metered (mobile data) connection. */
    var wifiOnly by mutableStateOf(prefs.getBoolean("wifi_only", false))
        private set

    fun toggleWifiOnly() {
        wifiOnly = !wifiOnly
        prefs.edit().putBoolean("wifi_only", wifiOnly).apply()
    }

    @SuppressLint("MissingPermission") // held by the installer's manifest only; a SecurityException is handled below
    private fun metered(): Boolean {
        // Only the installer's manifest holds this permission; this class is shared by every flavor.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.isActiveNetworkMetered
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isNewer(row: InstallerRow): Boolean {
        val have = row.installed ?: return false
        val now = latest ?: return false
        return ReleaseFeed.isNewer(have, now)
    }

    /** Picks only the games that are not installed yet. */
    fun pickNotInstalled() = rows.forEach { if (!it.busy) it.selected = it.installed == null && it.app.id != SELF }

    /** Picks only the installed games that have a newer release. */
    fun pickUpdates() = rows.forEach { if (!it.busy) it.selected = isNewer(it) }

    fun clearPicks() = rows.forEach { if (!it.busy) it.selected = false }

    /** Starts an installed game. */
    fun openApp(row: InstallerRow) {
        val intent = context.packageManager.getLaunchIntentForPackage(row.app.packageName)
        if (intent == null) {
            row.note = "Not installed."
            return
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens Android's app page for a game, where Uninstall is; this app never asks to delete packages itself. */
    fun removeApp(row: InstallerRow) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${row.app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @Volatile private var release: Release? = null

    /** The latest release's version once it is known; the screen compares installed versions with it. */
    var latest by mutableStateOf<String?>(null)

    fun rowsOf(group: InstallerGroup) = rows.filter { it.app.id in group.ids }

    /** Picks every game in [group], or clears them all if they are all picked already. */
    fun toggleGroup(group: InstallerGroup) {
        val mine = rowsOf(group).filterNot { it.busy }
        val pick = !mine.all { it.selected }
        mine.forEach { it.selected = pick }
    }

    val canInstall get() = !working && release != null && rows.any { it.selected }

    fun refresh() {
        refreshInstalled()
        if (working) return
        working = true
        thread(name = "installer-refresh") {
            try {
                val json = fetchText(ReleaseFeed.LATEST)
                val found = ReleaseFeed.parseRelease(json)
                release = found
                latest = found?.version
                notes = found?.let { ReleaseFeed.summary(it.notes) }.orEmpty()
                headline = if (found == null) "No release with APKs was found." else "Latest release: ${found.version}"
            } catch (e: java.io.IOException) {
                release = null
                latest = null
                headline = "Could not reach GitHub: ${e.message ?: "no network"}"
            } finally {
                working = false
                refreshInstalled()
                autoPickSelf()
            }
        }
    }

    /** If a newer installer is out, picks it so it is updated along with the games (always last). */
    private fun autoPickSelf() {
        rows.firstOrNull { it.app.id == SELF }?.let { if (isNewer(it)) it.selected = true }
    }

    fun refreshInstalled() {
        val pm = context.packageManager
        for (row in rows) {
            row.installed =
                try {
                    pm.getPackageInfo(row.app.packageName, 0).versionName
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
        }
    }

    /** True if the user has allowed this app to install apps; otherwise opens the settings page for it. */
    fun allowedToInstall(): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        headline = "Allow Sable Installer to install apps, then come back and press Install again."
        return false
    }

    fun installSelected() {
        val rel = release ?: return
        if (working || !allowedToInstall()) return
        // The installer replaces itself, which ends this process, so it goes last.
        val chosen = rows.filter { it.selected }.sortedBy { it.app.id == SELF }
        if (chosen.isEmpty()) return
        if (wifiOnly && metered()) {
            headline = "Wi-Fi only is on and this connection is metered. Connect to Wi-Fi or turn the option off."
            return
        }
        working = true
        thread(name = "installer-install") {
            try {
                val sums = rel.sums?.let { ReleaseFeed.parseSums(fetchText(it.url)) }.orEmpty()
                for (row in chosen) {
                    row.busy = true
                    try {
                        installOne(rel, row, sums)
                    } catch (e: java.io.IOException) {
                        row.note = "Failed: ${e.message ?: "download error"}"
                    } catch (e: SecurityException) {
                        row.note = "Android refused: ${e.message}"
                    } catch (e: IllegalStateException) {
                        row.note = "Install failed: ${e.message}"
                    } finally {
                        row.busy = false
                        refreshInstalled()
                    }
                }
            } finally {
                working = false
            }
        }
    }

    private fun installOne(
        rel: Release,
        row: InstallerRow,
        sums: Map<String, String>,
    ) {
        val asset = rel.apkFor(row.app)
        if (asset == null) {
            row.note = "Not in this release."
            return
        }
        val file = File(File(context.cacheDir, "apks").apply { mkdirs() }, asset.name)
        row.note = "Downloading…"
        downloadWithResume(asset.url, file) { note -> row.note = note }
        row.note = "Checking…"
        val want = sums[asset.name]
        val problem =
            when {
                want == null -> "SHA256SUMS.txt has no entry for ${asset.name}."
                sha256(file.readBytes()) != want -> "The download does not match its checksum."
                else -> InstallerChecks.problem(context.packageManager, file, row.app.packageName)
            }
        if (problem != null) {
            file.delete()
            row.note = problem
            return
        }
        row.note = "Waiting for you to confirm…"
        InstallEvents.expect(row.app.packageName)
        commit(file, row.app.packageName)
        val result = InstallEvents.await(row.app.packageName, 5)
        file.delete()
        row.note =
            when (result?.status) {
                PackageInstaller.STATUS_SUCCESS -> "Installed ${rel.version}"
                null -> "Timed out waiting for the install."
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled."
                else -> "Failed: ${result.message ?: "status ${result.status}"}"
            }
    }

    private fun commit(
        apk: File,
        pkg: String,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(pkg) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SableInstaller")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain")
        return conn
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [url] to [to], resuming a partly downloaded file; tries up to three times. */
    private fun downloadWithResume(
        url: String,
        to: File,
        note: (String) -> Unit,
    ) {
        val part = File(to.path + ".part")
        var last: java.io.IOException? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                download(url, part) { pct -> note(if (attempt > 1) "Downloading $pct% (try $attempt)" else "Downloading $pct%") }
                if (to.exists()) to.delete()
                if (!part.renameTo(to)) throw java.io.IOException("could not save the download")
                return
            } catch (e: java.io.IOException) {
                last = e
                note("Download interrupted, retrying…")
                Thread.sleep(1500)
            }
        }
        throw last ?: java.io.IOException("download error")
    }

    private fun download(
        url: String,
        to: File,
        progress: (Int) -> Unit,
    ) {
        val have = if (to.exists()) to.length() else 0L
        val conn = open(url)
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code == 416) {
                // The server thinks we already have it all (or the file changed): start over.
                to.delete()
                throw java.io.IOException("the partial file was out of date")
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw java.io.IOException("HTTP $code")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            val base = if (resumed) have else 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) base + length else -1L
            var done = base
            var shown = -1
            conn.inputStream.use { input ->
                java.io.FileOutputStream(to, resumed).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) {
                            shown = pct
                            progress(pct)
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw java.io.IOException("the connection closed early")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val SELF = "installer"
        private const val ATTEMPTS = 3

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Checks a downloaded APK before it is handed to Android: right package, signed with the Sable key. */
internal object InstallerChecks {
    /** The registered certificate (SHA-256) every Sable app is signed with; see docs/RELEASING.md. */
    const val SABLE_CERT = "9b2047390e5aa77537b8e8875cdfd3c2c72d0a0715e172a7f77457fc4300f292"

    /** A message saying what is wrong with [apk], or null if it is the Sable app [expectedPackage]. */
    fun problem(
        pm: PackageManager,
        apk: File,
        expectedPackage: String,
    ): String? {
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return "The download is not a valid APK."
        if (info.packageName != expectedPackage) return "The download is a different app (${info.packageName})."
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.isEmpty()) return "The download is not signed."
        if (signers.any { InstallerModel.sha256(it.toByteArray()) != SABLE_CERT }) return "The download is not signed with the Sable key."
        return null
    }
}
