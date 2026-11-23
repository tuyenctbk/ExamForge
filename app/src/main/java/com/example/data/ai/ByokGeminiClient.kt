package com.example.data.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.data.model.QuizMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "ByokGeminiClient"
private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
private const val BYOK_TIMEOUT_MS = 60_000L
private const val JPEG_QUALITY = 78

/**
 * Direct REST client used ONLY when the user opts into Bring Your Own Key (BYOK) in Settings.
 * Sends requests using the user's locally-encrypted Gemini API key so API usage is billed
 * exclusively to the user's own account ($0 developer API cost).
 */
class ByokGeminiClient {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Verifies a user-provided Gemini API key using the lightweight model metadata GET endpoint
     * (consumes zero generation tokens).
     */
    suspend fun verifyApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val cleaned = apiKey.trim()
        if (cleaned.length < 20) {
            return@withContext Result.failure(IllegalArgumentException("API key looks too short."))
        }
        try {
            val modelId = AiModelConfig.PRIMARY_STUDY_MODEL
            val request = Request.Builder()
                .url("$GEMINI_BASE_URL/$modelId?key=$cleaned")
                .get()
                .build()
            withTimeout(20_000L) {
                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val displayName = runCatching {
                            JSONObject(bodyStr).optString("displayName", modelId)
                        }.getOrDefault(modelId)
                        Result.success("Connected to $displayName (BYOK Unlimited active)")
                    } else {
                        val apiMessage = extractApiErrorMessage(bodyStr)
                        Result.failure(IllegalStateException(apiMessage ?: "Invalid key (HTTP ${response.code})."))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "BYOK key verification failed", e)
            Result.failure(IllegalStateException(e.localizedMessage ?: "Network error while checking API key."))
        }
    }

    /**
     * Multimodal structured JSON flashcard generation using the user's own Gemini API key.
     * Applies aggressive JPEG compression (~78% quality) to minimize user token consumption.
     */
    suspend fun generateStructuredFlashcardsJson(
        apiKey: String,
        bitmap: Bitmap?,
        promptText: String,
        systemInstruction: String,
        preferEcoLite: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val parts = JSONArray()
        if (bitmap != null) {
            val jpegBytes = compressBitmapToJpeg(bitmap)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                )
            )
        }
        parts.put(JSONObject().put("text", promptText))

        val payload = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            .put(
                "contents",
                JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.35)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", buildFlashcardResponseSchemaJson())
            )

        val initialModel = if (preferEcoLite) AiModelConfig.ECO_LITE_MODEL else AiModelConfig.PRIMARY_STUDY_MODEL
        val responseJson = executeWithEcoCascade(apiKey, payload, initialModel)
        extractCandidateText(responseJson)
            ?: throw IllegalStateException("Gemini (BYOK) returned an empty flashcard response.")
    }

    /**
     * Multi-turn Oral Quizmaster generation using the user's own Gemini API key.
     */
    suspend fun generateQuizmasterReply(
        apiKey: String,
        systemInstruction: String,
        deckContextPrompt: String,
        deckAckReply: String,
        history: List<QuizMessage>,
        userMessage: String,
        preferEcoLite: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val contents = JSONArray()
        contents.put(buildTurnJson("user", deckContextPrompt))
        contents.put(buildTurnJson("model", deckAckReply))
        history.takeLast(AiModelConfig.SLIDING_CHAT_WINDOW_TURNS).forEach { msg ->
            if (msg.text.isNotBlank()) {
                contents.put(buildTurnJson(msg.role, msg.text))
            }
        }
        contents.put(buildTurnJson("user", userMessage))

        val payload = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.65))

        val initialModel = if (preferEcoLite) AiModelConfig.ECO_LITE_MODEL else AiModelConfig.PRIMARY_STUDY_MODEL
        val responseJson = executeWithEcoCascade(apiKey, payload, initialModel)
        extractCandidateText(responseJson)
            ?: throw IllegalStateException("Quizmaster (BYOK) returned an empty reply.")
    }

    /**
     * Verifies a flashcard's accuracy using Gemini + real-time Google Search grounding (`google_search` tool).
     * Extracts real web grounding citations from `groundingMetadata.groundingChunks`.
     */
    suspend fun factCheckFlashcardWithSearch(
        apiKey: String,
        question: String,
        answer: String,
        subject: String
    ): FactCheckResult = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("You are an academic fact-checker for university exam flashcards.\n")
            append("Subject: ${subject.ifBlank { "General" }}\n")
            append("Flashcard Question: $question\n")
            append("Student's Current Answer: $answer\n\n")
            append(
                "Verify the accuracy of the answer using Google Search grounding. Respond ONLY in this exact format:\n" +
                    "VERDICT: VERIFIED | PARTIAL_NUANCE | NEEDS_CORRECTION\n" +
                    "SUMMARY: <1-2 crisp sentences on whether the card is accurate>\n" +
                    "IMPROVED_ANSWER: <exact corrected or polished answer ready to save on the card>\n" +
                    "EXPLANATION: <2-4 concise sentences explaining key facts, equations, or nuance>"
            )
        }

        val payload = JSONObject()
            .put("contents", JSONArray().put(buildTurnJson("user", prompt)))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("temperature", 0.2))

        val responseJson = executeWithEcoCascade(apiKey, payload, AiModelConfig.PRIMARY_STUDY_MODEL)
        val rawText = extractCandidateText(responseJson)
            ?: throw IllegalStateException("Fact-checker returned an empty response.")
        val sources = extractGroundingSources(responseJson)

        parseTaggedFactCheck(
            rawText = rawText,
            fallbackAnswer = answer,
            sources = sources,
            poweredBy = if (sources.isNotEmpty()) {
                "BYOK Gemini · Google Search Grounded (${sources.size} source${if (sources.size == 1) "" else "s"})"
            } else {
                "BYOK Gemini · Search Grounded"
            }
        )
    }

    private suspend fun executeWithEcoCascade(
        apiKey: String,
        payload: JSONObject,
        initialModel: String
    ): JSONObject {
        return try {
            executeGenerateContent(apiKey, payload, initialModel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (initialModel != AiModelConfig.ECO_LITE_MODEL && AiModelConfig.isRateLimitOrQuotaError(e)) {
                Log.w(TAG, "BYOK primary model rate-limited; cascading to ${AiModelConfig.ECO_LITE_MODEL}", e)
                executeGenerateContent(apiKey, payload, AiModelConfig.ECO_LITE_MODEL)
            } else {
                throw e
            }
        }
    }

    private suspend fun executeGenerateContent(
        apiKey: String,
        payload: JSONObject,
        modelName: String
    ): JSONObject {
        val request = Request.Builder()
            .url("$GEMINI_BASE_URL/$modelName:generateContent?key=${apiKey.trim()}")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        return withTimeout(BYOK_TIMEOUT_MS) {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMsg = extractApiErrorMessage(bodyStr)
                    throw IllegalStateException(
                        apiMsg ?: "BYOK Gemini error (HTTP ${response.code})."
                    )
                }
                JSONObject(bodyStr)
            }
        }
    }

    private fun extractCandidateText(root: JSONObject): String? {
        val candidates = root.optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val content = first.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
            if (partText.isNotEmpty()) sb.append(partText)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun extractGroundingSources(root: JSONObject): List<FactCheckSource> {
        val sources = mutableListOf<FactCheckSource>()
        val candidates = root.optJSONArray("candidates") ?: return sources
        val first = candidates.optJSONObject(0) ?: return sources
        val metadata = first.optJSONObject("groundingMetadata") ?: return sources
        val chunks = metadata.optJSONArray("groundingChunks") ?: return sources
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = web.optString("uri", "").trim()
            val title = web.optString("title", "").trim().ifBlank { uri }
            if (uri.startsWith("http")) {
                if (sources.none { it.url == uri }) {
                    sources.add(FactCheckSource(title = title.take(80), url = uri))
                }
            }
        }
        return sources.take(5)
    }

    private fun parseTaggedFactCheck(
        rawText: String,
        fallbackAnswer: String,
        sources: List<FactCheckSource>,
        poweredBy: String
    ): FactCheckResult {
        fun extractField(label: String, nextLabels: List<String>): String {
            val startIdx = rawText.indexOf("$label:", ignoreCase = true)
            if (startIdx == -1) return ""
            val contentStart = startIdx + label.length + 1
            var endIdx = rawText.length
            for (next in nextLabels) {
                val idx = rawText.indexOf("$next:", startIndex = contentStart, ignoreCase = true)
                if (idx != -1 && idx < endIdx) endIdx = idx
            }
            return rawText.substring(contentStart, endIdx).trim()
        }

        val verdictRaw = extractField("VERDICT", listOf("SUMMARY", "IMPROVED_ANSWER", "EXPLANATION"))
        val summary = extractField("SUMMARY", listOf("IMPROVED_ANSWER", "EXPLANATION"))
            .ifBlank { rawText.take(260) }
        val improved = extractField("IMPROVED_ANSWER", listOf("EXPLANATION"))
            .ifBlank { fallbackAnswer }
        val explanation = extractField("EXPLANATION", emptyList())
            .ifBlank { summary }

        return FactCheckResult(
            verdict = FactCheckVerdict.fromRaw(verdictRaw.split(" ", "\n").firstOrNull()),
            summary = summary,
            improvedAnswer = improved,
            detailedExplanation = explanation,
            sources = sources,
            poweredByLabel = poweredBy
        )
    }

    private fun buildTurnJson(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun extractApiErrorMessage(bodyStr: String): String? = runCatching {
        JSONObject(bodyStr).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun compressBitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    private fun buildFlashcardResponseSchemaJson(): JSONObject {
        val cardProperties = JSONObject()
            .put(
                "cardType",
                JSONObject()
                    .put("type", "STRING")
                    .put("enum", JSONArray().put("MULTIPLE_CHOICE").put("FILL_IN_BLANK").put("CONCEPT_EXPLAIN"))
            )
            .put("question", JSONObject().put("type", "STRING"))
            .put("answer", JSONObject().put("type", "STRING"))
            .put("explanation", JSONObject().put("type", "STRING"))
            .put("hint", JSONObject().put("type", "STRING"))
            .put(
                "options",
                JSONObject()
                    .put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "STRING"))
            )
            .put("correctOptionIndex", JSONObject().put("type", "INTEGER"))

        val cardRequired = JSONArray()
            .put("cardType")
            .put("question")
            .put("answer")
            .put("explanation")
            .put("hint")
            .put("options")
            .put("correctOptionIndex")

        val rootProperties = JSONObject()
            .put("deckTitle", JSONObject().put("type", "STRING"))
            .put("subject", JSONObject().put("type", "STRING"))
            .put("summary", JSONObject().put("type", "STRING"))
            .put(
                "flashcards",
                JSONObject()
                    .put("type", "ARRAY")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "OBJECT")
                            .put("properties", cardProperties)
                            .put("required", cardRequired)
                    )
            )

        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", rootProperties)
            .put("required", JSONArray().put("deckTitle").put("subject").put("summary").put("flashcards"))
    }
}
