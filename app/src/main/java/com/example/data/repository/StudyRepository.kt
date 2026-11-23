package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.local.AppDatabase
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.model.CardType
import com.example.data.model.DeckSourceType
import com.example.data.model.Flashcard
import com.example.data.model.FlashcardFirestore
import com.example.data.model.GeneratedCardDraft
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import com.example.data.model.StudyDeck
import com.example.data.model.StudyDeckFirestore
import com.example.data.model.UserProfile
import com.example.data.model.UserProfileFirestore
import com.example.widget.FlashcardWidgetUpdater
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "StudyRepository"

class StudyRepository(
    private val appContext: Context,
    private val db: FirebaseFirestore,
    private val localDb: AppDatabase,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        appContext = context.applicationContext,
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        localDb = AppDatabase.getInstance(context.applicationContext)
    )

    private val deckDao = localDb.deckDao()
    private val flashcardDao = localDb.flashcardDao()

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun generateSafeId(prefix: String): String {
        val raw = UUID.randomUUID().toString().replace("-", "").take(16)
        return "${prefix}_$raw"
    }

    // --- OFFLINE-FIRST ROOM OBSERVATION ---

    fun observeDecksLocal(userId: String): Flow<List<StudyDeck>> =
        deckDao.observeDecks(userId).map { list -> list.map { it.toDomain() } }

    fun observeAllCardsLocal(userId: String): Flow<List<Flashcard>> =
        flashcardDao.observeAllCards(userId).map { list -> list.map { it.toDomain() } }

    fun observeCardsForDeckLocal(userId: String, deckId: String): Flow<List<Flashcard>> =
        flashcardDao.observeCardsForDeck(userId, deckId).map { list -> list.map { it.toDomain() } }

    // --- REALTIME FIRESTORE SYNC STREAMS ---

    fun syncDecksFromCloud(userId: String): Flow<List<StudyDeck>> {
        val path = "users/$userId/decks"
        return db.collection("users").document(userId).collection("decks")
            .snapshots()
            .map { snapshot ->
                val remoteDecks = snapshot.toObjects(
                    StudyDeckFirestore::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { dto ->
                    StudyDeck(
                        id = dto.id,
                        userId = dto.userId.ifBlank { userId },
                        title = dto.title,
                        subject = dto.subject,
                        description = dto.description,
                        sourceType = DeckSourceType.fromRaw(dto.sourceType),
                        accentIndex = dto.accentIndex.toInt().coerceIn(0, 5),
                        cardCount = dto.cardCount.toInt().coerceAtLeast(0),
                        masteredCount = dto.masteredCount.toInt().coerceAtLeast(0),
                        updatedAtMillis = (dto.updatedAt ?: dto.createdAt)?.toDate()?.time
                            ?: System.currentTimeMillis()
                    )
                }
                if (remoteDecks.isEmpty()) {
                    deckDao.deleteAllDecksForUser(userId)
                } else {
                    deckDao.upsertDecks(remoteDecks.map { it.toEntity() })
                    deckDao.pruneRemovedDecks(userId, remoteDecks.map { it.id })
                }
                remoteDecks
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun syncCardsFromCloud(userId: String): Flow<List<Flashcard>> {
        val path = "users/$userId/cards"
        return db.collection("users").document(userId).collection("cards")
            .snapshots()
            .map { snapshot ->
                val pendingLocalById = flashcardDao.getPendingSyncCards(userId).associateBy { it.id }
                val remoteCards = snapshot.toObjects(
                    FlashcardFirestore::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { dto ->
                    Flashcard(
                        id = dto.id,
                        userId = dto.userId.ifBlank { userId },
                        deckId = dto.deckId,
                        cardType = CardType.fromRaw(dto.cardType),
                        question = dto.question,
                        answer = dto.answer,
                        explanation = dto.explanation,
                        hint = dto.hint,
                        options = dto.options,
                        correctOptionIndex = dto.correctOptionIndex.toInt(),
                        easeFactor = dto.easeFactor.coerceIn(1.3, 5.0),
                        intervalDays = dto.intervalDays.toInt().coerceAtLeast(0),
                        repetitions = dto.repetitions.toInt().coerceAtLeast(0),
                        dueAtMillis = dto.dueAtMillis.coerceAtLeast(0L),
                        lastReviewedAtMillis = dto.lastReviewedAtMillis.coerceAtLeast(0L),
                        pendingSync = false
                    )
                }

                // Preserve any unsynced local review updates (e.g. from offline subway study or widget)
                val toUpsert = remoteCards
                    .filterNot { pendingLocalById.containsKey(it.id) }
                    .map { it.toEntity(pendingSync = false) }
                if (toUpsert.isNotEmpty()) {
                    flashcardDao.upsertCards(toUpsert)
                }
                if (remoteCards.isEmpty()) {
                    flashcardDao.deleteAllSyncedCardsForUser(userId)
                } else {
                    flashcardDao.pruneRemovedSyncedCards(userId, remoteCards.map { it.id })
                }
                FlashcardWidgetUpdater.refreshAll(appContext)
                remoteCards
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        val path = "users/$userId"
        return db.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                if (!snapshot.exists()) return@map null
                val dto = snapshot.toObject(
                    UserProfileFirestore::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: return@map null
                UserProfile(
                    userId = dto.userId.ifBlank { userId },
                    displayName = dto.displayName.ifBlank { "Student" },
                    streakDays = dto.streakDays.toInt().coerceAtLeast(0),
                    totalReviewed = dto.totalReviewed.toInt().coerceAtLeast(0),
                    lastStudyDayKey = dto.lastStudyDayKey
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, path)
                }
                throw error
            }
    }

    // --- MUTATIONS (OFFLINE ROOM + FIRESTORE SYNC) ---

    suspend fun ensureUserProfile(userId: String, displayName: String): Result<Unit> {
        val path = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch." }
            val docRef = db.collection("users").document(uid)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val safeName = displayName.trim().ifBlank { "Student" }.take(80)
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "streakDays" to 0L,
                    "totalReviewed" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun flushPendingOfflineReviews(userId: String): Result<Int> {
        val pending = flashcardDao.getPendingSyncCards(userId)
        if (pending.isEmpty()) return Result.success(0)
        var syncedCount = 0
        for (entity in pending) {
            val docPath = "users/$userId/cards/${entity.id}"
            try {
                val uid = requireUserId()
                if (uid != userId) return Result.success(syncedCount)
                val docRef = db.collection("users").document(uid)
                    .collection("cards").document(entity.id)
                docRef.update(
                    mapOf(
                        "easeFactor" to entity.easeFactor.coerceIn(1.3, 5.0),
                        "intervalDays" to entity.intervalDays.toLong().coerceIn(0L, 36500L),
                        "repetitions" to entity.repetitions.toLong().coerceIn(0L, 10000L),
                        "dueAtMillis" to entity.dueAtMillis.coerceAtLeast(0L),
                        "lastReviewedAtMillis" to entity.lastReviewDate.coerceAtLeast(0L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                flashcardDao.markSynced(entity.id)
                syncedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, docPath)
                Log.w(TAG, "Deferred sync for card ${entity.id}: ${e.message}")
            }
        }
        return Result.success(syncedCount)
    }

    suspend fun createDeckWithCards(
        title: String,
        subject: String,
        description: String,
        sourceType: DeckSourceType,
        accentIndex: Int,
        drafts: List<GeneratedCardDraft>
    ): Result<String> {
        val deckId = generateSafeId("deck")
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/decks/$deckId"
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().ifBlank { "Untitled Study Deck" }.take(120)
            val cleanSubject = subject.trim().ifBlank { "General" }.take(60)
            val cleanDesc = description.trim().take(400)
            val safeAccent = accentIndex.coerceIn(0, 5)
            val nowMillis = System.currentTimeMillis()

            val localDeck = StudyDeck(
                id = deckId,
                userId = uid,
                title = cleanTitle,
                subject = cleanSubject,
                description = cleanDesc,
                sourceType = sourceType,
                accentIndex = safeAccent,
                cardCount = drafts.size,
                masteredCount = 0,
                updatedAtMillis = nowMillis
            )

            val localCards = drafts.map { draft ->
                val cardId = generateSafeId("card")
                val safeOptions = if (draft.cardType == CardType.MULTIPLE_CHOICE) {
                    draft.options.map { it.trim().take(240) }.filter { it.isNotEmpty() }.take(6)
                } else {
                    emptyList()
                }
                val safeCorrectIndex = if (draft.cardType == CardType.MULTIPLE_CHOICE && safeOptions.isNotEmpty()) {
                    draft.correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
                } else {
                    -1
                }
                Flashcard(
                    id = cardId,
                    userId = uid,
                    deckId = deckId,
                    cardType = draft.cardType,
                    question = draft.question.trim().ifBlank { "Review concept" }.take(1000),
                    answer = draft.answer.trim().ifBlank { "See explanation" }.take(1500),
                    explanation = draft.explanation.trim().take(1500),
                    hint = draft.hint.trim().take(300),
                    options = safeOptions,
                    correctOptionIndex = safeCorrectIndex,
                    easeFactor = 2.5,
                    intervalDays = 0,
                    repetitions = 0,
                    dueAtMillis = nowMillis,
                    lastReviewedAtMillis = 0L,
                    pendingSync = false
                )
            }

            // Write immediately to Room SQLite cache
            deckDao.upsertDeck(localDeck.toEntity())
            if (localCards.isNotEmpty()) {
                flashcardDao.upsertCards(localCards.map { it.toEntity(pendingSync = false) })
            }
            FlashcardWidgetUpdater.refreshAll(appContext)

            // Atomic batch write to Firestore (satisfies existsAfter deck check in rules!)
            val batch = db.batch()
            val deckRef = db.collection("users").document(uid).collection("decks").document(deckId)
            docPath = deckRef.path
            batch.set(
                deckRef,
                mapOf(
                    "userId" to uid,
                    "title" to localDeck.title,
                    "subject" to localDeck.subject,
                    "description" to localDeck.description,
                    "sourceType" to localDeck.sourceType.name,
                    "accentIndex" to localDeck.accentIndex.toLong(),
                    "cardCount" to localCards.size.toLong(),
                    "masteredCount" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            localCards.forEach { card ->
                val cardRef = db.collection("users").document(uid).collection("cards").document(card.id)
                batch.set(
                    cardRef,
                    mapOf(
                        "userId" to uid,
                        "deckId" to deckId,
                        "cardType" to card.cardType.name,
                        "question" to card.question,
                        "answer" to card.answer,
                        "explanation" to card.explanation,
                        "hint" to card.hint,
                        "options" to card.options,
                        "correctOptionIndex" to card.correctOptionIndex.toLong(),
                        "easeFactor" to card.easeFactor,
                        "intervalDays" to card.intervalDays.toLong(),
                        "repetitions" to card.repetitions.toLong(),
                        "dueAtMillis" to card.dueAtMillis,
                        "lastReviewedAtMillis" to card.lastReviewedAtMillis,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(deckId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun addCardsToExistingDeck(
        deckId: String,
        drafts: List<GeneratedCardDraft>
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/decks/$deckId"
        return try {
            val uid = requireUserId()
            require(deckId.isNotBlank()) { "Deck ID cannot be blank" }
            val nowMillis = System.currentTimeMillis()

            val newCards = drafts.map { draft ->
                val cardId = generateSafeId("card")
                val safeOptions = if (draft.cardType == CardType.MULTIPLE_CHOICE) {
                    draft.options.map { it.trim().take(240) }.filter { it.isNotEmpty() }.take(6)
                } else {
                    emptyList()
                }
                val safeCorrectIndex = if (draft.cardType == CardType.MULTIPLE_CHOICE && safeOptions.isNotEmpty()) {
                    draft.correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
                } else {
                    -1
                }
                Flashcard(
                    id = cardId,
                    userId = uid,
                    deckId = deckId,
                    cardType = draft.cardType,
                    question = draft.question.trim().ifBlank { "Review concept" }.take(1000),
                    answer = draft.answer.trim().ifBlank { "See explanation" }.take(1500),
                    explanation = draft.explanation.trim().take(1500),
                    hint = draft.hint.trim().take(300),
                    options = safeOptions,
                    correctOptionIndex = safeCorrectIndex,
                    easeFactor = 2.5,
                    intervalDays = 0,
                    repetitions = 0,
                    dueAtMillis = nowMillis,
                    lastReviewedAtMillis = 0L,
                    pendingSync = false
                )
            }

            if (newCards.isEmpty()) return Result.success(Unit)
            flashcardDao.upsertCards(newCards.map { it.toEntity(pendingSync = false) })

            val allDeckCards = flashcardDao.getAllCardsSnapshot(uid).filter { it.deckId == deckId }
            val masteredCount = allDeckCards.count { it.toDomain().isMastered() }
            deckDao.getDeckById(deckId)?.let { existingDeck ->
                deckDao.upsertDeck(
                    existingDeck.copy(
                        cardCount = allDeckCards.size,
                        masteredCount = masteredCount,
                        updatedAtMillis = nowMillis
                    )
                )
            }
            FlashcardWidgetUpdater.refreshAll(appContext)

            val batch = db.batch()
            newCards.forEach { card ->
                val cardRef = db.collection("users").document(uid).collection("cards").document(card.id)
                batch.set(
                    cardRef,
                    mapOf(
                        "userId" to uid,
                        "deckId" to deckId,
                        "cardType" to card.cardType.name,
                        "question" to card.question,
                        "answer" to card.answer,
                        "explanation" to card.explanation,
                        "hint" to card.hint,
                        "options" to card.options,
                        "correctOptionIndex" to card.correctOptionIndex.toLong(),
                        "easeFactor" to card.easeFactor,
                        "intervalDays" to card.intervalDays.toLong(),
                        "repetitions" to card.repetitions.toLong(),
                        "dueAtMillis" to card.dueAtMillis,
                        "lastReviewedAtMillis" to card.lastReviewedAtMillis,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            val deckRef = db.collection("users").document(uid).collection("decks").document(deckId)
            docPath = deckRef.path
            batch.update(
                deckRef,
                mapOf(
                    "cardCount" to allDeckCards.size.toLong(),
                    "masteredCount" to masteredCount.toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun recordCardReview(card: Flashcard, rating: ReviewRating): Result<Flashcard> {
        val nowMillis = System.currentTimeMillis()
        val scheduled = SpacedRepetitionEngine.scheduleNextReview(card, rating, nowMillis)
        val updatedCard = card.copy(
            easeFactor = scheduled.easeFactor,
            intervalDays = scheduled.intervalDays,
            repetitions = scheduled.repetitions,
            dueAtMillis = scheduled.dueAtMillis,
            lastReviewedAtMillis = scheduled.lastReviewedAtMillis,
            pendingSync = true
        )

        // 1. Always save immediately to Room SQLite (works 100% offline on the subway!)
        flashcardDao.upsertCard(updatedCard.toEntity(pendingSync = true))
        val deckCards = flashcardDao.getAllCardsSnapshot(card.userId).filter { it.deckId == card.deckId }
        val masteredCount = deckCards.count { it.toDomain().isMastered() }
        deckDao.getDeckById(card.deckId)?.let { existingDeck ->
            deckDao.upsertDeck(
                existingDeck.copy(
                    cardCount = deckCards.size,
                    masteredCount = masteredCount,
                    updatedAtMillis = nowMillis
                )
            )
        }
        FlashcardWidgetUpdater.refreshAll(appContext)

        // 2. Sync to Firestore (if offline, Room retains pendingSync = true for next flush)
        var docPath = "users/${card.userId}/cards/${card.id}"
        return try {
            val uid = requireUserId()
            check(uid == card.userId) { "Signed-in user mismatch." }
            val batch = db.batch()
            val cardRef = db.collection("users").document(uid).collection("cards").document(card.id)
            docPath = cardRef.path
            batch.update(
                cardRef,
                mapOf(
                    "easeFactor" to updatedCard.easeFactor,
                    "intervalDays" to updatedCard.intervalDays.toLong(),
                    "repetitions" to updatedCard.repetitions.toLong(),
                    "dueAtMillis" to updatedCard.dueAtMillis,
                    "lastReviewedAtMillis" to updatedCard.lastReviewedAtMillis,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            val deckRef = db.collection("users").document(uid).collection("decks").document(card.deckId)
            batch.update(
                deckRef,
                mapOf(
                    "cardCount" to deckCards.size.toLong(),
                    "masteredCount" to masteredCount.toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            flashcardDao.markSynced(card.id)

            // Update user study streak & review total
            updateStudyStats(uid, nowMillis)
            Result.success(updatedCard.copy(pendingSync = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            // Local Room update already succeeded — return failure so UI can show offline-cached hint if desired
            Result.failure(e)
        }
    }

    private suspend fun updateStudyStats(uid: String, nowMillis: Long) {
        val path = "users/$uid"
        try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
            val yesterdayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis - 86_400_000L))
            val userRef = db.collection("users").document(uid)
            val snap = userRef.get().await()
            if (snap.exists()) {
                val prevTotal = (snap.getLong("totalReviewed") ?: 0L)
                val prevStreak = (snap.getLong("streakDays") ?: 0L)
                val prevDay = snap.getString("lastStudyDayKey")
                val nextStreak = when (prevDay) {
                    todayKey -> prevStreak.coerceAtLeast(1L)
                    yesterdayKey -> prevStreak + 1L
                    else -> 1L
                }
                userRef.update(
                    mapOf(
                        "streakDays" to nextStreak.coerceAtMost(10000L),
                        "totalReviewed" to (prevTotal + 1L).coerceAtMost(1000000L),
                        "lastStudyDayKey" to todayKey,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun updateCardContent(card: Flashcard): Result<Unit> {
        val docPath = "users/${card.userId}/cards/${card.id}"
        // 1. Update immediately in local Room SQLite database
        flashcardDao.upsertCard(card.toEntity(pendingSync = card.pendingSync))
        FlashcardWidgetUpdater.refreshAll(appContext)

        // 2. Sync content edit to Firestore (strictly matching allowed edit keys in firestore.rules)
        return try {
            val uid = requireUserId()
            check(uid == card.userId) { "Signed-in user mismatch." }
            val safeOptions = if (card.cardType == CardType.MULTIPLE_CHOICE) {
                card.options.map { it.trim().take(240) }.filter { it.isNotEmpty() }.take(6)
            } else {
                emptyList()
            }
            val safeCorrectIndex = if (card.cardType == CardType.MULTIPLE_CHOICE && safeOptions.isNotEmpty()) {
                card.correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
            } else {
                -1
            }
            db.collection("users").document(uid)
                .collection("cards").document(card.id)
                .update(
                    mapOf(
                        "cardType" to card.cardType.name,
                        "question" to card.question.trim().ifBlank { "Review concept" }.take(1000),
                        "answer" to card.answer.trim().ifBlank { "See explanation" }.take(1500),
                        "explanation" to card.explanation.trim().take(1500),
                        "hint" to card.hint.trim().take(300),
                        "options" to safeOptions,
                        "correctOptionIndex" to safeCorrectIndex.toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            flashcardDao.markSynced(card.id)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteCard(card: Flashcard): Result<Unit> {
        var docPath = "users/${card.userId}/cards/${card.id}"
        return try {
            val uid = requireUserId()
            check(uid == card.userId) { "Signed-in user mismatch." }
            flashcardDao.deleteCardById(card.id)
            val remaining = flashcardDao.getAllCardsSnapshot(uid).filter { it.deckId == card.deckId }
            val masteredCount = remaining.count { it.toDomain().isMastered() }
            deckDao.getDeckById(card.deckId)?.let { existingDeck ->
                deckDao.upsertDeck(
                    existingDeck.copy(
                        cardCount = remaining.size,
                        masteredCount = masteredCount,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
            FlashcardWidgetUpdater.refreshAll(appContext)

            val batch = db.batch()
            val cardRef = db.collection("users").document(uid).collection("cards").document(card.id)
            docPath = cardRef.path
            batch.delete(cardRef)
            val deckRef = db.collection("users").document(uid).collection("decks").document(card.deckId)
            batch.update(
                deckRef,
                mapOf(
                    "cardCount" to remaining.size.toLong(),
                    "masteredCount" to masteredCount.toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteDeck(deck: StudyDeck): Result<Unit> {
        var docPath = "users/${deck.userId}/decks/${deck.id}"
        return try {
            val uid = requireUserId()
            check(uid == deck.userId) { "Signed-in user mismatch." }
            val cardsToDelete = flashcardDao.getAllCardsSnapshot(uid).filter { it.deckId == deck.id }
            flashcardDao.deleteCardsForDeck(deck.id)
            deckDao.deleteDeckById(deck.id)
            FlashcardWidgetUpdater.refreshAll(appContext)

            val batch = db.batch()
            cardsToDelete.forEach { card ->
                batch.delete(db.collection("users").document(uid).collection("cards").document(card.id))
            }
            val deckRef = db.collection("users").document(uid).collection("decks").document(deck.id)
            docPath = deckRef.path
            batch.delete(deckRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
