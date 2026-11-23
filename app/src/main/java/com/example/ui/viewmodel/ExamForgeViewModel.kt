package com.example.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.ai.AiQuotaExceededException
import com.example.data.ai.FactCheckResult
import com.example.data.analytics.AppEventTracker
import com.example.data.analytics.AppEventType
import com.example.data.analytics.AutoPopupSuggestion
import com.example.data.analytics.AutoPopupType
import com.example.data.analytics.EngagementPromptEngine
import com.example.data.analytics.EngagementTelemetryState
import com.example.data.analytics.PopupTriggerMoment
import com.example.data.donation.DonationBillingManager
import com.example.data.donation.DonationBillingUiState
import com.example.data.donation.DonationTier
import com.example.data.local.AiUsagePlanState
import com.example.data.local.PreferencesRepository
import com.example.data.model.CardType
import com.example.data.model.DeckSourceType
import com.example.data.model.Flashcard
import com.example.data.model.GeneratedCardDraft
import com.example.data.model.GeneratedDeckDraft
import com.example.data.model.QuizMessage
import com.example.data.model.ReviewRating
import com.example.data.model.StudyDeck
import com.example.data.model.UserProfile
import com.example.data.repository.GeminiStudyService
import com.example.data.repository.StudyRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ExamForgeViewModel"

sealed interface ForgeUiState {
    data object Idle : ForgeUiState
    data class Generating(val isOffline: Boolean = false) : ForgeUiState
    data class Preview(val draft: GeneratedDeckDraft) : ForgeUiState
    data object Saving : ForgeUiState
    data class Saved(val deckId: String, val cardCount: Int) : ForgeUiState
    data class Error(val message: String, val isQuotaLimit: Boolean = false) : ForgeUiState
}

sealed interface FactCheckUiState {
    data object Idle : FactCheckUiState
    data class Checking(val card: Flashcard) : FactCheckUiState
    data class Ready(val card: Flashcard, val result: FactCheckResult) : FactCheckUiState
    data class Error(val card: Flashcard, val message: String, val isQuotaLimit: Boolean = false) : FactCheckUiState
}

sealed interface ByokKeyUiState {
    data object Idle : ByokKeyUiState
    data object Verifying : ByokKeyUiState
    data class Saved(val message: String) : ByokKeyUiState
    data class Error(val message: String) : ByokKeyUiState
}

data class QuizmasterUiState(
    val selectedDeckId: String? = null,
    val messages: List<QuizMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val lastCompletedAiReply: String? = null
)

data class StudySessionUiState(
    val deckId: String = "",
    val deckTitle: String = "",
    val queue: List<Flashcard> = emptyList(),
    val currentIndex: Int = 0,
    val revealed: Boolean = false,
    val hintShown: Boolean = false,
    val selectedMcqOption: Int? = null,
    val clozeInput: String = "",
    val clozeChecked: Boolean = false,
    val reviewedCount: Int = 0,
    val masteredInSession: Int = 0
) {
    val currentCard: Flashcard? get() = queue.getOrNull(currentIndex)
    val isFinished: Boolean get() = queue.isEmpty() || currentIndex >= queue.size
}

data class SyncStatusState(
    val isOnline: Boolean = true,
    val cloudSyncError: String? = null,
    val pendingOfflineCount: Int = 0,
    val lastActionBanner: String? = null
)

class ExamForgeViewModel(
    private val currentUserId: String,
    private val initialDisplayName: String,
    private val studyRepository: StudyRepository,
    private val geminiService: GeminiStudyService,
    private val preferencesRepository: PreferencesRepository,
    private val eventTracker: AppEventTracker,
    appContext: Context
) : ViewModel() {

    private val _decksLoaded = MutableStateFlow(false)
    val decksLoaded: StateFlow<Boolean> = _decksLoaded.asStateFlow()

    val decks: StateFlow<List<StudyDeck>> = studyRepository.observeDecksLocal(currentUserId)
        .let { flow ->
            kotlinx.coroutines.flow.flow {
                flow.collect { list ->
                    _decksLoaded.value = true
                    emit(list)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val allCards: StateFlow<List<Flashcard>> = studyRepository.observeAllCardsLocal(currentUserId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val aiUsagePlan: StateFlow<AiUsagePlanState> = preferencesRepository.aiUsagePlan
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = AiUsagePlanState()
        )

    val matchBlitzBestScore: StateFlow<Int> = preferencesRepository.matchBlitzBestScore
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = 0
        )

    val engagementTelemetry: StateFlow<EngagementTelemetryState> = eventTracker.telemetryState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = EngagementTelemetryState()
        )

    private val _activePopupSuggestion = MutableStateFlow<AutoPopupSuggestion?>(null)
    val activePopupSuggestion: StateFlow<AutoPopupSuggestion?> = _activePopupSuggestion.asStateFlow()

    private val _donationSheetVisible = MutableStateFlow(false)
    val donationSheetVisible: StateFlow<Boolean> = _donationSheetVisible.asStateFlow()

    private val _donationSheetMilestone = MutableStateFlow<Int?>(null)
    val donationSheetMilestone: StateFlow<Int?> = _donationSheetMilestone.asStateFlow()

    private val donationBillingManager = DonationBillingManager(
        appContext = appContext,
        onSupporterVerified = { productId, _ ->
            viewModelScope.launch {
                eventTracker.markActiveSupporter("GOOGLE_PLAY:$productId")
                _actionBanner.value = "Thank you for supporting ExamForge! Active Supporter badge unlocked."
            }
        }
    )

    val donationBillingState: StateFlow<DonationBillingUiState> = donationBillingManager.billingState

    private val sessionStartedAtMs: Long = System.currentTimeMillis()
    private var promptShownThisSession: Boolean = false

    private val _cloudUserProfile = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = combine(
        _cloudUserProfile,
        preferencesRepository.observeLocalStudyProfile(currentUserId, initialDisplayName)
    ) { cloud, local ->
        if (cloud == null) {
            local
        } else {
            cloud.copy(
                streakDays = maxOf(cloud.streakDays, local.streakDays),
                totalReviewed = maxOf(cloud.totalReviewed, local.totalReviewed)
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UserProfile(
            userId = currentUserId,
            displayName = initialDisplayName,
            streakDays = 0,
            totalReviewed = 0,
            lastStudyDayKey = null
        )
    )

    private val _forgeState = MutableStateFlow<ForgeUiState>(ForgeUiState.Idle)
    val forgeState: StateFlow<ForgeUiState> = _forgeState.asStateFlow()

    private val _selectedSnapshot = MutableStateFlow<Bitmap?>(null)
    val selectedSnapshot: StateFlow<Bitmap?> = _selectedSnapshot.asStateFlow()

    private val _quizState = MutableStateFlow(QuizmasterUiState())
    val quizState: StateFlow<QuizmasterUiState> = _quizState.asStateFlow()

    private val _studySession = MutableStateFlow(StudySessionUiState())
    val studySession: StateFlow<StudySessionUiState> = _studySession.asStateFlow()

    private val _factCheckState = MutableStateFlow<FactCheckUiState>(FactCheckUiState.Idle)
    val factCheckState: StateFlow<FactCheckUiState> = _factCheckState.asStateFlow()

    private val _byokKeyState = MutableStateFlow<ByokKeyUiState>(ByokKeyUiState.Idle)
    val byokKeyState: StateFlow<ByokKeyUiState> = _byokKeyState.asStateFlow()

    // In-memory cache so re-checking the same flashcard consumes 0 tokens
    private val factCheckCache = mutableMapOf<String, FactCheckResult>()

    private val _cloudSyncError = MutableStateFlow<String?>(null)
    private val _actionBanner = MutableStateFlow<String?>(null)

    private val connectivityFlow = observeNetworkOnline(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), true)

    val syncStatus: StateFlow<SyncStatusState> = combine(
        connectivityFlow,
        _cloudSyncError,
        allCards,
        _actionBanner
    ) { online, err, cards, banner ->
        SyncStatusState(
            isOnline = online,
            cloudSyncError = err,
            pendingOfflineCount = cards.count { it.pendingSync },
            lastActionBanner = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = SyncStatusState()
    )

    init {
        viewModelScope.launch {
            preferencesRepository.setActiveUserId(currentUserId)
            studyRepository.ensureUserProfile(currentUserId, initialDisplayName)
            // Log app session start (explicitly never triggers a Rate/Share popup at app launch)
            eventTracker.logEvent(
                type = AppEventType.APP_OPENED,
                detail = "v${BuildConfig.VERSION_NAME}",
                value = BuildConfig.VERSION_CODE
            )
        }
        startCloudSyncListeners()
        observeConnectivityForFlush()
        donationBillingManager.startConnectionAndLoad()
    }

    override fun onCleared() {
        super.onCleared()
        donationBillingManager.release()
    }

    private fun startCloudSyncListeners() {
        viewModelScope.launch {
            studyRepository.syncDecksFromCloud(currentUserId)
                .catch { error ->
                    val fsErr = unwrapFirestoreError(error)
                    if (fsErr == null && error is CancellationException) throw error
                    val root = fsErr ?: error
                    Log.w(TAG, "Cloud deck sync paused", root)
                    _cloudSyncError.value = root.message
                }
                .collect {
                    _cloudSyncError.value = null
                }
        }

        viewModelScope.launch {
            studyRepository.syncCardsFromCloud(currentUserId)
                .catch { error ->
                    val fsErr = unwrapFirestoreError(error)
                    if (fsErr == null && error is CancellationException) throw error
                    val root = fsErr ?: error
                    Log.w(TAG, "Cloud card sync paused", root)
                    _cloudSyncError.value = root.message
                }
                .collect {
                    _cloudSyncError.value = null
                }
        }

        viewModelScope.launch {
            studyRepository.observeUserProfile(currentUserId)
                .catch { error ->
                    val fsErr = unwrapFirestoreError(error)
                    if (fsErr == null && error is CancellationException) throw error
                    Log.w(TAG, "Profile observer error", fsErr ?: error)
                }
                .collect { profile ->
                    _cloudUserProfile.value = profile
                }
        }
    }

    private fun observeConnectivityForFlush() {
        viewModelScope.launch {
            connectivityFlow.collect { isOnline ->
                if (isOnline) {
                    studyRepository.flushPendingOfflineReviews(currentUserId)
                }
            }
        }
    }

    fun manualSyncPendingReviews() {
        viewModelScope.launch {
            val result = studyRepository.flushPendingOfflineReviews(currentUserId)
            result.onSuccess { count ->
                _actionBanner.value = if (count > 0) {
                    "Synced $count offline review(s) to Firestore."
                } else {
                    "All flashcards are synced with the cloud."
                }
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Sync deferred — staying in offline mode."
            }
        }
    }

    fun clearBanner() {
        _actionBanner.value = null
    }

    // --- SNAPSHOT TO FLASHCARD FORGE ---

    fun setSelectedSnapshot(bitmap: Bitmap?) {
        _selectedSnapshot.value = bitmap
    }

    fun clearSelectedSnapshot() {
        _selectedSnapshot.value = null
    }

    fun forgeFlashcards(
        bitmap: Bitmap?,
        rawNotes: String,
        targetCount: Int,
        subjectHint: String,
        forceOffline: Boolean = false,
        forceEcoLite: Boolean = false
    ) {
        viewModelScope.launch {
            val isOfflineAttempt = forceOffline || (bitmap == null && aiUsagePlan.value.preferOfflineParser)
            _forgeState.value = ForgeUiState.Generating(isOffline = isOfflineAttempt)
            try {
                val draft = geminiService.generateDeckFromSnapshot(
                    bitmap = bitmap,
                    rawNotes = rawNotes,
                    targetCardCount = targetCount,
                    subjectHint = subjectHint,
                    forceOffline = forceOffline,
                    forceEcoLite = forceEcoLite
                )
                _forgeState.value = ForgeUiState.Preview(draft)
            } catch (e: AiQuotaExceededException) {
                Log.w(TAG, "Daily free AI forge quota reached", e)
                eventTracker.logEvent(AppEventType.QUOTA_HIT, detail = "forge_quota")
                _forgeState.value = ForgeUiState.Error(
                    message = e.message ?: "Daily free AI limit reached.",
                    isQuotaLimit = true
                )
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Flashcard generation timed out", e)
                eventTracker.logEvent(AppEventType.ERROR_OCCURRED, detail = "forge_timeout")
                _forgeState.value = ForgeUiState.Error(
                    "Gemini took longer than 60 seconds. Try a closer crop or Offline Instant Mode."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Flashcard generation failed", e)
                eventTracker.logEvent(AppEventType.ERROR_OCCURRED, detail = "forge_error")
                _forgeState.value = ForgeUiState.Error(
                    e.localizedMessage ?: "Could not parse notes into flashcards."
                )
            }
        }
    }

    fun updateDraftMetadata(title: String, subject: String) {
        val current = _forgeState.value as? ForgeUiState.Preview ?: return
        _forgeState.value = ForgeUiState.Preview(
            current.draft.copy(title = title, subject = subject)
        )
    }

    fun removeDraftCard(localId: String) {
        val current = _forgeState.value as? ForgeUiState.Preview ?: return
        val remaining = current.draft.cards.filterNot { it.localId == localId }
        _forgeState.value = ForgeUiState.Preview(current.draft.copy(cards = remaining))
    }

    fun saveForgedDeck(targetExistingDeckId: String?, accentIndex: Int) {
        val preview = _forgeState.value as? ForgeUiState.Preview ?: return
        val draft = preview.draft
        if (draft.cards.isEmpty()) {
            _forgeState.value = ForgeUiState.Error("Keep at least one flashcard before saving.")
            return
        }
        viewModelScope.launch {
            _forgeState.value = ForgeUiState.Saving
            if (targetExistingDeckId != null) {
                val res = studyRepository.addCardsToExistingDeck(targetExistingDeckId, draft.cards)
                res.onSuccess {
                    _forgeState.value = ForgeUiState.Saved(targetExistingDeckId, draft.cards.size)
                    _actionBanner.value = "Added ${draft.cards.size} cards to your deck."
                    eventTracker.logEvent(AppEventType.DECK_FORGED, detail = draft.title, value = draft.cards.size)
                    maybeEvaluateAutoPopup(PopupTriggerMoment.DECK_FORGED)
                }.onFailure { err ->
                    eventTracker.logEvent(AppEventType.ERROR_OCCURRED, detail = "save_cards_failed")
                    _forgeState.value = ForgeUiState.Error(err.localizedMessage ?: "Failed to save cards.")
                }
            } else {
                val res = studyRepository.createDeckWithCards(
                    title = draft.title,
                    subject = draft.subject,
                    description = draft.summary,
                    sourceType = draft.sourceType,
                    accentIndex = accentIndex,
                    drafts = draft.cards
                )
                res.onSuccess { deckId ->
                    _forgeState.value = ForgeUiState.Saved(deckId, draft.cards.size)
                    _actionBanner.value = "Forged \"${draft.title}\" with ${draft.cards.size} cards."
                    eventTracker.logEvent(AppEventType.DECK_FORGED, detail = draft.title, value = draft.cards.size)
                    maybeEvaluateAutoPopup(PopupTriggerMoment.DECK_FORGED)
                }.onFailure { err ->
                    eventTracker.logEvent(AppEventType.ERROR_OCCURRED, detail = "save_deck_failed")
                    _forgeState.value = ForgeUiState.Error(err.localizedMessage ?: "Failed to save deck.")
                }
            }
        }
    }

    fun resetForgeState() {
        _forgeState.value = ForgeUiState.Idle
        _selectedSnapshot.value = null
    }

    // --- MANUAL DECK & CARD MANAGEMENT ---

    fun loadSampleStudyDecks() {
        viewModelScope.launch {
            val result = studyRepository.loadSampleStudyDecks()
            result.onSuccess { totalCards ->
                _actionBanner.value = "Loaded 4 starter study decks ($totalCards cards) into offline cache."
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Unable to load sample decks."
            }
        }
    }

    fun createManualDeck(title: String, subject: String, description: String, accentIndex: Int) {
        viewModelScope.launch {
            val result = studyRepository.createDeckWithCards(
                title = title,
                subject = subject,
                description = description,
                sourceType = DeckSourceType.MANUAL,
                accentIndex = accentIndex,
                drafts = emptyList()
            )
            result.onSuccess {
                _actionBanner.value = "Created deck \"$title\"."
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Unable to create deck."
            }
        }
    }

    fun addManualCard(
        deckId: String,
        cardType: CardType,
        question: String,
        answer: String,
        explanation: String,
        hint: String,
        options: List<String>,
        correctIndex: Int
    ) {
        viewModelScope.launch {
            val draft = GeneratedCardDraft(
                localId = UUID.randomUUID().toString(),
                cardType = cardType,
                question = question,
                answer = answer,
                explanation = explanation,
                hint = hint,
                options = options,
                correctOptionIndex = correctIndex
            )
            val result = studyRepository.addCardsToExistingDeck(deckId, listOf(draft))
            result.onSuccess {
                _actionBanner.value = "Flashcard saved to deck."
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Unable to save card."
            }
        }
    }

    fun deleteDeck(deck: StudyDeck) {
        viewModelScope.launch {
            studyRepository.deleteDeck(deck)
                .onSuccess { _actionBanner.value = "Deleted \"${deck.title}\"." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Failed to delete deck." }
        }
    }

    fun deleteCard(card: Flashcard) {
        viewModelScope.launch {
            studyRepository.deleteCard(card)
                .onSuccess { _actionBanner.value = "Card removed." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Failed to remove card." }
        }
    }

    // --- ACTIVE RECALL STUDY SESSION ---

    fun startStudySession(deck: StudyDeck?, dueOnly: Boolean) {
        val now = System.currentTimeMillis()
        val sourceCards = if (deck != null) {
            allCards.value.filter { it.deckId == deck.id }
        } else {
            allCards.value
        }
        val filtered = if (dueOnly) {
            val due = sourceCards.filter { it.isDue(now) }
            if (due.isNotEmpty()) due else sourceCards
        } else {
            sourceCards
        }
        _studySession.value = StudySessionUiState(
            deckId = deck?.id ?: "all_due",
            deckTitle = deck?.title ?: "Daily Spaced Review",
            queue = filtered.sortedBy { it.dueAtMillis },
            currentIndex = 0
        )
    }

    fun selectMcqOption(index: Int) {
        _studySession.update { state ->
            if (state.selectedMcqOption != null) state
            else state.copy(selectedMcqOption = index, revealed = true)
        }
    }

    fun updateClozeInput(value: String) {
        _studySession.update { it.copy(clozeInput = value) }
    }

    fun checkClozeAnswer() {
        _studySession.update { it.copy(clozeChecked = true, revealed = true) }
    }

    fun revealCurrentCard() {
        _studySession.update { it.copy(revealed = true) }
    }

    fun toggleHint() {
        _studySession.update { it.copy(hintShown = !it.hintShown) }
    }

    fun rateCurrentCard(rating: ReviewRating) {
        val current = _studySession.value.currentCard ?: return
        val wasLastCard = (_studySession.value.currentIndex + 1) >= _studySession.value.queue.size
        val nextReviewed = _studySession.value.reviewedCount + 1
        val nextMastered = _studySession.value.masteredInSession +
            if (rating == ReviewRating.GOOD || rating == ReviewRating.EASY) 1 else 0
        viewModelScope.launch {
            studyRepository.recordCardReview(current, rating)
            eventTracker.logEvent(AppEventType.CARD_REVIEWED, detail = rating.name)
            if (wasLastCard && nextReviewed >= 2) {
                eventTracker.logEvent(
                    type = AppEventType.STUDY_SESSION_COMPLETED,
                    detail = _studySession.value.deckTitle,
                    value = nextMastered
                )
                if (nextMastered * 2 >= nextReviewed) {
                    maybeEvaluateAutoPopup(PopupTriggerMoment.STUDY_COMPLETED)
                }
            }
        }
        _studySession.update { state ->
            state.copy(
                currentIndex = state.currentIndex + 1,
                revealed = false,
                hintShown = false,
                selectedMcqOption = null,
                clozeInput = "",
                clozeChecked = false,
                reviewedCount = nextReviewed,
                masteredInSession = nextMastered
            )
        }
    }

    // --- GEMINI ORAL QUIZMASTER ---

    fun selectQuizDeck(deckId: String) {
        if (_quizState.value.selectedDeckId == deckId && _quizState.value.messages.isNotEmpty()) return
        _quizState.value = QuizmasterUiState(selectedDeckId = deckId)
    }

    fun startQuizSession(deckId: String, isOralMode: Boolean) {
        val deck = decks.value.firstOrNull { it.id == deckId } ?: return
        val deckCards = allCards.value.filter { it.deckId == deckId }
        if (deckCards.isEmpty()) {
            _quizState.value = QuizmasterUiState(
                selectedDeckId = deckId,
                errorMessage = "Add or forge flashcards in \"${deck.title}\" before starting an oral quiz."
            )
            return
        }
        _quizState.value = QuizmasterUiState(selectedDeckId = deckId)
        sendQuizMessage(
            prompt = "Start my active-recall quiz on \"${deck.title}\". Ask me the first high-yield question now!",
            isOralMode = isOralMode,
            hideUserBubble = true
        )
    }

    fun sendQuizMessage(prompt: String, isOralMode: Boolean, hideUserBubble: Boolean = false) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _quizState.value.isStreaming) return

        val deckId = _quizState.value.selectedDeckId ?: decks.value.firstOrNull()?.id
        val deck = decks.value.firstOrNull { it.id == deckId }
        if (deck == null) {
            _quizState.update { it.copy(errorMessage = "Select or forge a study deck first.") }
            return
        }
        val deckCards = allCards.value.filter { it.deckId == deck.id }
        val priorHistory = _quizState.value.messages.filter { !it.isStreaming }

        val userBubble = if (!hideUserBubble) {
            QuizMessage(id = UUID.randomUUID().toString(), role = "user", text = trimmed)
        } else {
            null
        }
        val aiBubbleId = UUID.randomUUID().toString()
        val placeholderAi = QuizMessage(
            id = aiBubbleId,
            role = "model",
            text = "",
            isStreaming = true
        )

        _quizState.update { state ->
            state.copy(
                selectedDeckId = deck.id,
                messages = state.messages + listOfNotNull(userBubble) + placeholderAi,
                isStreaming = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            var latestReply = ""
            try {
                geminiService.streamQuizmasterReply(
                    deckTitle = deck.title,
                    deckSubject = deck.subject,
                    cards = deckCards,
                    history = priorHistory,
                    userMessage = trimmed,
                    isOralMode = isOralMode
                ).collect { accumulated ->
                    latestReply = accumulated
                    _quizState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == aiBubbleId) msg.copy(text = accumulated, isStreaming = true)
                                else msg
                            }
                        )
                    }
                }
                _quizState.update { state ->
                    state.copy(
                        isStreaming = false,
                        lastCompletedAiReply = latestReply,
                        messages = state.messages.map { msg ->
                            if (msg.id == aiBubbleId) msg.copy(text = latestReply, isStreaming = false)
                            else msg
                        }
                    )
                }
                eventTracker.logEvent(AppEventType.QUIZ_TURN_COMPLETED, detail = deck.title)
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Quizmaster timed out", e)
                eventTracker.logEvent(AppEventType.ERROR_OCCURRED, detail = "quiz_timeout")
                failStreamingBubble(aiBubbleId, "Quizmaster timed out. Tap retry or check your connection.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Quizmaster streaming failed", e)
                eventTracker.logEvent(AppEventType.ERROR_OCCURRED, detail = "quiz_error")
                failStreamingBubble(aiBubbleId, e.localizedMessage ?: "Quizmaster unavailable right now.")
            }
        }
    }

    private fun failStreamingBubble(aiBubbleId: String, message: String) {
        _quizState.update { state ->
            state.copy(
                isStreaming = false,
                errorMessage = message,
                messages = state.messages.filterNot { it.id == aiBubbleId && it.text.isBlank() }
            )
        }
    }

    fun clearSpokenReply() {
        _quizState.update { it.copy(lastCompletedAiReply = null) }
    }

    fun resetQuizConversation() {
        _quizState.update {
            it.copy(messages = emptyList(), isStreaming = false, errorMessage = null, lastCompletedAiReply = null)
        }
    }

    // --- FLASHCARD FACT-CHECK & SEARCH GROUNDING (SESSION CACHED) ---

    fun verifyFlashcard(card: Flashcard, subject: String) {
        val cacheKey = "${card.id}|${card.question}|${card.answer}"
        val cached = factCheckCache[cacheKey]
        if (cached != null) {
            _factCheckState.value = FactCheckUiState.Ready(card = card, result = cached)
            return
        }

        viewModelScope.launch {
            _factCheckState.value = FactCheckUiState.Checking(card)
            try {
                val result = geminiService.verifyFlashcard(
                    question = card.question,
                    answer = card.answer,
                    subject = subject
                )
                factCheckCache[cacheKey] = result
                _factCheckState.value = FactCheckUiState.Ready(card = card, result = result)
            } catch (e: AiQuotaExceededException) {
                _factCheckState.value = FactCheckUiState.Error(
                    card = card,
                    message = e.message ?: "Daily free Fact-Check quota reached.",
                    isQuotaLimit = true
                )
            } catch (e: TimeoutCancellationException) {
                _factCheckState.value = FactCheckUiState.Error(
                    card = card,
                    message = "Fact-check timed out. Please try again."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fact-check failed for card ${card.id}", e)
                _factCheckState.value = FactCheckUiState.Error(
                    card = card,
                    message = e.localizedMessage ?: "Unable to verify this flashcard right now."
                )
            }
        }
    }

    fun applyFactCheckAnswer(card: Flashcard, improvedAnswer: String, improvedExplanation: String) {
        viewModelScope.launch {
            val updated = card.copy(
                answer = improvedAnswer.trim().ifBlank { card.answer }.take(1500),
                explanation = improvedExplanation.trim().ifBlank { card.explanation }.take(1500)
            )
            studyRepository.updateCardContent(updated)
                .onSuccess {
                    _factCheckState.value = FactCheckUiState.Idle
                    _actionBanner.value = "Updated flashcard with verified answer."
                }
                .onFailure { err ->
                    _actionBanner.value = err.localizedMessage ?: "Could not save verified answer."
                }
        }
    }

    fun dismissFactCheck() {
        _factCheckState.value = FactCheckUiState.Idle
    }

    // --- ZERO-DEV-COST BYOK & SMART USAGE PLAN CONTROLS ---

    fun testAndSaveByokKey(rawKey: String) {
        val cleaned = rawKey.trim()
        if (cleaned.isBlank()) {
            _byokKeyState.value = ByokKeyUiState.Error("Paste your Gemini API key first.")
            return
        }
        viewModelScope.launch {
            _byokKeyState.value = ByokKeyUiState.Verifying
            val verification = geminiService.verifyByokKey(cleaned)
            verification.onSuccess { statusText ->
                preferencesRepository.saveByokGeminiKey(cleaned)
                _byokKeyState.value = ByokKeyUiState.Saved(statusText)
                _actionBanner.value = "BYOK Unlimited connected securely on device."
            }.onFailure { err ->
                _byokKeyState.value = ByokKeyUiState.Error(
                    err.localizedMessage ?: "Could not validate this API key."
                )
            }
        }
    }

    fun removeByokKey() {
        viewModelScope.launch {
            preferencesRepository.clearByokGeminiKey()
            _byokKeyState.value = ByokKeyUiState.Idle
            _actionBanner.value = "Removed personal API key. Switched to Smart Free Plan."
        }
    }

    fun clearByokKeyStatus() {
        _byokKeyState.value = ByokKeyUiState.Idle
    }

    fun setPreferOfflineParser(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPreferOfflineParser(enabled)
        }
    }

    fun setEcoLiteMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setEcoLiteMode(enabled)
            _actionBanner.value = if (enabled) {
                "Eco Lite AI enabled — lightweight model stretches your free quota 3× further."
            } else {
                "Standard AI tier restored."
            }
        }
    }

    fun claimDailyGraceCredits() {
        viewModelScope.launch {
            val granted = preferencesRepository.claimDailyGraceCredits()
            _actionBanner.value = if (granted) {
                "+3 Free Grace Credits added for today! Continue right where you left off."
            } else {
                "Today's +3 Grace Credits were already claimed. Resets at midnight."
            }
            if (granted && (_forgeState.value as? ForgeUiState.Error)?.isQuotaLimit == true) {
                _forgeState.value = ForgeUiState.Idle
            }
        }
    }

    fun recordMatchBlitzScore(score: Int) {
        viewModelScope.launch {
            val prevBest = matchBlitzBestScore.value
            preferencesRepository.saveMatchBlitzBestScore(score)
            eventTracker.logEvent(AppEventType.MATCH_BLITZ_COMPLETED, detail = "score=$score", value = score)
            if (score >= 400 || (score > prevBest && score > 0)) {
                maybeEvaluateAutoPopup(PopupTriggerMoment.MATCH_BLITZ_HIGH_SCORE)
            }
        }
    }

    // --- AUTO-POPUP ELIGIBILITY ENGINE & EVENT TRACKING CONTROLS ---

    private suspend fun maybeEvaluateAutoPopup(moment: PopupTriggerMoment) {
        val state = eventTracker.getSnapshot()
        val nowMs = System.currentTimeMillis()
        val sessionAgeMs = (nowMs - sessionStartedAtMs).coerceAtLeast(0L)
        val suggestion = EngagementPromptEngine.evaluateBestPopup(
            state = state,
            moment = moment,
            sessionAgeMs = sessionAgeMs,
            promptShownThisSession = promptShownThisSession,
            currentVersionCode = BuildConfig.VERSION_CODE,
            nowMs = nowMs
        )
        if (suggestion != null) {
            promptShownThisSession = true
            eventTracker.recordPopupShown(suggestion.type, suggestion.milestoneCount, nowMs)
            _activePopupSuggestion.value = suggestion
        }
    }

    fun acceptPopupSuggestion(suggestion: AutoPopupSuggestion) {
        _activePopupSuggestion.value = null
        viewModelScope.launch {
            eventTracker.recordPopupAccepted(suggestion.type)
        }
    }

    fun dismissPopupSuggestionLater(suggestion: AutoPopupSuggestion) {
        _activePopupSuggestion.value = null
        viewModelScope.launch {
            eventTracker.recordPopupDismissed(suggestion.type)
        }
    }

    fun optOutPopupSuggestion(suggestion: AutoPopupSuggestion) {
        _activePopupSuggestion.value = null
        viewModelScope.launch {
            eventTracker.recordPopupOptOut(
                type = suggestion.type,
                skippedVersionCode = suggestion.targetVersionCode
            )
        }
    }

    // --- MULTI-CHANNEL DONATION CONTROLS ---

    fun prepareSupportScreen(source: String = "settings_support_screen") {
        donationBillingManager.clearMessages()
        donationBillingManager.startConnectionAndLoad()
        viewModelScope.launch {
            eventTracker.logEvent(AppEventType.DONATION_OPENED, detail = source)
        }
    }

    fun openDonationSheet(source: String, milestoneCount: Int? = null) {
        donationBillingManager.clearMessages()
        donationBillingManager.startConnectionAndLoad()
        _donationSheetMilestone.value = milestoneCount
        _donationSheetVisible.value = true
        viewModelScope.launch {
            eventTracker.logEvent(AppEventType.DONATION_OPENED, detail = source, value = milestoneCount ?: 0)
        }
    }

    fun dismissDonationSheet() {
        _donationSheetVisible.value = false
        _donationSheetMilestone.value = null
    }

    fun purchaseDonationViaGooglePlay(activity: Activity?, tier: DonationTier) {
        donationBillingManager.launchDonationPurchase(activity = activity, tier = tier)
    }

    fun restoreDonationPurchases() {
        donationBillingManager.restoreSupporterPurchases(silent = false)
    }

    fun recordExternalDonationAction(channel: String, detail: String) {
        viewModelScope.launch {
            eventTracker.logEvent(AppEventType.DONATION_OPENED, detail = "$channel:$detail")
        }
    }

    fun dismissHomeDonationBanner() {
        viewModelScope.launch {
            eventTracker.recordDonationBannerDismissed()
        }
    }

    fun optOutDonationReminders() {
        viewModelScope.launch {
            eventTracker.recordPopupOptOut(AutoPopupType.DONATE_SUPPORT)
            _actionBanner.value = "Donation reminders turned off. Support remains available anytime in Settings."
        }
    }

    fun manualCheckForUpdates() {
        viewModelScope.launch {
            eventTracker.logEvent(AppEventType.MANUAL_UPDATE_CHECK, detail = "v${BuildConfig.VERSION_NAME}")
            val state = eventTracker.getSnapshot()
            if (state.latestKnownVersionCode > BuildConfig.VERSION_CODE) {
                _activePopupSuggestion.value = AutoPopupSuggestion(
                    type = AutoPopupType.UPDATE_APP,
                    reason = "Manual update check",
                    targetVersionCode = state.latestKnownVersionCode,
                    targetVersionName = state.latestKnownVersionName.ifBlank { "v${state.latestKnownVersionCode}" }
                )
            } else {
                _actionBanner.value = "You are on the latest version (${BuildConfig.VERSION_NAME})."
            }
        }
    }

    fun logManualRate() {
        viewModelScope.launch {
            eventTracker.logEvent(AppEventType.MANUAL_RATE, detail = "settings")
            eventTracker.recordPopupAccepted(AutoPopupType.RATE_APP)
        }
    }

    fun logManualShare() {
        viewModelScope.launch {
            eventTracker.logEvent(AppEventType.MANUAL_SHARE, detail = "settings")
            eventTracker.recordPopupAccepted(AutoPopupType.SHARE_APP)
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    private fun observeNetworkOnline(context: Context) = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun isCurrentlyOnline(): Boolean {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        trySend(isCurrentlyOnline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(isCurrentlyOnline())
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }
}
