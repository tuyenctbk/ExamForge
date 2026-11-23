package com.example.data.ai

import android.graphics.Bitmap
import com.example.data.model.Flashcard
import com.example.data.model.QuizMessage
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized capability-based model registry and free-tier resilience helpers.
 * Never hardcode Gemini model IDs inside UI screens or ViewModels — update here only.
 */
object AiModelConfig {
    /** Tier 1: Primary standard multimodal & reasoning model (no billing required). */
    const val PRIMARY_STUDY_MODEL = "gemini-3.8-flash"

    /** Tier 2: Ultra-fast, high-efficiency lite model for Eco Lite Mode & 429 auto-cascade. */
    const val ECO_LITE_MODEL = "gemini-3.5-flash-lite"

    /** Sliding window cap for multi-turn chat history to keep token payloads compact. */
    const val SLIDING_CHAT_WINDOW_TURNS = 8

    /** Fingerprint deduplication window so accidental double-taps consume 0 extra tokens. */
    const val DEDUP_TTL_MS = 40_000L

    /** Grace credits granted once per day when a user runs out mid-task. */
    const val DAILY_GRACE_CREDIT_GRANT = 3

    /** Threshold (80%) for showing the proactive Stage 1 soft-nudge banner & auto-cascading. */
    const val SOFT_NUDGE_THRESHOLD = 0.80f

    /**
     * Detects HTTP 429 / RESOURCE_EXHAUSTED / rate-limit errors eligible for automatic Tier 2 cascade.
     */
    fun isRateLimitOrQuotaError(error: Throwable): Boolean {
        val msg = (error.message ?: "").lowercase(Locale.US)
        return msg.contains("429") ||
            msg.contains("resource_exhausted") ||
            msg.contains("quota") ||
            msg.contains("rate limit") ||
            msg.contains("too many requests") ||
            msg.contains("overloaded")
    }

    /**
     * Resolves whether to start directly on Tier 2 Eco Lite based on user toggle or >= 80% usage.
     */
    fun shouldPreferEcoLite(
        ecoLiteMode: Boolean,
        hasByokKey: Boolean,
        usageRatio: Float,
        forceEcoLite: Boolean = false
    ): Boolean {
        if (forceEcoLite) return true
        if (ecoLiteMode) return true
        return !hasByokKey && usageRatio >= SOFT_NUDGE_THRESHOLD
    }
}

enum class AiCapabilityTier(val displayLabel: String) {
    PRIMARY_CLOUD("Standard AI"),
    ECO_LITE_CLOUD("Eco Lite AI"),
    LOCAL_FALLBACK("Offline Zero-Cost")
}

/**
 * Short-lived in-memory fingerprint cache (30-45s) preventing accidental double-taps
 * from burning duplicate free-tier AI quota or tokens.
 */
class AiRequestDeduplicator<T>(
    private val ttlMs: Long = AiModelConfig.DEDUP_TTL_MS
) {
    private data class CacheEntry<T>(
        val value: T,
        val storedAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry<T>>()

    fun getIfFresh(fingerprint: String, nowMs: Long = System.currentTimeMillis()): T? {
        val entry = cache[fingerprint] ?: return null
        if (nowMs - entry.storedAtMs > ttlMs) {
            cache.remove(fingerprint)
            return null
        }
        return entry.value
    }

    fun put(fingerprint: String, value: T, nowMs: Long = System.currentTimeMillis()) {
        if (cache.size > 32) {
            cache.entries.removeIf { (_, v) -> nowMs - v.storedAtMs > ttlMs }
        }
        cache[fingerprint] = CacheEntry(value, nowMs)
    }

    companion object {
        fun fingerprint(
            operation: String,
            textPayload: String,
            bitmap: Bitmap? = null,
            extraTag: String = ""
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(operation.toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(textPayload.trim().lowercase(Locale.US).toByteArray(Charsets.UTF_8))
            digest.update('|'.code.toByte())
            digest.update(extraTag.toByteArray(Charsets.UTF_8))
            if (bitmap != null) {
                val centerPixel = if (bitmap.width > 0 && bitmap.height > 0) {
                    bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                } else {
                    0
                }
                val cornerPixel = if (bitmap.width > 2 && bitmap.height > 2) {
                    bitmap.getPixel(1, 1)
                } else {
                    0
                }
                val mediaSig = "${bitmap.width}x${bitmap.height}:$centerPixel:$cornerPixel"
                digest.update(mediaSig.toByteArray(Charsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Tier 3 Zero-Cost Local Reflex Engine for the Oral Quizmaster.
 * Runs 100% deterministically on-device (0 cloud tokens) when daily cloud quiz quota is exhausted
 * or the user is offline, using the deck's real flashcards for instant active-recall coaching.
 */
object LocalQuizmasterFallback {

    private val stopWords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "to", "of", "and", "or",
        "in", "on", "at", "for", "with", "by", "as", "it", "this", "that", "from", "into"
    )

    fun generateReply(
        deckTitle: String,
        cards: List<Flashcard>,
        history: List<QuizMessage>,
        userInput: String
    ): String {
        if (cards.isEmpty()) {
            return "Add at least one flashcard to \"$deckTitle\" so we can practice offline active recall together."
        }

        val userTurns = history.count { it.role.equals("user", ignoreCase = true) }
        val normalized = userInput.trim().lowercase(Locale.US)

        if (history.isEmpty() || normalized.startsWith("start ") || normalized.contains("quiz me")) {
            val firstCard = cards.first()
            return "Offline Reflex Coach active ($0 quota) for \"$deckTitle\". " +
                "Question 1 of ${cards.size}: ${firstCard.question}"
        }

        val currentIdx = ((userTurns - 1).coerceAtLeast(0)) % cards.size
        val currentCard = cards[currentIdx]
        val nextIdx = (currentIdx + 1) % cards.size
        val nextCard = cards[nextIdx]

        if (normalized == "hint" || normalized.contains("give me a hint")) {
            val hintText = currentCard.hint.ifBlank {
                val words = currentCard.answer.split(Regex("\\s+")).filter { it.isNotBlank() }
                "Starts with \"${words.take(3).joinToString(" ")}...\" (${words.size} words)."
            }
            return "Hint: $hintText Try answering again in your own words."
        }

        if (normalized == "skip" || normalized == "pass" || normalized == "next" || normalized.contains("don't know")) {
            return "[Review Needed] The key answer is: ${currentCard.answer}. " +
                "Next question (${nextIdx + 1}/${cards.size}): ${nextCard.question}"
        }

        val overlapScore = computeKeywordOverlap(userInput, currentCard.answer)
        val badge = when {
            overlapScore >= 0.55f -> "[Mastered]"
            overlapScore >= 0.25f -> "[Almost There]"
            else -> "[Review Needed]"
        }
        val coachingLine = when (badge) {
            "[Mastered]" -> "Spot on! You captured the core concept accurately."
            "[Almost There]" -> "Close — key detail to tighten: ${currentCard.answer}."
            else -> "Let's lock this one in: ${currentCard.answer}."
        }
        return "$badge $coachingLine Next question (${nextIdx + 1}/${cards.size}): ${nextCard.question}"
    }

    private fun computeKeywordOverlap(userAnswer: String, referenceAnswer: String): Float {
        val refTokens = tokenize(referenceAnswer)
        if (refTokens.isEmpty()) return 0.5f
        val userTokens = tokenize(userAnswer)
        if (userTokens.isEmpty()) return 0f
        val matched = refTokens.count { ref ->
            userTokens.any { u -> u == ref || (ref.length >= 5 && u.startsWith(ref.take(4))) }
        }
        return matched.toFloat() / refTokens.size.toFloat()
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }
}
