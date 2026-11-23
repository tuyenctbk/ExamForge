package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.firebase.isFirebaseConfigured
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.local.SampleStudyDecks
import com.example.data.local.computeStudyProgress
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
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "StudyRepository"

class StudyRepository(
    private val appContext: Context,
    private val db: FirebaseFirestore?,
    private val localDb: AppDatabase,
    private val auth: FirebaseAuth? = if (isFirebaseConfigured(appContext)) runCatching { Firebase.auth }.getOrNull() else null,
    private val ownerUserId: String = LOCAL_GUEST_USER_ID,
    private val preferencesRepository: PreferencesRepository? = null
) {
    constructor(context: Context, ownerUserId: String = LOCAL_GUEST_USER_ID, preferencesRepository: PreferencesRepository? = null) : this(
        appContext = context.applicationContext,
        db = if (isFirebaseConfigured(context.applicationContext)) {
            runCatching {
                FirebaseFirestore.getInstance(
                    context.applicationContext.getString(R.string.firestore_database_id)
                )
            }.getOrNull()
        } else {
            null
        },
        localDb = AppDatabase.getInstance(context.applicationContext),
        ownerUserId = ownerUserId,
        preferencesRepository = preferencesRepository
    )

    private val deckDao = localDb.deckDao()
    private val flashcardDao = localDb.flashcardDao()

    private fun activeUserId(): String =
        auth?.currentUser?.uid?.takeIf { it.isNotBlank() } ?: ownerUserId

    private fun activeCloudDbAndUid(): Pair<FirebaseFirestore, String>? {
        val firestore = db ?: return null
        val uid = auth?.currentUser?.uid ?: return null
        if (uid != ownerUserId && ownerUserId != LOCAL_GUEST_USER_ID) return null
        return firestore to uid
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
        val (firestore, cloudUid) = activeCloudDbAndUid() ?: return emptyFlow()
        if (cloudUid != userId) return emptyFlow()

        val path = "users/$userId/decks"
        return firestore.collection("users").document(userId).collection("decks")
            .snapshots()
            .map { snapshot ->
                val pendingDeckIds = flashcardDao.getPendingSyncCards(userId).map { it.deckId }.toSet()
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
                val keepIds = (remoteDecks.map { it.id } + pendingDeckIds).distinct()
                if (remoteDecks.isNotEmpty()) {
                    deckDao.upsertDecks(remoteDecks.map { it.toEntity() })
                }
                if (keepIds.isEmpty()) {
                    deckDao.deleteAllDecksForUser(userId)
                } else {
                    deckDao.pruneRemovedDecks(userId, keepIds)
                }
                remoteDecks
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun syncCardsFromCloud(userId: String): Flow<List<Flashcard>> {
        val (firestore, cloudUid) = activeCloudDbAndUid() ?: return emptyFlow()
        if (cloudUid != userId) return emptyFlow()

        val path = "users/$userId/cards"
        return firestore.collection("users").document(userId).collection("cards")
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
                if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun observeUserProfile(userId: String): Flow<UserProfile?> {
        val (firestore, cloudUid) = activeCloudDbAndUid() ?: return emptyFlow()
        if (cloudUid != userId) return emptyFlow()

        val path = "users/$userId"
        return firestore.collection("users").document(userId)
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
                if (exceptionToLog != null && auth?.currentUser?.uid == userId) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, path)
                }
                throw error
            }
    }

    // --- MUTATIONS (OFFLINE-FIRST ROOM + OPTIONAL FIRESTORE SYNC) ---

    suspend fun ensureUserProfile(userId: String, displayName: String): Result<Unit> {
        val (firestore, uid) = activeCloudDbAndUid() ?: return Result.success(Unit)
        if (uid != userId) return Result.success(Unit)

        val path = "users/$userId"
        return try {
            val docRef = firestore.collection("users").document(uid)
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
        val (firestore, uid) = activeCloudDbAndUid() ?: return Result.success(0)
        if (uid != userId) return Result.success(0)

        val pending = flashcardDao.getPendingSyncCards(userId)
        if (pending.isEmpty()) return Result.success(0)
        var syncedCount = 0
        for (entity in pending) {
            val docPath = "users/$userId/cards/${entity.id}"
            try {
                // Ensure parent deck exists in Firestore before upserting card
                val localDeck = deckDao.getDeckById(entity.deckId)
                if (localDeck != null) {
                    val deckRef = firestore.collection("users").document(uid)
                        .collection("decks").document(localDeck.id)
                    val deckSnap = deckRef.get().await()
                    if (!deckSnap.exists()) {
                        deckRef.set(
                            mapOf(
                                "userId" to uid,
                                "title" to localDeck.title,
                                "subject" to localDeck.subject,
                                "description" to localDeck.description,
                                "sourceType" to localDeck.sourceType,
                                "accentIndex" to localDeck.accentIndex.toLong(),
                                "cardCount" to localDeck.cardCount.toLong(),
                                "masteredCount" to localDeck.masteredCount.toLong(),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }

                val docRef = firestore.collection("users").document(uid)
                    .collection("cards").document(entity.id)
                val cardSnap = docRef.get().await()
                if (cardSnap.exists()) {
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
                } else {
                    val domain = entity.toDomain()
                    docRef.set(
                        mapOf(
                            "userId" to uid,
                            "deckId" to domain.deckId,
                            "cardType" to domain.cardType.name,
                            "question" to domain.question,
                            "answer" to domain.answer,
                            "explanation" to domain.explanation,
                            "hint" to domain.hint,
                            "options" to domain.options,
                            "correctOptionIndex" to domain.correctOptionIndex.toLong(),
                            "easeFactor" to domain.easeFactor.coerceIn(1.3, 5.0),
                            "intervalDays" to domain.intervalDays.toLong().coerceIn(0L, 36500L),
                            "repetitions" to domain.repetitions.toLong().coerceIn(0L, 10000L),
                            "dueAtMillis" to domain.dueAtMillis.coerceAtLeast(0L),
                            "lastReviewedAtMillis" to domain.lastReviewedAtMillis.coerceAtLeast(0L),
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                }
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

    suspend fun loadSampleStudyDecks(): Result<Int> {
        return try {
            val uid = activeUserId()
            val nowMillis = System.currentTimeMillis()
            val dayMs = 86_400_000L
            val bundles = SampleStudyDecks.defaultBundles()
            var totalCardsCreated = 0

            bundles.forEachIndexed { deckIdx, bundle ->
                val deckId = generateSafeId("deck")
                val cards = bundle.cards.mapIndexed { cardIdx, seed ->
                    val cardId = generateSafeId("card")
                    val draft = seed.draft
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
                    val isMastered = seed.mastered
                    val repetitions = if (isMastered) 3 else 0
                    val intervalDays = if (isMastered) 7 else 0
                    val lastReviewed = if (isMastered) nowMillis - (2L * dayMs) else 0L
                    val dueAt = if (isMastered) nowMillis + (5L * dayMs) else nowMillis - ((cardIdx + 1) * 3600_000L)

                    Flashcard(
                        id = cardId,
                        userId = uid,
                        deckId = deckId,
                        cardType = draft.cardType,
                        question = draft.question,
                        answer = draft.answer,
                        explanation = draft.explanation,
                        hint = draft.hint,
                        options = safeOptions,
                        correctOptionIndex = safeCorrectIndex,
                        easeFactor = if (isMastered) 2.6 else 2.5,
                        intervalDays = intervalDays,
                        repetitions = repetitions,
                        dueAtMillis = dueAt,
                        lastReviewedAtMillis = lastReviewed,
                        pendingSync = activeCloudDbAndUid() != null
                    )
                }

                val masteredCount = cards.count { it.isMastered() }
                val localDeck = StudyDeck(
                    id = deckId,
                    userId = uid,
                    title = bundle.title,
                    subject = bundle.subject,
                    description = bundle.description,
                    sourceType = bundle.sourceType,
                    accentIndex = bundle.accentIndex,
                    cardCount = cards.size,
                    masteredCount = masteredCount,
                    updatedAtMillis = nowMillis - (deckIdx * 60_000L)
                )

                deckDao.upsertDeck(localDeck.toEntity())
                flashcardDao.upsertCards(cards.map { it.toEntity(pendingSync = it.pendingSync) })
                totalCardsCreated += cards.size

                // Best-effort sync to Firestore when signed in
                activeCloudDbAndUid()?.let { (firestore, cloudUid) ->
                    runCatching {
                        val batch = firestore.batch()
                        val deckRef = firestore.collection("users").document(cloudUid).collection("decks").document(deckId)
                        batch.set(
                            deckRef,
                            mapOf(
                                "userId" to cloudUid,
                                "title" to localDeck.title,
                                "subject" to localDeck.subject,
                                "description" to localDeck.description,
                                "sourceType" to localDeck.sourceType.name,
                                "accentIndex" to localDeck.accentIndex.toLong(),
                                "cardCount" to cards.size.toLong(),
                                "masteredCount" to masteredCount.toLong(),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        cards.forEach { card ->
                            val cardRef = firestore.collection("users").document(cloudUid).collection("cards").document(card.id)
                            batch.set(
                                cardRef,
                                mapOf(
                                    "userId" to cloudUid,
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
                        cards.forEach { flashcardDao.markSynced(it.id) }
                    }.onFailure { err ->
                        Log.w(TAG, "Sample deck cloud sync deferred: ${err.message}")
                    }
                }
            }
            FlashcardWidgetUpdater.refreshAll(appContext)
            Result.success(totalCardsCreated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading sample study decks", e)
            Result.failure(e)
        }
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
        val uid = activeUserId()
        val cloudTarget = activeCloudDbAndUid()
        var docPath = "users/$uid/decks/$deckId"
        return try {
            val cleanTitle = title.trim().ifBlank { "Untitled Study Deck" }.take(120)
            val cleanSubject = subject.trim().ifBlank { "General" }.take(60)
            val cleanDesc = description.trim().take(400)
            val safeAccent = accentIndex.coerceIn(0, 5)
            val nowMillis = System.currentTimeMillis()
            val pendingUntilCloud = cloudTarget != null

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
                    pendingSync = pendingUntilCloud
                )
            }

            // 1. Write immediately to Room SQLite cache (offline-first guarantee)
            deckDao.upsertDeck(localDeck.toEntity())
            if (localCards.isNotEmpty()) {
                flashcardDao.upsertCards(localCards.map { it.toEntity(pendingSync = pendingUntilCloud) })
            }
            FlashcardWidgetUpdater.refreshAll(appContext)

            // 2. Best-effort atomic batch write to Firestore when cloud is active
            if (cloudTarget != null) {
                val (firestore, cloudUid) = cloudTarget
                try {
                    val batch = firestore.batch()
                    val deckRef = firestore.collection("users").document(cloudUid).collection("decks").document(deckId)
                    docPath = deckRef.path
                    batch.set(
                        deckRef,
                        mapOf(
                            "userId" to cloudUid,
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
                        val cardRef = firestore.collection("users").document(cloudUid).collection("cards").document(card.id)
                        batch.set(
                            cardRef,
                            mapOf(
                                "userId" to cloudUid,
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
                    localCards.forEach { flashcardDao.markSynced(it.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.CREATE, docPath)
                    Log.w(TAG, "Deck saved locally in Room; cloud sync deferred: ${e.message}")
                }
            }
            Result.success(deckId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating deck locally", e)
            Result.failure(e)
        }
    }

    suspend fun addCardsToExistingDeck(
        deckId: String,
        drafts: List<GeneratedCardDraft>
    ): Result<Unit> {
        val uid = activeUserId()
        val cloudTarget = activeCloudDbAndUid()
        var docPath = "users/$uid/decks/$deckId"
        return try {
            require(deckId.isNotBlank()) { "Deck ID cannot be blank" }
            val nowMillis = System.currentTimeMillis()
            val pendingUntilCloud = cloudTarget != null

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
                    pendingSync = pendingUntilCloud
                )
            }

            if (newCards.isEmpty()) return Result.success(Unit)
            flashcardDao.upsertCards(newCards.map { it.toEntity(pendingSync = pendingUntilCloud) })

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

            if (cloudTarget != null) {
                val (firestore, cloudUid) = cloudTarget
                try {
                    val batch = firestore.batch()
                    newCards.forEach { card ->
                        val cardRef = firestore.collection("users").document(cloudUid).collection("cards").document(card.id)
                        batch.set(
                            cardRef,
                            mapOf(
                                "userId" to cloudUid,
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
                    val deckRef = firestore.collection("users").document(cloudUid).collection("decks").document(deckId)
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
                    newCards.forEach { flashcardDao.markSynced(it.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.WRITE, docPath)
                    Log.w(TAG, "Added cards locally in Room; cloud sync deferred: ${e.message}")
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding cards locally", e)
            Result.failure(e)
        }
    }

    suspend fun recordCardReview(card: Flashcard, rating: ReviewRating): Result<Flashcard> {
        val nowMillis = System.currentTimeMillis()
        val scheduled = SpacedRepetitionEngine.scheduleNextReview(card, rating, nowMillis)
        val cloudTarget = activeCloudDbAndUid()
        val updatedCard = card.copy(
            easeFactor = scheduled.easeFactor,
            intervalDays = scheduled.intervalDays,
            repetitions = scheduled.repetitions,
            dueAtMillis = scheduled.dueAtMillis,
            lastReviewedAtMillis = scheduled.lastReviewedAtMillis,
            pendingSync = cloudTarget != null
        )

        // 1. Always save immediately to Room SQLite + local study streak (works 100% offline!)
        flashcardDao.upsertCard(updatedCard.toEntity(pendingSync = cloudTarget != null))
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
        preferencesRepository?.recordLocalReviewStat(nowMillis)
        FlashcardWidgetUpdater.refreshAll(appContext)

        // 2. Sync to Firestore when signed in & online (if offline, Room retains pendingSync = true)
        if (cloudTarget == null) {
            return Result.success(updatedCard)
        }
        val (firestore, uid) = cloudTarget
        if (uid != card.userId) return Result.success(updatedCard)

        var docPath = "users/${card.userId}/cards/${card.id}"
        return try {
            val batch = firestore.batch()
            val cardRef = firestore.collection("users").document(uid).collection("cards").document(card.id)
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
            val deckRef = firestore.collection("users").document(uid).collection("decks").document(card.deckId)
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

            // Update cloud study streak & review total
            updateStudyStats(firestore, uid, nowMillis)
            Result.success(updatedCard.copy(pendingSync = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Log.w(TAG, "Review saved locally in Room; cloud sync deferred: ${e.message}")
            Result.success(updatedCard)
        }
    }

    private suspend fun updateStudyStats(firestore: FirebaseFirestore, uid: String, nowMillis: Long) {
        val path = "users/$uid"
        try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
            val yesterdayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis - 86_400_000L))
            val userRef = firestore.collection("users").document(uid)
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
        val cloudTarget = activeCloudDbAndUid()
        val docPath = "users/${card.userId}/cards/${card.id}"
        // 1. Update immediately in local Room SQLite database
        flashcardDao.upsertCard(card.toEntity(pendingSync = cloudTarget != null))
        FlashcardWidgetUpdater.refreshAll(appContext)

        if (cloudTarget == null) return Result.success(Unit)
        val (firestore, uid) = cloudTarget
        if (uid != card.userId) return Result.success(Unit)

        // 2. Sync content edit to Firestore (strictly matching allowed edit keys in firestore.rules)
        return try {
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
            firestore.collection("users").document(uid)
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
            Log.w(TAG, "Card edit saved locally in Room; cloud sync deferred: ${e.message}")
            Result.success(Unit)
        }
    }

    suspend fun deleteCard(card: Flashcard): Result<Unit> {
        val uid = activeUserId()
        val cloudTarget = activeCloudDbAndUid()
        var docPath = "users/${card.userId}/cards/${card.id}"
        return try {
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

            if (cloudTarget != null && cloudTarget.second == card.userId) {
                val (firestore, cloudUid) = cloudTarget
                try {
                    val batch = firestore.batch()
                    val cardRef = firestore.collection("users").document(cloudUid).collection("cards").document(card.id)
                    docPath = cardRef.path
                    batch.delete(cardRef)
                    val deckRef = firestore.collection("users").document(cloudUid).collection("decks").document(card.deckId)
                    batch.update(
                        deckRef,
                        mapOf(
                            "cardCount" to remaining.size.toLong(),
                            "masteredCount" to masteredCount.toLong(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.commit().await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.DELETE, docPath)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteDeck(deck: StudyDeck): Result<Unit> {
        val uid = activeUserId()
        val cloudTarget = activeCloudDbAndUid()
        var docPath = "users/${deck.userId}/decks/${deck.id}"
        return try {
            val cardsToDelete = flashcardDao.getAllCardsSnapshot(uid).filter { it.deckId == deck.id }
            flashcardDao.deleteCardsForDeck(deck.id)
            deckDao.deleteDeckById(deck.id)
            FlashcardWidgetUpdater.refreshAll(appContext)

            if (cloudTarget != null && cloudTarget.second == deck.userId) {
                val (firestore, cloudUid) = cloudTarget
                try {
                    val batch = firestore.batch()
                    cardsToDelete.forEach { card ->
                        batch.delete(firestore.collection("users").document(cloudUid).collection("cards").document(card.id))
                    }
                    val deckRef = firestore.collection("users").document(cloudUid).collection("decks").document(deck.id)
                    docPath = deckRef.path
                    batch.delete(deckRef)
                    batch.commit().await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.DELETE, docPath)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val LOCAL_GUEST_USER_ID = "local_offline_student"
    }
}
