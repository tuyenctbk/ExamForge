package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.FlashcardAiService
import com.example.data.analytics.AppEventTracker
import com.example.data.analytics.EngagementPromptEngine
import com.example.data.firebase.authStateFlow
import com.example.data.firebase.configureAppCheck
import com.example.data.firebase.isFirebaseConfigured
import com.example.data.firebase.signOutUser
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.repository.FlashcardLocalRepository
import com.example.data.repository.GeminiStudyService
import com.example.data.repository.StudyRepository
import com.example.ui.components.DonationSheetDialog
import com.example.ui.components.EngagementPromptDialog
import com.example.ui.components.FactCheckDialog
import com.example.ui.components.TopBarSupporterAction
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CameraCaptureScreen
import com.example.ui.screens.DeckDetailScreen
import com.example.ui.screens.DecksScreen
import com.example.ui.screens.FlashcardListScreen
import com.example.ui.screens.ForgeScreen
import com.example.ui.screens.MatchBlitzGameScreen
import com.example.ui.screens.QuizmasterScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudyArenaScreen
import com.example.ui.screens.StudySession
import com.example.ui.screens.SupportDevelopmentScreen
import com.example.ui.theme.ExamForgeTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.util.rememberHasCameraHardware
import com.example.ui.util.rememberIsAndroidTv
import com.example.ui.viewmodel.ExamForgeViewModel
import com.example.ui.viewmodel.FlashcardViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check safely before UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        // STEP 2: Enable Edge-to-Edge display
        enableEdgeToEdge()

        setContent {
            val prefsRepo = remember { PreferencesRepository(applicationContext) }
            val themeMode by prefsRepo.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val oralAutoSpeak by prefsRepo.oralAutoSpeak.collectAsStateWithLifecycle(initialValue = true)

            ExamForgeTheme(themeMode = themeMode) {
                ExamForgeRoot(
                    hostActivity = this@MainActivity,
                    prefsRepo = prefsRepo,
                    themeMode = themeMode,
                    oralAutoSpeak = oralAutoSpeak
                )
            }
        }
    }
}

private enum class MainTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    DECKS(R.string.tab_decks, Icons.Filled.Style, Icons.Outlined.Style, "nav_decks"),
    FORGE(R.string.tab_forge, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "nav_forge"),
    QUIZMASTER(R.string.tab_quizmaster, Icons.Filled.RecordVoiceOver, Icons.Outlined.RecordVoiceOver, "nav_quizmaster"),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
}

private sealed interface SubScreen {
    data object None : SubScreen
    data class DeckDetail(val deckId: String) : SubScreen
    data class StudyArena(val returnToDeckId: String? = null) : SubScreen
    data class MatchBlitz(val deckId: String?, val deckTitle: String) : SubScreen
    data object SavedFlashcards : SubScreen
    data class StudySession(val deckId: String? = null) : SubScreen
    data object SupportDevelopment : SubScreen
    data class CameraCapture(
        val saveDirectToRoom: Boolean = false,
        val targetDeckId: String? = null
    ) : SubScreen
}

private val SubScreenSaver = listSaver<SubScreen, Any?>(
    save = { screen ->
        when (screen) {
            SubScreen.None -> listOf("none")
            is SubScreen.DeckDetail -> listOf("deck_detail", screen.deckId)
            is SubScreen.StudyArena -> listOf("study_arena", screen.returnToDeckId)
            is SubScreen.MatchBlitz -> listOf("match_blitz", screen.deckId, screen.deckTitle)
            SubScreen.SavedFlashcards -> listOf("saved_flashcards")
            is SubScreen.StudySession -> listOf("study_session", screen.deckId)
            SubScreen.SupportDevelopment -> listOf("support_dev")
            is SubScreen.CameraCapture -> listOf("camera_capture", screen.saveDirectToRoom, screen.targetDeckId)
        }
    },
    restore = { list ->
        when (list.firstOrNull() as? String) {
            "deck_detail" -> SubScreen.DeckDetail(list.getOrNull(1) as? String ?: "")
            "study_arena" -> SubScreen.StudyArena(returnToDeckId = list.getOrNull(1) as? String)
            "match_blitz" -> SubScreen.MatchBlitz(
                deckId = list.getOrNull(1) as? String,
                deckTitle = list.getOrNull(2) as? String ?: ""
            )
            "saved_flashcards" -> SubScreen.SavedFlashcards
            "study_session" -> SubScreen.StudySession(deckId = list.getOrNull(1) as? String)
            "support_dev" -> SubScreen.SupportDevelopment
            "camera_capture" -> SubScreen.CameraCapture(
                saveDirectToRoom = list.getOrNull(1) as? Boolean ?: false,
                targetDeckId = list.getOrNull(2) as? String
            )
            else -> SubScreen.None
        }
    }
)

@Composable
private fun ExamForgeRoot(
    hostActivity: Activity,
    prefsRepo: PreferencesRepository,
    themeMode: ThemeMode,
    oralAutoSpeak: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firebaseAvailable = remember(context) { isFirebaseConfigured(context) }
    val auth = remember(firebaseAvailable) {
        if (firebaseAvailable) runCatching { Firebase.auth }.getOrNull() else null
    }
    val authFlow = remember(auth) { auth?.authStateFlow() ?: flowOf<FirebaseUser?>(null) }
    val currentUser by authFlow.collectAsStateWithLifecycle(initialValue = auth?.currentUser)
    val isLocalGuestMode by prefsRepo.localGuestMode.collectAsStateWithLifecycle(initialValue = false)

    val user = currentUser
    if (user == null && !isLocalGuestMode) {
        AuthScreen(
            firebaseAvailable = firebaseAvailable,
            onAuthSuccess = {
                scope.launch { prefsRepo.setLocalGuestMode(false) }
            },
            onContinueOffline = {
                scope.launch { prefsRepo.setLocalGuestMode(true) }
            }
        )
    } else {
        val effectiveUserId = user?.uid ?: StudyRepository.LOCAL_GUEST_USER_ID
        val isCloudSignedIn = user != null
        key(effectiveUserId) {
            ExamForgeAppShell(
                hostActivity = hostActivity,
                currentUserId = effectiveUserId,
                isCloudSignedIn = isCloudSignedIn,
                userEmail = user?.email,
                userDisplayName = user?.displayName ?: if (isCloudSignedIn) null else stringResource(id = R.string.profile_local_student),
                prefsRepo = prefsRepo,
                themeMode = themeMode,
                oralAutoSpeak = oralAutoSpeak
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamForgeAppShell(
    hostActivity: Activity,
    currentUserId: String,
    isCloudSignedIn: Boolean,
    userEmail: String?,
    userDisplayName: String?,
    prefsRepo: PreferencesRepository,
    themeMode: ThemeMode,
    oralAutoSpeak: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val isTv = rememberIsAndroidTv()
    val hasCamera = rememberHasCameraHardware()

    val viewModel: ExamForgeViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val firestore = if (isFirebaseConfigured(app)) {
                    runCatching {
                        val databaseId = app.getString(R.string.firestore_database_id)
                        FirebaseFirestore.getInstance(databaseId)
                    }.getOrNull()
                } else {
                    null
                }
                val localDb = AppDatabase.getInstance(app)
                val repo = StudyRepository(
                    appContext = app,
                    db = firestore,
                    localDb = localDb,
                    ownerUserId = currentUserId,
                    preferencesRepository = prefsRepo
                )
                ExamForgeViewModel(
                    currentUserId = currentUserId,
                    initialDisplayName = userDisplayName ?: userEmail?.substringBefore("@") ?: app.getString(R.string.profile_default_student),
                    studyRepository = repo,
                    geminiService = GeminiStudyService(preferencesRepository = prefsRepo),
                    preferencesRepository = prefsRepo,
                    eventTracker = AppEventTracker(app),
                    appContext = app
                )
            }
        }
    )

    val flashcardViewModel: FlashcardViewModel = viewModel(
        key = "flashcards_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val firestore = if (isFirebaseConfigured(app)) {
                    runCatching {
                        val databaseId = app.getString(R.string.firestore_database_id)
                        FirebaseFirestore.getInstance(databaseId)
                    }.getOrNull()
                } else {
                    null
                }
                val localDb = AppDatabase.getInstance(app)
                val repo = StudyRepository(
                    appContext = app,
                    db = firestore,
                    localDb = localDb,
                    ownerUserId = currentUserId,
                    preferencesRepository = prefsRepo
                )
                FlashcardViewModel(
                    currentUserId = currentUserId,
                    localRepository = FlashcardLocalRepository(localDb, app),
                    flashcardAiService = FlashcardAiService(preferencesRepository = prefsRepo),
                    studyRepository = repo,
                    appContext = app
                )
            }
        }
    )

    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val decksLoaded by viewModel.decksLoaded.collectAsStateWithLifecycle()
    val allCards by viewModel.allCards.collectAsStateWithLifecycle()
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val forgeState by viewModel.forgeState.collectAsStateWithLifecycle()
    val selectedSnapshot by viewModel.selectedSnapshot.collectAsStateWithLifecycle()
    val quizState by viewModel.quizState.collectAsStateWithLifecycle()
    val studySession by viewModel.studySession.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val aiUsagePlan by viewModel.aiUsagePlan.collectAsStateWithLifecycle()
    val matchBlitzBestScore by viewModel.matchBlitzBestScore.collectAsStateWithLifecycle()
    val factCheckState by viewModel.factCheckState.collectAsStateWithLifecycle()
    val byokKeyState by viewModel.byokKeyState.collectAsStateWithLifecycle()
    val engagementTelemetry by viewModel.engagementTelemetry.collectAsStateWithLifecycle()
    val activePopupSuggestion by viewModel.activePopupSuggestion.collectAsStateWithLifecycle()
    val donationSheetVisible by viewModel.donationSheetVisible.collectAsStateWithLifecycle()
    val donationSheetMilestone by viewModel.donationSheetMilestone.collectAsStateWithLifecycle()
    val donationBillingState by viewModel.donationBillingState.collectAsStateWithLifecycle()

    val showHomeDonationBanner = remember(engagementTelemetry) {
        EngagementPromptEngine.shouldShowHomeDonationBanner(engagementTelemetry)
    }
    val showPostOpSupportCard = remember(engagementTelemetry) {
        EngagementPromptEngine.shouldShowPostOperationSupportCard(engagementTelemetry)
    }

    val flashcardStatusMessage by flashcardViewModel.statusMessage.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.DECKS) }
    var subScreen by rememberSaveable(stateSaver = SubScreenSaver) { mutableStateOf<SubScreen>(SubScreen.None) }

    LaunchedEffect(syncStatus.lastActionBanner) {
        val banner = syncStatus.lastActionBanner
        if (!banner.isNullOrBlank()) {
            snackbarHostState.showSnackbar(banner)
            viewModel.clearBanner()
        }
    }

    LaunchedEffect(flashcardStatusMessage) {
        val message = flashcardStatusMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            flashcardViewModel.clearStatusMessage()
        }
    }

    // Smart anti-spam Auto-Popup (Update / Rate / Share / Milestone Support) rendered at root
    EngagementPromptDialog(
        suggestion = activePopupSuggestion,
        onAccept = viewModel::acceptPopupSuggestion,
        onDismissLater = viewModel::dismissPopupSuggestionLater,
        onOptOut = viewModel::optOutPopupSuggestion,
        onOpenDonationSheet = { milestone ->
            viewModel.openDonationSheet(source = "auto_prompt", milestoneCount = milestone)
        }
    )

    // Multi-channel Donation Bottom Sheet (explicitly bound to live hostActivity for Google Play Billing)
    DonationSheetDialog(
        visible = donationSheetVisible,
        hostActivity = hostActivity,
        isActiveSupporter = engagementTelemetry.isActiveSupporter,
        milestoneCount = donationSheetMilestone,
        billingState = donationBillingState,
        onPurchaseGooglePlay = viewModel::purchaseDonationViaGooglePlay,
        onRestorePurchases = viewModel::restoreDonationPurchases,
        onExternalChannelUsed = viewModel::recordExternalDonationAction,
        onOptOutReminders = viewModel::optOutDonationReminders,
        onDismiss = viewModel::dismissDonationSheet
    )

    // Fact-check dialog rendered at root so it works seamlessly over DeckDetailScreen
    FactCheckDialog(
        state = factCheckState,
        onDismiss = viewModel::dismissFactCheck,
        onApplyAnswer = viewModel::applyFactCheckAnswer,
        onOpenAiSettings = {
            viewModel.dismissFactCheck()
            subScreen = SubScreen.None
            selectedTab = MainTab.SETTINGS
        }
    )

    val tvSafePaddingModifier = if (isTv) {
        Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
    } else {
        Modifier
    }

    AnimatedContent(
        targetState = subScreen,
        transitionSpec = {
            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
        },
        modifier = tvSafePaddingModifier,
        label = "subscreen_transition"
    ) { activeSub ->
        when (activeSub) {
            is SubScreen.StudyArena -> {
                StudyArenaScreen(
                    session = studySession,
                    onBack = {
                        subScreen = if (activeSub.returnToDeckId != null) {
                            SubScreen.DeckDetail(activeSub.returnToDeckId)
                        } else {
                            SubScreen.None
                        }
                    },
                    onSelectMcq = viewModel::selectMcqOption,
                    onUpdateCloze = viewModel::updateClozeInput,
                    onCheckCloze = viewModel::checkClozeAnswer,
                    onReveal = viewModel::revealCurrentCard,
                    onToggleHint = viewModel::toggleHint,
                    onRate = { viewModel.rateCurrentCard(it) },
                    showPostOpSupportCard = showPostOpSupportCard,
                    onOpenSupportSheet = { viewModel.openDonationSheet(source = "study_complete_card") },
                    onDismissSupportCard = viewModel::dismissHomeDonationBanner
                )
            }

            is SubScreen.MatchBlitz -> {
                val gameCards = remember(allCards, activeSub.deckId) {
                    if (activeSub.deckId == null) allCards else allCards.filter { it.deckId == activeSub.deckId }
                }
                MatchBlitzGameScreen(
                    deckTitle = activeSub.deckTitle,
                    cards = gameCards,
                    bestScore = matchBlitzBestScore,
                    onScoreRecorded = viewModel::recordMatchBlitzScore,
                    onBack = {
                        subScreen = if (activeSub.deckId != null) {
                            SubScreen.DeckDetail(activeSub.deckId)
                        } else {
                            SubScreen.None
                        }
                    }
                )
            }

            is SubScreen.DeckDetail -> {
                val deck = decks.firstOrNull { it.id == activeSub.deckId }
                if (deck == null) {
                    LaunchedEffect(decksLoaded, activeSub.deckId) {
                        if (decksLoaded) {
                            subScreen = SubScreen.None
                        }
                    }
                } else {
                    val deckCards = remember(allCards, deck.id) {
                        allCards.filter { it.deckId == deck.id }
                    }
                    DeckDetailScreen(
                        deck = deck,
                        cards = deckCards,
                        onBack = { subScreen = SubScreen.None },
                        onStartStudy = { dueOnly ->
                            viewModel.startStudySession(deck, dueOnly = dueOnly)
                            subScreen = SubScreen.StudyArena(returnToDeckId = deck.id)
                        },
                        onStartOralQuiz = {
                            viewModel.startQuizSession(deck.id, oralAutoSpeak)
                            subScreen = SubScreen.None
                            selectedTab = MainTab.QUIZMASTER
                        },
                        onStartMatchBlitz = {
                            subScreen = SubScreen.MatchBlitz(deckId = deck.id, deckTitle = deck.title)
                        },
                        onVerifyCard = { card ->
                            viewModel.verifyFlashcard(card = card, subject = deck.subject)
                        },
                        onAddManualCard = { type, q, a, exp, hint, opts, correctIdx ->
                            viewModel.addManualCard(deck.id, type, q, a, exp, hint, opts, correctIdx)
                        },
                        onEditCard = { cardId, q, a, exp, hint ->
                            flashcardViewModel.updateFlashcardById(cardId, q, a, exp, hint)
                        },
                        onSnapCardsForDeck = {
                            if (hasCamera) {
                                subScreen = SubScreen.CameraCapture(
                                    saveDirectToRoom = true,
                                    targetDeckId = deck.id
                                )
                            } else {
                                subScreen = SubScreen.None
                                selectedTab = MainTab.FORGE
                            }
                        },
                        onDeleteCard = viewModel::deleteCard,
                        onDeleteDeck = {
                            viewModel.deleteDeck(deck)
                            subScreen = SubScreen.None
                        }
                    )
                }
            }

            is SubScreen.SavedFlashcards -> {
                FlashcardListScreen(
                    viewModel = flashcardViewModel,
                    decks = decks,
                    onBack = { subScreen = SubScreen.None },
                    onTriggerCameraCapture = { targetDeckId ->
                        if (hasCamera) {
                            subScreen = SubScreen.CameraCapture(
                                saveDirectToRoom = true,
                                targetDeckId = targetDeckId
                            )
                        } else {
                            subScreen = SubScreen.None
                            selectedTab = MainTab.FORGE
                        }
                    },
                    onStartStudySession = { deckId ->
                        subScreen = SubScreen.StudySession(deckId = deckId)
                    }
                )
            }

            is SubScreen.StudySession -> {
                StudySession(
                    viewModel = flashcardViewModel,
                    decks = decks,
                    deckId = activeSub.deckId,
                    onBack = { subScreen = SubScreen.SavedFlashcards }
                )
            }

            is SubScreen.CameraCapture -> {
                CameraCaptureScreen(
                    onBack = {
                        subScreen = if (activeSub.saveDirectToRoom) {
                            if (activeSub.targetDeckId != null) {
                                SubScreen.DeckDetail(activeSub.targetDeckId)
                            } else {
                                SubScreen.SavedFlashcards
                            }
                        } else {
                            SubScreen.None
                        }
                    },
                    onUsePhotoForForge = { bitmap ->
                        viewModel.setSelectedSnapshot(bitmap)
                        selectedTab = MainTab.FORGE
                        subScreen = SubScreen.None
                    },
                    onAnalyzePhotoNow = { bitmap ->
                        if (activeSub.saveDirectToRoom) {
                            flashcardViewModel.addFlashcardsFromCameraCapture(
                                bitmap = bitmap,
                                targetDeckId = activeSub.targetDeckId
                            )
                            subScreen = if (activeSub.targetDeckId != null) {
                                SubScreen.DeckDetail(activeSub.targetDeckId)
                            } else {
                                SubScreen.SavedFlashcards
                            }
                        } else {
                            viewModel.setSelectedSnapshot(bitmap)
                            viewModel.forgeFlashcards(
                                bitmap = bitmap,
                                rawNotes = "",
                                targetCount = 6,
                                subjectHint = ""
                            )
                            selectedTab = MainTab.FORGE
                            subScreen = SubScreen.None
                        }
                    }
                )
            }

            is SubScreen.SupportDevelopment -> {
                SupportDevelopmentScreen(
                    hostActivity = hostActivity,
                    isActiveSupporter = engagementTelemetry.isActiveSupporter,
                    billingState = donationBillingState,
                    onPurchaseGooglePlay = viewModel::purchaseDonationViaGooglePlay,
                    onRestorePurchases = viewModel::restoreDonationPurchases,
                    onExternalChannelUsed = viewModel::recordExternalDonationAction,
                    onBack = { subScreen = SubScreen.None }
                )
            }

            SubScreen.None -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useNavRail = maxWidth >= 600.dp || isTv

                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                                                    contentDescription = stringResource(id = R.string.cd_app_logo),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(id = R.string.app_name),
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                    }
                                },
                                actions = {
                                    TopBarSupporterAction(
                                        isActiveSupporter = engagementTelemetry.isActiveSupporter,
                                        onOpenSupport = { viewModel.openDonationSheet(source = "top_bar") }
                                    )
                                    IconButton(
                                        onClick = {
                                            val nextMode = if (themeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
                                            scope.launch { prefsRepo.setThemeMode(nextMode) }
                                        },
                                        modifier = Modifier.testTag("top_bar_theme_toggle")
                                    ) {
                                        Icon(
                                            imageVector = if (themeMode == ThemeMode.LIGHT) Icons.Default.DarkMode else Icons.Default.LightMode,
                                            contentDescription = stringResource(id = R.string.cd_toggle_theme),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background
                                )
                            )
                        },
                        bottomBar = {
                            if (!useNavRail) {
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    MainTab.entries.forEach { tab ->
                                        val selected = selectedTab == tab
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { selectedTab = tab },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = stringResource(id = tab.labelRes)
                                                )
                                            },
                                            label = { Text(stringResource(id = tab.labelRes)) },
                                            modifier = Modifier.testTag(tab.testTag)
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (useNavRail) {
                                NavigationRail(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxHeight()
                                ) {
                                    Spacer(modifier = Modifier.size(8.dp))
                                    MainTab.entries.forEach { tab ->
                                        val selected = selectedTab == tab
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { selectedTab = tab },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = stringResource(id = tab.labelRes)
                                                )
                                            },
                                            label = { Text(stringResource(id = tab.labelRes)) },
                                            modifier = Modifier.testTag(tab.testTag)
                                        )
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                AnimatedContent(
                                    targetState = selectedTab,
                                    transitionSpec = {
                                        val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 16 * dir })
                                            .togetherWith(fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 16 * dir })
                                    },
                                    label = "main_tab_transition"
                                ) { tab ->
                                    when (tab) {
                                        MainTab.DECKS -> {
                                            DecksScreen(
                                                decks = decks,
                                                allCards = allCards,
                                                profile = profile,
                                                syncStatus = syncStatus,
                                                isCloudSignedIn = isCloudSignedIn,
                                                matchBlitzBestScore = matchBlitzBestScore,
                                                onOpenForge = {
                                                    if (hasCamera) {
                                                        subScreen = SubScreen.CameraCapture()
                                                    } else {
                                                        selectedTab = MainTab.FORGE
                                                    }
                                                },
                                                onOpenSavedFlashcards = { subScreen = SubScreen.SavedFlashcards },
                                                onOpenMatchBlitzAll = {
                                                    subScreen = SubScreen.MatchBlitz(
                                                        deckId = null,
                                                        deckTitle = context.getString(R.string.match_blitz_all_decks)
                                                    )
                                                },
                                                onOpenDeckDetail = { deck -> subScreen = SubScreen.DeckDetail(deck.id) },
                                                onQuickStudyAllDue = {
                                                    viewModel.startStudySession(deck = null, dueOnly = true)
                                                    subScreen = SubScreen.StudyArena(returnToDeckId = null)
                                                },
                                                onQuickStudyDeck = { deck ->
                                                    viewModel.startStudySession(deck = deck, dueOnly = true)
                                                    subScreen = SubScreen.StudyArena(returnToDeckId = deck.id)
                                                },
                                                onStartOralQuizDeck = { deck ->
                                                    viewModel.startQuizSession(deck.id, oralAutoSpeak)
                                                    selectedTab = MainTab.QUIZMASTER
                                                },
                                                onCreateManualDeck = viewModel::createManualDeck,
                                                showHomeDonationBanner = showHomeDonationBanner,
                                                completedTasksCount = engagementTelemetry.successfulCoreOps,
                                                onOpenSupportSheet = { viewModel.openDonationSheet(source = "home_banner") },
                                                onDismissHomeDonationBanner = viewModel::dismissHomeDonationBanner
                                            )
                                        }

                                        MainTab.FORGE -> {
                                            ForgeScreen(
                                                forgeState = forgeState,
                                                selectedSnapshot = selectedSnapshot,
                                                existingDecks = decks,
                                                aiUsagePlan = aiUsagePlan,
                                                onSnapshotSelected = viewModel::setSelectedSnapshot,
                                                onOpenCameraCapture = { subScreen = SubScreen.CameraCapture() },
                                                onForgeRequested = { bmp, notes, count, hint ->
                                                    viewModel.forgeFlashcards(bmp, notes, count, hint)
                                                },
                                                onForgeOfflineRequested = { notes, count, hint ->
                                                    viewModel.forgeFlashcards(
                                                        bitmap = null,
                                                        rawNotes = notes,
                                                        targetCount = count,
                                                        subjectHint = hint,
                                                        forceOffline = true
                                                    )
                                                },
                                                onForgeEcoLiteRequested = { bmp, notes, count, hint ->
                                                    viewModel.forgeFlashcards(
                                                        bitmap = bmp,
                                                        rawNotes = notes,
                                                        targetCount = count,
                                                        subjectHint = hint,
                                                        forceEcoLite = true
                                                    )
                                                },
                                                onEnableEcoLite = { viewModel.setEcoLiteMode(true) },
                                                onClaimGraceCredits = viewModel::claimDailyGraceCredits,
                                                onOpenByokSettings = { selectedTab = MainTab.SETTINGS },
                                                onUpdateDraftMeta = viewModel::updateDraftMetadata,
                                                onRemoveDraftCard = viewModel::removeDraftCard,
                                                onSaveDraft = viewModel::saveForgedDeck,
                                                onResetForge = viewModel::resetForgeState,
                                                onOpenSavedDeck = { deckId ->
                                                    selectedTab = MainTab.DECKS
                                                    subScreen = SubScreen.DeckDetail(deckId)
                                                },
                                                showPostOpSupportCard = showPostOpSupportCard,
                                                onOpenSupportSheet = { viewModel.openDonationSheet(source = "forge_complete_card") },
                                                onDismissSupportCard = viewModel::dismissHomeDonationBanner
                                            )
                                        }

                                        MainTab.QUIZMASTER -> {
                                            QuizmasterScreen(
                                                decks = decks,
                                                quizState = quizState,
                                                oralAutoSpeak = oralAutoSpeak,
                                                aiUsagePlan = aiUsagePlan,
                                                onToggleOralAutoSpeak = { enabled ->
                                                    scope.launch { prefsRepo.setOralAutoSpeak(enabled) }
                                                },
                                                onSelectDeck = viewModel::selectQuizDeck,
                                                onStartSession = viewModel::startQuizSession,
                                                onSendMessage = { text, oral -> viewModel.sendQuizMessage(text, oral) },
                                                onClearSpokenReply = viewModel::clearSpokenReply,
                                                onResetConversation = viewModel::resetQuizConversation,
                                                onEnableEcoLite = { viewModel.setEcoLiteMode(true) },
                                                onClaimGraceCredits = viewModel::claimDailyGraceCredits,
                                                onOpenByokSettings = { selectedTab = MainTab.SETTINGS },
                                                onOpenForge = { selectedTab = MainTab.FORGE }
                                            )
                                        }

                                        MainTab.SETTINGS -> {
                                            SettingsScreen(
                                                isCloudSignedIn = isCloudSignedIn,
                                                userEmail = userEmail,
                                                userDisplayName = userDisplayName,
                                                profile = profile,
                                                themeMode = themeMode,
                                                oralAutoSpeak = oralAutoSpeak,
                                                syncStatus = syncStatus,
                                                totalCachedCards = allCards.size,
                                                previewWidgetCard = allCards.firstOrNull(),
                                                aiUsagePlan = aiUsagePlan,
                                                byokKeyState = byokKeyState,
                                                onTestAndSaveByokKey = viewModel::testAndSaveByokKey,
                                                onRemoveByokKey = viewModel::removeByokKey,
                                                onPreferOfflineParserChange = viewModel::setPreferOfflineParser,
                                                onEcoLiteModeChange = viewModel::setEcoLiteMode,
                                                onClaimGraceCredits = viewModel::claimDailyGraceCredits,
                                                engagementTelemetry = engagementTelemetry,
                                                onCheckForUpdate = viewModel::manualCheckForUpdates,
                                                onManualRate = viewModel::logManualRate,
                                                onManualShare = viewModel::logManualShare,
                                                onOpenSupportSheet = {
                                                    viewModel.prepareSupportScreen("settings_support_screen")
                                                    subScreen = SubScreen.SupportDevelopment
                                                },
                                                onThemeModeChange = { mode ->
                                                    scope.launch { prefsRepo.setThemeMode(mode) }
                                                },
                                                onOralAutoSpeakChange = { enabled ->
                                                    scope.launch { prefsRepo.setOralAutoSpeak(enabled) }
                                                },
                                                onSyncNow = viewModel::manualSyncPendingReviews,
                                                onSignOut = {
                                                    signOutUser(
                                                        credentialManager = credentialManager,
                                                        scope = scope,
                                                        beforeSignOut = {
                                                            prefsRepo.setActiveUserId(null)
                                                            prefsRepo.setLocalGuestMode(false)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
