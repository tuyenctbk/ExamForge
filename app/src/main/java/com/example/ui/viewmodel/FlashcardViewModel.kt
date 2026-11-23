package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.FlashcardAiService
import com.example.data.local.AppDatabase
import com.example.data.local.Flashcard
import com.example.data.local.computeStudyProgress
import com.example.data.local.decodeOptionsJson
import com.example.data.local.encodeOptionsJson
import com.example.data.local.toDomain
import com.example.data.model.CardType
import com.example.data.model.DeckSourceType
import com.example.data.model.GeneratedCardDraft
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import com.example.data.repository.FlashcardLocalRepository
import com.example.data.repository.StudyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil

private const val TAG = "FlashcardViewModel"

enum class ReviewUrgency {
    CRITICAL_LAPSE,
    OVERDUE,
    NEVER_REVIEWED,
    UPCOMING
}

data class FlashcardReviewFlag(
    val cardId: String,
    val isReviewNow: Boolean,
    val urgency: ReviewUrgency,
    val statusLabel: String,
    val elapsedSinceLastReviewMillis: Long,
    val reinforcementIntervalMillis: Long,
    val nextReviewAtMillis: Long,
    val overdueMillis: Long,
    val priorityScore: Double
)

data class ReviewNowSessionUiState(
    val isActive: Boolean = false,
    val queue: List<Flashcard> = emptyList(),
    val currentIndex: Int = 0,
    val answerRevealed: Boolean = false,
    val hintShown: Boolean = false,
    val reviewedInCycle: Int = 0,
    val reinforcedMasteredCount: Int = 0,
    val totalFlaggedAtStart: Int = 0,
    val currentFlag: FlashcardReviewFlag? = null,
    val isCompleted: Boolean = false
) {
    val currentCard: Flashcard?
        get() = if (!isCompleted) queue.getOrNull(currentIndex) else null
}

/**
 * Scheduling algorithm that evaluates each flashcard's [Flashcard.lastReviewDate] and retention
 * interval to flag cards due for reinforcement and order them for active-recall cycling.
 */
object ReviewNowScheduler {
    const val DAY_MILLIS = 86_400_000L
    const val TEN_MINUTES_MILLIS = 600_000L

    fun computeReinforcementIntervalMillis(card: Flashcard): Long {
        if (card.lastReviewDate <= 0L) return 0L
        if (card.repetitions <= 0 || card.intervalDays <= 0) {
            return TEN_MINUTES_MILLIS
        }
        val baseIntervalMillis = card.intervalDays.coerceAtLeast(1) * DAY_MILLIS
        return if (card.studyProgress in 1..49 && card.intervalDays > 1) {
            (baseIntervalMillis * 0.85).toLong().coerceAtLeast(DAY_MILLIS)
        } else {
            baseIntervalMillis
        }
    }

    fun evaluateFlag(
        card: Flashcard,
        nowMillis: Long = System.currentTimeMillis()
    ): FlashcardReviewFlag {
        if (card.lastReviewDate <= 0L) {
            return FlashcardReviewFlag(
                cardId = card.id,
                isReviewNow = true,
                urgency = ReviewUrgency.NEVER_REVIEWED,
                statusLabel = "Review Now · Never reviewed",
                elapsedSinceLastReviewMillis = Long.MAX_VALUE,
                reinforcementIntervalMillis = 0L,
                nextReviewAtMillis = nowMillis,
                overdueMillis = 0L,
                priorityScore = 500.0 + (100 - card.studyProgress.coerceIn(0, 100))
            )
        }

        val intervalMillis = computeReinforcementIntervalMillis(card)
        val elapsedMillis = (nowMillis - card.lastReviewDate).coerceAtLeast(0L)
        val nextReviewAt = card.lastReviewDate + intervalMillis
        val isDue = nowMillis >= nextReviewAt

        if (isDue) {
            val overdueMillis = (nowMillis - nextReviewAt).coerceAtLeast(0L)
            val overdueDays = (overdueMillis / DAY_MILLIS).toInt()
            val urgency = if (card.repetitions <= 0 || card.intervalDays <= 0) {
                ReviewUrgency.CRITICAL_LAPSE
            } else {
                ReviewUrgency.OVERDUE
            }
            val label = when {
                urgency == ReviewUrgency.CRITICAL_LAPSE -> "Review Now · Reinforce"
                overdueDays >= 1 -> "Review Now · ${overdueDays}d overdue"
                else -> "Review Now · Due today"
            }
            val overdueRatio = overdueMillis.toDouble() / intervalMillis.coerceAtLeast(1L).toDouble()
            val baseTierScore = if (urgency == ReviewUrgency.CRITICAL_LAPSE) 2_000.0 else 1_000.0
            val priorityScore = baseTierScore + (overdueRatio * 200.0) + (100 - card.studyProgress.coerceIn(0, 100))
            return FlashcardReviewFlag(
                cardId = card.id,
                isReviewNow = true,
                urgency = urgency,
                statusLabel = label,
                elapsedSinceLastReviewMillis = elapsedMillis,
                reinforcementIntervalMillis = intervalMillis,
                nextReviewAtMillis = nextReviewAt,
                overdueMillis = overdueMillis,
                priorityScore = priorityScore
            )
        }

        val remainingMillis = (nextReviewAt - nowMillis).coerceAtLeast(0L)
        val remainingDays = ceil(remainingMillis.toDouble() / DAY_MILLIS.toDouble()).toInt().coerceAtLeast(1)
        val statusLabel = if (remainingMillis < DAY_MILLIS) {
            "Reviewed recently"
        } else {
            "Fresh · Next in ${remainingDays}d"
        }

        return FlashcardReviewFlag(
            cardId = card.id,
            isReviewNow = false,
            urgency = ReviewUrgency.UPCOMING,
            statusLabel = statusLabel,
            elapsedSinceLastReviewMillis = elapsedMillis,
            reinforcementIntervalMillis = intervalMillis,
            nextReviewAtMillis = nextReviewAt,
            overdueMillis = 0L,
            priorityScore = 0.0
        )
    }

    fun buildReviewNowQueue(
        cards: List<Flashcard>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<Flashcard> {
        return cards
            .map { card -> card to evaluateFlag(card, nowMillis) }
            .filter { (_, flag) -> flag.isReviewNow }
            .sortedWith(
                compareByDescending<Pair<Flashcard, FlashcardReviewFlag>> { (_, flag) -> flag.priorityScore }
                    .thenBy { (card, _) -> card.lastReviewDate }
                    .thenBy { (card, _) -> card.dueAtMillis }
            )
            .map { (card, _) -> card }
    }
}

sealed interface FlashcardListUiState {
    data object Loading : FlashcardListUiState
    data class Success(
        val flashcards: List<Flashcard>,
        val totalCount: Int,
        val dueCount: Int,
        val reviewNowCount: Int,
        val masteredCount: Int,
        val averageStudyProgress: Int,
        val reviewFlags: Map<String, FlashcardReviewFlag> = emptyMap()
    ) : FlashcardListUiState

    data class Error(val message: String) : FlashcardListUiState
}

sealed interface CameraCaptureImportState {
    data object Idle : CameraCaptureImportState
    data object Analyzing : CameraCaptureImportState
    data class Success(
        val addedCount: Int,
        val deckId: String,
        val deckTitle: String
    ) : CameraCaptureImportState

    data class Error(val message: String) : CameraCaptureImportState
}

/**
 * ViewModel that performs reactive reads and CRUD operations on the Room [Flashcard] entity
 * stored in [AppDatabase], including creating flashcards directly from CameraX photo captures
 * via Gemini multimodal analysis.
 */
class FlashcardViewModel(
    private val currentUserId: String,
    private val localRepository: FlashcardLocalRepository,
    private val flashcardAiService: FlashcardAiService = FlashcardAiService(),
    private val studyRepository: StudyRepository? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedDeckId = MutableStateFlow<String?>(null)
    val selectedDeckId: StateFlow<String?> = _selectedDeckId.asStateFlow()

    private val _reviewNowOnly = MutableStateFlow(false)
    val reviewNowOnly: StateFlow<Boolean> = _reviewNowOnly.asStateFlow()

    private val _scheduleClockMillis = MutableStateFlow(System.currentTimeMillis())

    private val _reviewNowSession = MutableStateFlow(ReviewNowSessionUiState())
    val reviewNowSession: StateFlow<ReviewNowSessionUiState> = _reviewNowSession.asStateFlow()

    private val _cameraCaptureState = MutableStateFlow<CameraCaptureImportState>(CameraCaptureImportState.Idle)
    val cameraCaptureState: StateFlow<CameraCaptureImportState> = _cameraCaptureState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _dbError = MutableStateFlow<String?>(null)

    private val allRoomFlashcards: StateFlow<List<Flashcard>> = localRepository
        .observeSavedFlashcards(currentUserId)
        .catch { error ->
            if (error is CancellationException) throw error
            Log.e(TAG, "Failed observing Room flashcards", error)
            _dbError.value = error.localizedMessage ?: "Unable to load saved flashcards."
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    /**
     * Reactive map of each card ID to its computed 'Review Now' reinforcement flag based on
     * [Flashcard.lastReviewDate] and SM-2 retention interval.
     */
    val reviewFlags: StateFlow<Map<String, FlashcardReviewFlag>> = combine(
        allRoomFlashcards,
        _scheduleClockMillis
    ) { cards, nowMillis ->
        cards.associate { card ->
            card.id to ReviewNowScheduler.evaluateFlag(card, nowMillis)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyMap()
    )

    /**
     * Prioritized queue of flashcards flagged for 'Review Now' within the active deck filter.
     */
    val reviewNowQueue: StateFlow<List<Flashcard>> = combine(
        allRoomFlashcards,
        _selectedDeckId,
        _scheduleClockMillis
    ) { cards, deckFilter, nowMillis ->
        val scoped = if (deckFilter.isNullOrBlank()) cards else cards.filter { it.deckId == deckFilter }
        ReviewNowScheduler.buildReviewNowQueue(scoped, nowMillis)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    /**
     * Reactive list of saved Room [Flashcard] entities filtered by optional deck, 'Review Now' flag,
     * and search query.
     */
    val savedFlashcards: StateFlow<List<Flashcard>> = combine(
        allRoomFlashcards,
        _selectedDeckId,
        _searchQuery,
        _reviewNowOnly,
        reviewFlags
    ) { cards, deckFilter, query, onlyReviewNow, flags ->
        val deckFiltered = if (deckFilter.isNullOrBlank()) {
            cards
        } else {
            cards.filter { it.deckId == deckFilter }
        }
        val reviewFiltered = if (onlyReviewNow) {
            deckFiltered
                .filter { flags[it.id]?.isReviewNow == true }
                .sortedByDescending { flags[it.id]?.priorityScore ?: 0.0 }
        } else {
            deckFiltered
        }
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            reviewFiltered
        } else {
            reviewFiltered.filter { card ->
                card.question.contains(trimmedQuery, ignoreCase = true) ||
                    card.answer.contains(trimmedQuery, ignoreCase = true) ||
                    card.explanation.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    val uiState: StateFlow<FlashcardListUiState> = combine(
        savedFlashcards,
        allRoomFlashcards,
        reviewFlags,
        _dbError
    ) { filteredCards, allCards, flags, errorMsg ->
        if (errorMsg != null) {
            FlashcardListUiState.Error(errorMsg)
        } else {
            val now = System.currentTimeMillis()
            val dueCount = allCards.count { it.dueAtMillis <= now }
            val reviewNowCount = flags.values.count { it.isReviewNow }
            val masteredCount = allCards.count { it.studyProgress >= 100 }
            val avgProgress = if (allCards.isEmpty()) {
                0
            } else {
                (allCards.sumOf { it.studyProgress } / allCards.size).coerceIn(0, 100)
            }
            FlashcardListUiState.Success(
                flashcards = filteredCards,
                totalCount = allCards.size,
                dueCount = dueCount,
                reviewNowCount = reviewNowCount,
                masteredCount = masteredCount,
                averageStudyProgress = avgProgress,
                reviewFlags = flags
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = FlashcardListUiState.Loading
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setDeckFilter(deckId: String?) {
        _selectedDeckId.value = deckId
    }

    fun setReviewNowOnly(enabled: Boolean) {
        _scheduleClockMillis.value = System.currentTimeMillis()
        _reviewNowOnly.value = enabled
    }

    fun toggleReviewNowFilter() {
        setReviewNowOnly(!_reviewNowOnly.value)
    }

    fun refreshReviewSchedule(nowMillis: Long = System.currentTimeMillis()) {
        _scheduleClockMillis.value = nowMillis
    }

    /**
     * Starts an interactive 'Review Now' cycling session containing all flashcards flagged for
     * reinforcement based on their [Flashcard.lastReviewDate].
     */
    fun startReviewNowCycle(
        deckId: String? = _selectedDeckId.value,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        _scheduleClockMillis.value = nowMillis
        viewModelScope.launch {
            val allCards = allRoomFlashcards.value.ifEmpty {
                localRepository.observeSavedFlashcards(currentUserId).first()
            }
            val sourceCards = if (deckId.isNullOrBlank()) {
                allCards
            } else {
                allCards.filter { it.deckId == deckId }
            }
            val queue = ReviewNowScheduler.buildReviewNowQueue(sourceCards, nowMillis)
            if (queue.isEmpty()) {
                _reviewNowSession.value = ReviewNowSessionUiState(
                    isActive = true,
                    queue = emptyList(),
                    isCompleted = false
                )
            } else {
                _reviewNowSession.value = ReviewNowSessionUiState(
                    isActive = true,
                    queue = queue,
                    currentIndex = 0,
                    answerRevealed = false,
                    hintShown = false,
                    reviewedInCycle = 0,
                    reinforcedMasteredCount = 0,
                    totalFlaggedAtStart = queue.size,
                    currentFlag = ReviewNowScheduler.evaluateFlag(queue.first(), nowMillis),
                    isCompleted = false
                )
            }
        }
    }

    fun revealReviewNowAnswer() {
        _reviewNowSession.update { session ->
            if (!session.isActive || session.isCompleted) session
            else session.copy(answerRevealed = true)
        }
    }

    fun toggleReviewNowFlip() {
        _reviewNowSession.update { session ->
            if (!session.isActive || session.isCompleted) session
            else session.copy(answerRevealed = !session.answerRevealed)
        }
    }

    fun toggleReviewNowHint() {
        _reviewNowSession.update { session ->
            if (!session.isActive || session.isCompleted) session
            else session.copy(hintShown = !session.hintShown)
        }
    }

    /**
     * Cycles forward through cards flagged for 'Review Now' reinforcement (wrapping cleanly).
     */
    fun cycleNextReviewCard(nowMillis: Long = System.currentTimeMillis()) {
        _reviewNowSession.update { session ->
            if (!session.isActive || session.isCompleted || session.queue.isEmpty()) return@update session
            val nextIdx = (session.currentIndex + 1) % session.queue.size
            val nextCard = session.queue[nextIdx]
            session.copy(
                currentIndex = nextIdx,
                answerRevealed = false,
                hintShown = false,
                currentFlag = ReviewNowScheduler.evaluateFlag(nextCard, nowMillis)
            )
        }
    }

    /**
     * Cycles backward through cards flagged for 'Review Now' reinforcement (wrapping cleanly).
     */
    fun cyclePreviousReviewCard(nowMillis: Long = System.currentTimeMillis()) {
        _reviewNowSession.update { session ->
            if (!session.isActive || session.isCompleted || session.queue.isEmpty()) return@update session
            val prevIdx = (session.currentIndex - 1 + session.queue.size) % session.queue.size
            val prevCard = session.queue[prevIdx]
            session.copy(
                currentIndex = prevIdx,
                answerRevealed = false,
                hintShown = false,
                currentFlag = ReviewNowScheduler.evaluateFlag(prevCard, nowMillis)
            )
        }
    }

    /**
     * Records a reinforcement rating during a 'Review Now' cycle, persists the new [Flashcard.lastReviewDate]
     * in Room, and advances or requeues the card accordingly.
     */
    fun submitReviewNowRating(
        rating: ReviewRating,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val currentSession = _reviewNowSession.value
        val activeCard = currentSession.currentCard ?: return

        recordFlashcardReview(activeCard, rating, nowMillis)
        _scheduleClockMillis.value = nowMillis

        val scheduled = SpacedRepetitionEngine.scheduleNextReview(activeCard.toDomain(), rating, nowMillis)
        val updatedSnapshot = activeCard.copy(
            studyProgress = computeStudyProgress(
                repetitions = scheduled.repetitions,
                intervalDays = scheduled.intervalDays,
                lastReviewDate = scheduled.lastReviewedAtMillis
            ),
            easeFactor = scheduled.easeFactor,
            intervalDays = scheduled.intervalDays,
            repetitions = scheduled.repetitions,
            dueAtMillis = scheduled.dueAtMillis,
            lastReviewDate = scheduled.lastReviewedAtMillis
        )

        _reviewNowSession.update { session ->
            val currentList = session.queue
            if (currentList.isEmpty()) return@update session.copy(isCompleted = true, currentFlag = null)

            val remainingWithoutCurrent = currentList.filterIndexed { index, _ -> index != session.currentIndex }
            val nextQueue = if (rating == ReviewRating.AGAIN) {
                // Keep missed cards at the back of the reinforcement cycle
                remainingWithoutCurrent + updatedSnapshot
            } else {
                remainingWithoutCurrent
            }

            val nextReviewedCount = session.reviewedInCycle + 1
            val nextMasteredCount = session.reinforcedMasteredCount +
                if (rating == ReviewRating.GOOD || rating == ReviewRating.EASY) 1 else 0

            if (nextQueue.isEmpty()) {
                session.copy(
                    queue = emptyList(),
                    currentIndex = 0,
                    answerRevealed = false,
                    hintShown = false,
                    reviewedInCycle = nextReviewedCount,
                    reinforcedMasteredCount = nextMasteredCount,
                    currentFlag = null,
                    isCompleted = true
                )
            } else {
                val nextIndex = session.currentIndex.coerceIn(0, nextQueue.lastIndex)
                val nextCard = nextQueue[nextIndex]
                session.copy(
                    queue = nextQueue,
                    currentIndex = nextIndex,
                    answerRevealed = false,
                    hintShown = false,
                    reviewedInCycle = nextReviewedCount,
                    reinforcedMasteredCount = nextMasteredCount,
                    currentFlag = ReviewNowScheduler.evaluateFlag(nextCard, nowMillis),
                    isCompleted = false
                )
            }
        }
    }

    fun closeReviewNowCycle() {
        _reviewNowSession.value = ReviewNowSessionUiState()
    }

    fun clearCameraCaptureState() {
        _cameraCaptureState.value = CameraCaptureImportState.Idle
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // --- CREATE ---

    /**
     * Inserts a new [Flashcard] entity into Room (and syncs to Firestore when available).
     */
    fun addFlashcard(
        deckId: String?,
        question: String,
        answer: String,
        explanation: String = "",
        hint: String = "",
        cardType: CardType = CardType.CONCEPT_EXPLAIN,
        options: List<String> = emptyList(),
        correctOptionIndex: Int = -1
    ) {
        val cleanQuestion = question.trim().take(1000)
        val cleanAnswer = answer.trim().take(1500)
        if (cleanQuestion.isBlank() || cleanAnswer.isBlank()) {
            _statusMessage.value = "Question and answer cannot be empty."
            return
        }

        viewModelScope.launch {
            try {
                val safeOptions = if (cardType == CardType.MULTIPLE_CHOICE) {
                    options.map { it.trim().take(240) }.filter { it.isNotEmpty() }.take(6)
                } else {
                    emptyList()
                }
                val safeCorrectIndex = if (cardType == CardType.MULTIPLE_CHOICE && safeOptions.isNotEmpty()) {
                    correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
                } else {
                    -1
                }
                val draft = GeneratedCardDraft(
                    localId = UUID.randomUUID().toString(),
                    cardType = cardType,
                    question = cleanQuestion,
                    answer = cleanAnswer,
                    explanation = explanation.trim().take(1500),
                    hint = hint.trim().take(300),
                    options = safeOptions,
                    correctOptionIndex = safeCorrectIndex
                )

                val repo = studyRepository
                if (repo != null && !deckId.isNullOrBlank()) {
                    repo.addCardsToExistingDeck(deckId, listOf(draft))
                    _statusMessage.value = "Flashcard saved."
                    return@launch
                }

                if (repo != null && deckId.isNullOrBlank()) {
                    repo.createDeckWithCards(
                        title = "Quick Study Cards",
                        subject = "General",
                        description = "Saved flashcards",
                        sourceType = DeckSourceType.MANUAL,
                        accentIndex = 0,
                        drafts = listOf(draft)
                    )
                    _statusMessage.value = "Flashcard saved to new deck."
                    return@launch
                }

                // Pure Room persistence path
                val resolvedDeckId = deckId?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCAL_DECK_ID
                localRepository.ensureLocalDeck(
                    deckId = resolvedDeckId,
                    userId = currentUserId,
                    title = "Saved Study Cards",
                    subject = "General",
                    description = "Offline Room flashcard collection",
                    sourceType = DeckSourceType.MANUAL,
                    accentIndex = 0
                )
                val now = System.currentTimeMillis()
                val entity = Flashcard(
                    id = generateCardId(),
                    userId = currentUserId,
                    deckId = resolvedDeckId,
                    cardType = cardType.name,
                    question = cleanQuestion,
                    answer = cleanAnswer,
                    explanation = explanation.trim().take(1500),
                    hint = hint.trim().take(300),
                    optionsJson = encodeOptionsJson(safeOptions),
                    correctOptionIndex = safeCorrectIndex,
                    studyProgress = 0,
                    easeFactor = 2.5,
                    intervalDays = 0,
                    repetitions = 0,
                    dueAtMillis = now,
                    lastReviewDate = 0L,
                    pendingSync = true
                )
                localRepository.insertFlashcard(entity)
                _statusMessage.value = "Flashcard saved locally."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding flashcard", e)
                _statusMessage.value = e.localizedMessage ?: "Failed to save flashcard."
            }
        }
    }

    /**
     * Analyzes a photo captured from CameraX using [FlashcardAiService] (Firebase AI / Gemini)
     * and persists the parsed [Flashcard] entities directly into Room [AppDatabase] (with Firestore sync).
     */
    fun addFlashcardsFromCameraCapture(
        bitmap: Bitmap,
        targetDeckId: String? = null,
        subjectHint: String = "",
        targetCardCount: Int = 6
    ) {
        viewModelScope.launch {
            _cameraCaptureState.value = CameraCaptureImportState.Analyzing
            try {
                val parsedBatch = flashcardAiService.parseImageToFlashcards(
                    capturedImage = bitmap,
                    targetCardCount = targetCardCount,
                    subjectHint = subjectHint
                )
                val drafts = parsedBatch.toCardDrafts()

                val repo = studyRepository
                if (repo != null) {
                    if (!targetDeckId.isNullOrBlank()) {
                        val res = repo.addCardsToExistingDeck(targetDeckId, drafts)
                        if (res.isSuccess) {
                            _cameraCaptureState.value = CameraCaptureImportState.Success(
                                addedCount = drafts.size,
                                deckId = targetDeckId,
                                deckTitle = parsedBatch.deckTitle
                            )
                            _statusMessage.value = "Saved ${drafts.size} camera flashcards to Room & cloud."
                            return@launch
                        }
                    } else {
                        val res = repo.createDeckWithCards(
                            title = parsedBatch.deckTitle,
                            subject = parsedBatch.subject,
                            description = parsedBatch.summary,
                            sourceType = DeckSourceType.PHOTO,
                            accentIndex = (parsedBatch.deckTitle.hashCode().and(Int.MAX_VALUE)) % 6,
                            drafts = drafts
                        )
                        if (res.isSuccess) {
                            val newDeckId = res.getOrThrow()
                            _cameraCaptureState.value = CameraCaptureImportState.Success(
                                addedCount = drafts.size,
                                deckId = newDeckId,
                                deckTitle = parsedBatch.deckTitle
                            )
                            _statusMessage.value = "Saved ${drafts.size} flashcards from camera capture."
                            return@launch
                        }
                    }
                }

                // Direct offline-first Room persistence fallback / standalone Room path
                val resolvedDeckId = targetDeckId?.takeIf { it.isNotBlank() } ?: generateDeckId()
                localRepository.ensureLocalDeck(
                    deckId = resolvedDeckId,
                    userId = currentUserId,
                    title = parsedBatch.deckTitle,
                    subject = parsedBatch.subject,
                    description = parsedBatch.summary,
                    sourceType = DeckSourceType.PHOTO,
                    accentIndex = (parsedBatch.deckTitle.hashCode().and(Int.MAX_VALUE)) % 6
                )

                val entities = parsedBatch.toRoomFlashcards(
                    userId = currentUserId,
                    deckId = resolvedDeckId,
                    pendingSync = true
                )

                localRepository.insertFlashcards(entities)
                _cameraCaptureState.value = CameraCaptureImportState.Success(
                    addedCount = entities.size,
                    deckId = resolvedDeckId,
                    deckTitle = parsedBatch.deckTitle
                )
                _statusMessage.value = "Saved ${entities.size} flashcards from camera capture to Room."
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Camera capture Gemini analysis timed out", e)
                _cameraCaptureState.value = CameraCaptureImportState.Error(
                    "Analysis timed out. Try a clearer photo with tighter framing."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating flashcards from camera capture", e)
                _cameraCaptureState.value = CameraCaptureImportState.Error(
                    e.localizedMessage ?: "Could not extract flashcards from the captured photo."
                )
            }
        }
    }

    // --- UPDATE ---

    /**
     * Updates an existing Room [Flashcard] entity's content and syncs the edit when online.
     */
    fun updateFlashcard(
        card: Flashcard,
        question: String,
        answer: String,
        explanation: String = card.explanation,
        hint: String = card.hint,
        cardType: CardType = CardType.fromRaw(card.cardType),
        options: List<String> = decodeOptionsJson(card.optionsJson),
        correctOptionIndex: Int = card.correctOptionIndex
    ) {
        val cleanQuestion = question.trim().take(1000)
        val cleanAnswer = answer.trim().take(1500)
        if (cleanQuestion.isBlank() || cleanAnswer.isBlank()) {
            _statusMessage.value = "Question and answer cannot be empty."
            return
        }

        viewModelScope.launch {
            try {
                val safeOptions = if (cardType == CardType.MULTIPLE_CHOICE) {
                    options.map { it.trim().take(240) }.filter { it.isNotEmpty() }.take(6)
                } else {
                    emptyList()
                }
                val safeCorrectIndex = if (cardType == CardType.MULTIPLE_CHOICE && safeOptions.isNotEmpty()) {
                    correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
                } else {
                    -1
                }
                val updatedEntity = card.copy(
                    cardType = cardType.name,
                    question = cleanQuestion,
                    answer = cleanAnswer,
                    explanation = explanation.trim().take(1500),
                    hint = hint.trim().take(300),
                    optionsJson = encodeOptionsJson(safeOptions),
                    correctOptionIndex = safeCorrectIndex
                )

                val repo = studyRepository
                if (repo != null) {
                    repo.updateCardContent(updatedEntity.toDomain())
                } else {
                    localRepository.updateFlashcard(updatedEntity)
                }
                _statusMessage.value = "Flashcard updated."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating flashcard ${card.id}", e)
                _statusMessage.value = e.localizedMessage ?: "Unable to update flashcard."
            }
        }
    }

    /**
     * Convenience update by card ID for screens holding domain models.
     */
    fun updateFlashcardById(
        cardId: String,
        question: String,
        answer: String,
        explanation: String,
        hint: String
    ) {
        viewModelScope.launch {
            val existing = localRepository.getFlashcardById(cardId) ?: return@launch
            updateFlashcard(
                card = existing,
                question = question,
                answer = answer,
                explanation = explanation,
                hint = hint
            )
        }
    }

    /**
     * Updates a card's spaced-repetition metadata (`studyProgress` and `lastReviewDate`) in Room.
     */
    fun recordFlashcardReview(
        card: Flashcard,
        rating: ReviewRating,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            try {
                _scheduleClockMillis.value = nowMillis
                val domainCard = card.toDomain()
                val repo = studyRepository
                if (repo != null) {
                    repo.recordCardReview(domainCard, rating)
                    _statusMessage.value = "Progress updated (${rating.label})."
                    return@launch
                }

                val scheduled = SpacedRepetitionEngine.scheduleNextReview(domainCard, rating, nowMillis)
                val nextProgress = computeStudyProgress(
                    repetitions = scheduled.repetitions,
                    intervalDays = scheduled.intervalDays,
                    lastReviewDate = scheduled.lastReviewedAtMillis
                )
                localRepository.updateReviewMetadata(
                    cardId = card.id,
                    userId = card.userId,
                    deckId = card.deckId,
                    studyProgress = nextProgress,
                    easeFactor = scheduled.easeFactor,
                    intervalDays = scheduled.intervalDays,
                    repetitions = scheduled.repetitions,
                    dueAtMillis = scheduled.dueAtMillis,
                    lastReviewDate = scheduled.lastReviewedAtMillis,
                    pendingSync = true
                )
                _statusMessage.value = "Progress updated ($nextProgress%)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed recording review for ${card.id}", e)
                _statusMessage.value = e.localizedMessage ?: "Failed to record review."
            }
        }
    }

    // --- DELETE ---

    /**
     * Deletes a [Flashcard] entity from Room (and Firestore when synced).
     */
    fun deleteFlashcard(card: Flashcard) {
        viewModelScope.launch {
            try {
                val repo = studyRepository
                if (repo != null) {
                    repo.deleteCard(card.toDomain())
                } else {
                    localRepository.deleteFlashcard(card)
                }
                _statusMessage.value = "Flashcard deleted."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting flashcard ${card.id}", e)
                _statusMessage.value = e.localizedMessage ?: "Unable to delete flashcard."
            }
        }
    }

    fun deleteFlashcardById(cardId: String) {
        viewModelScope.launch {
            val existing = localRepository.getFlashcardById(cardId)
            if (existing != null) {
                deleteFlashcard(existing)
            } else {
                localRepository.deleteFlashcardById(cardId)
                _statusMessage.value = "Flashcard deleted."
            }
        }
    }

    private fun generateCardId(): String =
        "card_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private fun generateDeckId(): String =
        "deck_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    companion object {
        private const val DEFAULT_LOCAL_DECK_ID = "deck_saved_cards"

        fun factory(
            currentUserId: String,
            context: Context,
            flashcardAiService: FlashcardAiService = FlashcardAiService(),
            studyRepository: StudyRepository? = null
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                val database = AppDatabase.getInstance(appContext)
                FlashcardViewModel(
                    currentUserId = currentUserId,
                    localRepository = FlashcardLocalRepository(database, appContext),
                    flashcardAiService = flashcardAiService,
                    studyRepository = studyRepository
                )
            }
        }
    }
}
