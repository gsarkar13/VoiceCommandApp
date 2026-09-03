package com.voicecommand.partner.gate

import kotlin.math.sqrt

object Dtw {
    private val UNREACHABLE = Float.MAX_VALUE / 4

    fun distance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return UNREACHABLE
        val n = a.size
        val m = b.size
        var prev = FloatArray(m + 1) { UNREACHABLE }
        var cur = FloatArray(m + 1) { UNREACHABLE }
        prev[0] = 0f
        for (i in 1..n) {
            cur[0] = UNREACHABLE
            for (j in 1..m) {
                val cost = frameCost(a[i - 1], b[j - 1])
                cur[j] = cost + minOf(prev[j], prev[j - 1], cur[j - 1])
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[m] / (n + m)
    }

    private fun frameCost(x: FloatArray, y: FloatArray): Float {
        var sum = 0f
        for (i in x.indices) {
            val d = x[i] - y[i]
            sum += d * d
        }
        return sqrt(sum)
    }
}
