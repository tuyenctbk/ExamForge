package com.example.ui.screens

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.Flashcard
import com.example.data.local.toDomain
import com.example.data.model.CardType
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import com.example.data.model.StudyDeck
import com.example.ui.util.tvFocusHighlight
import com.example.ui.viewmodel.FlashcardReviewFlag
import com.example.ui.viewmodel.FlashcardViewModel
import com.example.ui.viewmodel.ReviewNowScheduler
import com.example.ui.viewmodel.ReviewUrgency
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Full-screen interactive 'Study Session' composable that fetches flashcards flagged for
 * 'Review Now' from [FlashcardViewModel], displays one card at a time with a 3D Y-axis flip
 * animation to reveal the answer, and persists reinforcement ratings back to Room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySession(
    viewModel: FlashcardViewModel,
    decks: List<StudyDeck>,
    deckId: String? = null,
    onBack: () -> Unit
) {
    val exitSession: () -> Unit = {
        viewModel.closeReviewNowCycle()
        onBack()
    }

    BackHandler(onBack = exitSession)

    val session by viewModel.reviewNowSession.collectAsStateWithLifecycle()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    LaunchedEffect(deckId) {
        if (!session.isActive) {
            viewModel.startReviewNowCycle(deckId = deckId)
        }
    }
    var slideDirection by remember { mutableIntStateOf(1) }
    val deckTitleById = remember(decks) { decks.associate { it.id to it.title } }
    val defaultSessionTitle = stringResource(id = R.string.study_session_title)
    val fallbackDeckTitle = stringResource(id = R.string.study_session_fallback_title)
    val scopedTitle = remember(deckId, deckTitleById, defaultSessionTitle, fallbackDeckTitle) {
        if (deckId.isNullOrBlank()) defaultSessionTitle
        else deckTitleById[deckId] ?: fallbackDeckTitle
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = scopedTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when {
                            session.isCompleted -> stringResource(id = R.string.study_session_reinforcement_complete)
                            session.queue.isEmpty() -> stringResource(id = R.string.study_session_no_cards_due)
                            else -> stringResource(
                                id = R.string.study_session_progress_subtitle,
                                session.currentIndex + 1,
                                session.queue.size,
                                session.reviewedInCycle
                            )
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = exitSession,
                        modifier = Modifier.testTag("study_session_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.cd_navigate_back)
                        )
                    }
                },
                actions = {
                    if (!session.isCompleted && session.queue.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(id = R.string.study_session_due_badge, session.queue.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("study_session_fullscreen")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                val totalAtStart = session.totalFlaggedAtStart.coerceAtLeast(1)
                val progressFraction = when {
                    session.isCompleted -> 1f
                    session.queue.isEmpty() -> 1f
                    else -> ((totalAtStart - session.queue.size).coerceAtLeast(0).toFloat() / totalAtStart.toFloat())
                        .coerceIn(0f, 1f)
                }

                val animatedProgress by animateFloatAsState(
                    targetValue = progressFraction,
                    label = "study_session_progress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                val lastSm2 = session.lastScheduledOutcome
                val context = LocalContext.current
                AnimatedVisibility(visible = lastSm2 != null) {
                    lastSm2?.let { outcome ->
                        val is24Hour = DateFormat.is24HourFormat(context)
                        val locale = Locale.getDefault()
                        val pattern = when {
                            outcome.intervalDays <= 0 -> if (is24Hour) "HH:mm" else "h:mm a"
                            outcome.intervalDays >= 180 -> "MMM d, yyyy"
                            else -> "MMM d"
                        }
                        val nextDateLabel = remember(outcome.nextReviewAtMillis, pattern, locale) {
                            SimpleDateFormat(pattern, locale).format(Date(outcome.nextReviewAtMillis))
                        }
                        val efLabel = remember(outcome.easeFactor) {
                            String.format(Locale.US, "%.2f", outcome.easeFactor)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .testTag("study_session_sm2_feedback")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(
                                        id = R.string.sm2_next_review_pill,
                                        nextDateLabel,
                                        outcome.intervalLabel,
                                        efLabel
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val activeCard = session.currentCard
                when {
                    session.isCompleted -> {
                        StudySessionSummaryCard(
                            icon = Icons.Default.EmojiEvents,
                            title = stringResource(id = R.string.study_arena_complete_title),
                            body = stringResource(id = R.string.study_session_complete_body, session.reviewedInCycle),
                            buttonLabel = stringResource(id = R.string.study_session_finish),
                            onDone = exitSession
                        )
                    }

                    activeCard == null -> {
                        StudySessionSummaryCard(
                            icon = Icons.Default.CheckCircle,
                            title = stringResource(id = R.string.study_session_caught_up_title),
                            body = stringResource(id = R.string.study_session_caught_up_body),
                            buttonLabel = stringResource(id = R.string.study_session_return),
                            onDone = exitSession
                        )
                    }

                    else -> {
                        val deckName = deckTitleById[activeCard.deckId] ?: stringResource(id = R.string.flashcards_screen_title)

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AnimatedContent(
                                targetState = activeCard,
                                transitionSpec = {
                                    (slideInHorizontally { width -> (width / 3) * slideDirection } + fadeIn()) togetherWith
                                        (slideOutHorizontally { width -> -(width / 3) * slideDirection } + fadeOut())
                                },
                                label = "study_session_card_switch"
                            ) { card ->
                                key(card.id) {
                                    StudySessionFlipCard(
                                        card = card,
                                        deckName = deckName,
                                        reviewFlag = session.currentFlag,
                                        isFlipped = session.answerRevealed,
                                        hintShown = session.hintShown,
                                        onFlip = {
                                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            viewModel.toggleReviewNowFlip()
                                        },
                                        onToggleHint = viewModel::toggleReviewNowHint
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (session.queue.size > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            slideDirection = -1
                                            viewModel.cyclePreviousReviewCard()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .height(50.dp)
                                            .testTag("study_session_prev_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                                            contentDescription = stringResource(id = R.string.study_session_cd_prev_card),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = viewModel::toggleReviewNowFlip,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("study_session_flip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Flip,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (session.answerRevealed) {
                                            stringResource(id = R.string.study_session_flip_to_question)
                                        } else {
                                            stringResource(id = R.string.study_session_flip_to_answer)
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (session.queue.size > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            slideDirection = 1
                                            viewModel.cycleNextReviewCard()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier
                                            .height(50.dp)
                                            .testTag("study_session_next_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                                            contentDescription = stringResource(id = R.string.study_session_cd_next_card),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(visible = session.answerRevealed) {
                                val domainCard = remember(activeCard) { activeCard.toDomain() }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(id = R.string.study_session_rate_prompt),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        ReviewRating.entries.forEach { rating ->
                                            val intervalLabel = remember(domainCard, rating) {
                                                SpacedRepetitionEngine.previewNextIntervalLabel(domainCard, rating)
                                            }
                                            val containerColor = when (rating) {
                                                ReviewRating.AGAIN -> MaterialTheme.colorScheme.errorContainer
                                                ReviewRating.HARD -> MaterialTheme.colorScheme.surfaceContainerHigh
                                                ReviewRating.GOOD -> MaterialTheme.colorScheme.secondaryContainer
                                                ReviewRating.EASY -> MaterialTheme.colorScheme.primaryContainer
                                            }
                                            val contentColor = when (rating) {
                                                ReviewRating.AGAIN -> MaterialTheme.colorScheme.onErrorContainer
                                                ReviewRating.HARD -> MaterialTheme.colorScheme.onSurface
                                                ReviewRating.GOOD -> MaterialTheme.colorScheme.onSecondaryContainer
                                                ReviewRating.EASY -> MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                            Button(
                                                onClick = {
                                                    slideDirection = 1
                                                    viewModel.submitReviewNowRating(rating)
                                                },
                                                enabled = session.answerRevealed,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = containerColor,
                                                    contentColor = contentColor
                                                ),
                                                shape = RoundedCornerShape(14.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(56.dp)
                                                    .testTag("study_session_rate_${rating.name.lowercase()}")
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = localizedRatingLabel(rating),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = intervalLabel,
                                                        style = MaterialTheme.typography.labelSmall
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
    }
}

@Composable
private fun StudySessionFlipCard(
    card: Flashcard,
    deckName: String,
    reviewFlag: FlashcardReviewFlag?,
    isFlipped: Boolean,
    hintShown: Boolean,
    onFlip: () -> Unit,
    onToggleHint: () -> Unit
) {
    val density = LocalDensity.current.density
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "flashcard_y_flip"
    )
    val showingBack = rotation > 90f
    val domainCard = remember(card) { card.toDomain() }
    val cardType = domainCard.cardType
    val flipLiftScale = 1f + 0.04f * abs(sin(Math.toRadians(rotation.toDouble()))).toFloat()
    val cardElevation by animateDpAsState(
        targetValue = if (isFlipped) 8.dp else 4.dp,
        label = "flashcard_elevation"
    )

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.5.dp,
            color = if (showingBack) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            }
        ),
        tonalElevation = cardElevation,
        shadowElevation = cardElevation,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = 520.dp)
            .graphicsLayer {
                rotationY = rotation
                scaleX = flipLiftScale
                scaleY = flipLiftScale
                cameraDistance = 14f * density
            }
            .tvFocusHighlight(RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onFlip)
            .testTag("study_session_flip_card")
    ) {
        if (!showingBack) {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = cardType.shortBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (reviewFlag != null) {
                        val pillBg = if (reviewFlag.urgency == ReviewUrgency.CRITICAL_LAPSE) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                        val pillFg = if (reviewFlag.urgency == ReviewUrgency.CRITICAL_LAPSE) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                        Surface(color = pillBg, shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = localizedReviewFlagLabel(reviewFlag),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = pillFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                ) {
                    Text(
                        text = "$deckName · ${formatLastReviewed(card.lastReviewDate)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = card.question,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (cardType == CardType.MULTIPLE_CHOICE && domainCard.options.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            domainCard.options.forEachIndexed { idx, opt ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "${('A' + idx)}. $opt",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (card.hint.isNotBlank()) {
                        TextButton(
                            onClick = onToggleHint,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("study_session_hint_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hintShown) {
                                    stringResource(id = R.string.study_session_hide_hint)
                                } else {
                                    stringResource(id = R.string.study_arena_show_hint)
                                }
                            )
                        }
                        AnimatedVisibility(visible = hintShown) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(id = R.string.flashcard_hint_prefix, card.hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Flip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.study_session_tap_reveal),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.study_session_answer_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = stringResource(id = R.string.study_session_mastery_pct, card.studyProgress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = card.answer,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    if (card.explanation.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(id = R.string.study_session_explanation_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = card.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Flip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.study_session_tap_back),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun StudySessionSummaryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    buttonLabel: String,
    onDone: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
    ) {
        val badgeScale = remember { Animatable(0.65f) }
        LaunchedEffect(Unit) {
            badgeScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(28.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier
                    .size(64.dp)
                    .scale(badgeScale.value)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDone,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("study_session_done_button")
            ) {
                Text(text = buttonLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun localizedRatingLabel(rating: ReviewRating): String = when (rating) {
    ReviewRating.AGAIN -> stringResource(id = R.string.study_rating_again)
    ReviewRating.HARD -> stringResource(id = R.string.study_rating_hard)
    ReviewRating.GOOD -> stringResource(id = R.string.study_rating_good)
    ReviewRating.EASY -> stringResource(id = R.string.study_rating_easy)
}

@Composable
internal fun localizedReviewFlagLabel(flag: FlashcardReviewFlag): String {
    return when (flag.urgency) {
        ReviewUrgency.NEVER_REVIEWED -> stringResource(id = R.string.review_flag_never_reviewed)
        ReviewUrgency.CRITICAL_LAPSE -> stringResource(id = R.string.review_flag_reinforce)
        ReviewUrgency.OVERDUE -> {
            val overdueDays = (flag.overdueMillis / ReviewNowScheduler.DAY_MILLIS).toInt()
            if (overdueDays >= 1) {
                stringResource(id = R.string.review_flag_overdue_days, overdueDays)
            } else {
                stringResource(id = R.string.review_flag_due_today)
            }
        }
        ReviewUrgency.UPCOMING -> {
            val remainingMillis = (flag.nextReviewAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remainingMillis < ReviewNowScheduler.DAY_MILLIS) {
                stringResource(id = R.string.review_flag_reviewed_recently)
            } else {
                val remainingDays = ceil(
                    remainingMillis.toDouble() / ReviewNowScheduler.DAY_MILLIS.toDouble()
                ).toInt().coerceAtLeast(1)
                stringResource(id = R.string.review_flag_fresh_in_days, remainingDays)
            }
        }
    }
}

@Composable
private fun formatLastReviewed(epochMillis: Long): String {
    if (epochMillis <= 0L) return stringResource(id = R.string.flashcard_not_reviewed_yet)
    val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return stringResource(id = R.string.study_session_last_reviewed, formatter.format(Date(epochMillis)))
}
