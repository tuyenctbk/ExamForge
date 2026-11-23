package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.Flashcard
import com.example.data.local.toDomain
import com.example.data.model.CardType
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import com.example.data.model.StudyDeck
import com.example.ui.viewmodel.CameraCaptureImportState
import com.example.ui.viewmodel.FlashcardListUiState
import com.example.ui.viewmodel.FlashcardReviewFlag
import com.example.ui.viewmodel.FlashcardViewModel
import com.example.ui.viewmodel.ReviewNowSessionUiState
import com.example.ui.viewmodel.ReviewUrgency
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Composable screen that directly observes [FlashcardViewModel], lists saved Room [Flashcard]
 * entities in a [LazyColumn] showing each card's question, answer, and study metadata, and
 * launches either the full-screen [StudySession] or CameraX capture workflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardListScreen(
    viewModel: FlashcardViewModel,
    decks: List<StudyDeck>,
    onBack: () -> Unit,
    onTriggerCameraCapture: (targetDeckId: String?) -> Unit,
    onStartStudySession: ((targetDeckId: String?) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraCaptureState by viewModel.cameraCaptureState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedDeckId by viewModel.selectedDeckId.collectAsStateWithLifecycle()
    val reviewNowOnly by viewModel.reviewNowOnly.collectAsStateWithLifecycle()
    val reviewNowQueue by viewModel.reviewNowQueue.collectAsStateWithLifecycle()
    val reviewNowSession by viewModel.reviewNowSession.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshReviewSchedule()
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<Flashcard?>(null) }

    val deckTitleById = remember(decks) { decks.associate { it.id to it.title } }
    val savedCardsFallback = stringResource(id = R.string.flashcard_saved_cards_fallback)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(id = R.string.flashcards_screen_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        val subtitle = when (val state = uiState) {
                            is FlashcardListUiState.Success ->
                                stringResource(
                                    id = R.string.flashcard_saved_subtitle,
                                    state.totalCount,
                                    state.averageStudyProgress
                                )
                            is FlashcardListUiState.Loading ->
                                stringResource(id = R.string.flashcard_loading_db)
                            is FlashcardListUiState.Error ->
                                stringResource(id = R.string.flashcard_offline_cache_unavailable)
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("flashcard_list_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.cd_navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.testTag("manual_add_card_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(id = R.string.cd_flashcard_add_manual),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (com.example.ui.util.rememberHasCameraHardware()) {
                ExtendedFloatingActionButton(
                    onClick = { onTriggerCameraCapture(selectedDeckId) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = stringResource(id = R.string.cd_flashcard_camera_fab)
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(id = R.string.flashcards_fab_scan),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("camera_capture_fab")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
                    .testTag("flashcard_lazy_column"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (cameraCaptureState !is CameraCaptureImportState.Idle) {
                    item(key = "camera_import_banner") {
                        CameraImportStatusBanner(
                            state = cameraCaptureState,
                            onDismiss = viewModel::clearCameraCaptureState
                        )
                    }
                }

                if (uiState is FlashcardListUiState.Success) {
                    val success = uiState as FlashcardListUiState.Success
                    item(key = "summary_header") {
                        FlashcardStatsBanner(
                            totalCount = success.totalCount,
                            reviewNowCount = success.reviewNowCount,
                            masteredCount = success.masteredCount,
                            averageProgress = success.averageStudyProgress
                        )
                    }
                    item(key = "review_now_launcher") {
                        ReviewNowLauncherBanner(
                            scopedDueCount = reviewNowQueue.size,
                            totalDueCount = success.reviewNowCount,
                            isDeckScoped = !selectedDeckId.isNullOrBlank(),
                            onStartReviewNow = {
                                if (onStartStudySession != null) {
                                    onStartStudySession(selectedDeckId)
                                } else {
                                    viewModel.startReviewNowCycle(selectedDeckId)
                                }
                            }
                        )
                    }
                }

                item(key = "search_bar") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        placeholder = { Text(stringResource(id = R.string.flashcards_search_placeholder)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(id = R.string.cd_clear_search)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("flashcards_search_input")
                    )
                }

                item(key = "deck_filters") {
                    val dueChipCount = if (selectedDeckId != null) {
                        reviewNowQueue.size
                    } else {
                        (uiState as? FlashcardListUiState.Success)?.reviewNowCount ?: reviewNowQueue.size
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = reviewNowOnly,
                                onClick = viewModel::toggleReviewNowFilter,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text(stringResource(id = R.string.review_now_chip, dueChipCount)) },
                                modifier = Modifier.testTag("filter_review_now_chip")
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedDeckId == null,
                                onClick = { viewModel.setDeckFilter(null) },
                                label = { Text(stringResource(id = R.string.review_now_all_decks)) }
                            )
                        }
                        items(decks, key = { it.id }) { deck ->
                            FilterChip(
                                selected = selectedDeckId == deck.id,
                                onClick = { viewModel.setDeckFilter(deck.id) },
                                label = {
                                    Text(
                                        text = deck.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 180.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                when (val state = uiState) {
                    is FlashcardListUiState.Loading -> {
                        item(key = "loading_state") {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp)
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    is FlashcardListUiState.Error -> {
                        item(key = "error_state") {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }

                    is FlashcardListUiState.Success -> {
                        if (state.flashcards.isEmpty()) {
                            item(key = "empty_state") {
                                EmptyFlashcardsState(
                                    isFiltered = searchQuery.isNotBlank() || selectedDeckId != null || reviewNowOnly,
                                    isReviewNowFiltered = reviewNowOnly && state.totalCount > 0,
                                    onShowAllCards = { viewModel.setReviewNowOnly(false) },
                                    onScanNotes = { onTriggerCameraCapture(selectedDeckId) },
                                    onAddManual = { showCreateDialog = true }
                                )
                            }
                        } else {
                            items(state.flashcards, key = { it.id }) { card ->
                                FlashcardListItemCard(
                                    card = card,
                                    deckTitle = deckTitleById[card.deckId] ?: savedCardsFallback,
                                    reviewFlag = state.reviewFlags[card.id],
                                    onEdit = { editingCard = card },
                                    onMarkReviewed = { viewModel.recordFlashcardReview(card, ReviewRating.GOOD) },
                                    onDelete = { viewModel.deleteFlashcard(card) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        RoomFlashcardEditorDialog(
            title = stringResource(id = R.string.flashcard_new_title),
            initialQuestion = "",
            initialAnswer = "",
            initialExplanation = "",
            initialHint = "",
            confirmLabel = stringResource(id = R.string.flashcard_save),
            onDismiss = { showCreateDialog = false },
            onConfirm = { q, a, exp, hint ->
                showCreateDialog = false
                viewModel.addFlashcard(
                    deckId = selectedDeckId ?: decks.firstOrNull()?.id,
                    question = q,
                    answer = a,
                    explanation = exp,
                    hint = hint
                )
            }
        )
    }

    editingCard?.let { card ->
        RoomFlashcardEditorDialog(
            title = stringResource(id = R.string.flashcard_edit_title),
            initialQuestion = card.question,
            initialAnswer = card.answer,
            initialExplanation = card.explanation,
            initialHint = card.hint,
            confirmLabel = stringResource(id = R.string.flashcard_update),
            onDismiss = { editingCard = null },
            onConfirm = { q, a, exp, hint ->
                editingCard = null
                viewModel.updateFlashcard(
                    card = card,
                    question = q,
                    answer = a,
                    explanation = exp,
                    hint = hint
                )
            }
        )
    }

    if (reviewNowSession.isActive && onStartStudySession == null) {
        ReviewNowCycleDialog(
            session = reviewNowSession,
            deckTitleById = deckTitleById,
            onRevealAnswer = viewModel::revealReviewNowAnswer,
            onToggleHint = viewModel::toggleReviewNowHint,
            onPreviousCard = { viewModel.cyclePreviousReviewCard() },
            onNextCard = { viewModel.cycleNextReviewCard() },
            onRateCard = { rating -> viewModel.submitReviewNowRating(rating) },
            onClose = viewModel::closeReviewNowCycle
        )
    }
}

@Composable
private fun ReviewNowLauncherBanner(
    scopedDueCount: Int,
    totalDueCount: Int,
    isDeckScoped: Boolean,
    onStartReviewNow: () -> Unit
) {
    val activeCount = if (isDeckScoped) scopedDueCount else totalDueCount
    val hasDue = activeCount > 0

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (hasDue) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (hasDue) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_now_launcher_banner")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = if (hasDue) Icons.Default.Schedule else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (hasDue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasDue) {
                        stringResource(id = R.string.review_now_banner_due_title, activeCount)
                    } else {
                        stringResource(id = R.string.review_now_banner_caught_up_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (hasDue) {
                        stringResource(id = R.string.review_now_banner_due_desc)
                    } else {
                        stringResource(id = R.string.review_now_banner_caught_up_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hasDue) {
                Button(
                    onClick = onStartReviewNow,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("start_review_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(id = R.string.review_now_label),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun FlashcardStatsBanner(
    totalCount: Int,
    reviewNowCount: Int,
    masteredCount: Int,
    averageProgress: Int
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatColumn(label = stringResource(id = R.string.review_now_stat_saved), value = "$totalCount")
            StatColumn(label = stringResource(id = R.string.review_now_label), value = "$reviewNowCount")
            StatColumn(label = stringResource(id = R.string.review_now_stat_mastered), value = "$masteredCount")
            StatColumn(label = stringResource(id = R.string.review_now_stat_mastery), value = "$averageProgress%")
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FlashcardListItemCard(
    card: Flashcard,
    deckTitle: String,
    reviewFlag: FlashcardReviewFlag?,
    onEdit: () -> Unit,
    onMarkReviewed: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardType = remember(card.cardType) { CardType.fromRaw(card.cardType) }
    val lastReviewText = formatReviewTimestamp(card.lastReviewDate)
    var expanded by remember(card.id) { mutableStateOf(false) }
    val hasDetails = card.explanation.isNotBlank() || card.hint.isNotBlank()
    val isFlaggedReviewNow = reviewFlag?.isReviewNow == true

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (isFlaggedReviewNow) 1.5.dp else 1.dp,
            color = if (isFlaggedReviewNow) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = hasDetails) { expanded = !expanded }
            .testTag("flashcard_item_${card.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = cardType.shortBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (reviewFlag != null) {
                        val badgeColor = when {
                            reviewFlag.urgency == ReviewUrgency.CRITICAL_LAPSE ->
                                MaterialTheme.colorScheme.errorContainer
                            reviewFlag.isReviewNow ->
                                MaterialTheme.colorScheme.primaryContainer
                            else ->
                                MaterialTheme.colorScheme.secondaryContainer
                        }
                        val badgeTextColor = when {
                            reviewFlag.urgency == ReviewUrgency.CRITICAL_LAPSE ->
                                MaterialTheme.colorScheme.onErrorContainer
                            reviewFlag.isReviewNow ->
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else ->
                                MaterialTheme.colorScheme.onSecondaryContainer
                        }
                        Surface(
                            color = badgeColor,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("review_flag_badge_${card.id}")
                        ) {
                            Text(
                                text = localizedReviewFlagLabel(reviewFlag),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (reviewFlag.isReviewNow) FontWeight.Bold else FontWeight.Medium,
                                color = badgeTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    } else {
                        Text(
                            text = deckTitle.take(24),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_flashcard_${card.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(id = R.string.cd_flashcard_edit),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_flashcard_${card.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(id = R.string.cd_flashcard_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.flashcard_q_badge),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = card.question,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.flashcard_a_badge),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = card.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && hasDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    if (card.explanation.isNotBlank()) {
                        Text(
                            text = stringResource(id = R.string.flashcard_explanation_prefix, card.explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (card.hint.isNotBlank()) {
                        Text(
                            text = stringResource(id = R.string.flashcard_hint_prefix, card.hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.flashcard_progress_pct, card.studyProgress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = lastReviewText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            val animatedStudyProgress by animateFloatAsState(
                targetValue = card.studyProgress.coerceIn(0, 100) / 100f,
                label = "flashcard_item_progress"
            )
            LinearProgressIndicator(
                progress = { animatedStudyProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasDetails) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (expanded) {
                                stringResource(id = R.string.flashcard_hide_notes)
                            } else {
                                stringResource(id = R.string.flashcard_explanation)
                            }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                TextButton(
                    onClick = onMarkReviewed,
                    modifier = Modifier.testTag("quick_review_flashcard_${card.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(id = R.string.review_now_mark_reviewed))
                }
            }
        }
    }
}

@Composable
private fun CameraImportStatusBanner(
    state: CameraCaptureImportState,
    onDismiss: () -> Unit
) {
    val containerColor = when (state) {
        is CameraCaptureImportState.Analyzing -> MaterialTheme.colorScheme.primaryContainer
        is CameraCaptureImportState.Success -> MaterialTheme.colorScheme.secondaryContainer
        is CameraCaptureImportState.Error -> MaterialTheme.colorScheme.errorContainer
        CameraCaptureImportState.Idle -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when (state) {
        is CameraCaptureImportState.Analyzing -> MaterialTheme.colorScheme.onPrimaryContainer
        is CameraCaptureImportState.Success -> MaterialTheme.colorScheme.onSecondaryContainer
        is CameraCaptureImportState.Error -> MaterialTheme.colorScheme.onErrorContainer
        CameraCaptureImportState.Idle -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            when (state) {
                is CameraCaptureImportState.Analyzing -> {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.flashcard_camera_parsing),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                }

                is CameraCaptureImportState.Success -> {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.flashcard_camera_added, state.addedCount, state.deckTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.cd_dismiss),
                            tint = contentColor
                        )
                    }
                }

                is CameraCaptureImportState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.cd_dismiss),
                            tint = contentColor
                        )
                    }
                }

                CameraCaptureImportState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun EmptyFlashcardsState(
    isFiltered: Boolean,
    isReviewNowFiltered: Boolean = false,
    onShowAllCards: () -> Unit = {},
    onScanNotes: () -> Unit,
    onAddManual: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isReviewNowFiltered) Icons.Default.CheckCircle else Icons.Default.Style,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = when {
                    isReviewNowFiltered -> stringResource(id = R.string.flashcard_review_now_empty_title)
                    isFiltered -> stringResource(id = R.string.flashcard_no_matching)
                    else -> stringResource(id = R.string.flashcards_empty_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isReviewNowFiltered) {
                    stringResource(id = R.string.flashcard_review_now_empty_body)
                } else {
                    stringResource(id = R.string.flashcards_empty_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            if (isReviewNowFiltered) {
                Button(
                    onClick = onShowAllCards,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("empty_show_all_cards_button")
                ) {
                    Text(stringResource(id = R.string.review_now_show_all))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onScanNotes,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("empty_scan_notes_button")
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.flashcards_fab_scan))
                    }
                    OutlinedButton(
                        onClick = onAddManual,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(id = R.string.flashcard_add_card))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RoomFlashcardEditorDialog(
    title: String,
    initialQuestion: String,
    initialAnswer: String,
    initialExplanation: String,
    initialHint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (question: String, answer: String, explanation: String, hint: String) -> Unit
) {
    var question by remember { mutableStateOf(initialQuestion) }
    var answer by remember { mutableStateOf(initialAnswer) }
    var explanation by remember { mutableStateOf(initialExplanation) }
    var hint by remember { mutableStateOf(initialHint) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { if (it.length <= 1000) question = it },
                    label = { Text(stringResource(id = R.string.flashcard_question_label)) },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editor_question_input")
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { if (it.length <= 1500) answer = it },
                    label = { Text(stringResource(id = R.string.flashcard_answer_label)) },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editor_answer_input")
                )
                OutlinedTextField(
                    value = explanation,
                    onValueChange = { if (it.length <= 1500) explanation = it },
                    label = { Text(stringResource(id = R.string.flashcard_explanation_opt)) },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { if (it.length <= 300) hint = it },
                    label = { Text(stringResource(id = R.string.flashcard_hint_opt)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(question.trim(), answer.trim(), explanation.trim(), hint.trim()) },
                enabled = question.isNotBlank() && answer.isNotBlank(),
                modifier = Modifier.testTag("editor_confirm_button")
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun ReviewNowCycleDialog(
    session: ReviewNowSessionUiState,
    deckTitleById: Map<String, String>,
    onRevealAnswer: () -> Unit,
    onToggleHint: () -> Unit,
    onPreviousCard: () -> Unit,
    onNextCard: () -> Unit,
    onRateCard: (ReviewRating) -> Unit,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(onBack = onClose)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .testTag("review_now_cycle_dialog")
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = stringResource(id = R.string.review_now_dialog_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (session.isCompleted) {
                                    stringResource(id = R.string.review_now_dialog_reinforced_count, session.reviewedInCycle)
                                } else {
                                    stringResource(
                                        id = R.string.review_now_dialog_progress,
                                        session.currentIndex + 1,
                                        session.queue.size,
                                        session.reviewedInCycle
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("review_now_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(id = R.string.review_now_dialog_close_cd)
                            )
                        }
                    }

                    val total = session.totalFlaggedAtStart.coerceAtLeast(1)
                    val progressFraction = if (session.isCompleted) {
                        1f
                    } else {
                        ((total - session.queue.size).coerceAtLeast(0).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    val activeCard = session.currentCard
                    if (session.isCompleted || activeCard == null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.review_now_dialog_done_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(id = R.string.review_now_dialog_done_body, session.reviewedInCycle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = onClose,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("review_now_done_button")
                            ) {
                                Text(
                                    text = stringResource(id = R.string.review_now_dialog_done_btn),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        val deckName = deckTitleById[activeCard.deckId]
                            ?: stringResource(id = R.string.flashcard_saved_cards_fallback)
                        val flag = session.currentFlag
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (flag != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = localizedReviewFlagLabel(flag),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(
                                text = "$deckName · ${formatReviewTimestamp(activeCard.lastReviewDate)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = activeCard.question,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (activeCard.hint.isNotBlank() && !session.answerRevealed) {
                                    TextButton(
                                        onClick = onToggleHint,
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Lightbulb,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (session.hintShown) {
                                                stringResource(id = R.string.study_session_hide_hint)
                                            } else {
                                                stringResource(id = R.string.study_arena_show_hint)
                                            }
                                        )
                                    }
                                    AnimatedVisibility(visible = session.hintShown) {
                                        Text(
                                            text = stringResource(id = R.string.flashcard_hint_prefix, activeCard.hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = session.answerRevealed,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = activeCard.answer,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                        if (activeCard.explanation.isNotBlank()) {
                                            Text(
                                                text = activeCard.explanation,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!session.answerRevealed) {
                            Button(
                                onClick = onRevealAnswer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("review_now_reveal_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(id = R.string.study_arena_reveal_answer),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            val domainCard = remember(activeCard) { activeCard.toDomain() }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ReviewRating.entries.forEach { rating ->
                                    val intervalText = remember(domainCard, rating) {
                                        SpacedRepetitionEngine.previewNextIntervalLabel(domainCard, rating)
                                    }
                                    FilledTonalButton(
                                        onClick = { onRateCard(rating) },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("review_now_rate_${rating.name.lowercase()}")
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = localizedRatingLabel(rating),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = intervalText,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (session.queue.size > 1) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = onPreviousCard,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("review_now_prev_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(id = R.string.review_now_dialog_prev))
                                }
                                OutlinedButton(
                                    onClick = onNextCard,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("review_now_next_button")
                                ) {
                                    Text(stringResource(id = R.string.review_now_dialog_next))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
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

@Composable
private fun formatReviewTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return stringResource(id = R.string.flashcard_not_reviewed_yet)
    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return stringResource(id = R.string.flashcard_reviewed_date, formatter.format(Date(epochMillis)))
}
