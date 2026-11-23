package com.example.data.ai

enum class FactCheckVerdict(val badgeLabel: String) {
    VERIFIED("Verified"),
    PARTIAL_NUANCE("Add Nuance"),
    NEEDS_CORRECTION("Needs Correction");

    companion object {
        fun fromRaw(raw: String?): FactCheckVerdict = when (raw?.uppercase()?.trim()) {
            "VERIFIED" -> VERIFIED
            "NEEDS_CORRECTION" -> NEEDS_CORRECTION
            else -> PARTIAL_NUANCE
        }
    }
}

data class FactCheckSource(
    val title: String,
    val url: String
)

data class FactCheckResult(
    val verdict: FactCheckVerdict,
    val summary: String,
    val improvedAnswer: String,
    val detailedExplanation: String,
    val sources: List<FactCheckSource>,
    val poweredByLabel: String
)

/**
 * Thrown when the managed built-in daily free quota is exhausted and no offline text fallback applies.
 */
class AiQuotaExceededException(
    val actionName: String,
    val dailyLimit: Int
) : IllegalStateException(
    "Daily free $actionName limit ($dailyLimit/day) reached. Add your own free Gemini API key in Settings for unlimited AI, or use Offline Mode."
)
