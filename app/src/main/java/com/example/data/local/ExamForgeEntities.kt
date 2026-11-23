package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.DeckSourceType
import com.example.data.model.StudyDeck

@Entity(
    tableName = "decks",
    indices = [Index(value = ["userId"])]
)
data class DeckEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val subject: String,
    val description: String,
    val sourceType: String,
    val accentIndex: Int,
    val cardCount: Int,
    val masteredCount: Int,
    val updatedAtMillis: Long
)

fun DeckEntity.toDomain(): StudyDeck = StudyDeck(
    id = id,
    userId = userId,
    title = title,
    subject = subject,
    description = description,
    sourceType = DeckSourceType.fromRaw(sourceType),
    accentIndex = accentIndex.coerceIn(0, 5),
    cardCount = cardCount,
    masteredCount = masteredCount,
    updatedAtMillis = updatedAtMillis
)

fun StudyDeck.toEntity(): DeckEntity = DeckEntity(
    id = id,
    userId = userId,
    title = title,
    subject = subject,
    description = description,
    sourceType = sourceType.name,
    accentIndex = accentIndex.coerceIn(0, 5),
    cardCount = cardCount,
    masteredCount = masteredCount,
    updatedAtMillis = updatedAtMillis
)
