package com.nyora.hasan72341.backups.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.builtins.ListSerializer

/** JVM reference encoder/decoder for a bounded, path-safe version-1 .nyorabk archive. */
object NyoraBackupCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    private val entries = listOf(
        "sources.json", "categories.json", "preferences.json", "manga.ndjson", "chapters.ndjson",
        "library.ndjson", "history.ndjson", "bookmarks.ndjson", "tracking.ndjson",
    )
    private val orderedEntries = listOf("manifest.json") + entries
    private val requiredEntries = setOf("manifest.json") + entries
    private val timestamp = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    data class Limits(
        val maxArchiveBytes: Int = 64 * 1024 * 1024,
        val maxSectionBytes: Int = 16 * 1024 * 1024,
        val maxTotalUncompressedBytes: Int = 96 * 1024 * 1024,
        val maxRecordsPerSection: Int = 100_000,
        val maxCompressionRatio: Double = 200.0,
    ) {
        init {
            require(
                maxArchiveBytes > 0 &&
                    maxSectionBytes > 0 &&
                    maxTotalUncompressedBytes > 0 &&
                    maxRecordsPerSection > 0 &&
                    maxCompressionRatio >= 1.0,
            )
        }
    }

    open class InvalidBackupException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
    class UnsupportedVersionException(message: String) : InvalidBackupException(message)

    fun encode(snapshot: NyoraBackupSnapshot): ByteArray {
        validateSnapshot(snapshot)
        val sections = linkedMapOf(
            "sources.json" to json.encodeToString(ListSerializer(NyoraBackupSource.serializer()), snapshot.sources).encodeToByteArray(),
            "categories.json" to json.encodeToString(ListSerializer(NyoraBackupCategory.serializer()), snapshot.categories).encodeToByteArray(),
            "preferences.json" to json.encodeToString(ListSerializer(NyoraBackupPreference.serializer()), snapshot.preferences).encodeToByteArray(),
            "manga.ndjson" to ndjson(snapshot.manga, NyoraBackupManga.serializer()),
            "chapters.ndjson" to ndjson(snapshot.chapters, NyoraBackupChapter.serializer()),
            "library.ndjson" to ndjson(snapshot.library, NyoraBackupLibrary.serializer()),
            "history.ndjson" to ndjson(snapshot.history, NyoraBackupHistory.serializer()),
            "bookmarks.ndjson" to ndjson(snapshot.bookmarks, NyoraBackupBookmark.serializer()),
            "tracking.ndjson" to ndjson(snapshot.tracking, NyoraBackupTracking.serializer()),
        )
        val manifest = NyoraBackupManifest(
            format = NyoraBackupSnapshot.FORMAT,
            schemaVersion = NyoraBackupSnapshot.SCHEMA_VERSION,
            archiveId = snapshot.archiveId,
            createdAt = snapshot.createdAt,
            appVersion = snapshot.appVersion,
            platform = snapshot.platform,
            mode = snapshot.mode,
            sections = sections.map { (entry, bytes) ->
                NyoraBackupManifestSection(entry, recordCount(entry, bytes), bytes.size.toLong(), sha256(bytes))
            },
            omissions = snapshot.omissions,
        )
        return zip(linkedMapOf("manifest.json" to json.encodeToString(manifest).encodeToByteArray()) + sections)
    }

    fun decode(bytes: ByteArray, limits: Limits = Limits()): NyoraBackupSnapshot {
        if (bytes.size > limits.maxArchiveBytes) invalid("Archive exceeds the configured compressed size limit")
        val archive = readArchive(bytes, limits)
        val manifestBytes = archive["manifest.json"] ?: invalid("Archive is missing manifest.json")
        val manifestText = decodeUtf8(manifestBytes, "manifest")
        val manifest = parse("manifest") { json.decodeFromString<NyoraBackupManifest>(manifestText) }
        if (manifest.format != NyoraBackupSnapshot.FORMAT) invalid("Not a Nyora backup archive")
        if (manifest.schemaVersion != NyoraBackupSnapshot.SCHEMA_VERSION) {
            throw UnsupportedVersionException("Unsupported Nyora backup schema version ${manifest.schemaVersion}")
        }
        if (archive.keys.toList() != orderedEntries) invalid("Archive entries are missing, duplicated, or out of order")
        validateManifest(manifest, manifestText, archive, limits)
        val snapshot = NyoraBackupSnapshot(
            archiveId = manifest.archiveId,
            createdAt = manifest.createdAt,
            appVersion = manifest.appVersion,
            platform = manifest.platform,
            mode = manifest.mode,
            sources = parseRegistry("sources", archive.value("sources.json"), NyoraBackupSource.serializer(), limits),
            categories = parseRegistry("categories", archive.value("categories.json"), NyoraBackupCategory.serializer(), limits),
            preferences = parseRegistry("preferences", archive.value("preferences.json"), NyoraBackupPreference.serializer(), limits),
            manga = parseNdjson("manga", archive.value("manga.ndjson"), NyoraBackupManga.serializer(), limits),
            chapters = parseNdjson("chapters", archive.value("chapters.ndjson"), NyoraBackupChapter.serializer(), limits),
            library = parseNdjson("library", archive.value("library.ndjson"), NyoraBackupLibrary.serializer(), limits),
            history = parseNdjson("history", archive.value("history.ndjson"), NyoraBackupHistory.serializer(), limits),
            bookmarks = parseNdjson("bookmarks", archive.value("bookmarks.ndjson"), NyoraBackupBookmark.serializer(), limits),
            tracking = parseNdjson("tracking", archive.value("tracking.ndjson"), NyoraBackupTracking.serializer(), limits),
            omissions = manifest.omissions,
        )
        validateSnapshot(snapshot)
        return snapshot
    }

    private fun readArchive(bytes: ByteArray, limits: Limits): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        val metadata = readCentralDirectory(bytes)
        var total = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in requiredEntries || !isSafeName(entry.name)) invalid("Archive contains an unsafe entry")
                    if (result.containsKey(entry.name)) invalid("Archive contains duplicate entry ${entry.name}")
                    val content = readBounded(zip, limits.maxSectionBytes)
                    val archiveEntry = metadata.getValue(entry.name)
                    if (content.size.toLong() != archiveEntry.uncompressedSize) invalid("Archive entry length is inconsistent")
                    val compressedSize = archiveEntry.compressedSize
                    if (
                        content.isNotEmpty() &&
                        compressedSize > 0L &&
                        content.size.toDouble() / compressedSize.toDouble() > limits.maxCompressionRatio
                    ) {
                        invalid("Archive entry exceeds the configured decompression ratio")
                    }
                    total += content.size
                    if (total > limits.maxTotalUncompressedBytes) invalid("Archive exceeds the configured uncompressed size limit")
                    result[entry.name] = content
                    zip.closeEntry()
                }
            }
        } catch (error: InvalidBackupException) {
            throw error
        } catch (error: Exception) {
            throw InvalidBackupException("Archive is corrupt", error)
        }
        return result
    }

    private data class ArchiveEntryMetadata(
        val compressedSize: Long,
        val uncompressedSize: Long,
    )

    private fun readCentralDirectory(bytes: ByteArray): Map<String, ArchiveEntryMetadata> {
        val eocd = findEndOfCentralDirectory(bytes)
        val disk = bytes.u16(eocd + 4)
        val centralDisk = bytes.u16(eocd + 6)
        val entriesOnDisk = bytes.u16(eocd + 8)
        val entryCount = bytes.u16(eocd + 10)
        val centralSize = bytes.u32(eocd + 12)
        val centralOffset = bytes.u32(eocd + 16)
        val commentLength = bytes.u16(eocd + 20)
        if (
            disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount ||
            entryCount == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL ||
            eocd + 22 + commentLength != bytes.size || centralOffset + centralSize != eocd.toLong()
        ) {
            invalid("Archive uses an unsupported ZIP structure")
        }
        val result = linkedMapOf<String, ArchiveEntryMetadata>()
        var offset = centralOffset.toInt()
        repeat(entryCount) {
            if (offset + 46 > eocd || bytes.u32(offset) != 0x02014b50L) invalid("Archive central directory is corrupt")
            val flags = bytes.u16(offset + 8)
            val method = bytes.u16(offset + 10)
            val compressedSize = bytes.u32(offset + 20)
            val uncompressedSize = bytes.u32(offset + 24)
            val nameLength = bytes.u16(offset + 28)
            val extraLength = bytes.u16(offset + 30)
            val entryCommentLength = bytes.u16(offset + 32)
            val startDisk = bytes.u16(offset + 34)
            val next = offset + 46 + nameLength + extraLength + entryCommentLength
            if (
                next > eocd || flags and 1 != 0 || method !in setOf(0, 8) || startDisk != 0 ||
                compressedSize == 0xffffffffL || uncompressedSize == 0xffffffffL
            ) {
                invalid("Archive uses an encrypted or unsupported ZIP entry")
            }
            val name = decodeUtf8(bytes.copyOfRange(offset + 46, offset + 46 + nameLength), "ZIP entry name")
            if (name !in requiredEntries || !isSafeName(name) || result.containsKey(name)) invalid("Archive contains an unsafe or duplicate entry")
            result[name] = ArchiveEntryMetadata(compressedSize, uncompressedSize)
            offset = next
        }
        if (offset != eocd) invalid("Archive central directory is corrupt")
        return result
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimum = maxOf(0, bytes.size - 65_557)
        for (offset in bytes.size - 22 downTo minimum) {
            if (bytes.u32(offset) == 0x06054b50L) return offset
        }
        invalid("Archive end record is missing")
    }

    private fun ByteArray.u16(offset: Int): Int {
        if (offset < 0 || offset + 2 > size) invalid("Archive metadata is truncated")
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.u32(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) invalid("Archive metadata is truncated")
        return (u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)) and 0xffffffffL
    }

    private fun validateManifest(manifest: NyoraBackupManifest, manifestText: String, archive: Map<String, ByteArray>, limits: Limits) {
        val objectKeys = parse("manifest") { json.parseToJsonElement(manifestText).jsonObject.keys }
        if (objectKeys != manifestFields) invalid("Manifest fields are incomplete or unsupported")
        val encodedSections = parse("manifest") { json.parseToJsonElement(manifestText).jsonObject.getValue("sections").jsonArray }
        if (encodedSections.any { it.jsonObject.keys != manifestSectionFields }) invalid("Manifest section fields are incomplete or unsupported")
        validateHeader(manifest.archiveId, manifest.createdAt, manifest.appVersion, manifest.platform, manifest.mode)
        if (manifest.sections.map { it.entry }.toSet() != entries.toSet() || manifest.sections.size != entries.size) invalid("Manifest sections are incomplete or duplicated")
        manifest.sections.forEach { section ->
            if (section.schemaVersion != NyoraBackupSnapshot.SCHEMA_VERSION || section.optional) invalid("Unsupported section version for ${section.entry}")
            if (section.recordCount < 0 || section.recordCount > limits.maxRecordsPerSection) invalid("Section record count exceeds the configured limit")
            val bytes = archive[section.entry] ?: invalid("Manifest references a missing section")
            if (section.byteLength != bytes.size.toLong()) invalid("Section length mismatch for ${section.entry}")
            if (section.sha256 != sha256(bytes)) invalid("Section checksum mismatch for ${section.entry}")
            if (section.recordCount != recordCount(section.entry, bytes)) invalid("Section record count mismatch for ${section.entry}")
        }
        if (manifest.omissions.any { it.key.isBlank() || it.value < 0 }) invalid("Manifest omissions are invalid")
    }

    private fun validateSnapshot(snapshot: NyoraBackupSnapshot) {
        if (snapshot.schemaVersion != NyoraBackupSnapshot.SCHEMA_VERSION) throw UnsupportedVersionException("Unsupported Nyora backup schema version ${snapshot.schemaVersion}")
        validateHeader(snapshot.archiveId, snapshot.createdAt, snapshot.appVersion, snapshot.platform, snapshot.mode)
        snapshot.sources.forEach {
            try {
                NyoraBackupIdentity.requireRemoteSourceId(it.id)
            } catch (error: IllegalArgumentException) {
                throw InvalidBackupException(error.message ?: "Source identity is invalid", error)
            }
            validateRow(it.id, it.updatedAt, it.deletedAt)
        }
        snapshot.categories.forEach { validateUuid(it.id, "category id"); validateRow(it.id, it.updatedAt, it.deletedAt) }
        snapshot.preferences.forEach { if (it.key.isBlank() || it.id != it.key) invalid("Preference identity is invalid"); validateRow(it.id, it.updatedAt, it.deletedAt) }
        val sourceIds = snapshot.sources.map { it.id }.toSet()
        val mangaIds = snapshot.manga.map { it.id }.toSet()
        val chapterIds = snapshot.chapters.map { it.id }.toSet()
        val chaptersById = snapshot.chapters.associateBy { it.id }
        val categoryIds = snapshot.categories.map { it.id }.toSet()
        unique(snapshot.sources.map { it.id }, "source")
        unique(snapshot.categories.map { it.id }, "category")
        unique(snapshot.preferences.map { it.id }, "preference")
        unique(snapshot.manga.map { it.id }, "manga")
        unique(snapshot.chapters.map { it.id }, "chapter")
        unique(snapshot.library.map { it.id }, "library")
        unique(snapshot.history.map { it.id }, "history")
        unique(snapshot.bookmarks.map { it.id }, "bookmark")
        unique(snapshot.tracking.map { it.id }, "tracking")
        snapshot.manga.forEach {
            validateRow(it.id, it.updatedAt, it.deletedAt)
            if (it.sourceId !in sourceIds) invalid("Manga references a missing source")
            if (it.id != NyoraBackupIdentity.mangaId(it.sourceId, it.contentKey)) invalid("Manga canonical identity is invalid")
        }
        snapshot.chapters.forEach {
            validateRow(it.id, it.updatedAt, it.deletedAt)
            if (it.mangaId !in mangaIds) invalid("Chapter references a missing manga")
            if (it.id != NyoraBackupIdentity.chapterId(it.mangaId, it.chapterKey)) invalid("Chapter canonical identity is invalid")
        }
        snapshot.library.forEach {
            validateRow(it.id, it.updatedAt, it.deletedAt)
            if (it.id != it.mangaId || it.mangaId !in mangaIds || it.categoryIds.any { id -> id !in categoryIds }) invalid("Library references are invalid")
        }
        snapshot.history.forEach {
            validateRow(it.id, it.updatedAt, it.deletedAt)
            if (it.mangaId !in mangaIds || (it.chapterId != null && chaptersById[it.chapterId]?.mangaId != it.mangaId)) invalid("History references are invalid")
        }
        snapshot.bookmarks.forEach {
            validateUuid(it.id, "bookmark id"); validateRow(it.id, it.updatedAt, it.deletedAt)
            if (it.mangaId !in mangaIds || (it.chapterId != null && chaptersById[it.chapterId]?.mangaId != it.mangaId)) invalid("Bookmark references are invalid")
        }
        snapshot.tracking.forEach {
            validateRow(it.id, it.updatedAt, it.deletedAt)
            if (it.mangaId !in mangaIds || !Regex("[a-z][a-z0-9-]*").matches(it.service) || !Regex("[a-z][a-z0-9-]*").matches(it.status)) invalid("Tracking identity is invalid")
        }
    }

    private fun validateHeader(archiveId: String, createdAt: String, appVersion: String, platform: String, mode: String) {
        validateUuid(archiveId, "archive id")
        validateTimestamp(createdAt)
        if (appVersion.isBlank() || platform.isBlank() || mode != NyoraBackupSnapshot.MODE_PORTABLE_DATA) invalid("Archive header is invalid")
    }

    private fun validateRow(id: String, updatedAt: String, deletedAt: String?) {
        if (id.isBlank()) invalid("Record id is blank")
        validateTimestamp(updatedAt)
        deletedAt?.let(::validateTimestamp)
    }

    private fun validateTimestamp(value: String) {
        if (!timestamp.matches(value)) invalid("Timestamps must be RFC 3339 UTC with millisecond precision")
        try { Instant.parse(value) } catch (_: Exception) { invalid("Timestamp is invalid") }
    }

    private fun validateUuid(value: String, label: String) {
        if (!uuid.matches(value)) invalid("$label must be a lower-case UUID")
        try { UUID.fromString(value) } catch (_: Exception) { invalid("$label is invalid") }
    }

    private fun unique(ids: List<String>, type: String) {
        if (ids.size != ids.toSet().size) invalid("Duplicate $type identity")
    }

    private fun isSafeName(name: String): Boolean = name in requiredEntries && !name.startsWith('/') && !name.contains("..") && !name.contains('\\')

    private fun readBounded(input: ZipInputStream, maximum: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximum) invalid("Section exceeds the configured size limit")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun <T> ndjson(rows: List<T>, serializer: kotlinx.serialization.KSerializer<T>): ByteArray =
        if (rows.isEmpty()) ByteArray(0) else rows.joinToString("\n") { json.encodeToString(serializer, it) }.plus("\n").encodeToByteArray()

    private fun <T> parseNdjson(section: String, bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>, limits: Limits): List<T> {
        val text = decodeUtf8(bytes, section)
        if (text.isEmpty()) return emptyList()
        if (!text.endsWith('\n') || text.endsWith("\n\n")) invalid("Section $section has invalid NDJSON framing")
        val lines = text.dropLast(1).split('\n')
        if (lines.any { it.isBlank() }) invalid("Section $section has an empty NDJSON record")
        if (lines.size > limits.maxRecordsPerSection) invalid("Section $section exceeds the configured record limit")
        return lines.map { line -> parse(section) { json.decodeFromString(serializer, line) } }
    }

    private fun <T> parseRegistry(section: String, bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>, limits: Limits): List<T> {
        val rows = parse(section) { json.decodeFromString(ListSerializer(serializer), decodeUtf8(bytes, section)) }
        if (rows.size > limits.maxRecordsPerSection) invalid("Section $section exceeds the configured record limit")
        return rows
    }

    private fun recordCount(entry: String, bytes: ByteArray): Long = when (entry) {
        "sources.json" -> parse("sources") { json.decodeFromString(ListSerializer(NyoraBackupSource.serializer()), decodeUtf8(bytes, "sources")).size.toLong() }
        "categories.json" -> parse("categories") { json.decodeFromString(ListSerializer(NyoraBackupCategory.serializer()), decodeUtf8(bytes, "categories")).size.toLong() }
        "preferences.json" -> parse("preferences") { json.decodeFromString(ListSerializer(NyoraBackupPreference.serializer()), decodeUtf8(bytes, "preferences")).size.toLong() }
        else -> decodeUtf8(bytes, entry).lineSequence().count { it.isNotBlank() }.toLong()
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) -> zip.writeStored(name, value) } }
        bytes.toByteArray()
    }

    private fun ZipOutputStream.writeStored(name: String, value: ByteArray) {
        val crc = CRC32().apply { update(value) }
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = value.size.toLong(); compressedSize = size; this.crc = crc.value; time = 0L })
        write(value)
        closeEntry()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun decodeUtf8(bytes: ByteArray, section: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw InvalidBackupException("Invalid UTF-8 in $section section", error)
    }
    private fun Map<String, ByteArray>.value(entry: String): ByteArray = this[entry] ?: invalid("Archive is missing $entry")
    private fun invalid(message: String): Nothing = throw InvalidBackupException(message)
    private inline fun <T> parse(section: String, block: () -> T): T = try { block() } catch (error: InvalidBackupException) { throw error } catch (error: Exception) { throw InvalidBackupException("Invalid $section section", error) }
}

private val manifestFields = setOf("format", "schemaVersion", "archiveId", "createdAt", "appVersion", "platform", "mode", "sections", "omissions")
private val manifestSectionFields = setOf("entry", "recordCount", "byteLength", "sha256", "schemaVersion", "optional")

@Serializable
private data class NyoraBackupManifest(
    val format: String,
    val schemaVersion: Int,
    val archiveId: String = "",
    val createdAt: String = "",
    val appVersion: String = "",
    val platform: String = "",
    val mode: String = "",
    val sections: List<NyoraBackupManifestSection> = emptyList(),
    val omissions: Map<String, Int> = emptyMap(),
)

@Serializable
private data class NyoraBackupManifestSection(
    val entry: String,
    val recordCount: Long,
    val byteLength: Long,
    val sha256: String,
    val schemaVersion: Int = NyoraBackupSnapshot.SCHEMA_VERSION,
    val optional: Boolean = false,
)
