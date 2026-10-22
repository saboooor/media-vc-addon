package ca.saboor.discs.visualizer

import kotlin.math.*

/** 1024-point FFT, at most twenty analyses per second; mono samples are tapped without modifying audio. */
class SpectrumAnalyzer {
    private val samples = DoubleArray(1024)
    private val re = DoubleArray(1024)
    private val im = DoubleArray(1024)
    private val lowBins = IntArray(16)
    private val highBins = IntArray(16)
    private var bandSampleRate = 0
    private var cursor = 0
    private var elapsed = 0
    @Volatile var levels = FloatArray(16)
        private set

    @Synchronized fun accept(channels: Array<FloatArray>, offset: Int, length: Int, sampleRate: Int) {
        if (channels.isEmpty()) return
        for (index in offset until offset + length) {
            var mono = 0.0
            for (channel in channels) mono += channel[index]
            samples[cursor] = mono / channels.size
            cursor = (cursor + 1) and 1023
            if (++elapsed >= sampleRate / 20) {
                elapsed = 0
                analyze(sampleRate)
            }
        }
    }

    @Synchronized fun reset() {
        samples.fill(0.0)
        elapsed = 0
        cursor = 0
        levels = FloatArray(16)
    }

    private fun analyze(sampleRate: Int) {
        for (i in re.indices) re[i] = samples[(cursor + i) and 1023] * window[i]
        im.fill(0.0)
        if (bandSampleRate != sampleRate) {
            for (band in 0 until 16) {
                lowBins[band] = (bandEdges[band] * 1024 / sampleRate).toInt().coerceIn(1, 511)
                highBins[band] = (bandEdges[band + 1] * 1024 / sampleRate).toInt().coerceIn(lowBins[band] + 1, 512)
            }
            bandSampleRate = sampleRate
        }
        var j = 0
        for (i in 1 until 1024) {
            var bit = 512
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val value = re[i]; re[i] = re[j]; re[j] = value }
        }
        var size = 2
        while (size <= 1024) {
            for (base in 0 until 1024 step size) {
                for (k in 0 until size / 2) {
                    val twiddle = k * 1024 / size
                    val cosine = cosines[twiddle]
                    val sine = sines[twiddle]
                    val a = base + k
                    val b = a + size / 2
                    val real = re[b] * cosine - im[b] * sine
                    val imag = re[b] * sine + im[b] * cosine
                    re[b] = re[a] - real; im[b] = im[a] - imag
                    re[a] += real; im[a] += imag
                }
            }
            size *= 2
        }
        levels = FloatArray(16) { band ->
            val low = lowBins[band]
            val high = highBins[band]
            var magnitude = 0.0
            for (bin in low until high) magnitude = max(magnitude, hypot(re[bin], im[bin]) / 256)
            ((20 * log10(max(magnitude, 0.00001)) + 45) / 45).coerceIn(0.0, 1.0).pow(1.6).toFloat()
        }
    }
    companion object {
        private val window = DoubleArray(1024) { 0.5 - 0.5 * cos(2 * PI * it / 1023) }
        private val cosines = DoubleArray(512) { cos(-2 * PI * it / 1024) }
        private val sines = DoubleArray(512) { sin(-2 * PI * it / 1024) }
        private val bandEdges = DoubleArray(17) { 50 * 320.0.pow(it / 16.0) }
    }
}
