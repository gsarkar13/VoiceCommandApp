package com.voicecommand.partner.gate

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object Mfcc {
    private const val FRAME_LEN = 400
    private const val FRAME_STEP = 160
    private const val N_FFT = 512
    private const val MEL_COUNT = 26
    private const val CEPS_COUNT = 13
    private const val PRE_EMPHASIS = 0.97f
    private const val SAMPLE_RATE = 16000

    private val hamming = FloatArray(FRAME_LEN) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_LEN - 1))).toFloat()
    }

    private val melFilters: Array<FloatArray> = buildMelFilters()

    fun frames(samples: ShortArray): Array<FloatArray> {
        if (samples.size < FRAME_LEN) return emptyArray()
        val pcm = FloatArray(samples.size) { samples[it] / 32768f }
        for (i in pcm.size - 1 downTo 1) {
            pcm[i] = pcm[i] - PRE_EMPHASIS * pcm[i - 1]
        }
        val count = 1 + (pcm.size - FRAME_LEN) / FRAME_STEP
        val out = ArrayList<FloatArray>(count)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        val power = FloatArray(N_FFT / 2 + 1)
        for (f in 0 until count) {
            val start = f * FRAME_STEP
            for (i in 0 until FRAME_LEN) {
                re[i] = pcm[start + i] * hamming[i]
                im[i] = 0f
            }
            for (i in FRAME_LEN until N_FFT) {
                re[i] = 0f
                im[i] = 0f
            }
            fft(re, im)
            for (k in power.indices) {
                power[k] = (re[k] * re[k] + im[k] * im[k]) / N_FFT
            }
            val mel = FloatArray(MEL_COUNT)
            for (m in 0 until MEL_COUNT) {
                var sum = 0f
                val filter = melFilters[m]
                for (k in filter.indices) {
                    sum += filter[k] * power[k]
                }
                mel[m] = ln(sum + 1e-10f)
            }
            val ceps = FloatArray(CEPS_COUNT)
            for (c in 0 until CEPS_COUNT) {
                var sum = 0f
                for (m in 0 until MEL_COUNT) {
                    sum += mel[m] * cos(PI * c * (m + 0.5) / MEL_COUNT).toFloat()
                }
                ceps[c] = sum
            }
            out.add(ceps)
        }
        return out.toTypedArray()
    }

    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += (s.toDouble() / 32768.0) * (s.toDouble() / 32768.0)
        return sqrt(sum / samples.size)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wR = cos(ang).toFloat()
            val wI = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cR = 1f
                var cI = 0f
                for (k in 0 until len / 2) {
                    val aIdx = i + k
                    val bIdx = i + k + len / 2
                    val bR = re[bIdx]
                    val bI = im[bIdx]
                    val vR = bR * cR - bI * cI
                    val vI = bR * cI + bI * cR
                    val uR = re[aIdx]
                    val uI = im[aIdx]
                    re[aIdx] = uR + vR
                    im[aIdx] = uI + vI
                    re[bIdx] = uR - vR
                    im[bIdx] = uI - vI
                    val nR = cR * wR - cI * wI
                    cI = cR * wI + cI * wR
                    cR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun buildMelFilters(): Array<FloatArray> {
        val nyquist = SAMPLE_RATE / 2.0
        val melMin = 0.0
        val melMax = 2595.0 * kotlin.math.log10(1.0 + nyquist / 700.0)
        val melPoints = DoubleArray(MEL_COUNT + 2) { i ->
            val m = melMin + (melMax - melMin) * i / (MEL_COUNT + 1)
            700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
        }
        val bins = N_FFT / 2 + 1
        val filters = Array(MEL_COUNT) { FloatArray(bins) }
        for (m in 0 until MEL_COUNT) {
            val start = melPoints[m]
            val center = melPoints[m + 1]
            val end = melPoints[m + 2]
            for (k in 0 until bins) {
                val f = k.toDouble() * SAMPLE_RATE / N_FFT
                val w = when {
                    f >= start && f <= center && center > start -> ((f - start) / (center - start)).toFloat()
                    f > center && f <= end && end > center -> ((end - f) / (end - center)).toFloat()
                    else -> 0f
                }
                filters[m][k] = w
            }
        }
        return filters
    }
}
