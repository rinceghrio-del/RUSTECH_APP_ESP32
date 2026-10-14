package com.example.facerobot.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Nag-iimbak ng mga naka-enroll na mukha (pangalan -> MARAMING embedding samples) gamit ang
 * SharedPreferences bilang simpleng JSON.
 *
 * Upgrade: bawat tao ay may hanggang MAX_SAMPLES_PER_PERSON na samples (iba't ibang anggulo/liwanag),
 * at may "margin check" para hindi magkamali kapag magkahawig ang dalawang naka-enroll.
 *
 * NOTE: bagong key ang ginamit (known_faces_v2) dahil ang mga lumang embeddings ay galing sa
 * hindi-naka-align na crop at hindi tutugma sa bagong aligned na embeddings. Kusa silang hindi
 * na gagamitin - kailangan lang mag-enroll ulit.
 */
class FaceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "face_store"
        private const val KEY_FACES = "known_faces_v2"

        /** Cosine similarity. Panoorin ang % sa status chip at i-tune: taasan kung may maling kilala, ibaba kung hindi ka makilala. */
        const val MATCH_THRESHOLD = 0.65f

        /** Dapat lamang ng ganito ang pinakamataas na tao sa pangalawa, kung hindi ay "ambiguous" (hindi kilala). */
        const val AMBIGUITY_MARGIN = 0.05f

        const val MAX_SAMPLES_PER_PERSON = 10

        private fun dot(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return -1f
            var d = 0f
            for (i in a.indices) d += a[i] * b[i]
            return d
        }

        /** Itapon ang mga sample na malayo sa gitna ng grupo (hal. ibang tao ang aksidenteng napasok). */
        fun filterConsistent(samples: List<FloatArray>, minSim: Float = 0.6f): List<FloatArray> {
            if (samples.size < 3) return samples
            val c = FloatArray(samples[0].size)
            for (s in samples) for (i in c.indices) c[i] += s[i]
            var n = 0f
            for (v in c) n += v * v
            n = sqrt(n).coerceAtLeast(1e-6f)
            for (i in c.indices) c[i] /= n
            val kept = samples.filter { dot(it, c) >= minSim }
            return if (kept.isEmpty()) samples else kept
        }
    }

    data class KnownFace(val name: String, val embedding: FloatArray)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val knownFaces = mutableListOf<KnownFace>()

    init {
        load()
    }

    private fun load() {
        knownFaces.clear()
        val json = prefs.getString(KEY_FACES, null) ?: return
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val embArray = obj.getJSONArray("embedding")
                val embedding = FloatArray(embArray.length()) { embArray.getDouble(it).toFloat() }
                knownFaces.add(KnownFace(name, embedding))
            }
        } catch (e: Exception) {
            knownFaces.clear()
        }
    }

    private fun persist() {
        val array = JSONArray()
        for (face in knownFaces) {
            val obj = JSONObject()
            obj.put("name", face.name)
            val embArray = JSONArray()
            for (v in face.embedding) embArray.put(v.toDouble())
            obj.put("embedding", embArray)
            array.put(obj)
        }
        prefs.edit().putString(KEY_FACES, array.toString()).apply()
    }

    /** Isang sample lang (compat sa lumang tawag). Pinapalitan ang existing na may parehong pangalan. */
    fun enroll(name: String, embedding: FloatArray) = enroll(name, listOf(embedding))

    /** Maraming samples para sa isang tao. Pinapalitan ang lahat ng existing na may parehong pangalan. */
    fun enroll(name: String, embeddings: List<FloatArray>) {
        val clean = name.trim()
        knownFaces.removeAll { it.name.equals(clean, ignoreCase = true) }
        for (e in embeddings.takeLast(MAX_SAMPLES_PER_PERSON)) {
            knownFaces.add(KnownFace(clean, e))
        }
        persist()
    }

    fun remove(name: String) {
        knownFaces.removeAll { it.name.equals(name, ignoreCase = true) }
        persist()
    }

    fun allNames(): List<String> = knownFaces.map { it.name }.distinctBy { it.lowercase() }

    fun isEmpty(): Boolean = knownFaces.isEmpty()

    data class MatchResult(val name: String, val similarity: Float)

    /**
     * Per-tao na score = average ng pinakamataas na 2 samples (o 1 kung isa lang).
     * Null kung walang naka-enroll, kulang ang score, o magkalapit ang top 2 na tao (ambiguous).
     */
    fun match(embedding: FloatArray): MatchResult? {
        if (knownFaces.isEmpty()) return null

        val scores = knownFaces
            .groupBy { it.name.lowercase() }
            .map { (_, faces) ->
                val sims = faces.map { dot(embedding, it.embedding) }.sortedDescending()
                val top = sims.take(2)
                Pair(faces[0].name, top.average().toFloat())
            }
            .sortedByDescending { it.second }

        val best = scores.first()
        if (best.second < MATCH_THRESHOLD) return null

        if (scores.size > 1 && best.second - scores[1].second < AMBIGUITY_MARGIN) {
            return null // masyadong magkalapit ang dalawang tao, mas mabuting huwag manghula
        }

        return MatchResult(best.first, best.second)
    }
}
