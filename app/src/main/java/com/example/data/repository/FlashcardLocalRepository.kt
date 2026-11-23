package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DeckEntity
import com.example.data.local.Flashcard
import com.example.data.local.toDomain
import com.example.data.model.DeckSourceType
import com.example.widget.FlashcardWidgetUpdater
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed local repository providing reactive reads and suspend CRUD operations
 * for the Room [Flashcard] entity in [AppDatabase].
 */
class FlashcardLocalRepository(
    private val database: AppDatabase,
    private val appContext: Context? = null
) {
    private val flashcardDao = database.flashcardDao()
    private val deckDao = database.deckDao()

    fun observeSavedFlashcards(userId: String): Flow<List<Flashcard>> =
        flashcardDao.observeAllCards(userId)

    fun observeFlashcardsForDeck(userId: String, deckId: String): Flow<List<Flashcard>> =
        flashcardDao.observeCardsForDeck(userId, deckId)

    fun observeFlashcardsByProgress(userId: String, deckId: String): Flow<List<Flashcard>> =
        flashcardDao.observeCardsByStudyProgress(userId, deckId)

    fun searchFlashcards(userId: String, query: String): Flow<List<Flashcard>> =
        flashcardDao.searchCards(userId, query)

    suspend fun getFlashcardById(cardId: String): Flashcard? =
        flashcardDao.getCardById(cardId)

    suspend fun insertFlashcard(card: Flashcard) {
        flashcardDao.upsertCard(card)
        recalculateDeckCounts(card.userId, card.deckId)
        refreshWidgets()
    }

    suspend fun insertFlashcards(cards: List<Flashcard>) {
        if (cards.isEmpty()) return
        flashcardDao.upsertCards(cards)
        val affectedPairs = cards.map { it.userId to it.deckId }.distinct()
        affectedPairs.forEach { (uid, deckId) -> recalculateDeckCounts(uid, deckId) }
        refreshWidgets()
    }

    suspend fun ensureLocalDeck(
        deckId: String,
        userId: String,
        title: String,
        subject: String,
        description: String,
        sourceType: DeckSourceType,
        accentIndex: Int
    ): DeckEntity {
        val existing = deckDao.getDeckById(deckId)
        if (existing != null) return existing
        val created = DeckEntity(
            id = deckId,
            userId = userId,
            title = title.trim().ifBlank { "Camera Capture Deck" }.take(120),
            subject = subject.trim().ifBlank { "General" }.take(60),
            description = description.trim().take(400),
            sourceType = sourceType.name,
            accentIndex = accentIndex.coerceIn(0, 5),
            cardCount = 0,
            masteredCount = 0,
            updatedAtMillis = System.currentTimeMillis()
        )
        deckDao.upsertDeck(created)
        return created
    }

    suspend fun updateFlashcard(card: Flashcard) {
        flashcardDao.updateCard(card)
        recalculateDeckCounts(card.userId, card.deckId)
        refreshWidgets()
    }

    suspend fun updateReviewMetadata(
        cardId: String,
        userId: String,
        deckId: String,
        studyProgress: Int,
        easeFactor: Double,
        intervalDays: Int,
        repetitions: Int,
        dueAtMillis: Long,
        lastReviewDate: Long,
        pendingSync: Boolean
    ) {
        flashcardDao.updateReviewMetadata(
            cardId = cardId,
            studyProgress = studyProgress,
            easeFactor = easeFactor,
            intervalDays = intervalDays,
            repetitions = repetitions,
            dueAtMillis = dueAtMillis,
            lastReviewDate = lastReviewDate,
            pendingSync = pendingSync
        )
        recalculateDeckCounts(userId, deckId)
        refreshWidgets()
    }

    suspend fun deleteFlashcard(card: Flashcard) {
        flashcardDao.deleteCard(card)
        recalculateDeckCounts(card.userId, card.deckId)
        refreshWidgets()
    }

    suspend fun deleteFlashcardById(cardId: String) {
        val existing = flashcardDao.getCardById(cardId)
        flashcardDao.deleteCardById(cardId)
        if (existing != null) {
            recalculateDeckCounts(existing.userId, existing.deckId)
        }
        refreshWidgets()
    }

    private suspend fun recalculateDeckCounts(userId: String, deckId: String) {
        val existingDeck = deckDao.getDeckById(deckId) ?: return
        val deckCards = flashcardDao.getAllCardsSnapshot(userId).filter { it.deckId == deckId }
        val masteredCount = deckCards.count { it.toDomain().isMastered() }
        deckDao.upsertDeck(
            existingDeck.copy(
                cardCount = deckCards.size,
                masteredCount = masteredCount,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    private fun refreshWidgets() {
        appContext?.let { FlashcardWidgetUpdater.refreshAll(it) }
    }
}
