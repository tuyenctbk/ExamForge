package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.CardType
import com.example.data.model.Flashcard
import com.example.data.model.StudyDeck
import com.example.ui.util.tvFocusHighlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckDetailScreen(
    deck: StudyDeck,
    cards: List<Flashcard>,
    onBack: () -> Unit,
    onStartStudy: (dueOnly: Boolean) -> Unit,
    onStartOralQuiz: () -> Unit,
    onStartMatchBlitz: () -> Unit,
    onVerifyCard: (Flashcard) -> Unit,
    onAddManualCard: (
        cardType: CardType,
        question: String,
        answer: String,
        explanation: String,
        hint: String,
        options: List<String>,
        correctIndex: Int
    ) -> Unit,
    onEditCard: (cardId: String, question: String, answer: String, explanation: String, hint: String) -> Unit,
    onSnapCardsForDeck: () -> Unit,
    onDeleteCard: (Flashcard) -> Unit,
    onDeleteDeck: () -> Unit
) {
    BackHandler(onBack = onBack)

    var showAddCardDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<Flashcard?>(null) }
    var showDeleteDeckConfirm by remember { mutableStateOf(false) }
    val now = remember(cards) { System.currentTimeMillis() }
    val dueCount = remember(cards, now) { cards.count { it.isDue(now) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deck.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${deck.subject} · ${cards.size} cards",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("deck_detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.cd_navigate_back)
                        )
                    }
                },
                actions = {
                    if (com.example.ui.util.rememberHasCameraHardware()) {
                        IconButton(
                            onClick = onSnapCardsForDeck,
                            modifier = Modifier.testTag("snap_cards_for_deck_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Scan notes into deck",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(
                        onClick = { showAddCardDialog = true },
                        modifier = Modifier.testTag("add_card_toolbar_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add flashcard"
                        )
                    }
                    IconButton(
                        onClick = { showDeleteDeckConfirm = true },
                        modifier = Modifier.testTag("delete_deck_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete deck",
                            tint = MaterialTheme.colorScheme.error
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            if (deck.description.isNotBlank()) {
                                Text(
                                    text = deck.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { onStartStudy(dueCount > 0) },
                                    enabled = cards.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .tvFocusHighlight(RoundedCornerShape(14.dp))
                                        .testTag("detail_study_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (dueCount > 0) "Study Due ($dueCount)" else "Practice All")
                                }

                                OutlinedButton(
                                    onClick = onStartOralQuiz,
                                    enabled = cards.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .tvFocusHighlight(RoundedCornerShape(14.dp))
                                        .testTag("detail_oral_quiz_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Oral Quiz")
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = onStartMatchBlitz,
                                enabled = cards.size >= 3,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .tvFocusHighlight(RoundedCornerShape(14.dp))
                                    .testTag("detail_match_blitz_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (cards.size >= 3) "Play 60s Match Blitz (Offline Game)" else "Add 3+ cards for Match Blitz"
                                )
                            }
                        }
                    }
                }

                if (cards.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = "No flashcards in this deck yet",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Add a card manually or scan notes from the Forge tab and append them here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(onClick = { showAddCardDialog = true }) {
                                    Text("Add Flashcard")
                                }
                            }
                        }
                    }
                } else {
                    items(cards, key = { it.id }) { card ->
                        DeckCardRow(
                            card = card,
                            isDueNow = card.isDue(now),
                            onVerify = { onVerifyCard(card) },
                            onEdit = { editingCard = card },
                            onDelete = { onDeleteCard(card) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (showAddCardDialog) {
        AddManualCardDialog(
            onDismiss = { showAddCardDialog = false },
            onSave = { type, q, a, exp, hint, opts, correctIdx ->
                showAddCardDialog = false
                onAddManualCard(type, q, a, exp, hint, opts, correctIdx)
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
                onEditCard(card.id, q, a, exp, hint)
            }
        )
    }

    if (showDeleteDeckConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteDeckConfirm = false },
            title = { Text("Delete \"${deck.title}\"?") },
            text = { Text("This removes the deck and all ${cards.size} flashcards from both cloud sync and local offline cache.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDeckConfirm = false
                        onDeleteDeck()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDeckConfirm = false }) {
                    Text(stringResource(id = R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun DeckCardRow(
    card: Flashcard,
    isDueNow: Boolean,
    onVerify: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = card.cardType.shortBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        color = if (isDueNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isDueNow) "DUE NOW" else "IN ${card.intervalDays}D",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDueNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onVerify,
                        modifier = Modifier.testTag("deck_verify_card_${card.id}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.FactCheck,
                            contentDescription = "Fact-check flashcard",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("deck_edit_card_${card.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit flashcard",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("deck_delete_card_${card.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete flashcard",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.question,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AddManualCardDialog(
    onDismiss: () -> Unit,
    onSave: (CardType, String, String, String, String, List<String>, Int) -> Unit
) {
    var cardType by remember { mutableStateOf(CardType.CONCEPT_EXPLAIN) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var optA by remember { mutableStateOf("") }
    var optB by remember { mutableStateOf("") }
    var optC by remember { mutableStateOf("") }
    var optD by remember { mutableStateOf("") }
    var correctIdx by remember { mutableIntStateOf(0) }

    val rawOptions = listOf(optA, optB, optC, optD)
    val nonBlankIndexed = rawOptions.mapIndexedNotNull { idx, value ->
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) idx to trimmed else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Flashcard") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardType.entries.forEach { type ->
                        FilterChip(
                            selected = cardType == type,
                            onClick = { cardType = type },
                            label = { Text(type.shortBadge) }
                        )
                    }
                }
                OutlinedTextField(
                    value = question,
                    onValueChange = { if (it.length <= 1000) question = it },
                    label = { Text(if (cardType == CardType.FILL_IN_BLANK) "Sentence (use ___ for blank)" else "Question") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (cardType == CardType.MULTIPLE_CHOICE) {
                    Text(
                        text = "Tap a radio button to mark the correct choice",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        0 to Pair(optA, { v: String -> optA = v }),
                        1 to Pair(optB, { v: String -> optB = v }),
                        2 to Pair(optC, { v: String -> optC = v }),
                        3 to Pair(optD, { v: String -> optD = v })
                    ).forEach { (slotIdx, pair) ->
                        val (value, onChange) = pair
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = correctIdx == slotIdx,
                                onClick = { correctIdx = slotIdx },
                                enabled = value.isNotBlank() || slotIdx < 2
                            )
                            OutlinedTextField(
                                value = value,
                                onValueChange = {
                                    onChange(it.take(200))
                                    if (correctIdx == slotIdx && it.isBlank() && optA.isNotBlank()) {
                                        correctIdx = 0
                                    }
                                },
                                label = {
                                    val letter = ('A' + slotIdx)
                                    Text(if (correctIdx == slotIdx) "Option $letter (Correct)" else "Option $letter")
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { if (it.length <= 1500) answer = it },
                        label = { Text("Answer") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = explanation,
                    onValueChange = { if (it.length <= 1500) explanation = it },
                    label = { Text("Explanation (optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { if (it.length <= 300) hint = it },
                    label = { Text("Hint (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val validCorrectSelected = nonBlankIndexed.any { it.first == correctIdx }
            val isValid = if (cardType == CardType.MULTIPLE_CHOICE) {
                question.isNotBlank() && nonBlankIndexed.size >= 2 && (validCorrectSelected || nonBlankIndexed.isNotEmpty())
            } else {
                question.isNotBlank() && answer.isNotBlank()
            }
            Button(
                onClick = {
                    if (cardType == CardType.MULTIPLE_CHOICE) {
                        val filteredOpts = nonBlankIndexed.map { it.second }
                        val resolvedIndex = nonBlankIndexed.indexOfFirst { it.first == correctIdx }
                            .let { if (it >= 0) it else 0 }
                        val finalAns = filteredOpts.getOrElse(resolvedIndex) { "" }
                        onSave(
                            cardType,
                            question.trim(),
                            finalAns,
                            explanation.trim(),
                            hint.trim(),
                            filteredOpts,
                            resolvedIndex
                        )
                    } else {
                        onSave(
                            cardType,
                            question.trim(),
                            answer.trim(),
                            explanation.trim(),
                            hint.trim(),
                            emptyList(),
                            -1
                        )
                    }
                },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
