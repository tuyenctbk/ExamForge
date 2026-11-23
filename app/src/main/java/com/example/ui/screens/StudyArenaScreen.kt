package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CardType
import com.example.data.model.Flashcard
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import com.example.ui.components.PostOperationSupportCard
import com.example.ui.viewmodel.StudySessionUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyArenaScreen(
    session: StudySessionUiState,
    onBack: () -> Unit,
    onSelectMcq: (Int) -> Unit,
    onUpdateCloze: (String) -> Unit,
    onCheckCloze: () -> Unit,
    onReveal: () -> Unit,
    onToggleHint: () -> Unit,
    onRate: (ReviewRating) -> Unit,
    showPostOpSupportCard: Boolean = false,
    onOpenSupportSheet: () -> Unit = {},
    onDismissSupportCard: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val total = session.queue.size.coerceAtLeast(1)
    val progress = (session.currentIndex.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session.deckTitle,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            text = if (session.isFinished) {
                                "Session complete"
                            } else {
                                "Card ${session.currentIndex + 1} of ${session.queue.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("study_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.cd_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                val animatedProgress by animateFloatAsState(
                    targetValue = if (session.isFinished) 1f else progress,
                    label = "study_arena_progress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                val card = session.currentCard
                if (session.isFinished || card == null) {
                    StudyCompleteSummary(
                        reviewedCount = session.reviewedCount,
                        masteredCount = session.masteredInSession,
                        showPostOpSupportCard = showPostOpSupportCard,
                        onOpenSupportSheet = onOpenSupportSheet,
                        onDismissSupportCard = onDismissSupportCard,
                        onDone = onBack
                    )
                } else {
                    AnimatedContent(
                        targetState = card,
                        transitionSpec = {
                            (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it / 3 } + fadeOut())
                        },
                        modifier = Modifier.weight(1f),
                        label = "study_arena_card_transition"
                    ) { activeCard ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            FlashcardPromptCard(
                                card = activeCard,
                                hintShown = session.hintShown,
                                onToggleHint = onToggleHint
                            )

                            when (activeCard.cardType) {
                                CardType.MULTIPLE_CHOICE -> {
                                    McqInteractionBlock(
                                        card = activeCard,
                                        selectedIndex = session.selectedMcqOption,
                                        onSelect = onSelectMcq
                                    )
                                }

                                CardType.FILL_IN_BLANK -> {
                                    ClozeInteractionBlock(
                                        card = activeCard,
                                        input = session.clozeInput,
                                        checked = session.clozeChecked,
                                        onInputChange = onUpdateCloze,
                                        onCheck = onCheckCloze
                                    )
                                }

                                CardType.CONCEPT_EXPLAIN -> {
                                    if (!session.revealed) {
                                        Button(
                                            onClick = onReveal,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(54.dp)
                                                .testTag("reveal_answer_button")
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
                                    }
                                }
                            }

                            AnimatedVisibility(visible = session.revealed) {
                                AnswerExplanationPanel(card = activeCard)
                            }
                        }
                    }

                    // Spaced-Repetition SM-2 Rating Footer
                    AnimatedVisibility(visible = session.revealed) {
                        Column(modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)) {
                            Text(
                                text = "How well did you recall this?",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ReviewRating.entries.forEach { rating ->
                                    val intervalLabel = SpacedRepetitionEngine.previewNextIntervalLabel(card, rating)
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
                                        onClick = { onRate(rating) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = containerColor,
                                            contentColor = contentColor
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 4.dp,
                                            vertical = 10.dp
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .testTag("rate_${rating.name.lowercase()}_button")
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            val ratingText = when (rating) {
                                                ReviewRating.AGAIN -> stringResource(id = R.string.study_rating_again)
                                                ReviewRating.HARD -> stringResource(id = R.string.study_rating_hard)
                                                ReviewRating.GOOD -> stringResource(id = R.string.study_rating_good)
                                                ReviewRating.EASY -> stringResource(id = R.string.study_rating_easy)
                                            }
                                            Text(
                                                text = ratingText,
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

@Composable
private fun FlashcardPromptCard(
    card: Flashcard,
    hintShown: Boolean,
    onToggleHint: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = card.cardType.displayLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (card.hint.isNotBlank()) {
                    TextButton(onClick = onToggleHint) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(id = R.string.study_arena_show_hint))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = card.question,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            AnimatedVisibility(visible = hintShown && card.hint.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    Text(
                        text = "Hint: ${card.hint}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun McqInteractionBlock(
    card: Flashcard,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.options.forEachIndexed { idx, option ->
            val answered = selectedIndex != null
            val isCorrectOption = idx == card.correctOptionIndex
            val isPickedWrong = answered && selectedIndex == idx && !isCorrectOption

            val bg by animateColorAsState(
                targetValue = when {
                    answered && isCorrectOption -> MaterialTheme.colorScheme.secondaryContainer
                    isPickedWrong -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                label = "mcq_option_bg_$idx"
            )
            val border by animateColorAsState(
                targetValue = when {
                    answered && isCorrectOption -> MaterialTheme.colorScheme.secondary
                    isPickedWrong -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "mcq_option_border_$idx"
            )
            Surface(
                color = bg,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(if (answered && (isCorrectOption || isPickedWrong)) 2.dp else 1.dp, border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = !answered) { onSelect(idx) }
                    .testTag("mcq_option_$idx")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "${('A' + idx)}.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClozeInteractionBlock(
    card: Flashcard,
    input: String,
    checked: Boolean,
    onInputChange: (String) -> Unit,
    onCheck: () -> Unit
) {
    val isExactMatch = checked && input.trim().equals(card.answer.trim(), ignoreCase = true)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            enabled = !checked,
            label = { Text("Fill in the blank") },
            placeholder = { Text("Type missing term or formula...") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cloze_answer_input")
        )
        if (!checked) {
            Button(
                onClick = onCheck,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("check_cloze_button")
            ) {
                Text("Check Answer", fontWeight = FontWeight.Bold)
            }
        } else {
            Surface(
                color = if (isExactMatch) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isExactMatch) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isExactMatch) "Spot on!" else "Compare your answer with the key below.",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerExplanationPanel(card: Flashcard) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "ANSWER KEY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.answer,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (card.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = card.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StudyCompleteSummary(
    reviewedCount: Int,
    masteredCount: Int,
    showPostOpSupportCard: Boolean,
    onOpenSupportSheet: () -> Unit,
    onDismissSupportCard: () -> Unit,
    onDone: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
    ) {
        val trophyScale = remember { Animatable(0.6f) }
        LaunchedEffect(Unit) {
            trophyScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(56.dp)
                    .scale(trophyScale.value)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(id = R.string.study_arena_complete_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.study_arena_reviewed_summary, reviewedCount, masteredCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(22.dp))
            OutlinedButton(
                onClick = onDone,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("study_complete_done_button")
            ) {
                Text(stringResource(id = R.string.study_arena_back_to_decks))
            }

            if (showPostOpSupportCard) {
                Spacer(modifier = Modifier.height(16.dp))
                PostOperationSupportCard(
                    headline = "Great recall session!",
                    onOpenSupport = onOpenSupportSheet,
                    onDismiss = onDismissSupportCard
                )
            }
        }
    }
}
