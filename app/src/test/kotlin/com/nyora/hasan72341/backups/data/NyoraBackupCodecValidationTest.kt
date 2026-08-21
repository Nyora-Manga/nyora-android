package com.nyora.hasan72341.backups.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals


private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
	try {
		block()
		org.junit.Assert.fail("Expected ${T::class.java.name}")
	} catch (error: Throwable) {
		if (error !is T) throw error
	}
}

class NyoraBackupCodecValidationTest {

    private val snapshot = NyoraBackupSnapshot(
        archiveId = "123e4567-e89b-12d3-a456-426614174000",
        createdAt = "2026-08-21T12:34:56.789Z",
        appVersion = "2.0.7",
        platform = "desktop",
        sources = listOf(NyoraBackupSource("data:mangadex", "2026.08", updatedAt = "2026-08-21T12:34:56.789Z")),
        categories = listOf(NyoraBackupCategory("223e4567-e89b-12d3-a456-426614174000", "Reading", 0, "2026-08-21T12:34:56.789Z")),
        preferences = listOf(NyoraBackupPreference("reader.direction", JsonPrimitive("vertical"), "2026-08-21T12:34:56.789Z")),
        manga = listOf(
            NyoraBackupManga(
                id = "c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a",
                sourceId = "data:mangadex",
                contentKey = "/manga/berserk",
                title = "Berserk",
                updatedAt = "2026-08-21T12:34:56.789Z",
            ),
        ),
        chapters = listOf(
            NyoraBackupChapter(
                id = "bfabb5a378d533daea8d03f85903f1a9e5553a23dbd4bd201173179da95a6277",
                mangaId = "c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a",
                chapterKey = "/chapter/1",
                name = "Chapter 1",
                updatedAt = "2026-08-21T12:34:56.789Z",
            ),
        ),
        library = listOf(NyoraBackupLibrary("c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a", "2026-08-21T12:34:56.789Z", categoryIds = listOf("223e4567-e89b-12d3-a456-426614174000"))),
        history = listOf(NyoraBackupHistory("c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a", "bfabb5a378d533daea8d03f85903f1a9e5553a23dbd4bd201173179da95a6277", 3, 0.5, "2026-08-21T12:34:56.789Z")),
        bookmarks = listOf(NyoraBackupBookmark("323e4567-e89b-12d3-a456-426614174000", "c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a", "bfabb5a378d533daea8d03f85903f1a9e5553a23dbd4bd201173179da95a6277", 3, "panel", "2026-08-21T12:34:56.789Z")),
        tracking = listOf(NyoraBackupTracking("anilist:1", "c5ef7bb1a3fd99addd09ee105f6de9bc2a1e77285a27d2d906597a06ec76bf3a", "anilist", "reading", "2026-08-21T12:34:56.789Z")),
    )

    @Test
    fun `round trips a validated snapshot through deterministic archive bytes`() {
        val first = NyoraBackupCodec.encode(snapshot)
        val second = NyoraBackupCodec.encode(snapshot)

        assertArrayEquals(first, second)
        assertEquals(snapshot, NyoraBackupCodec.decode(first))
    }

    @Test
    fun `decodes the checked in minimal golden archive`() {
        val bytes = requireNotNull(javaClass.classLoader.getResource("backup/v1/golden-minimal.nyorabk"))
            .readBytes()
        assertEquals(snapshot, NyoraBackupCodec.decode(bytes))
        assertArrayEquals(bytes, NyoraBackupCodec.encode(snapshot))
    }

    @Test
    fun `rejects a section checksum mismatch`() {
        val corrupt = corruptSection(NyoraBackupCodec.encode(snapshot), "manga.ndjson") {
            it.decodeToString().replace("Berserk", "Berserx").encodeToByteArray()
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> { NyoraBackupCodec.decode(corrupt) }
    }

    @Test
    fun `rejects traversal and duplicate archive entries`() {
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(zipOf("../manifest.json" to "{}".encodeToByteArray()))
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(zipOf("manifest.json" to "{}".encodeToByteArray(), "manifest.json" to "{}".encodeToByteArray()))
        }
    }

    @Test
    fun `rejects unsupported version invalid source and missing references`() {
        assertFailsWith<NyoraBackupCodec.UnsupportedVersionException> {
            NyoraBackupCodec.decode(
                zipOf("manifest.json" to """{"format":"app.nyora.backup","schemaVersion":2}""".encodeToByteArray()),
            )
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.encode(snapshot.copy(sources = listOf(snapshot.sources.single().copy(id = "parser:MANGADEX"))))
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.encode(snapshot.copy(chapters = listOf(snapshot.chapters.single().copy(mangaId = "a".repeat(64)))))
        }
    }

    @Test
    fun `enforces configured archive byte limits`() {
        val archive = NyoraBackupCodec.encode(snapshot)
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(archive, NyoraBackupCodec.Limits(maxArchiveBytes = archive.size - 1))
        }
    }

    @Test
    fun `enforces record limits for registry and ndjson sections`() {
        val twoSources = snapshot.copy(sources = snapshot.sources + NyoraBackupSource("data:other", "2026.08", updatedAt = snapshot.createdAt))
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(NyoraBackupCodec.encode(twoSources), NyoraBackupCodec.Limits(maxRecordsPerSection = 1))
        }
        val secondMangaId = NyoraBackupIdentity.mangaId("data:mangadex", "/manga/other")
        val twoManga = snapshot.copy(manga = snapshot.manga + NyoraBackupManga(secondMangaId, "data:mangadex", "/manga/other", "Other", snapshot.createdAt))
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(NyoraBackupCodec.encode(twoManga), NyoraBackupCodec.Limits(maxRecordsPerSection = 1))
        }
    }

    @Test
    fun `enforces section and total uncompressed limits`() {
        val archive = NyoraBackupCodec.encode(snapshot)
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(archive, NyoraBackupCodec.Limits(maxSectionBytes = 1))
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(archive, NyoraBackupCodec.Limits(maxSectionBytes = archive.size, maxTotalUncompressedBytes = 1))
        }
    }

    @Test
    fun `rejects manifest count and length mismatches`() {
        val archive = NyoraBackupCodec.encode(snapshot)
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(transformManifest(archive) { it.replaceFirst("\"recordCount\":1", "\"recordCount\":2") })
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(transformManifest(archive) { it.replaceFirst("\"byteLength\":", "\"byteLength\":9") })
        }
    }

    @Test
    fun `requires every v1 manifest field and nonoptional v1 sections`() {
        val archive = NyoraBackupCodec.encode(snapshot)
        listOf<(String) -> String>(
            { it.replace(",\"omissions\":{}", "") },
            { it.replaceFirst(",\"schemaVersion\":1,\"optional\":false", ",\"optional\":false") },
            { it.replaceFirst("\"optional\":false", "\"optional\":true") },
        ).forEach { mutate ->
            assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
                NyoraBackupCodec.decode(transformManifest(archive, mutate))
            }
        }
    }

    @Test
    fun `rejects malformed utf8 in manifest and data sections`() {
        val archive = NyoraBackupCodec.encode(snapshot)
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(corruptSection(archive, "manifest.json") { it + byteArrayOf(0xc3.toByte()) })
        }
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(corruptDataWithMatchingManifestChecksum(archive, "manga.ndjson"))
        }
    }

    @Test
    fun `requires history and bookmarks to reference chapters of their manga`() {
        val secondMangaId = NyoraBackupIdentity.mangaId("data:mangadex", "/manga/other")
        val invalidHistory = snapshot.copy(
            manga = snapshot.manga + NyoraBackupManga(secondMangaId, "data:mangadex", "/manga/other", "Other", snapshot.createdAt),
            history = listOf(snapshot.history.single().copy(mangaId = secondMangaId)),
            bookmarks = listOf(snapshot.bookmarks.single().copy(mangaId = secondMangaId)),
        )
        assertFailsWith<NyoraBackupCodec.InvalidBackupException> { NyoraBackupCodec.encode(invalidHistory) }
    }

    @Test
    fun `writes ndjson with exactly one terminal newline`() {
        val manga = archiveEntries(NyoraBackupCodec.encode(snapshot)).getValue("manga.ndjson").decodeToString()
        assertEquals(1, manga.takeLastWhile { it == '\n' }.length)
    }

    @Test
    fun `rejects noncanonical ndjson terminators before restore`() {
        val archive = NyoraBackupCodec.encode(snapshot)
        val malformed = rewriteDataWithMatchingManifestChecksum(archive, "manga.ndjson") { bytes ->
            bytes.copyOf().also { it[it.lastIndex] = ' '.code.toByte() }
        }

        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(malformed)
        }
    }

    @Test
    fun `rejects archives exceeding the configured decompression ratio`() {
        val archive = deflate(
            NyoraBackupCodec.encode(
                snapshot.copy(manga = listOf(snapshot.manga.single().copy(description = "x".repeat(10_000)))),
            ),
        )

        assertFailsWith<NyoraBackupCodec.InvalidBackupException> {
            NyoraBackupCodec.decode(archive, NyoraBackupCodec.Limits(maxCompressionRatio = 1.0))
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        val offsets = mutableListOf<Int>()
        entries.forEach { (name, content) ->
            offsets += output.size()
            val nameBytes = name.encodeToByteArray()
            val crc = CRC32().apply { update(content) }.value
            output.le(0x04034b50, 4); output.le(20, 2); output.le(0, 2); output.le(0, 2); output.le(0, 2); output.le(0, 2)
            output.le(crc, 4); output.le(content.size, 4); output.le(content.size, 4); output.le(nameBytes.size, 2); output.le(0, 2)
            output.write(nameBytes); output.write(content)
        }
        val centralOffset = output.size()
        entries.forEachIndexed { index, (name, content) ->
            val nameBytes = name.encodeToByteArray()
            val crc = CRC32().apply { update(content) }.value
            output.le(0x02014b50, 4); output.le(20, 2); output.le(20, 2); output.le(0, 2); output.le(0, 2); output.le(0, 2); output.le(0, 2)
            output.le(crc, 4); output.le(content.size, 4); output.le(content.size, 4); output.le(nameBytes.size, 2); output.le(0, 2); output.le(0, 2); output.le(0, 2); output.le(0, 2); output.le(0, 4); output.le(offsets[index], 4)
            output.write(nameBytes)
        }
        val centralSize = output.size() - centralOffset
        output.le(0x06054b50, 4); output.le(0, 2); output.le(0, 2); output.le(entries.size, 2); output.le(entries.size, 2); output.le(centralSize, 4); output.le(centralOffset, 4); output.le(0, 2)
        output.toByteArray()
    }

    private fun ByteArrayOutputStream.le(value: Number, bytes: Int) {
        val bits = value.toLong()
        repeat(bytes) { write((bits ushr (it * 8)).toInt() and 0xff) }
    }

    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            archiveEntries(bytes).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(content)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun corruptSection(bytes: ByteArray, target: String, transform: (ByteArray) -> ByteArray): ByteArray {
        return zipOf(*archiveEntries(bytes).map { (name, value) -> name to if (name == target) transform(value) else value }.toTypedArray())
    }

    private fun transformManifest(bytes: ByteArray, transform: (String) -> String): ByteArray =
        corruptSection(bytes, "manifest.json") { transform(it.decodeToString()).encodeToByteArray() }

    private fun corruptDataWithMatchingManifestChecksum(bytes: ByteArray, target: String): ByteArray {
        val entries = archiveEntries(bytes)
        val corrupt = entries.getValue(target).copyOf().also { it[it.indexOf('B'.code.toByte())] = 0xc3.toByte() }
        entries[target] = corrupt
        val oldHash = sha256(archiveEntries(bytes).getValue(target))
        entries["manifest.json"] = entries.getValue("manifest.json").decodeToString()
            .replace(oldHash, sha256(corrupt)).encodeToByteArray()
        return zipOf(*entries.toList().toTypedArray())
    }

    private fun rewriteDataWithMatchingManifestChecksum(
        bytes: ByteArray,
        target: String,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        val entries = archiveEntries(bytes)
        val original = entries.getValue(target)
        val replacement = transform(original)
        require(original.size == replacement.size)
        entries[target] = replacement
        entries["manifest.json"] = entries.getValue("manifest.json").decodeToString()
            .replace(sha256(original), sha256(replacement)).encodeToByteArray()
        return zipOf(*entries.toList().toTypedArray())
    }

    private fun archiveEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                this[entry.name] = zip.readBytes()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
