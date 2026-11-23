package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AiUsagePlanState
import com.example.data.model.CardType
import com.example.data.model.GeneratedCardDraft
import com.example.data.model.StudyDeck
import com.example.ui.components.QuotaSoftNudgeBanner
import com.example.ui.components.SmartQuotaRecoveryCard
import com.example.ui.theme.DeckAccentPalette
import com.example.ui.components.PostOperationSupportCard
import com.example.ui.viewmodel.ForgeUiState

@Composable
fun ForgeScreen(
    forgeState: ForgeUiState,
    selectedSnapshot: Bitmap?,
    existingDecks: List<StudyDeck>,
    aiUsagePlan: AiUsagePlanState,
    onSnapshotSelected: (Bitmap?) -> Unit,
    onOpenCameraCapture: () -> Unit,
    onForgeRequested: (bitmap: Bitmap?, rawNotes: String, cardCount: Int, subjectHint: String) -> Unit,
    onForgeOfflineRequested: (rawNotes: String, cardCount: Int, subjectHint: String) -> Unit,
    onForgeEcoLiteRequested: (bitmap: Bitmap?, rawNotes: String, cardCount: Int, subjectHint: String) -> Unit,
    onEnableEcoLite: () -> Unit,
    onClaimGraceCredits: () -> Unit,
    onOpenByokSettings: () -> Unit,
    onUpdateDraftMeta: (title: String, subject: String) -> Unit,
    onRemoveDraftCard: (localId: String) -> Unit,
    onSaveDraft: (targetExistingDeckId: String?, accentIndex: Int) -> Unit,
    onResetForge: () -> Unit,
    onOpenSavedDeck: (deckId: String) -> Unit,
    showPostOpSupportCard: Boolean = false,
    onOpenSupportSheet: () -> Unit = {},
    onDismissSupportCard: () -> Unit = {}
) {
    val context = LocalContext.current
    var rawNotes by rememberSaveable { mutableStateOf("") }
    var subjectHint by rememberSaveable { mutableStateOf("") }
    var targetCount by rememberSaveable { mutableIntStateOf(6) }
    var selectedAccent by rememberSaveable { mutableIntStateOf(0) }
    var selectedExistingDeckId by rememberSaveable { mutableStateOf<String?>(null) }

    // Live Audio Lecture-to-Flashcards (free on-device SpeechRecognizer, $0 audio API cost)
    var isDictatingLecture by remember { mutableStateOf(false) }
    var livePartialTranscript by remember { mutableStateOf("") }
    var dictationStatus by remember { mutableStateOf<String?>(null) }

    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isDictatingLecture = true
                dictationStatus = "Listening to lecture... speak definitions, formulas, or key points."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isDictatingLecture = false
            }

            override fun onError(error: Int) {
                isDictatingLecture = false
                livePartialTranscript = ""
                dictationStatus = "Lecture mic paused — tap Dictate Lecture again to continue."
            }

            override fun onResults(results: Bundle?) {
                isDictatingLecture = false
                livePartialTranscript = ""
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!spoken.isNullOrBlank()) {
                    rawNotes = if (rawNotes.isBlank()) {
                        spoken
                    } else {
                        "${rawNotes.trimEnd()}\n$spoken"
                    }.take(6000)
                    dictationStatus = "Appended spoken lecture snippet ($0 on-device speech)."
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!partial.isNullOrBlank()) {
                    livePartialTranscript = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    val startLectureDictation: () -> Unit = {
        if (speechRecognizer == null) {
            dictationStatus = "On-device speech recognition is unavailable on this device."
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            try {
                speechRecognizer.startListening(intent)
            } catch (_: Exception) {
                isDictatingLecture = false
                dictationStatus = "Unable to start microphone right now."
            }
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            dictationStatus = null
            startLectureDictation()
        } else {
            dictationStatus = "Microphone permission denied. You can still type or paste notes below."
        }
    }

    // Zero-permission Android Photo Picker (Google Play Policy compliant)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSnapshotSelected(decodeBitmapFromUri(context, uri))
        }
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
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.forge_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            val badgeText = when {
                                aiUsagePlan.preferOfflineParser -> "Offline ($0)"
                                aiUsagePlan.hasByokKey -> "BYOK Unlimited"
                                aiUsagePlan.ecoLiteMode -> "Eco Lite · ${aiUsagePlan.remainingForges} left"
                                else -> "Free AI · ${aiUsagePlan.remainingForges} left"
                            }
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.forge_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Stage 1 Proactive Soft Nudge Banner at >= 80% daily quota
            if (aiUsagePlan.isApproachingForgeLimit && forgeState is ForgeUiState.Idle) {
                item {
                    QuotaSoftNudgeBanner(
                        featureLabel = "AI Forge",
                        remainingCount = aiUsagePlan.remainingForges,
                        resetLabel = aiUsagePlan.timeUntilResetLabel(),
                        ecoLiteEnabled = aiUsagePlan.ecoLiteMode,
                        onSwitchToEcoLite = onEnableEcoLite
                    )
                }
            }

            when (forgeState) {
                is ForgeUiState.Generating -> {
                    item {
                        GeneratingCard()
                    }
                }

                is ForgeUiState.Saving -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Saving deck to Room SQLite & Firestore...",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }

                is ForgeUiState.Saved -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "${forgeState.cardCount} Flashcards Forged!",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Cached locally for offline subway study and synced to your Firestore cloud library.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = {
                                            val id = forgeState.deckId
                                            rawNotes = ""
                                            onResetForge()
                                            onOpenSavedDeck(id)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("open_forged_deck_button")
                                    ) {
                                        Text(stringResource(R.string.forge_open_deck))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            rawNotes = ""
                                            onResetForge()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Text(stringResource(R.string.forge_scan_another))
                                    }
                                }

                                if (showPostOpSupportCard) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    PostOperationSupportCard(
                                        headline = "Deck forged & saved offline!",
                                        onOpenSupport = onOpenSupportSheet,
                                        onDismiss = onDismissSupportCard
                                    )
                                }
                            }
                        }
                    }
                }

                is ForgeUiState.Preview -> {
                    val draft = forgeState.draft
                    item {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(18.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Review Forged Deck (${draft.cards.size} cards)",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    IconButton(onClick = onResetForge) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Start over"
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = draft.title,
                                    onValueChange = { onUpdateDraftMeta(it.take(120), draft.subject) },
                                    label = { Text("Deck Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = draft.subject,
                                    onValueChange = { onUpdateDraftMeta(draft.title, it.take(60)) },
                                    label = { Text("Subject") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (draft.summary.isNotBlank()) {
                                    Text(
                                        text = draft.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Save destination selector (New Deck or append to existing)
                                Text(
                                    text = "Destination",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        FilterChip(
                                            selected = selectedExistingDeckId == null,
                                            onClick = { selectedExistingDeckId = null },
                                            label = { Text("New Deck") }
                                        )
                                    }
                                    items(existingDecks) { deck ->
                                        FilterChip(
                                            selected = selectedExistingDeckId == deck.id,
                                            onClick = { selectedExistingDeckId = deck.id },
                                            label = { Text(deck.title.take(22)) }
                                        )
                                    }
                                }

                                if (selectedExistingDeckId == null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                                    } else null,
                                                    modifier = Modifier.size(28.dp)
                                                ) {}
                                            }
                                        }
                                    }
                                }

                                Button(
                                    onClick = { onSaveDraft(selectedExistingDeckId, selectedAccent) },
                                    enabled = draft.cards.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("save_forged_deck_button")
                                ) {
                                    Text(
                                        text = if (selectedExistingDeckId == null) {
                                            "Save New Deck (${draft.cards.size} cards)"
                                        } else {
                                            "Append ${draft.cards.size} Cards"
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    items(draft.cards, key = { it.localId }) { cardDraft ->
                        DraftCardPreviewItem(
                            card = cardDraft,
                            onRemove = { onRemoveDraftCard(cardDraft.localId) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                is ForgeUiState.Idle, is ForgeUiState.Error -> {
                    if (forgeState is ForgeUiState.Error) {
                        item {
                            if (forgeState.isQuotaLimit) {
                                SmartQuotaRecoveryCard(
                                    aiUsagePlan = aiUsagePlan,
                                    headline = "Today's standard AI Forge allowance is complete",
                                    localFallbackLabel = "Build Cards Offline ($0 Local Parser)",
                                    showLocalFallbackButton = rawNotes.isNotBlank(),
                                    onClaimGraceCredits = onClaimGraceCredits,
                                    onRetryWithEcoLite = {
                                        onForgeEcoLiteRequested(
                                            selectedSnapshot,
                                            rawNotes,
                                            targetCount,
                                            subjectHint
                                        )
                                    },
                                    onUseLocalFallback = {
                                        onForgeOfflineRequested(rawNotes, targetCount, subjectHint)
                                    },
                                    onOpenByokSettings = onOpenByokSettings
                                )
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = forgeState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Capture / Photo Picker Zone
                    item {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(18.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.forge_step_capture),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                if (selectedSnapshot != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(210.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                    ) {
                                        Image(
                                            bitmap = selectedSnapshot.asImageBitmap(),
                                            contentDescription = "Selected study snapshot",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                            shape = CircleShape,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                        ) {
                                            IconButton(onClick = { onSnapshotSelected(null) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove photo"
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = onOpenCameraCapture,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(50.dp)
                                            .testTag("snap_camera_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = stringResource(id = R.string.cd_snap_photo),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.forge_camerax_scan))
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(50.dp)
                                            .testTag("pick_gallery_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhotoLibrary,
                                            contentDescription = stringResource(id = R.string.cd_pick_gallery),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.forge_gallery))
                                    }
                                }
                            }
                        }
                    }

                    // Notes & Generation Controls
                    item {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(18.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.forge_step_notes),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            if (isDictatingLecture) {
                                                speechRecognizer?.stopListening()
                                                isDictatingLecture = false
                                                livePartialTranscript = ""
                                            } else {
                                                val hasMic = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.RECORD_AUDIO
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (hasMic) {
                                                    startLectureDictation()
                                                } else {
                                                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("forge_dictate_lecture_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isDictatingLecture) Icons.Default.Stop else Icons.Default.Mic,
                                            contentDescription = if (isDictatingLecture) stringResource(R.string.forge_dictate_stop) else stringResource(R.string.forge_dictate_start),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (isDictatingLecture) stringResource(R.string.forge_dictate_stop)
                                            else stringResource(R.string.forge_dictate_start)
                                        )
                                    }
                                }

                                if (livePartialTranscript.isNotBlank() || dictationStatus != null) {
                                    Surface(
                                        color = if (isDictatingLecture) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (livePartialTranscript.isNotBlank()) {
                                                "Live transcript: \"$livePartialTranscript\""
                                            } else {
                                                dictationStatus.orEmpty()
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isDictatingLecture) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = rawNotes,
                                    onValueChange = { if (it.length <= 6000) rawNotes = it },
                                    label = { Text(stringResource(R.string.forge_notes_label)) },
                                    placeholder = { Text("e.g., ATP Synthase: Enzyme driving ATP synthesis via proton gradient...") },
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("forge_notes_input")
                                )

                                OutlinedTextField(
                                    value = subjectHint,
                                    onValueChange = { if (it.length <= 60) subjectHint = it },
                                    label = { Text(stringResource(R.string.forge_subject_label)) },
                                    placeholder = { Text("e.g., Physics 201, Organic Chemistry") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.forge_cards_count),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    listOf(4, 6, 8, 10).forEach { count ->
                                        FilterChip(
                                            selected = targetCount == count,
                                            onClick = { targetCount = count },
                                            label = { Text("$count") }
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        onForgeRequested(selectedSnapshot, rawNotes, targetCount, subjectHint)
                                    },
                                    enabled = selectedSnapshot != null || rawNotes.isNotBlank(),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("forge_generate_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            aiUsagePlan.preferOfflineParser && selectedSnapshot == null -> stringResource(R.string.forge_generate_offline)
                                            aiUsagePlan.ecoLiteMode -> stringResource(R.string.forge_generate_eco_lite)
                                            else -> stringResource(R.string.forge_generate_gemini)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (rawNotes.isNotBlank() && selectedSnapshot == null && !aiUsagePlan.preferOfflineParser) {
                                    OutlinedButton(
                                        onClick = {
                                            onForgeOfflineRequested(rawNotes, targetCount, subjectHint)
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("forge_offline_zero_cost_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OfflineBolt,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.forge_instant_offline_btn))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun GeneratingCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Gemini is parsing your study material...",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Extracting equations, diagrams, and key concepts into Multiple Choice, Fill-in-the-Blank, and Concept Explanation cards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DraftCardPreviewItem(
    card: GeneratedCardDraft,
    onRemove: () -> Unit,
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
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = card.cardType.displayLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Remove generated card",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.question,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (card.cardType == CardType.MULTIPLE_CHOICE && card.options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                card.options.forEachIndexed { idx, opt ->
                    val isRight = idx == card.correctOptionIndex
                    Text(
                        text = "${('A' + idx)}. $opt${if (isRight) "  ✓" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isRight) FontWeight.Bold else FontWeight.Normal,
                        color = if (isRight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Answer: ${card.answer}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Medium
                )
            }
            if (card.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = card.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (_: Exception) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }
}
