package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.material3.TextButton
import com.example.R
import com.example.data.local.AiUsagePlanState
import com.example.data.model.QuizMessage
import com.example.data.model.StudyDeck
import com.example.ui.components.QuotaSoftNudgeBanner
import com.example.ui.viewmodel.QuizmasterUiState
import java.util.Locale

@Composable
fun QuizmasterScreen(
    decks: List<StudyDeck>,
    quizState: QuizmasterUiState,
    oralAutoSpeak: Boolean,
    aiUsagePlan: AiUsagePlanState,
    onToggleOralAutoSpeak: (Boolean) -> Unit,
    onSelectDeck: (String) -> Unit,
    onStartSession: (deckId: String, oralMode: Boolean) -> Unit,
    onSendMessage: (text: String, oralMode: Boolean) -> Unit,
    onClearSpokenReply: () -> Unit,
    onResetConversation: () -> Unit,
    onEnableEcoLite: () -> Unit,
    onClaimGraceCredits: () -> Unit,
    onOpenByokSettings: () -> Unit,
    onOpenForge: () -> Unit
) {
    val context = LocalContext.current
    var draftInput by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val activeDeckId = quizState.selectedDeckId ?: decks.firstOrNull()?.id

    // Text-to-Speech engine for Oral Quizmaster voice readout
    val tts = remember(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.US
                ttsReady = true
            }
        }
        engine
    }

    // SpeechRecognizer for reciting answers aloud
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(speechRecognizer, tts) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                voiceNotice = "Listening... recite your answer aloud."
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                voiceNotice = "Didn't catch that — tap the mic to try again or type below."
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!spoken.isNullOrBlank()) {
                    voiceNotice = null
                    onSendMessage(spoken, oralAutoSpeak)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    draftInput = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            try {
                tts.stop()
                tts.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    val startVoiceCapture: () -> Unit = {
        if (speechRecognizer == null) {
            voiceNotice = "Speech recognition is unavailable on this device. Type your answer below."
        } else {
            tts.stop()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            try {
                speechRecognizer.startListening(intent)
            } catch (_: Exception) {
                isListening = false
                voiceNotice = "Unable to start microphone right now."
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceNotice = null
            startVoiceCapture()
        } else {
            voiceNotice = "Microphone permission denied. You can still chat by typing below."
        }
    }

    // Automatically read finished AI replies aloud when Oral Mode is enabled
    LaunchedEffect(quizState.lastCompletedAiReply, oralAutoSpeak, ttsReady) {
        val reply = quizState.lastCompletedAiReply
        if (!reply.isNullOrBlank() && oralAutoSpeak && ttsReady) {
            tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "quiz_reply")
            onClearSpokenReply()
        }
    }

    // Keep chat scrolled to newest turn
    LaunchedEffect(quizState.messages.size, quizState.messages.lastOrNull()?.text?.length) {
        if (quizState.messages.isNotEmpty()) {
            listState.animateScrollToItem(quizState.messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Header row with Oral Voice readout toggle & Reset
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.quizmaster_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.quizmaster_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (oralAutoSpeak) tts.stop()
                            onToggleOralAutoSpeak(!oralAutoSpeak)
                        },
                        modifier = Modifier.testTag("quiz_tts_toggle")
                    ) {
                        Icon(
                            imageVector = if (oralAutoSpeak) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = stringResource(id = R.string.cd_speak_toggle),
                            tint = if (oralAutoSpeak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (quizState.messages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                tts.stop()
                                onResetConversation()
                            },
                            modifier = Modifier.testTag("quiz_reset_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Restart quiz"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (decks.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.quizmaster_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Snap a whiteboard or paste lecture notes in the Forge tab first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = onOpenForge) {
                            Text(stringResource(R.string.quizmaster_go_to_forge))
                        }
                    }
                }
                return@Column
            }

            // Deck selector chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(decks, key = { it.id }) { deck ->
                    FilterChip(
                        selected = activeDeckId == deck.id,
                        onClick = {
                            tts.stop()
                            onSelectDeck(deck.id)
                        },
                        label = { Text(deck.title.take(24)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stage 1 Proactive Soft Nudge Banner at >= 80% daily quiz turns
            if (aiUsagePlan.isApproachingQuizLimit) {
                QuotaSoftNudgeBanner(
                    featureLabel = "Cloud Quizmaster",
                    remainingCount = aiUsagePlan.remainingQuizTurns,
                    resetLabel = aiUsagePlan.timeUntilResetLabel(),
                    ecoLiteEnabled = aiUsagePlan.ecoLiteMode,
                    onSwitchToEcoLite = onEnableEcoLite,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else if (!aiUsagePlan.hasByokKey && (aiUsagePlan.preferOfflineParser || aiUsagePlan.remainingQuizTurns <= 0)) {
                // Tier 3 Seamless Local Reflex Coach Notice (non-blocking — quizzing never stops!)
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("quiz_local_reflex_banner")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Offline Reflex Coach Active ($0 Quota)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Instant on-device grading active • ${aiUsagePlan.timeUntilResetLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (aiUsagePlan.canClaimGraceCredits) {
                            TextButton(onClick = onClaimGraceCredits) {
                                Text("+3 Cloud")
                            }
                        } else {
                            TextButton(onClick = onOpenByokSettings) {
                                Text("BYOK")
                            }
                        }
                    }
                }
            }

            // Error / voice status banners
            if (quizState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = quizState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (voiceNotice != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = voiceNotice ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Conversation list or Launch Prompt
            if (quizState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
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
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = CircleShape,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.quizmaster_ready_title),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.quizmaster_ready_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = {
                                    activeDeckId?.let { onStartSession(it, oralAutoSpeak) }
                                },
                                enabled = activeDeckId != null && !quizState.isStreaming,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("start_oral_quiz_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.quizmaster_start_button), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(quizState.messages, key = { it.id }) { msg ->
                        QuizChatBubble(message = msg)
                    }
                }

                // Quick follow-up chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSendMessage("Give me a hint", oralAutoSpeak) },
                        enabled = !quizState.isStreaming,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.quizmaster_hint_chip), style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { onSendMessage("Skip and ask the next question", oralAutoSpeak) },
                        enabled = !quizState.isStreaming,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.quizmaster_next_chip), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Bottom composer with Voice Mic + Text input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp)
            ) {
                Surface(
                    color = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isListening) {
                                speechRecognizer?.stopListening()
                                isListening = false
                            } else {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    startVoiceCapture()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !quizState.isStreaming,
                        modifier = Modifier.testTag("quiz_mic_button")
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = stringResource(id = R.string.cd_voice_mic),
                            tint = if (isListening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                OutlinedTextField(
                    value = draftInput,
                    onValueChange = { if (it.length <= 1000) draftInput = it },
                    placeholder = { Text(stringResource(R.string.quizmaster_input_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quiz_text_input")
                )

                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    IconButton(
                        onClick = {
                            val text = draftInput.trim()
                            if (text.isNotEmpty()) {
                                draftInput = ""
                                tts.stop()
                                onSendMessage(text, oralAutoSpeak)
                            }
                        },
                        enabled = draftInput.isNotBlank() && !quizState.isStreaming,
                        modifier = Modifier.testTag("quiz_send_button")
                    ) {
                        if (quizState.isStreaming) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send answer",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizChatBubble(message: QuizMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp
            ),
            border = if (!isUser) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = if (isUser) "YOU" else "QUIZMASTER",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.text.ifBlank { "Thinking..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
