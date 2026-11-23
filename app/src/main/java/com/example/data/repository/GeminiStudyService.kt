package com.example.data.repository

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ai.AiModelConfig
import com.example.data.ai.ByokGeminiClient
import com.example.data.ai.FactCheckResult
import com.example.data.ai.FlashcardAiService
import com.example.data.ai.LocalQuizmasterFallback
import com.example.data.local.AiQuotaAction
import com.example.data.local.PreferencesRepository
import com.example.data.model.DeckSourceType
import com.example.data.model.Flashcard
import com.example.data.model.GeneratedDeckDraft
import com.example.data.model.QuizMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

private const val TAG = "GeminiStudyService"
private const val AI_TIMEOUT_MS = 60_000L

private const val QUIZMASTER_SYSTEM_INSTRUCTION =
    "You are the ExamForge Oral Quizmaster — a sharp, encouraging active-recall coach. " +
        "Conduct a focused oral/chat quiz on the student's study deck. " +
        "Rules: (1) Ask ONE crisp, high-yield question at a time. " +
        "(2) When the student answers (by voice or text), start with a verdict badge like [Mastered], [Almost There], or [Review Needed], " +
        "give 2-3 concise sentences of sharp feedback highlighting any missed mechanism/formula, and then immediately ask the next question. " +
        "(3) Keep responses concise and natural to listen to aloud — avoid giant walls of markdown or bullet spam. " +
        "(4) If the student says 'hint', give a gentle nudge without revealing the full answer."

class GeminiStudyService(
    private val preferencesRepository: PreferencesRepository? = null,
    private val byokClient: ByokGeminiClient = ByokGeminiClient(),
    private val flashcardAiService: FlashcardAiService = FlashcardAiService(
        preferencesRepository = preferencesRepository,
        byokClient = byokClient
    )
) {

    private val quizmasterPrimaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelConfig.PRIMARY_STUDY_MODEL,
            generationConfig = generationConfig {
                temperature = 0.65f
            },
            systemInstruction = content { text(QUIZMASTER_SYSTEM_INSTRUCTION) }
        )
    }

    private val quizmasterEcoLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelConfig.ECO_LITE_MODEL,
            generationConfig = generationConfig {
                temperature = 0.65f
            },
            systemInstruction = content { text(QUIZMASTER_SYSTEM_INSTRUCTION) }
        )
    }

    suspend fun generateDeckFromSnapshot(
        bitmap: Bitmap?,
        rawNotes: String,
        targetCardCount: Int = 6,
        subjectHint: String = "",
        forceOffline: Boolean = false,
        forceEcoLite: Boolean = false
    ): GeneratedDeckDraft {
        val sourceType = if (bitmap != null) DeckSourceType.PHOTO else DeckSourceType.NOTES
        val parsedBatch = flashcardAiService.parseStudyMaterial(
            bitmap = bitmap,
            rawNotes = rawNotes,
            targetCardCount = targetCardCount,
            subjectHint = subjectHint,
            forceOffline = forceOffline,
            forceEcoLite = forceEcoLite
        )
        return parsedBatch.toGeneratedDeckDraft(sourceType = sourceType)
    }

    suspend fun verifyFlashcard(
        question: String,
        answer: String,
        subject: String
    ): FactCheckResult {
        return flashcardAiService.verifyFlashcard(
            question = question,
            answer = answer,
            subject = subject
        )
    }

    suspend fun verifyByokKey(apiKey: String): Result<String> {
        return flashcardAiService.verifyByokKey(apiKey)
    }

    /**
     * 3-Tier Oral Quizmaster streaming:
     * - Tier 1: BYOK or Primary Cloud (`gemini-3.8-flash`)
     * - Tier 2: Eco Lite (`gemini-3.5-flash-lite`) when Eco Lite is on, >= 80% quota, or on 429 cascade
     * - Tier 3: Instant Zero-Cost Local Reflex Coach ([LocalQuizmasterFallback]) when offline or quota is 0
     */
    fun streamQuizmasterReply(
        deckTitle: String,
        deckSubject: String,
        cards: List<Flashcard>,
        history: List<QuizMessage>,
        userMessage: String,
        isOralMode: Boolean
    ): Flow<String> = flow {
        val plan = preferencesRepository?.getAiUsagePlanSnapshot()
        val byokKey = preferencesRepository?.getDecryptedByokGeminiKey()?.takeIf { it.isNotBlank() }

        // Tier 3 direct route: Offline Zero-Cost preference or exhausted daily quiz quota
        if (plan?.preferOfflineParser == true || (byokKey.isNullOrBlank() && plan != null && plan.remainingQuizTurns <= 0)) {
            emit(
                LocalQuizmasterFallback.generateReply(
                    deckTitle = deckTitle,
                    cards = cards,
                    history = history,
                    userInput = userMessage
                )
            )
            return@flow
        }

        val deckDigest = buildString {
            append("Active Study Deck: $deckTitle ($deckSubject)\n")
            append("Reference Flashcards (${cards.size} total):\n")
            cards.take(18).forEachIndexed { index, card ->
                append("${index + 1}. [${card.cardType.shortBadge}] Q: ${card.question} | A: ${card.answer}")
                if (card.explanation.isNotBlank()) {
                    append(" | Why: ${card.explanation.take(160)}")
                }
                append("\n")
            }
            if (isOralMode) {
                append("\nNote: Oral voice mode is ON. Speak naturally in short, punchy sentences ideal for text-to-speech readout.")
            }
        }
        val deckContextPrompt = "Here is my study deck context for today's quiz:\n$deckDigest"
        val deckAckReply = "Got it! I have your $deckTitle deck loaded and ready to quiz you."

        val preferEcoLite = AiModelConfig.shouldPreferEcoLite(
            ecoLiteMode = plan?.ecoLiteMode == true,
            hasByokKey = !byokKey.isNullOrBlank(),
            usageRatio = plan?.quizUsageRatio ?: 0f
        )

        // Layer 1: BYOK Unlimited (user's own Gemini key -> $0 dev API cost)
        if (!byokKey.isNullOrBlank()) {
            try {
                val reply = byokClient.generateQuizmasterReply(
                    apiKey = byokKey,
                    systemInstruction = QUIZMASTER_SYSTEM_INSTRUCTION,
                    deckContextPrompt = deckContextPrompt,
                    deckAckReply = deckAckReply,
                    history = history,
                    userMessage = userMessage,
                    preferEcoLite = preferEcoLite
                )
                emit(reply)
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "BYOK Quizmaster failed; falling back to LocalQuizmasterFallback", e)
                emit(LocalQuizmasterFallback.generateReply(deckTitle, cards, history, userMessage))
                return@flow
            }
        }

        // Compact sliding context window (last 8 turns) to stretch free-tier tokens
        val chatHistory = buildList {
            add(content("user") { text(deckContextPrompt) })
            add(content("model") { text(deckAckReply) })
            history.takeLast(AiModelConfig.SLIDING_CHAT_WINDOW_TURNS).forEach { msg ->
                if (msg.text.isNotBlank()) {
                    add(content(msg.role) { text(msg.text) })
                }
            }
        }

        suspend fun streamFromModel(useEco: Boolean): String {
            val activeModel = if (useEco) quizmasterEcoLiteModel else quizmasterPrimaryModel
            val chat = activeModel.startChat(history = chatHistory)
            val accumulated = StringBuilder()
            withTimeout(AI_TIMEOUT_MS) {
                chat.sendMessageStream(userMessage).collect { chunk ->
                    val part = chunk.text.orEmpty()
                    if (part.isNotEmpty()) {
                        accumulated.append(part)
                        emit(accumulated.toString())
                    }
                }
            }
            return accumulated.toString()
        }

        try {
            val result = try {
                streamFromModel(preferEcoLite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!preferEcoLite && AiModelConfig.isRateLimitOrQuotaError(e)) {
                    Log.w(TAG, "Quizmaster Tier 1 rate-limited; auto-cascading to ${AiModelConfig.ECO_LITE_MODEL}", e)
                    preferencesRepository?.recordFallbackCascade()
                    streamFromModel(true)
                } else {
                    throw e
                }
            }

            if (result.isBlank()) {
                emit(LocalQuizmasterFallback.generateReply(deckTitle, cards, history, userMessage))
            } else {
                preferencesRepository?.recordFreeTierUsage(AiQuotaAction.QUIZMASTER)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud Quizmaster unreachable; seamlessly degrading to LocalQuizmasterFallback", e)
            emit(LocalQuizmasterFallback.generateReply(deckTitle, cards, history, userMessage))
        }
    }
}
