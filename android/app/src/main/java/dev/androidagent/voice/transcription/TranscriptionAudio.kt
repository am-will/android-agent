package dev.androidagent.voice.transcription

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object TranscriptionAudio {
    const val TARGET_SAMPLE_RATE = 24_000
    const val DEFAULT_DEVICE_SAMPLE_RATE = 44_100
    const val MIN_RECORDING_SECONDS = 0.5

    fun concatenate(buffers: List<ShortArray>): ShortArray {
        val totalSamples = buffers.sumOf { it.size }
        val samples = ShortArray(totalSamples)
        var offset = 0
        for (buffer in buffers) {
            buffer.copyInto(samples, destinationOffset = offset)
            offset += buffer.size
        }
        return samples
    }

    fun durationSeconds(sampleCount: Int, sampleRate: Int): Double {
        require(sampleRate > 0) { "sampleRate must be positive" }
        return sampleCount.toDouble() / sampleRate.toDouble()
    }

    fun isTooShort(sampleCount: Int, sampleRate: Int, minimumSeconds: Double = MIN_RECORDING_SECONDS): Boolean {
        return durationSeconds(sampleCount, sampleRate) < minimumSeconds
    }

    fun rmsLevel(buffer: ShortArray, size: Int = buffer.size): Float {
        if (size <= 0) {
            return 0f
        }
        val count = size.coerceAtMost(buffer.size)
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer[i].toDouble() / Short.MAX_VALUE.toDouble()
            sum += sample * sample
        }
        return (sqrt(sum / count) * 3.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun resamplePcm16(input: ShortArray, inputRate: Int, outputRate: Int = TARGET_SAMPLE_RATE): ShortArray {
        require(inputRate > 0) { "inputRate must be positive" }
        require(outputRate > 0) { "outputRate must be positive" }
        if (input.isEmpty() || inputRate == outputRate) {
            return input
        }

        val ratio = inputRate.toDouble() / outputRate.toDouble()
        val outputSize = (input.size / ratio).toInt().coerceAtLeast(1)
        val output = ShortArray(outputSize)
        for (i in output.indices) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val fraction = srcPos - srcIndex
            val first = input[srcIndex.coerceIn(input.indices)].toDouble()
            val second = input[(srcIndex + 1).coerceIn(input.indices)].toDouble()
            output[i] = (first + fraction * (second - first)).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()
        }
        return output
    }

    fun encodeWavMonoPcm16(samples: ShortArray, sampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val dataSize = samples.size * BYTES_PER_SAMPLE
        val output = ByteArrayOutputStream(WAV_HEADER_BYTES + dataSize)
        val writer = DataOutputStream(output)

        writer.writeBytes("RIFF")
        writer.writeIntLittleEndian(36 + dataSize)
        writer.writeBytes("WAVE")
        writer.writeBytes("fmt ")
        writer.writeIntLittleEndian(16)
        writer.writeShortLittleEndian(1)
        writer.writeShortLittleEndian(1)
        writer.writeIntLittleEndian(sampleRate)
        writer.writeIntLittleEndian(sampleRate * BYTES_PER_SAMPLE)
        writer.writeShortLittleEndian(BYTES_PER_SAMPLE)
        writer.writeShortLittleEndian(16)
        writer.writeBytes("data")
        writer.writeIntLittleEndian(dataSize)

        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            pcm.putShort(sample)
        }
        writer.write(pcm.array())
        return output.toByteArray()
    }

    private fun DataOutputStream.writeIntLittleEndian(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLittleEndian(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private const val WAV_HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2
}
