package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.Flashcard
import com.example.data.model.StudyDeck
import com.example.data.model.UserProfile
import com.example.ui.components.HomeDonationCompactBanner
import com.example.ui.theme.DeckAccentPalette
import com.example.ui.viewmodel.SyncStatusState

@Composable
fun DecksScreen(
    decks: List<StudyDeck>,
    allCards: List<Flashcard>,
    profile: UserProfile?,
    syncStatus: SyncStatusState,
    matchBlitzBestScore: Int,
    onOpenForge: () -> Unit,
    onOpenSavedFlashcards: () -> Unit,
    onOpenMatchBlitzAll: () -> Unit,
    onOpenDeckDetail: (StudyDeck) -> Unit,
    onQuickStudyAllDue: () -> Unit,
    onQuickStudyDeck: (StudyDeck) -> Unit,
    onStartOralQuizDeck: (StudyDeck) -> Unit,
    onCreateManualDeck: (title: String, subject: String, description: String, accentIndex: Int) -> Unit,
    showHomeDonationBanner: Boolean = false,
    completedTasksCount: Int = 0,
    onOpenSupportSheet: () -> Unit = {},
    onDismissHomeDonationBanner: () -> Unit = {}
) {
    val now = remember(allCards) { System.currentTimeMillis() }
    val totalDue = remember(allCards, now) { allCards.count { it.isDue(now) } }
    val subjects = remember(decks) {
        listOf("All") + decks.map { it.subject }.distinct().sorted()
    }
    var selectedSubject by rememberSaveable { mutableStateOf("All") }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    val filteredDecks = remember(decks, selectedSubject) {
        if (selectedSubject == "All") decks else decks.filter { it.subject == selectedSubject }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Review Dashboard Card
            item {
                ReviewHeroCard(
                    totalDue = totalDue,
                    totalCards = allCards.size,
                    streakDays = profile?.streakDays ?: 0,
                    matchBlitzBestScore = matchBlitzBestScore,
                    syncStatus = syncStatus,
                    onQuickStudyAllDue = onQuickStudyAllDue,
                    onOpenForge = onOpenForge,
                    onOpenMatchBlitzAll = onOpenMatchBlitzAll
                )
            }

            // Smart non-intrusive Home Screen Compact Support Banner (hidden for new users & Active Supporters)
            if (showHomeDonationBanner) {
                item(key = "home_donation_banner") {
                    HomeDonationCompactBanner(
                        completedTasksCount = completedTasksCount,
                        onOpenSupport = onOpenSupportSheet,
                        onDismiss = onDismissHomeDonationBanner
                    )
                }
            }

            // Section header + manual deck button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Your Study Decks",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${decks.size} deck(s) · ${allCards.size} cards cached offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenSavedFlashcards,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("open_saved_cards_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Style,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(stringResource(id = R.string.decks_saved_cards))
                        }
                        OutlinedButton(
                            onClick = { showCreateDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("new_manual_deck_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(id = R.string.decks_new_deck))
                        }
                    }
                }
            }

            // Subject filter chips
            if (subjects.size > 2) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(subjects) { subject ->
                            FilterChip(
                                selected = selectedSubject == subject,
                                onClick = { selectedSubject = subject },
                                label = {
                                    Text(
                                        if (subject == "All") stringResource(id = R.string.decks_all_filter) else subject
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Empty state or deck cards
            if (filteredDecks.isEmpty()) {
                item {
                    EmptyDecksCard(
                        onOpenForge = onOpenForge,
                        onCreateManual = { showCreateDialog = true }
                    )
                }
            } else {
                items(filteredDecks, key = { it.id }) { deck ->
                    val deckCards = allCards.filter { it.deckId == deck.id }
                    val dueCount = deckCards.count { it.isDue(now) }
                    val masteredCount = deckCards.count { it.isMastered() }
                    DeckListCard(
                        deck = deck,
                        totalCards = deckCards.size,
                        dueCount = dueCount,
                        masteredCount = masteredCount,
                        onClick = { onOpenDeckDetail(deck) },
                        onStudyNow = { onQuickStudyDeck(deck) },
                        onOralQuiz = { onStartOralQuizDeck(deck) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }

    if (showCreateDialog) {
        CreateDeckDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, subject, desc, accent ->
                showCreateDialog = false
                onCreateManualDeck(title, subject, desc, accent)
            }
        )
    }
}

@Composable
private fun ReviewHeroCard(
    totalDue: Int,
    totalCards: Int,
    streakDays: Int,
    matchBlitzBestScore: Int,
    syncStatus: SyncStatusState,
    onQuickStudyAllDue: () -> Unit,
    onOpenForge: () -> Unit,
    onOpenMatchBlitzAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sync status pill
                    Surface(
                        color = if (syncStatus.isOnline) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = if (syncStatus.isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (syncStatus.isOnline) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (syncStatus.isOnline) "CLOUD SYNCED" else "OFFLINE · ROOM DB",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (syncStatus.isOnline) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    // Streak chip
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${streakDays}d streak",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "$totalDue",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(id = R.string.decks_due_summary, totalDue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onOpenForge,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("hero_forge_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.flashcards_fab_scan))
                    }

                    OutlinedButton(
                        onClick = onQuickStudyAllDue,
                        enabled = totalCards > 0,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("hero_study_due_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.decks_study_all_due))
                    }
                }

                if (totalCards >= 3) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenMatchBlitzAll,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("hero_match_blitz_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val blitzLabel = stringResource(id = R.string.decks_match_blitz_button)
                        val bestSuffix = if (matchBlitzBestScore > 0) {
                            " · " + stringResource(id = R.string.decks_best_score, matchBlitzBestScore)
                        } else {
                            ""
                        }
                        Text("$blitzLabel$bestSuffix")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckListCard(
    deck: StudyDeck,
    totalCards: Int,
    dueCount: Int,
    masteredCount: Int,
    onClick: () -> Unit,
    onStudyNow: () -> Unit,
    onOralQuiz: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = DeckAccentPalette[deck.accentIndex.coerceIn(0, DeckAccentPalette.lastIndex)]
    val masteryFraction = if (totalCards > 0) masteredCount.toFloat() / totalCards.toFloat() else 0f
    val animatedMastery by animateFloatAsState(
        targetValue = masteryFraction,
        label = "deck_mastery_progress"
    )

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .testTag("deck_card_${deck.id}")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = deck.subject.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "· ${deck.sourceType.displayLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (dueCount > 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (dueCount > 0) "$dueCount due" else "Caught up",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dueCount > 0) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = deck.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (deck.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = deck.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$totalCards cards · $masteredCount mastered",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(masteryFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { animatedMastery },
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onStudyNow,
                    enabled = totalCards > 0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color(0xFF111827)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("study_deck_${deck.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Style,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Study Cards", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onOralQuiz,
                    enabled = totalCards > 0,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("oral_quiz_deck_${deck.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Oral Quiz")
                }
            }
        }
    }
}

@Composable
private fun EmptyDecksCard(
    onOpenForge: () -> Unit,
    onCreateManual: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_deck_1795276460613),
                contentDescription = stringResource(id = R.string.cd_empty_illustration),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.decks_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.decks_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onOpenForge,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("empty_open_forge_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.camera_capture_title))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onCreateManual) {
                Text(stringResource(id = R.string.decks_new_deck))
            }
        }
    }
}

@Composable
private fun CreateDeckDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, subject: String, description: String, accentIndex: Int) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var selectedAccent by rememberSaveable { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Study Deck") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 120) title = it },
                    label = { Text("Deck Title") },
                    placeholder = { Text("e.g., Neuroanatomy Cranial Nerves") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = subject,
                    onValueChange = { if (it.length <= 60) subject = it },
                    label = { Text("Subject") },
                    placeholder = { Text("e.g., Biology, Calculus, Law") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 350) description = it },
                    label = { Text("Short Summary (optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeckAccentPalette.forEachIndexed { idx, color ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { selectedAccent = idx }
                        ) {
                            Surface(
                                color = color,
                                shape = CircleShape,
                                border = if (selectedAccent == idx) {
                                    BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                } else {
                                    null
                                },
                                modifier = Modifier.size(30.dp)
                            ) {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title.trim().ifBlank { "New Study Deck" },
                        subject.trim().ifBlank { "General" },
                        description.trim(),
                        selectedAccent
                    )
                },
                enabled = title.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.common_cancel)) }
        }
    )
}
