package com.nyora.hasan72341.core.parser.datadriven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MANGA Plus answers protobuf and nothing else, so every title, chapter and page url the app shows
 * comes out of this reader. The wire format is hand-built here rather than generated, because the
 * point is that the reader agrees with the format — not with a generator that shares its bugs.
 */
class ProtoMessageTest {

	@Test
	fun readsAMultiByteVarint() {
		// field 1, wire type 0 (varint), value 300 -> 0xAC 0x02
		val message = ProtoMessage(bytes(0x08, 0xAC, 0x02))

		assertEquals(300L, message.long(1))
		assertEquals(300, message.int(1))
	}

	@Test
	fun readsALengthDelimitedString() {
		// field 2, wire type 2, length 2, "ab"
		val message = ProtoMessage(bytes(0x12, 0x02, 0x61, 0x62))

		assertEquals("ab", message.string(2))
	}

	@Test
	fun readsANestedMessage() {
		// field 3, wire type 2, length 2 -> { field 1, varint 7 }
		val message = ProtoMessage(bytes(0x1A, 0x02, 0x08, 0x07))

		assertEquals(7L, message.message(3)?.long(1))
	}

	@Test
	fun keepsRepeatedFieldsInOrder() {
		// field 3 twice: { field 1 = 1 } then { field 1 = 2 }
		val message = ProtoMessage(bytes(0x1A, 0x02, 0x08, 0x01, 0x1A, 0x02, 0x08, 0x02))

		assertEquals(listOf(1L, 2L), message.messages(3).map { it.long(1) })
	}

	@Test
	fun readsEveryFieldOfAMixedMessage() {
		val message = ProtoMessage(bytes(0x08, 0xAC, 0x02, 0x12, 0x02, 0x61, 0x62, 0x1A, 0x02, 0x08, 0x07))

		assertEquals(300L, message.long(1))
		assertEquals("ab", message.string(2))
		assertEquals(7L, message.message(3)?.long(1))
	}

	@Test
	fun returnsNothingForAbsentOrMistypedFields() {
		val message = ProtoMessage(bytes(0x08, 0xAC, 0x02, 0x12, 0x02, 0x61, 0x62))

		assertNull(message.long(9))
		assertNull(message.string(9))
		assertNull(message.message(9))
		// Field 1 is a varint, not bytes, and field 2 the other way round.
		assertNull(message.string(1))
		assertNull(message.long(2))
	}

	@Test
	fun skipsFixedWidthFieldsInsteadOfMisreadingWhatFollows() {
		// field 4 fixed64, then field 5 fixed32, then field 2 = "ab"
		val message = ProtoMessage(
			bytes(
				0x21, 0, 0, 0, 0, 0, 0, 0, 0,
				0x2D, 0, 0, 0, 0,
				0x12, 0x02, 0x61, 0x62,
			),
		)

		assertEquals("ab", message.string(2))
	}

	@Test
	fun stopsAtATruncatedLengthDelimitedFieldAndKeepsWhatItAlreadyRead() {
		// field 2 = "ab", then field 3 claiming 40 bytes with only 1 left
		val message = ProtoMessage(bytes(0x12, 0x02, 0x61, 0x62, 0x1A, 0x28, 0x00))

		assertEquals("ab", message.string(2))
		assertNull(message.message(3))
	}

	@Test
	fun readsNothingFromAnEmptyBody() {
		val message = ProtoMessage(ByteArray(0))

		assertNull(message.long(1))
		assertEquals(emptyList<ProtoMessage>(), message.messages(1))
	}

	private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
