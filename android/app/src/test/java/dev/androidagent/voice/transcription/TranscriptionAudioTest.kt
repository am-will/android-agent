package dev.androidagent.voice.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionAudioTest {
    @Test
    fun concatenateCopiesBuffersInOrder() {
        val result = TranscriptionAudio.concatenate(
            listOf(
                shortArrayOf(1, 2),
                shortArrayOf(3),
                shortArrayOf(4, 5)
            )
        )

        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), result)
    }

    @Test
    fun durationAndMinimumLengthUseSampleRate() {
        assertEquals(0.5, TranscriptionAudio.durationSeconds(12_000, 24_000), 0.0001)
        assertFalse(TranscriptionAudio.isTooShort(12_000, 24_000))
        assertTrue(TranscriptionAudio.isTooShort(11_999, 24_000))
    }

    @Test
    fun rmsLevelScalesAndClampsAmplitude() {
        assertEquals(0f, TranscriptionAudio.rmsLevel(shortArrayOf()), 0.0001f)
        assertEquals(0f, TranscriptionAudio.rmsLevel(shortArrayOf(10), size = 0), 0.0001f)

        val loud = TranscriptionAudio.rmsLevel(shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE))

        assertEquals(1f, loud, 0.0001f)
    }

    @Test
    fun resamplePcm16UsesLinearInterpolation() {
        val input = shortArrayOf(0, 10, 20, 30)

        val output = TranscriptionAudio.resamplePcm16(input, inputRate = 4, outputRate = 2)

        assertArrayEquals(shortArrayOf(0, 20), output)
    }

    @Test
    fun encodeWavMonoPcm16WritesExpectedHeaderAndLittleEndianSamples() {
        val wav = TranscriptionAudio.encodeWavMonoPcm16(
            samples = shortArrayOf(0x0102, 0x0304),
            sampleRate = 24_000
        )

        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals(40, wav.intLittleEndian(4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals(16, wav.intLittleEndian(16))
        assertEquals(1, wav.shortLittleEndian(20))
        assertEquals(1, wav.shortLittleEndian(22))
        assertEquals(24_000, wav.intLittleEndian(24))
        assertEquals(48_000, wav.intLittleEndian(28))
        assertEquals(2, wav.shortLittleEndian(32))
        assertEquals(16, wav.shortLittleEndian(34))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(4, wav.intLittleEndian(40))
        assertEquals(0x02, wav[44].toInt() and 0xFF)
        assertEquals(0x01, wav[45].toInt() and 0xFF)
        assertEquals(0x04, wav[46].toInt() and 0xFF)
        assertEquals(0x03, wav[47].toInt() and 0xFF)
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        return copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
    }

    private fun ByteArray.intLittleEndian(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.shortLittleEndian(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)
    }
}
