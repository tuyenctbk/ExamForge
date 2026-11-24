package com.example.data.ai

import androidx.annotation.StringRes
import com.example.R

enum class FactCheckVerdict(val badgeLabel: String, @StringRes val badgeLabelRes: Int) {
    VERIFIED("Verified", R.string.fact_check_verdict_verified),
    PARTIAL_NUANCE("Add Nuance", R.string.fact_check_verdict_nuance),
    NEEDS_CORRECTION("Needs Correction", R.string.fact_check_verdict_correction);

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
