package cn.edu.ahu.cardqr.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Kotlin QR encoder to matrices produced by the verified reference encoder. */
class QrEncoderTest {

    @Test
    fun matchesReferenceMatricesForPayCode() {
        for (fixture in QrFixtures.ALL) {
            val qr = QrEncoder.encode(QrFixtures.PAYLOAD, fixture.ecc)
            assertEquals("ecc=${fixture.ecc} version", fixture.version, (qr.size - 17) / 4)
            assertEquals("ecc=${fixture.ecc} size", fixture.rows.size, qr.size)

            val actual = (0 until qr.size).joinToString("\n") { y ->
                (0 until qr.size).joinToString("") { x -> if (qr.isDark(x, y)) "#" else "." }
            }
            val expected = fixture.rows.joinToString("\n")
            assertEquals("ecc=${fixture.ecc} matrix", expected, actual)
        }
    }

    @Test
    fun versionGrowsWithPayload() {
        val small = QrEncoder.encode("1234", QrEncoder.Ecc.LOW)
        val large = QrEncoder.encode("1".repeat(200), QrEncoder.Ecc.LOW)
        assertTrue(small.size < large.size)
    }

    @Test
    fun finderPatternsArePresent() {
        // The three finder patterns begin with a 7x7 dark border and a light ring.
        val qr = QrEncoder.encode(QrFixtures.PAYLOAD, QrEncoder.Ecc.LOW)
        val origins = listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7)
        for ((ox, oy) in origins) {
            for (i in 0 until 7) {
                assertTrue("top edge of finder at ($ox,$oy)", qr.isDark(ox + i, oy))
                assertTrue("bottom edge of finder at ($ox,$oy)", qr.isDark(ox + i, oy + 6))
                assertTrue("left edge of finder at ($ox,$oy)", qr.isDark(ox, oy + i))
                assertTrue("right edge of finder at ($ox,$oy)", qr.isDark(ox + 6, oy + i))
            }
            assertTrue("finder centre at ($ox,$oy)", qr.isDark(ox + 3, oy + 3))
            assertTrue("finder ring at ($ox,$oy)", !qr.isDark(ox + 1, oy + 1))
        }
    }

    @Test
    fun rejectsPayloadTooLargeForVersionForty() {
        try {
            QrEncoder.encode("x".repeat(4000), QrEncoder.Ecc.HIGH)
            throw AssertionError("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
