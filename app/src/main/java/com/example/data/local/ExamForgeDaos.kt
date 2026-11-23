package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeckDao {
    @Query("SELECT * FROM decks WHERE userId = :userId ORDER BY updatedAtMillis DESC")
    fun observeDecks(userId: String): Flow<List<DeckEntity>>

    @Query("SELECT * FROM decks WHERE id = :deckId LIMIT 1")
    suspend fun getDeckById(deckId: String): DeckEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDecks(decks: List<DeckEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeck(deck: DeckEntity)

    @Query("DELETE FROM decks WHERE id = :deckId")
    suspend fun deleteDeckById(deckId: String)

    @Query("DELETE FROM decks WHERE userId = :userId AND id NOT IN (:keepIds)")
    suspend fun pruneRemovedDecks(userId: String, keepIds: List<String>)

    @Query("DELETE FROM decks WHERE userId = :userId")
    suspend fun deleteAllDecksForUser(userId: String)
}

@Dao
interface FlashcardDao {
    @Query("SELECT * FROM flashcards WHERE userId = :userId ORDER BY dueAtMillis ASC")
    fun observeAllCards(userId: String): Flow<List<Flashcard>>

    @Query("SELECT * FROM flashcards WHERE userId = :userId AND deckId = :deckId ORDER BY dueAtMillis ASC")
    fun observeCardsForDeck(userId: String, deckId: String): Flow<List<Flashcard>>

    @Query("SELECT * FROM flashcards WHERE userId = :userId AND deckId = :deckId ORDER BY study_progress ASC, dueAtMillis ASC")
    fun observeCardsByStudyProgress(userId: String, deckId: String): Flow<List<Flashcard>>

    @Query(
        """
        SELECT * FROM flashcards
        WHERE userId = :userId
          AND (question LIKE '%' || :query || '%' OR answer LIKE '%' || :query || '%' OR explanation LIKE '%' || :query || '%')
        ORDER BY dueAtMillis ASC
        """
    )
    fun searchCards(userId: String, query: String): Flow<List<Flashcard>>

    @Query("SELECT * FROM flashcards WHERE id = :cardId LIMIT 1")
    suspend fun getCardById(cardId: String): Flashcard?

    @Query("SELECT * FROM flashcards WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingSyncCards(userId: String): List<Flashcard>

    @Query("SELECT * FROM flashcards WHERE userId = :userId ORDER BY dueAtMillis ASC")
    suspend fun getAllCardsSnapshot(userId: String): List<Flashcard>

    @Query("SELECT * FROM flashcards ORDER BY dueAtMillis ASC")
    suspend fun getAnyCardsForWidget(): List<Flashcard>

    @Query("SELECT * FROM flashcards WHERE userId = :userId AND dueAtMillis <= :nowMillis ORDER BY dueAtMillis ASC")
    suspend fun getDueCardsSnapshot(userId: String, nowMillis: Long): List<Flashcard>

    @Query("SELECT COUNT(*) FROM flashcards WHERE userId = :userId AND dueAtMillis <= :nowMillis")
    suspend fun countDueCards(userId: String, nowMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(cards: List<Flashcard>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCard(card: Flashcard)

    @Update
    suspend fun updateCard(card: Flashcard)

    @Delete
    suspend fun deleteCard(card: Flashcard)

    @Query(
        """
        UPDATE flashcards
        SET study_progress = :studyProgress,
            easeFactor = :easeFactor,
            intervalDays = :intervalDays,
            repetitions = :repetitions,
            dueAtMillis = :dueAtMillis,
            last_review_date = :lastReviewDate,
            pendingSync = :pendingSync
        WHERE id = :cardId
        """
    )
    suspend fun updateReviewMetadata(
        cardId: String,
        studyProgress: Int,
        easeFactor: Double,
        intervalDays: Int,
        repetitions: Int,
        dueAtMillis: Long,
        lastReviewDate: Long,
        pendingSync: Boolean
    )

    @Query("UPDATE flashcards SET pendingSync = 0 WHERE id = :cardId")
    suspend fun markSynced(cardId: String)

    @Query("DELETE FROM flashcards WHERE id = :cardId")
    suspend fun deleteCardById(cardId: String)

    @Query("DELETE FROM flashcards WHERE deckId = :deckId")
    suspend fun deleteCardsForDeck(deckId: String)

    @Query("DELETE FROM flashcards WHERE userId = :userId AND pendingSync = 0 AND id NOT IN (:keepIds)")
    suspend fun pruneRemovedSyncedCards(userId: String, keepIds: List<String>)

    @Query("DELETE FROM flashcards WHERE userId = :userId AND pendingSync = 0")
    suspend fun deleteAllSyncedCardsForUser(userId: String)
}
