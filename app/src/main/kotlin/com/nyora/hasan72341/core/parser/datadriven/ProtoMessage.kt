package com.nyora.hasan72341.core.parser.datadriven

/**
 * A schema-free protobuf wire-format reader.
 *
 * MANGA Plus' API answers `application/x-protobuf` and has no JSON mode any more, so its
 * responses have to be decoded. Pulling in a protobuf compiler and Shueisha's full `.proto` for the
 * handful of fields a manga reader needs would be a large dependency for a small job — and the wire
 * format itself is trivial: a stream of (field number, type) keys with varint, fixed or
 * length-delimited payloads.
 *
 * So this reads the wire format directly and exposes fields by number. Nothing here is MANGA Plus
 * specific; [com.nyora.hasan72341.core.parser.MangaPlusMangaRepository] holds the field numbers,
 * derived by decoding live responses.
 *
 * Ported from `nyora-shared-datadriven`'s `Protobuf.kt` so every Nyora client decodes the same way.
 */
internal class ProtoMessage(private val bytes: ByteArray) {

	/** field number -> every value seen for it, in order (protobuf repeats by repetition). */
	private val fields: Map<Int, List<Any>> by lazy { parse() }

	fun message(field: Int): ProtoMessage? = messages(field).firstOrNull()

	fun messages(field: Int): List<ProtoMessage> =
		fields[field].orEmpty().filterIsInstance<ByteArray>().map { ProtoMessage(it) }

	fun string(field: Int): String? =
		(fields[field].orEmpty().firstOrNull { it is ByteArray } as? ByteArray)
			?.toString(Charsets.UTF_8)

	fun long(field: Int): Long? = fields[field].orEmpty().firstOrNull { it is Long } as? Long

	fun int(field: Int): Int? = long(field)?.toInt()

	private fun parse(): Map<Int, List<Any>> {
		val out = LinkedHashMap<Int, MutableList<Any>>()
		var pos = 0
		while (pos < bytes.size) {
			val (key, afterKey) = varint(pos) ?: break
			pos = afterKey
			val field = (key ushr 3).toInt()
			if (field == 0) break
			when ((key and 7L).toInt()) {
				0 -> {
					val (value, next) = varint(pos) ?: break
					pos = next
					out.getOrPut(field) { mutableListOf() }.add(value)
				}

				1 -> pos += 8
				5 -> pos += 4
				2 -> {
					val (len, afterLen) = varint(pos) ?: break
					pos = afterLen
					val end = pos + len.toInt()
					if (len < 0 || end > bytes.size) return out
					out.getOrPut(field) { mutableListOf() }.add(bytes.copyOfRange(pos, end))
					pos = end
				}

				else -> return out // unknown wire type: stop rather than misread
			}
		}
		return out
	}

	private fun varint(start: Int): Pair<Long, Int>? {
		var value = 0L
		var shift = 0
		var pos = start
		while (pos < bytes.size) {
			val b = bytes[pos++].toInt() and 0xff
			value = value or ((b and 0x7f).toLong() shl shift)
			if (b and 0x80 == 0) return value to pos
			shift += 7
			if (shift > 63) return null
		}
		return null
	}
}
