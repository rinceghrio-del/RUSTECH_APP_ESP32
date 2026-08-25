package com.example.facerobot.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nag-iimbak ng mga naka-enroll na mukha (pangalan -> embedding) gamit ang SharedPreferences
 * bilang simpleng JSON. Wala tayong ginamit na external database para simple lang - sapat na
 * ito para sa ilang tao (rusty, ghrio, sauty, mama, atbp. - tulad ng sa Python mini-robot mo).
 */
class FaceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "face_store"
        private const val KEY_FACES = "known_faces_json"
        const val MATCH_THRESHOLD = 0.75f // cosine similarity - taasan kung madaling magkamali
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
            // Kung sira yung saved JSON sa kadahilanang ano man, mag-start na lang tayo ulit
            // sa blangkong listahan imbes na mag-crash.
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

    /** Idinadagdag o pinapalitan (kung existing na ang pangalan) ang isang mukha. */
    fun enroll(name: String, embedding: FloatArray) {
        knownFaces.removeAll { it.name.equals(name, ignoreCase = true) }
        knownFaces.add(KnownFace(name.trim(), embedding))
        persist()
    }

    fun remove(name: String) {
        knownFaces.removeAll { it.name.equals(name, ignoreCase = true) }
        persist()
    }

    fun allNames(): List<String> = knownFaces.map { it.name }

    fun isEmpty(): Boolean = knownFaces.isEmpty()

    data class MatchResult(val name: String, val similarity: Float)

    /** Hinahanap ang pinaka-malapit na kilalang mukha. Null kung wala pang naka-enroll o walang tumugma. */
    fun match(embedding: FloatArray): MatchResult? {
        if (knownFaces.isEmpty()) return null

        var bestName: String? = null
        var bestScore = -1f

        for (face in knownFaces) {
            val score = cosineSimilarity(embedding, face.embedding)
            if (score > bestScore) {
                bestScore = score
                bestName = face.name
            }
        }

        return if (bestName != null && bestScore >= MATCH_THRESHOLD) {
            MatchResult(bestName, bestScore)
        } else {
            null // may mukha, pero walang sapat na kalapitan sa kilalang mga tao
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        // Naka-L2-normalize na ang mga embedding bago i-store/i-compare, kaya ang dot
        // product mismo ay ang cosine similarity na.
        return dot
    }
}
