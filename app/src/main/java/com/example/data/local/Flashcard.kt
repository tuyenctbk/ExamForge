package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.CardType
import com.example.data.model.Flashcard as DomainFlashcard
import org.json.JSONArray

@Entity(
    tableName = "flashcards",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["deckId"]),
        Index(value = ["dueAtMillis"]),
        Index(value = ["last_review_date"])
    ]
)
data class Flashcard(
    @PrimaryKey val id: String,
    val userId: String,
    val deckId: String,
    val cardType: String,
    val question: String,
    val answer: String,
    val explanation: String = "",
    val hint: String = "",
    val optionsJson: String = "[]",
    val correctOptionIndex: Int = -1,
    @ColumnInfo(name = "study_progress")
    val studyProgress: Int = 0,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val dueAtMillis: Long = 0L,
    @ColumnInfo(name = "last_review_date")
    val lastReviewDate: Long = 0L,
    val pendingSync: Boolean = false
)

fun computeStudyProgress(
    repetitions: Int,
    intervalDays: Int,
    lastReviewDate: Long
): Int {
    if (lastReviewDate <= 0L && repetitions <= 0) return 0
    return when {
        repetitions >= 2 && intervalDays >= 6 -> 100
        repetitions >= 2 -> 80
        repetitions == 1 && intervalDays >= 3 -> 65
        repetitions == 1 -> 45
        else -> 20 // Reviewed before, recently reset via "Again"
    }
}

fun encodeOptionsJson(options: List<String>): String {
    val array = JSONArray()
    options.forEach { array.put(it) }
    return array.toString()
}

fun decodeOptionsJson(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { array.optString(it, "") }
    } catch (_: Exception) {
        emptyList()
    }
}

fun Flashcard.toDomain(): DomainFlashcard = DomainFlashcard(
    id = id,
    userId = userId,
    deckId = deckId,
    cardType = CardType.fromRaw(cardType),
    question = question,
    answer = answer,
    explanation = explanation,
    hint = hint,
    options = decodeOptionsJson(optionsJson),
    correctOptionIndex = correctOptionIndex,
    easeFactor = easeFactor,
    intervalDays = intervalDays,
    repetitions = repetitions,
    dueAtMillis = dueAtMillis,
    lastReviewedAtMillis = lastReviewDate,
    pendingSync = pendingSync
)

fun DomainFlashcard.toEntity(pendingSync: Boolean = this.pendingSync): Flashcard = Flashcard(
    id = id,
    userId = userId,
    deckId = deckId,
    cardType = cardType.name,
    question = question,
    answer = answer,
    explanation = explanation,
    hint = hint,
    optionsJson = encodeOptionsJson(options),
    correctOptionIndex = correctOptionIndex,
    studyProgress = computeStudyProgress(
        repetitions = repetitions,
        intervalDays = intervalDays,
        lastReviewDate = lastReviewedAtMillis
    ),
    easeFactor = easeFactor,
    intervalDays = intervalDays,
    repetitions = repetitions,
    dueAtMillis = dueAtMillis,
    lastReviewDate = lastReviewedAtMillis,
    pendingSync = pendingSync
)
