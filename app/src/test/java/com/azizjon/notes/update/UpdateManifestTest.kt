package com.azizjon.notes.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val sha = "a".repeat(64)
    private val url = "https://github.com/AzizjonKasimov/note-taking-app-releases/releases/download/v2.5/Notes-2.5.apk"

    private fun manifest(
        versionCode: Long = 16,
        apkUrl: String = url,
        size: Long? = 90_000_000,
        sha256: String? = sha,
    ) = buildString {
        append("""{"versionCode":$versionCode,"versionName":"2.5","apkUrl":"$apkUrl","notes":"People"""")
        if (size != null) append(""","apkSizeBytes":$size""")
        if (sha256 != null) append(""","sha256":"$sha256"""")
        append("}")
    }

    @Test
    fun aNewerCheckedReleaseIsOffered() {
        val info = parseUpdateManifest(manifest(), currentVersionCode = 15)!!
        assertEquals(16L, info.versionCode)
        assertEquals(90_000_000L, info.apkSizeBytes)
        assertEquals(sha, info.sha256)
    }

    @Test
    fun theSameOrAnOlderReleaseIsNot() {
        assertNull(parseUpdateManifest(manifest(versionCode = 15), currentVersionCode = 15))
        assertNull(parseUpdateManifest(manifest(versionCode = 14), currentVersionCode = 15))
    }

    @Test
    fun aReleaseWithoutItsSizeAndHashIsNeverOffered() {
        // 2.4's manifest had neither; 2.5 and later releases carry both.
        assertNull(parseUpdateManifest(manifest(size = null), currentVersionCode = 15))
        assertNull(parseUpdateManifest(manifest(sha256 = null), currentVersionCode = 15))
        assertNull(parseUpdateManifest(manifest(sha256 = "not-a-hash"), currentVersionCode = 15))
        assertNull(parseUpdateManifest(manifest(size = 0), currentVersionCode = 15))
    }

    @Test
    fun anApkFromAnywhereElseIsNeverOffered() {
        listOf(
            "http://github.com/AzizjonKasimov/note-taking-app-releases/releases/download/v2.5/Notes-2.5.apk",
            "https://github.com/someone-else/note-taking-app-releases/releases/download/v2.5/Notes-2.5.apk",
            "https://example.com/AzizjonKasimov/note-taking-app-releases/releases/download/v2.5/Notes-2.5.apk",
            "$url?x=1",
        ).forEach { bad -> assertNull(bad, parseUpdateManifest(manifest(apkUrl = bad), currentVersionCode = 15)) }
        assertNull(parseUpdateManifest("not json", currentVersionCode = 15))
    }

    @Test
    fun aDownloadMustMatchBothSizeAndHash() {
        assertTrue(integrityMatches(10, sha, 10, sha.uppercase()))
        assertFalse(integrityMatches(11, sha, 10, sha))
        assertFalse(integrityMatches(10, "b".repeat(64), 10, sha))
    }
}
