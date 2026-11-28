package org.sableos.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerTest {
    private val json =
        """
        {"url":"x","tag_name":"v2.1.0","name":"Sable Games 2.1.0","assets":[
        {"name":"sable-tartarus-2.1.0.apk","uploader":{"login":"a"},"browser_download_url":"https://github.com/aimindseye/sablegames/releases/download/v2.1.0/sable-tartarus-2.1.0.apk"},
        {"name":"SHA256SUMS.txt","uploader":{"login":"a"},"browser_download_url":"https:\/\/github.com\/aimindseye\/sablegames\/releases\/download\/v2.1.0\/SHA256SUMS.txt"}
        ]}
        """.trimIndent()

    @Test
    fun readsAReleaseAndFindsTheApkAndTheSums() {
        val r = ReleaseFeed.parseRelease(json)
        assertNotNull(r)
        assertEquals("2.1.0", r!!.version)
        val app = InstallerCatalog.apps.first { it.id == "tartarus" }
        assertEquals("sable-tartarus-2.1.0.apk", r.apkFor(app)?.name)
        assertNull(r.apkFor(InstallerCatalog.apps.first { it.id == "sudoku" }))
        assertTrue(r.sums!!.url.endsWith("/SHA256SUMS.txt"))
    }

    @Test
    fun refusesWhatIsNotARelease() {
        assertNull(ReleaseFeed.parseRelease("{}"))
        assertNull(ReleaseFeed.parseRelease("""{"tag_name":"v1.0.0","assets":[]}"""))
        // Plain http downloads are never used.
        assertNull(ReleaseFeed.parseRelease("""{"tag_name":"v1.0.0","assets":[{"browser_download_url":"http://example.com/a.apk"}]}"""))
    }

    @Test
    fun readsTheChecksumFile() {
        val h = "a".repeat(64)
        val sums = ReleaseFeed.parseSums("$h  ./sable-snake-1.0.0.apk\n${"B".repeat(64)} *sable-ludo-1.0.0.apk\nnot a line\n")
        assertEquals(h, sums["sable-snake-1.0.0.apk"])
        assertEquals("b".repeat(64), sums["sable-ludo-1.0.0.apk"])
        assertEquals(2, sums.size)
    }

    @Test
    fun comparesVersions() {
        assertTrue(ReleaseFeed.isNewer("2.0.0", "2.1.0"))
        assertTrue(ReleaseFeed.isNewer("1.9.9", "2.0.0"))
        assertFalse(ReleaseFeed.isNewer("2.1.0", "2.1.0"))
        assertFalse(ReleaseFeed.isNewer("2.1.0", "2.0.9"))
        assertFalse(ReleaseFeed.isNewer("dev", "2.0.0"))
    }

    @Test
    fun theCatalogCoversEveryAppOnce() {
        val ids = InstallerCatalog.apps.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(19, ids.size)
        // Every game is in exactly one group.
        assertEquals(ids.sorted(), InstallerCatalog.groups.flatMap { it.ids }.sorted())
        assertTrue(InstallerCatalog.apps.all { it.packageName == "io.github.aimindseye.sablegames.${it.id}" })
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", InstallerModel.sha256(byteArrayOf()))
    }

    @Test
    fun readsReleaseNotes() {
        val withNotes = json.replace("\"assets\"", "\"body\":\"## What is new\\r\\n- Bigger foes\\n- Better \\\"aim\\\"\",\"assets\"")
        val r = ReleaseFeed.parseRelease(withNotes)!!
        assertEquals("What is new\nBigger foes\nBetter \"aim\"", ReleaseFeed.summary(r.notes))
        assertEquals("", ReleaseFeed.parseRelease(json)!!.notes)
    }

    @Test
    fun summaryKeepsOnlyTheFirstLines() {
        assertEquals("a\nb", ReleaseFeed.summary("# a\n\n* b\nc\nd", 2))
    }
}
