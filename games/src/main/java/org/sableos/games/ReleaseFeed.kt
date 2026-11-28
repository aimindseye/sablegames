package org.sableos.games

/** One file attached to a GitHub release. */
internal data class ReleaseAsset(
    val name: String,
    val url: String,
)

/** What the installer needs from a GitHub release: its version and its files. */
internal data class Release(
    val version: String,
    val assets: List<ReleaseAsset>,
    /** The release notes, as plain text; empty if there are none. */
    val notes: String = "",
) {
    /** The APK for [app], named `sable-<id>-<version>.apk` by tools/build-release.sh. */
    fun apkFor(app: InstallerApp): ReleaseAsset? = assets.firstOrNull { it.name == "sable-${app.id}-$version.apk" }

    val sums: ReleaseAsset? get() = assets.firstOrNull { it.name == "SHA256SUMS.txt" }
}

/** Reading the two small pieces of text the installer fetches, without a JSON library. */
internal object ReleaseFeed {
    const val LATEST = "https://api.github.com/repos/aimindseye/sablegames/releases/latest"

    private val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
    private val body = Regex(""""body"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val download = Regex(""""browser_download_url"\s*:\s*"([^"]+)"""")

    /** Parses the body of the `releases/latest` API call. Returns null if it is not a release we can use. */
    fun parseRelease(json: String): Release? {
        val version = tag.find(json)?.groupValues?.get(1)?.removePrefix("v") ?: return null
        val assets =
            download
                .findAll(json)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.startsWith("https://") }
                .map { ReleaseAsset(it.substringAfterLast('/'), it) }
                .toList()
        val notes = body.find(json)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty().trim()
        return if (assets.isEmpty()) null else Release(version, assets, notes)
    }

    /** Undoes the JSON string escapes that matter in release notes. */
    fun unescape(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }
            val n = text[i + 1]
            i += 2
            when (n) {
                'n' -> out.append('\n')
                'r' -> Unit
                't' -> out.append(' ')
                'u' -> {
                    val code = text.substring(i, minOf(i + 4, text.length)).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 4
                    }
                }
                else -> out.append(n)
            }
        }
        return out.toString()
    }

    /** The first [lines] non-empty lines of [notes], without markdown marks, for a short summary. */
    fun summary(
        notes: String,
        lines: Int = 4,
    ): String =
        notes
            .lineSequence()
            .map { it.trim().trimStart('#', '*', '-', ' ') }
            .filter { it.isNotEmpty() }
            .take(lines)
            .joinToString("\n")

    /** Parses SHA256SUMS.txt (`<hash>  <file>` per line) into file name to lowercase hash. */
    fun parseSums(text: String): Map<String, String> =
        text
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].length == 64) parts[1].removePrefix("*").removePrefix("./") to parts[0].lowercase() else null
            }.toMap()

    /** True if [installed] is older than [latest], comparing dotted numbers (1.2.3). Unparseable versions never count as older. */
    fun isNewer(
        installed: String,
        latest: String,
    ): Boolean {
        val a = installed.split('.').map { it.toIntOrNull() ?: return false }
        val b = latest.split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }
}
