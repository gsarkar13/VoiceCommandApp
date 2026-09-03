package com.voicecommand.partner.engine

interface WakeDetector {
    val phraseIds: List<String>
    fun process(frame: ShortArray): Int
    fun close()
}
