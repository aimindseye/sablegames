package org.sableos.games

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
                headline = if (found == null) "No release with APKs was found." else "Latest release: ${found.version}"
            } catch (e: java.io.IOException) {
                release = null
                latest = null
                headline = "Could not reach GitHub: ${e.message ?: "no network"}"
            } finally {
                working = false
                refreshInstalled()
            }
        }
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
        val chosen = rows.filter { it.selected }
        if (chosen.isEmpty()) return
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
        download(asset.url, file) { pct -> row.note = "Downloading $pct%" }
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

    private fun download(
        url: String,
        to: File,
        progress: (Int) -> Unit,
    ) {
        val conn = open(url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var shown = -1
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
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
        } finally {
            conn.disconnect()
        }
    }

    companion object {
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
