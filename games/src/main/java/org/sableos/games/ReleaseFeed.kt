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
) {
    /** The APK for [app], named `sable-<id>-<version>.apk` by tools/build-release.sh. */
    fun apkFor(app: InstallerApp): ReleaseAsset? = assets.firstOrNull { it.name == "sable-${app.id}-$version.apk" }

    val sums: ReleaseAsset? get() = assets.firstOrNull { it.name == "SHA256SUMS.txt" }
}

/** Reading the two small pieces of text the installer fetches, without a JSON library. */
internal object ReleaseFeed {
    const val LATEST = "https://api.github.com/repos/aimindseye/sablegames/releases/latest"

    private val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
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
        return if (assets.isEmpty()) null else Release(version, assets)
    }

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
