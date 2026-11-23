package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.max
import kotlin.math.roundToInt

enum class CardType(val displayLabel: String, val shortBadge: String) {
    MULTIPLE_CHOICE("Multiple Choice", "MCQ"),
    FILL_IN_BLANK("Fill in the Blank", "CLOZE"),
    CONCEPT_EXPLAIN("Concept Explanation", "EXPLAIN");

    companion object {
        fun fromRaw(raw: String?): CardType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CONCEPT_EXPLAIN
    }
}

enum class DeckSourceType(val displayLabel: String) {
    PHOTO("Photo Scan"),
    NOTES("Text Notes"),
    MANUAL("Manual");

    companion object {
        fun fromRaw(raw: String?): DeckSourceType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MANUAL
    }
}

enum class ReviewRating(val quality: Int, val label: String, val subtitle: String) {
    AGAIN(1, "Again", "Reset"),
    HARD(3, "Hard", "Shorter"),
    GOOD(4, "Good", "On track"),
    EASY(5, "Easy", "Boost");
}

@Keep
@IgnoreExtraProperties
data class UserProfileFirestore(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val streakDays: Long = 0L,
    val totalReviewed: Long = 0L,
    val lastStudyDayKey: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class StudyDeckFirestore(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val subject: String = "",
    val description: String = "",
    val sourceType: String = DeckSourceType.MANUAL.name,
    val accentIndex: Long = 0L,
    val cardCount: Long = 0L,
    val masteredCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FlashcardFirestore(
    @DocumentId val id: String = "",
    val userId: String = "",
    val deckId: String = "",
    val cardType: String = CardType.CONCEPT_EXPLAIN.name,
    val question: String = "",
    val answer: String = "",
    val explanation: String = "",
    val hint: String = "",
    val options: List<String> = emptyList(),
    val correctOptionIndex: Long = -1L,
    val easeFactor: Double = 2.5,
    val intervalDays: Long = 0L,
    val repetitions: Long = 0L,
    val dueAtMillis: Long = 0L,
    val lastReviewedAtMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class UserProfile(
    val userId: String,
    val displayName: String,
    val streakDays: Int,
    val totalReviewed: Int,
    val lastStudyDayKey: String?
)

data class StudyDeck(
    val id: String,
    val userId: String,
    val title: String,
    val subject: String,
    val description: String,
    val sourceType: DeckSourceType,
    val accentIndex: Int,
    val cardCount: Int,
    val masteredCount: Int,
    val updatedAtMillis: Long
)

data class Flashcard(
    val id: String,
    val userId: String,
    val deckId: String,
    val cardType: CardType,
    val question: String,
    val answer: String,
    val explanation: String,
    val hint: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val easeFactor: Double,
    val intervalDays: Int,
    val repetitions: Int,
    val dueAtMillis: Long,
    val lastReviewedAtMillis: Long,
    val pendingSync: Boolean = false
) {
    fun isDue(nowMillis: Long = System.currentTimeMillis()): Boolean = dueAtMillis <= nowMillis
    fun isMastered(): Boolean = repetitions >= 2 && intervalDays >= 6
}

data class GeneratedCardDraft(
    val localId: String,
    val cardType: CardType,
    val question: String,
    val answer: String,
    val explanation: String,
    val hint: String,
    val options: List<String>,
    val correctOptionIndex: Int
)

data class GeneratedDeckDraft(
    val title: String,
    val subject: String,
    val summary: String,
    val sourceType: DeckSourceType,
    val cards: List<GeneratedCardDraft>
)

data class QuizMessage(
    val id: String,
    val role: String, // "user" or "model"
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

data class Sm2ScheduleResult(
    val easeFactor: Double,
    val intervalDays: Int,
    val repetitions: Int,
    val dueAtMillis: Long,
    val lastReviewedAtMillis: Long
)

object SpacedRepetitionEngine {
    const val DAY_MILLIS = 86_400_000L
    const val TEN_MINUTES_MILLIS = 600_000L

    fun scheduleNextReview(
        card: Flashcard,
        rating: ReviewRating,
        nowMillis: Long = System.currentTimeMillis()
    ): Sm2ScheduleResult = scheduleNextReviewByQuality(
        card = card,
        quality = rating.quality,
        nowMillis = nowMillis
    )

    /**
     * Canonical SuperMemo-2 (SM-2) spaced-repetition scheduler accepting a 0..5 quality score:
     * - q < 3 (Again / lapse): resets repetitions = 0, intervalDays = 0, due in 10 minutes
     * - q >= 3: increments repetitions and grows interval via EF' = max(1.3, EF + (0.1 - (5 - q)*(0.08 + (5 - q)*0.02)))
     */
    fun scheduleNextReviewByQuality(
        card: Flashcard,
        quality: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Sm2ScheduleResult {
        val q = quality.coerceIn(0, 5)
        var ef = card.easeFactor + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        ef = ef.coerceIn(1.3, 5.0)
        // Round to 2 decimals for clean readability
        ef = (ef * 100.0).roundToInt() / 100.0

        val nextRepetitions: Int
        val nextIntervalDays: Int
        val nextDueAtMillis: Long

        if (q < 3) {
            // Lapse -> review soon (10 minutes) and reset repetition streak
            nextRepetitions = 0
            nextIntervalDays = 0
            nextDueAtMillis = nowMillis + TEN_MINUTES_MILLIS
        } else {
            nextRepetitions = card.repetitions + 1
            nextIntervalDays = when (nextRepetitions) {
                1 -> if (q == 5) 3 else 1
                2 -> when (q) {
                    3 -> 3
                    5 -> 8
                    else -> 6
                }
                else -> {
                    val base = (card.intervalDays.coerceAtLeast(1) * ef).roundToInt()
                    val adjusted = when (q) {
                        3 -> max(1, (base * 0.8).roundToInt())
                        5 -> max(2, (base * 1.3).roundToInt())
                        else -> max(1, base)
                    }
                    adjusted.coerceAtMost(3650)
                }
            }
            nextDueAtMillis = nowMillis + nextIntervalDays * DAY_MILLIS
        }

        return Sm2ScheduleResult(
            easeFactor = ef,
            intervalDays = nextIntervalDays,
            repetitions = nextRepetitions,
            dueAtMillis = nextDueAtMillis,
            lastReviewedAtMillis = nowMillis
        )
    }

    fun formatIntervalLabel(intervalDays: Int): String = when {
        intervalDays <= 0 -> "10m"
        intervalDays == 1 -> "1d"
        intervalDays < 30 -> "${intervalDays}d"
        else -> "${(intervalDays / 30.0).roundToInt().coerceAtLeast(1)}mo"
    }

    fun previewNextIntervalLabel(card: Flashcard, rating: ReviewRating): String {
        val result = scheduleNextReview(card, rating, nowMillis = 0L)
        return formatIntervalLabel(result.intervalDays)
    }
}
