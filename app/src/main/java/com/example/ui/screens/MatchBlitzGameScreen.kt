package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Flashcard
import kotlinx.coroutines.delay

private enum class MatchGamePhase {
    READY,
    PLAYING,
    FINISHED
}

private data class MatchTile(
    val cardId: String,
    val text: String
)

private const val INITIAL_GAME_SECONDS = 60
private const val PAIRS_PER_ROUND = 5

/**
 * 100% offline, $0-API-cost Active-Recall Match Blitz mini-game built directly on saved flashcards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchBlitzGameScreen(
    deckTitle: String,
    cards: List<Flashcard>,
    bestScore: Int,
    onScoreRecorded: (Int) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val playableCards = remember(cards) {
        cards.filter { it.question.isNotBlank() && it.answer.isNotBlank() }
    }

    var phase by remember { mutableStateOf(MatchGamePhase.READY) }
    var timeLeftSeconds by remember { mutableIntStateOf(INITIAL_GAME_SECONDS) }
    var score by remember { mutableIntStateOf(0) }
    var combo by remember { mutableIntStateOf(0) }
    var maxCombo by remember { mutableIntStateOf(0) }
    var matchedPairsTotal by remember { mutableIntStateOf(0) }
    var attemptsTotal by remember { mutableIntStateOf(0) }
    var roundNumber by remember { mutableIntStateOf(1) }

    var questionTiles by remember { mutableStateOf<List<MatchTile>>(emptyList()) }
    var answerTiles by remember { mutableStateOf<List<MatchTile>>(emptyList()) }
    var matchedIdsInRound by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedQuestionId by remember { mutableStateOf<String?>(null) }
    var selectedAnswerId by remember { mutableStateOf<String?>(null) }
    var mismatchIds by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun loadNextRound() {
        val roundSample = playableCards.shuffled().take(PAIRS_PER_ROUND.coerceAtMost(playableCards.size))
        questionTiles = roundSample.map { MatchTile(it.id, it.question.take(110)) }.shuffled()
        answerTiles = roundSample.map { MatchTile(it.id, it.answer.take(110)) }.shuffled()
        matchedIdsInRound = emptySet()
        selectedQuestionId = null
        selectedAnswerId = null
        mismatchIds = null
    }

    fun startNewGame() {
        score = 0
        combo = 0
        maxCombo = 0
        matchedPairsTotal = 0
        attemptsTotal = 0
        roundNumber = 1
        timeLeftSeconds = INITIAL_GAME_SECONDS
        loadNextRound()
        phase = MatchGamePhase.PLAYING
    }

    // Countdown timer while playing
    LaunchedEffect(phase) {
        if (phase == MatchGamePhase.PLAYING) {
            while (timeLeftSeconds > 0 && phase == MatchGamePhase.PLAYING) {
                delay(1_000L)
                timeLeftSeconds = (timeLeftSeconds - 1).coerceAtLeast(0)
            }
            if (phase == MatchGamePhase.PLAYING) {
                phase = MatchGamePhase.FINISHED
                onScoreRecorded(score)
            }
        }
    }

    // Clear mismatch flash cleanly
    LaunchedEffect(mismatchIds) {
        if (mismatchIds != null) {
            delay(420L)
            mismatchIds = null
            selectedQuestionId = null
            selectedAnswerId = null
        }
    }

    // Evaluate pair whenever both sides are picked
    fun evaluateSelection(qId: String?, aId: String?) {
        if (qId == null || aId == null) return
        attemptsTotal += 1
        if (qId == aId) {
            combo += 1
            if (combo > maxCombo) maxCombo = combo
            val multiplier = combo.coerceAtMost(5)
            score += 100 * multiplier
            matchedPairsTotal += 1
            val updatedMatched = matchedIdsInRound + qId
            matchedIdsInRound = updatedMatched
            selectedQuestionId = null
            selectedAnswerId = null

            if (updatedMatched.size >= questionTiles.size) {
                roundNumber += 1
                timeLeftSeconds = (timeLeftSeconds + 8).coerceAtMost(90)
                score += 150
                loadNextRound()
            }
        } else {
            combo = 0
            timeLeftSeconds = (timeLeftSeconds - 2).coerceAtLeast(0)
            mismatchIds = qId to aId
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(id = R.string.match_blitz_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "$deckTitle · Offline $0 Study Game",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("match_blitz_back_button")
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
            if (playableCards.size < 3) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 520.dp)
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Need at least 3 flashcards",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Forge or add a few more cards to unlock Match Blitz speed training.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(onClick = onBack) {
                        Text("Back to Deck")
                    }
                }
                return@Box
            }

            when (phase) {
                MatchGamePhase.READY -> {
                    MatchBlitzStartOverlay(
                        deckTitle = deckTitle,
                        cardCount = playableCards.size,
                        bestScore = bestScore,
                        onStart = ::startNewGame
                    )
                }

                MatchGamePhase.FINISHED -> {
                    val accuracy = if (attemptsTotal == 0) 0 else ((matchedPairsTotal * 100) / attemptsTotal)
                    val isNewBest = score > 0 && score >= bestScore
                    MatchBlitzFinishedSummary(
                        score = score,
                        bestScore = maxOf(score, bestScore),
                        isNewBest = isNewBest,
                        matchedPairs = matchedPairsTotal,
                        accuracyPercent = accuracy,
                        maxCombo = maxCombo,
                        onPlayAgain = ::startNewGame,
                        onBack = onBack
                    )
                }

                MatchGamePhase.PLAYING -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // HUD Header
                        item {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Timer,
                                                contentDescription = null,
                                                tint = if (timeLeftSeconds <= 12) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "${timeLeftSeconds}s",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (timeLeftSeconds <= 12) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.LocalFireDepartment,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "x${combo.coerceAtLeast(1)} · Rd $roundNumber",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }

                                        Text(
                                            text = "$score pts",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    LinearProgressIndicator(
                                        progress = { (timeLeftSeconds.toFloat() / INITIAL_GAME_SECONDS.toFloat()).coerceIn(0f, 1f) },
                                        color = if (timeLeftSeconds <= 12) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(7.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "PROMPTS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "ANSWERS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Matching rows
                        items(questionTiles.size) { index ->
                            val qTile = questionTiles[index]
                            val aTile = answerTiles[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                MatchTileCard(
                                    text = qTile.text,
                                    isMatched = qTile.cardId in matchedIdsInRound,
                                    isSelected = selectedQuestionId == qTile.cardId,
                                    isError = mismatchIds?.first == qTile.cardId,
                                    onClick = {
                                        if (mismatchIds != null || qTile.cardId in matchedIdsInRound) return@MatchTileCard
                                        val nextQ = if (selectedQuestionId == qTile.cardId) null else qTile.cardId
                                        selectedQuestionId = nextQ
                                        if (nextQ != null && selectedAnswerId != null) {
                                            evaluateSelection(nextQ, selectedAnswerId)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("match_q_$index")
                                )

                                MatchTileCard(
                                    text = aTile.text,
                                    isMatched = aTile.cardId in matchedIdsInRound,
                                    isSelected = selectedAnswerId == aTile.cardId,
                                    isError = mismatchIds?.second == aTile.cardId,
                                    onClick = {
                                        if (mismatchIds != null || aTile.cardId in matchedIdsInRound) return@MatchTileCard
                                        val nextA = if (selectedAnswerId == aTile.cardId) null else aTile.cardId
                                        selectedAnswerId = nextA
                                        if (selectedQuestionId != null && nextA != null) {
                                            evaluateSelection(selectedQuestionId, nextA)
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("match_a_$index")
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchTileCard(
    text: String,
    isMatched: Boolean,
    isSelected: Boolean,
    isError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isMatched -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            isError -> MaterialTheme.colorScheme.errorContainer
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "match_tile_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isMatched -> MaterialTheme.colorScheme.secondary
            isError -> MaterialTheme.colorScheme.error
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "match_tile_border"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(if (isSelected || isError) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !isMatched, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = if (isMatched) "✓ Matched" else text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected || isMatched) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isMatched -> MaterialTheme.colorScheme.secondary
                    isError -> MaterialTheme.colorScheme.onErrorContainer
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MatchBlitzStartOverlay(
    deckTitle: String,
    cardCount: Int,
    bestScore: Int,
    onStart: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 520.dp)
            .padding(24.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.size(74.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(id = R.string.match_blitz_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Pair prompts with their exact answers from \"$deckTitle\" ($cardCount cards). Build combos up to x5 and clear rounds for +8s bonus time — 100% offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (bestScore > 0) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.decks_best_score, bestScore),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("match_blitz_start_button")
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(id = R.string.match_blitz_start), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MatchBlitzFinishedSummary(
    score: Int,
    bestScore: Int,
    isNewBest: Boolean,
    matchedPairs: Int,
    accuracyPercent: Int,
    maxCombo: Int,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 520.dp)
            .padding(24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.EmojiEvents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isNewBest) "New High Score!" else "Time's Up!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(id = R.string.match_blitz_score_label, score),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(18.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.padding(16.dp)
            ) {
                StatChip("Pairs", "$matchedPairs")
                StatChip("Accuracy", "$accuracyPercent%")
                StatChip("Max Streak", "x${maxCombo.coerceAtLeast(1)}")
                StatChip("Best", "$bestScore")
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onPlayAgain,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("match_blitz_replay_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(id = R.string.match_blitz_play_again))
            }
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
            ) {
                Text(stringResource(id = R.string.common_close))
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
