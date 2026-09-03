package com.voicecommand.partner.gate

import android.content.Context
import com.voicecommand.partner.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

object VoiceGate {
    data class Profile(val phraseId: String, val sequences: List<Array<FloatArray>>, val meanPairwise: Float)

    fun isEnrolled(context: Context): Boolean = Prefs.gateProfileJson(context) != null

    fun enrolledPhraseId(context: Context): String? = Prefs.gatePhraseId(context)

    fun enrolledPhraseLabel(context: Context): String? {
        val id = enrolledPhraseId(context) ?: return null
        return com.voicecommand.partner.data.WakePhraseStore.byId(context, id)?.label
    }

    fun enroll(context: Context, phraseId: String, samples: List<ShortArray>): Boolean {
        val seqs = samples.map { Mfcc.frames(it) }.filter { it.isNotEmpty() }
        if (seqs.size < 2) return false
        val mean = meanPairwise(seqs)
        Prefs.setGatePhraseId(context, phraseId)
        Prefs.setGateProfileJson(context, serialize(Profile(phraseId, seqs, mean)))
        return true
    }

    fun clear(context: Context) {
        Prefs.setGateProfileJson(context, null)
        Prefs.setGatePhraseId(context, null)
    }

    fun verify(context: Context, audio: ShortArray): Boolean {
        val profile = deserialize(Prefs.gateProfileJson(context)) ?: return true
        val frames = Mfcc.frames(audio)
        if (frames.isEmpty()) return false
        val best = profile.sequences.minOf { Dtw.distance(frames, it) }
        val threshold = maxOf(profile.meanPairwise * Prefs.gateMultiplier(context), 6f)
        return best <= threshold
    }

    private fun meanPairwise(seqs: List<Array<FloatArray>>): Float {
        var total = 0f
        var count = 0
        for (i in seqs.indices) {
            for (j in i + 1 until seqs.size) {
                total += Dtw.distance(seqs[i], seqs[j])
                count++
            }
        }
        return if (count == 0) 10f else total / count
    }

    private fun serialize(profile: Profile): String {
        val samples = JSONArray()
        profile.sequences.forEach { frames ->
            val framesJson = JSONArray()
            frames.forEach { ceps ->
                val arr = JSONArray()
                ceps.forEach { arr.put(it.toDouble()) }
                framesJson.put(arr)
            }
            samples.put(framesJson)
        }
        return JSONObject()
            .put("phraseId", profile.phraseId)
            .put("meanPairwise", profile.meanPairwise.toDouble())
            .put("samples", samples)
            .toString()
    }

    private fun deserialize(raw: String?): Profile? {
        if (raw == null) return null
        return try {
            val o = JSONObject(raw)
            val samplesJson = o.getJSONArray("samples")
            val seqs = ArrayList<Array<FloatArray>>(samplesJson.length())
            for (i in 0 until samplesJson.length()) {
                val framesJson = samplesJson.getJSONArray(i)
                val frames = Array(framesJson.length()) { f ->
                    val cepsJson = framesJson.getJSONArray(f)
                    FloatArray(cepsJson.length()) { c -> cepsJson.getDouble(c).toFloat() }
                }
                seqs.add(frames)
            }
            Profile(o.getString("phraseId"), seqs, o.getDouble("meanPairwise").toFloat())
        } catch (e: Exception) {
            null
        }
    }
}
