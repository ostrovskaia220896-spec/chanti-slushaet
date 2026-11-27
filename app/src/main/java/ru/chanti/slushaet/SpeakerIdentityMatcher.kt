package ru.chanti.slushaet

import kotlin.math.sqrt

/** Session-scoped speaker identity registry. It is intentionally not persisted across games. */
class SpeakerIdentityMatcher(
    private val threshold: Float = 0.5f,
    private val ambiguityMargin: Float = 0.06f
) {
    enum class Kind { NEW, MATCHED, AMBIGUOUS, UNKNOWN }
    data class Match(val speakerId: String?, val score: Float, val kind: Kind)
    private data class Profile(
        val id: String,
        var centroid: FloatArray,
        var observations: Int
    )

    private val profiles = mutableListOf<Profile>()
    private var nextId = 1
    val speakerCount: Int get() = profiles.size

    init {
        require(threshold > -1f && threshold < 1f)
        require(ambiguityMargin >= 0f && ambiguityMargin < 1f)
    }

    @Synchronized
    fun assign(embeddings: List<FloatArray>): Match {
        val usable = embeddings.filter { it.isNotEmpty() && it.all { value -> value.isFinite() } }
        if (usable.isEmpty()) return Match(null, -1f, Kind.UNKNOWN)
        val candidate = normalizedMean(usable) ?: return Match(null, -1f, Kind.UNKNOWN)

        var best: Profile? = null
        var bestScore = -2f
        var secondScore = -2f
        for (profile in profiles) {
            val score = cosine(candidate, profile.centroid)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = profile
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        if (best != null && bestScore >= threshold) {
            if (secondScore >= threshold && bestScore - secondScore < ambiguityMargin) {
                return Match(null, bestScore, Kind.AMBIGUOUS)
            }
            update(best, candidate)
            return Match(best.id, bestScore, Kind.MATCHED)
        }

        val id = "speaker_" + nextId.toString().padStart(2, '0')
        nextId++
        profiles.add(Profile(id, candidate, 1))
        return Match(id, bestScore, Kind.NEW)
    }

    private fun update(profile: Profile, candidate: FloatArray) {
        val weight = profile.observations.toFloat()
        val merged = FloatArray(profile.centroid.size) { i ->
            profile.centroid[i] * weight + candidate[i]
        }
        profile.centroid = normalize(merged) ?: profile.centroid
        profile.observations++
    }

    private fun normalizedMean(vectors: List<FloatArray>): FloatArray? {
        val dimension = vectors.first().size
        if (dimension == 0 || vectors.any { it.size != dimension }) return null
        val mean = FloatArray(dimension)
        for (vector in vectors) for (i in 0 until dimension) mean[i] += vector[i]
        return normalize(mean)
    }

    private fun normalize(vector: FloatArray): FloatArray? {
        var normSquared = 0.0
        for (value in vector) normSquared += value.toDouble() * value.toDouble()
        if (normSquared <= 1e-12 || !normSquared.isFinite()) return null
        val norm = sqrt(normSquared).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var score = 0.0
        for (i in a.indices) score += a[i].toDouble() * b[i].toDouble()
        return score.toFloat().coerceIn(-1f, 1f)
    }
}
