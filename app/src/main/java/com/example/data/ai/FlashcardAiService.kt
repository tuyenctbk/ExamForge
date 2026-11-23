package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.local.AiQuotaAction
import com.example.data.local.AiUsagePlanState
import com.example.data.local.Flashcard
import com.example.data.local.PreferencesRepository
import com.example.data.local.encodeOptionsJson
import com.example.data.model.CardType
import com.example.data.model.DeckSourceType
import com.example.data.model.GeneratedCardDraft
import com.example.data.model.GeneratedDeckDraft
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

private const val TAG = "FlashcardAiService"
private const val AI_TIMEOUT_MILLIS = 60_000L
private const val MAX_IMAGE_DIMENSION_PX = 1024

private const val FORGE_SYSTEM_INSTRUCTION =
    "You are ExamForge, an expert university tutor and visual OCR specialist. " +
        "Inspect captured camera photos of handwritten notes, whiteboards, math equations, " +
        "biology diagrams, and lecture slides, or spoken lecture transcripts, and parse them into high-yield structured flashcards. " +
        "Transcribe formulas and diagrams accurately using clean Unicode symbols (∑, ∫, →, ², √, Δ, π) " +
        "where appropriate. Always return a balanced mix of MULTIPLE_CHOICE, FILL_IN_BLANK, and CONCEPT_EXPLAIN cards."

/**
 * Structured flashcard data fields extracted by Gemini or OfflineNoteParser.
 */
data class StructuredFlashcardData(
    val cardType: CardType,
    val question: String,
    val answer: String,
    val explanation: String,
    val hint: String,
    val options: List<String>,
    val correctOptionIndex: Int
)

/**
 * Full structured batch returned by [FlashcardAiService] after analyzing study material.
 */
data class ParsedFlashcardBatch(
    val deckTitle: String,
    val subject: String,
    val summary: String,
    val flashcards: List<StructuredFlashcardData>,
    val generatedOffline: Boolean = false
) {
    fun toCardDrafts(): List<GeneratedCardDraft> = flashcards.map { card ->
        GeneratedCardDraft(
            localId = UUID.randomUUID().toString(),
            cardType = card.cardType,
            question = card.question,
            answer = card.answer,
            explanation = card.explanation,
            hint = card.hint,
            options = card.options,
            correctOptionIndex = card.correctOptionIndex
        )
    }

    fun toGeneratedDeckDraft(sourceType: DeckSourceType = DeckSourceType.PHOTO): GeneratedDeckDraft =
        GeneratedDeckDraft(
            title = deckTitle,
            subject = subject,
            summary = summary,
            sourceType = sourceType,
            cards = toCardDrafts()
        )

    fun toRoomFlashcards(
        userId: String,
        deckId: String,
        nowMillis: Long = System.currentTimeMillis(),
        pendingSync: Boolean = true
    ): List<Flashcard> = flashcards.map { card ->
        val safeOptions = if (card.cardType == CardType.MULTIPLE_CHOICE) {
            card.options.map { it.trim().take(240) }.filter { it.isNotEmpty() }.take(6)
        } else {
            emptyList()
        }
        val safeCorrectIndex = if (card.cardType == CardType.MULTIPLE_CHOICE && safeOptions.isNotEmpty()) {
            card.correctOptionIndex.coerceIn(0, safeOptions.lastIndex)
        } else {
            -1
        }
        Flashcard(
            id = "card_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            userId = userId,
            deckId = deckId,
            cardType = card.cardType.name,
            question = card.question.trim().ifBlank { "Review concept" }.take(1000),
            answer = card.answer.trim().ifBlank { "See explanation" }.take(1500),
            explanation = card.explanation.trim().take(1500),
            hint = card.hint.trim().take(300),
            optionsJson = encodeOptionsJson(safeOptions),
            correctOptionIndex = safeCorrectIndex,
            studyProgress = 0,
            easeFactor = 2.5,
            intervalDays = 0,
            repetitions = 0,
            dueAtMillis = nowMillis,
            lastReviewDate = 0L,
            pendingSync = pendingSync
        )
    }
}

/**
 * 3-Layer Hybrid Flashcard AI Service:
 * 1. User BYOK Gemini Key (Unlimited, $0 developer API cost)
 * 2. Built-in Firebase AI Logic (`gemini-3.8-flash` via App Check, guarded by Smart Daily Quota)
 * 3. Zero-Cost Graceful Fallback via [OfflineNoteParser] whenever offline, quota-exhausted, or preferred
 */
class FlashcardAiService(
    private val preferencesRepository: PreferencesRepository? = null,
    private val byokClient: ByokGeminiClient = ByokGeminiClient()
) {
    private val forgeDeduplicator = AiRequestDeduplicator<ParsedFlashcardBatch>()
    private val factCheckDeduplicator = AiRequestDeduplicator<FactCheckResult>()

    private val structuredFlashcardSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "deckTitle" to Schema.string(
                    description = "Concise, descriptive study deck title summarizing the captured notes (max 80 chars)"
                ),
                "subject" to Schema.string(
                    description = "Academic subject category such as Calculus, Biology, Organic Chemistry, Physics, History, CS"
                ),
                "summary" to Schema.string(
                    description = "1-2 sentence overview of the core concepts parsed from the study material"
                ),
                "flashcards" to Schema.array(
                    description = "Structured active-recall flashcards parsed from the content",
                    items = Schema.obj(
                        properties = mapOf(
                            "cardType" to Schema.enumeration(
                                values = listOf("MULTIPLE_CHOICE", "FILL_IN_BLANK", "CONCEPT_EXPLAIN"),
                                description = "Question format for active recall"
                            ),
                            "question" to Schema.string(
                                description = "Clear active-recall prompt. For FILL_IN_BLANK use ___ for the omitted key term or formula."
                            ),
                            "answer" to Schema.string(
                                description = "Exact correct answer, missing term, or concise model explanation."
                            ),
                            "explanation" to Schema.string(
                                description = "Clear teaching rationale, step-by-step derivation, or explanation of why alternatives are wrong."
                            ),
                            "hint" to Schema.string(
                                description = "Short helpful nudge without revealing the full answer."
                            ),
                            "options" to Schema.array(
                                items = Schema.string(),
                                description = "Exactly 4 plausible choices for MULTIPLE_CHOICE; empty array for other card types."
                            ),
                            "correctOptionIndex" to Schema.integer(
                                description = "0-based index (0..3) of the correct option for MULTIPLE_CHOICE; -1 for other types."
                            )
                        )
                    )
                )
            )
        )
    }

    private val factCheckSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "verdict" to Schema.enumeration(
                    values = listOf("VERIFIED", "PARTIAL_NUANCE", "NEEDS_CORRECTION"),
                    description = "Accuracy verdict for the student's flashcard answer"
                ),
                "summary" to Schema.string(
                    description = "1-2 sentence verification summary"
                ),
                "improvedAnswer" to Schema.string(
                    description = "Polished or corrected concise answer ready to save on the flashcard"
                ),
                "detailedExplanation" to Schema.string(
                    description = "2-4 sentence explanation noting key mechanisms, formulas, or caveats"
                )
            )
        )
    }

    // Lazily initialized so Firebase.ai is only evaluated after App Check configuration
    private val flashcardParserPrimaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelConfig.PRIMARY_STUDY_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = structuredFlashcardSchema
                temperature = 0.35f
            },
            systemInstruction = content { text(FORGE_SYSTEM_INSTRUCTION) }
        )
    }

    private val flashcardParserEcoLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelConfig.ECO_LITE_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = structuredFlashcardSchema
                temperature = 0.35f
            },
            systemInstruction = content { text(FORGE_SYSTEM_INSTRUCTION) }
        )
    }

    private val factCheckPrimaryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelConfig.PRIMARY_STUDY_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = factCheckSchema
                temperature = 0.2f
            },
            systemInstruction = content {
                text(
                    "You are an academic fact-checker reviewing student flashcards for exam accuracy. " +
                        "Identify factual errors, outdated values, or missing nuances, and provide a crisp improved answer."
                )
            }
        )
    }

    private val factCheckEcoLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelConfig.ECO_LITE_MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = factCheckSchema
                temperature = 0.2f
            },
            systemInstruction = content {
                text(
                    "You are an academic fact-checker reviewing student flashcards for exam accuracy. " +
                        "Identify factual errors, outdated values, or missing nuances, and provide a crisp improved answer."
                )
            }
        )
    }

    suspend fun parseImageToFlashcards(
        capturedImage: Bitmap,
        targetCardCount: Int = 6,
        subjectHint: String = "",
        additionalNotes: String = ""
    ): ParsedFlashcardBatch {
        return parseStudyMaterial(
            bitmap = capturedImage,
            rawNotes = additionalNotes,
            targetCardCount = targetCardCount,
            subjectHint = subjectHint
        )
    }

    suspend fun parseImageToRoomFlashcards(
        capturedImage: Bitmap,
        userId: String,
        deckId: String,
        targetCardCount: Int = 6,
        subjectHint: String = ""
    ): List<Flashcard> {
        val batch = parseImageToFlashcards(
            capturedImage = capturedImage,
            targetCardCount = targetCardCount,
            subjectHint = subjectHint
        )
        return batch.toRoomFlashcards(userId = userId, deckId = deckId)
    }

    /**
     * Multimodal parser supporting a captured camera [Bitmap], pasted/dictated notes, or both,
     * with automatic BYOK routing, smart daily quota guardrails, and graceful offline fallback.
     */
    suspend fun parseStudyMaterial(
        bitmap: Bitmap?,
        rawNotes: String = "",
        targetCardCount: Int = 6,
        subjectHint: String = "",
        forceOffline: Boolean = false,
        forceEcoLite: Boolean = false
    ): ParsedFlashcardBatch {
        require(bitmap != null || rawNotes.isNotBlank()) {
            "Capture a photo, speak a lecture snippet, or paste notes to forge flashcards."
        }

        val safeCount = targetCardCount.coerceIn(3, 12)
        val plan = preferencesRepository?.getAiUsagePlanSnapshot()
        val byokKey = preferencesRepository?.getDecryptedByokGeminiKey()?.takeIf { it.isNotBlank() }

        // Layer 0: Explicit offline parser or user-preferred zero-cost mode for text/voice notes
        if ((forceOffline || plan?.preferOfflineParser == true) && bitmap == null && rawNotes.isNotBlank()) {
            return OfflineNoteParser.parseNotesOffline(
                rawNotes = rawNotes,
                targetCardCount = safeCount,
                subjectHint = subjectHint
            ).copy(generatedOffline = true)
        }

        // Token diet: keep multimodal bitmap <= 1024px to minimize bandwidth & token cost
        val preparedBitmap = bitmap?.let { downscaleForPayloadLimit(it, MAX_IMAGE_DIMENSION_PX) }

        // Token hygiene: 40s fingerprint deduplication prevents double-tap quota waste
        val fingerprint = AiRequestDeduplicator.fingerprint(
            operation = "forge",
            textPayload = "$safeCount|$subjectHint|$rawNotes",
            bitmap = preparedBitmap,
            extraTag = if (forceEcoLite) "eco" else "std"
        )
        forgeDeduplicator.getIfFresh(fingerprint)?.let { cached ->
            Log.d(TAG, "Returning deduplicated forge batch (0 extra tokens)")
            return cached
        }

        val promptText = buildPromptText(safeCount, subjectHint, rawNotes)
        val startWithEcoLite = AiModelConfig.shouldPreferEcoLite(
            ecoLiteMode = plan?.ecoLiteMode == true,
            hasByokKey = !byokKey.isNullOrBlank(),
            usageRatio = plan?.forgeUsageRatio ?: 0f,
            forceEcoLite = forceEcoLite
        )

        // Layer 1: BYOK Unlimited (user's own Gemini API key)
        if (!byokKey.isNullOrBlank()) {
            return try {
                val jsonText = byokClient.generateStructuredFlashcardsJson(
                    apiKey = byokKey,
                    bitmap = preparedBitmap,
                    promptText = promptText,
                    systemInstruction = FORGE_SYSTEM_INSTRUCTION,
                    preferEcoLite = startWithEcoLite
                )
                parseStructuredJson(jsonText).also { forgeDeduplicator.put(fingerprint, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (bitmap == null && rawNotes.isNotBlank()) {
                    Log.w(TAG, "BYOK call failed; gracefully falling back to OfflineNoteParser", e)
                    OfflineNoteParser.parseNotesOffline(rawNotes, safeCount, subjectHint).copy(
                        summary = "Cloud AI unavailable (${e.localizedMessage?.take(60) ?: "offline"}) — built locally at $0 cost.",
                        generatedOffline = true
                    )
                } else {
                    throw e
                }
            }
        }

        // Layer 2: Built-in Smart Free Plan (Firebase AI Logic with daily quota + grace credits)
        if (plan != null && plan.remainingForges <= 0 && !forceEcoLite) {
            if (bitmap == null && rawNotes.isNotBlank()) {
                return OfflineNoteParser.parseNotesOffline(rawNotes, safeCount, subjectHint).copy(
                    summary = "Daily free AI quota (${AiUsagePlanState.FREE_DAILY_FORGE_LIMIT}/day) reached — built via Offline Mode. Claim +3 Grace Credits or add a free BYOK key in Settings.",
                    generatedOffline = true
                )
            }
            throw AiQuotaExceededException(
                actionName = "AI Forge",
                dailyLimit = plan.dailyForgeLimit
            )
        }

        val requestContent = content {
            if (preparedBitmap != null) {
                image(preparedBitmap)
            }
            text(promptText)
        }

        return try {
            val primaryModel = if (startWithEcoLite) flashcardParserEcoLiteModel else flashcardParserPrimaryModel
            val response = try {
                withTimeout(AI_TIMEOUT_MILLIS) { primaryModel.generateContent(requestContent) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!startWithEcoLite && AiModelConfig.isRateLimitOrQuotaError(e)) {
                    Log.w(TAG, "Tier 1 model rate-limited; auto-cascading to ${AiModelConfig.ECO_LITE_MODEL}", e)
                    preferencesRepository?.recordFallbackCascade()
                    withTimeout(AI_TIMEOUT_MILLIS) { flashcardParserEcoLiteModel.generateContent(requestContent) }
                } else {
                    throw e
                }
            }

            val jsonText = response.text
            if (jsonText.isNullOrBlank()) {
                throw IllegalStateException("Gemini returned an empty response. Try a clearer photo with better lighting.")
            }

            val parsed = parseStructuredJson(jsonText)
            // Atomic deduct-only-on-success
            preferencesRepository?.recordFreeTierUsage(AiQuotaAction.FORGE)
            forgeDeduplicator.put(fingerprint, parsed)
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (bitmap == null && rawNotes.isNotBlank()) {
                Log.w(TAG, "Cloud AI forge failed; gracefully falling back to OfflineNoteParser", e)
                OfflineNoteParser.parseNotesOffline(rawNotes, safeCount, subjectHint).copy(
                    summary = "Switched to Offline Instant Parser ($0 cost) while cloud AI is unreachable.",
                    generatedOffline = true
                )
            } else {
                throw e
            }
        }
    }

    /**
     * Fact-checks a single flashcard with 40s deduplication and Tier 1 -> Tier 2 Eco Lite cascade.
     */
    suspend fun verifyFlashcard(
        question: String,
        answer: String,
        subject: String
    ): FactCheckResult {
        val fingerprint = AiRequestDeduplicator.fingerprint(
            operation = "fact_check",
            textPayload = "$subject|$question|$answer"
        )
        factCheckDeduplicator.getIfFresh(fingerprint)?.let { cached ->
            return cached
        }

        val byokKey = preferencesRepository?.getDecryptedByokGeminiKey()?.takeIf { it.isNotBlank() }
        if (!byokKey.isNullOrBlank()) {
            return byokClient.factCheckFlashcardWithSearch(
                apiKey = byokKey,
                question = question,
                answer = answer,
                subject = subject
            ).also { factCheckDeduplicator.put(fingerprint, it) }
        }

        val plan = preferencesRepository?.getAiUsagePlanSnapshot()
        if (plan != null && plan.remainingFactChecks <= 0) {
            throw AiQuotaExceededException(
                actionName = "Fact-Check",
                dailyLimit = plan.dailyFactCheckLimit
            )
        }

        val prompt = buildString {
            append("Fact-check this exam flashcard for academic accuracy.\n")
            append("Subject: ${subject.ifBlank { "General" }}\n")
            append("Question: $question\n")
            append("Current Answer: $answer\n")
        }

        val preferEco = AiModelConfig.shouldPreferEcoLite(
            ecoLiteMode = plan?.ecoLiteMode == true,
            hasByokKey = false,
            usageRatio = plan?.factCheckUsageRatio ?: 0f
        )
        var usedEcoLite = preferEco
        val response = try {
            val activeModel = if (preferEco) factCheckEcoLiteModel else factCheckPrimaryModel
            withTimeout(AI_TIMEOUT_MILLIS) { activeModel.generateContent(prompt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!preferEco && AiModelConfig.isRateLimitOrQuotaError(e)) {
                usedEcoLite = true
                preferencesRepository?.recordFallbackCascade()
                withTimeout(AI_TIMEOUT_MILLIS) { factCheckEcoLiteModel.generateContent(prompt) }
            } else {
                throw e
            }
        }

        val jsonText = response.text
            ?: throw IllegalStateException("Fact-check returned an empty response.")
        val root = JSONObject(jsonText)
        preferencesRepository?.recordFreeTierUsage(AiQuotaAction.FACT_CHECK)

        val tierTag = if (usedEcoLite) "Eco Lite AI" else "Smart Free Plan"
        return FactCheckResult(
            verdict = FactCheckVerdict.fromRaw(root.optString("verdict")),
            summary = root.optString("summary", "Review completed.").trim(),
            improvedAnswer = root.optString("improvedAnswer", answer).trim().ifBlank { answer },
            detailedExplanation = root.optString("detailedExplanation", "").trim(),
            sources = emptyList(),
            poweredByLabel = "$tierTag · Add a free BYOK key in Settings for live Google Search citations"
        ).also { factCheckDeduplicator.put(fingerprint, it) }
    }

    suspend fun verifyByokKey(apiKey: String): Result<String> = byokClient.verifyApiKey(apiKey)

    private fun buildPromptText(safeCount: Int, subjectHint: String, rawNotes: String): String = buildString {
        append("Parse the provided study material into exactly $safeCount structured active-recall flashcards.\n")
        if (subjectHint.isNotBlank()) {
            append("Subject hint: ${subjectHint.trim()}\n")
        }
        append(
            "Create a balanced mix of MULTIPLE_CHOICE (4 realistic options with accurate 0-based correctOptionIndex), " +
                "FILL_IN_BLANK (use ___ for the missing term or formula), and CONCEPT_EXPLAIN flashcards.\n"
        )
        if (rawNotes.isNotBlank()) {
            append("\nStudent notes / spoken lecture transcript:\n${rawNotes.trim().take(6000)}")
        }
    }

    private fun parseStructuredJson(jsonString: String): ParsedFlashcardBatch {
        try {
            val root = JSONObject(jsonString)
            val deckTitle = root.optString("deckTitle", "Captured Study Deck").trim()
                .ifBlank { "Captured Study Deck" }
                .take(120)
            val subject = root.optString("subject", "General").trim()
                .ifBlank { "General" }
                .take(60)
            val summary = root.optString("summary", "").trim().take(400)
            val cardsArray = root.optJSONArray("flashcards")

            val parsedCards = mutableListOf<StructuredFlashcardData>()
            if (cardsArray != null) {
                for (i in 0 until cardsArray.length()) {
                    val item = cardsArray.optJSONObject(i) ?: continue
                    val rawType = CardType.fromRaw(item.optString("cardType"))
                    val question = item.optString("question", "").trim().take(1000)
                    val answer = item.optString("answer", "").trim().take(1500)
                    if (question.isBlank() || answer.isBlank()) continue

                    val explanation = item.optString("explanation", "").trim().take(1500)
                    val hint = item.optString("hint", "").trim().take(300)

                    val optionsList = mutableListOf<String>()
                    val optionsArray = item.optJSONArray("options")
                    if (optionsArray != null) {
                        for (j in 0 until optionsArray.length()) {
                            val opt = optionsArray.optString(j, "").trim().take(240)
                            if (opt.isNotEmpty()) optionsList.add(opt)
                        }
                    }

                    val effectiveType = if (rawType == CardType.MULTIPLE_CHOICE && optionsList.size < 2) {
                        CardType.CONCEPT_EXPLAIN
                    } else {
                        rawType
                    }
                    val finalOptions = if (effectiveType == CardType.MULTIPLE_CHOICE) {
                        optionsList.take(4)
                    } else {
                        emptyList()
                    }
                    val correctIndex = if (effectiveType == CardType.MULTIPLE_CHOICE && finalOptions.isNotEmpty()) {
                        item.optInt("correctOptionIndex", 0).coerceIn(0, finalOptions.lastIndex)
                    } else {
                        -1
                    }

                    parsedCards.add(
                        StructuredFlashcardData(
                            cardType = effectiveType,
                            question = question,
                            answer = answer,
                            explanation = explanation,
                            hint = hint,
                            options = finalOptions,
                            correctOptionIndex = correctIndex
                        )
                    )
                }
            }

            if (parsedCards.isEmpty()) {
                throw IllegalStateException(
                    "No structured flashcards could be parsed. Try retaking the photo closer to the notes."
                )
            }

            return ParsedFlashcardBatch(
                deckTitle = deckTitle,
                subject = subject,
                summary = summary,
                flashcards = parsedCards
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse structured Gemini flashcard JSON", e)
            throw e
        }
    }

    private fun downscaleForPayloadLimit(source: Bitmap, maxDim: Int): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= maxDim && height <= maxDim) return source
        val scale = maxDim.toFloat() / maxOf(width, height).toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
