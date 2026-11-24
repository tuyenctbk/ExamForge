package com.example.data.analytics

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "AppEventTracker"
private const val MAX_STORED_EVENTS = 60

private val Context.engagementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "examforge_engagement_prefs"
)

enum class AppEventType(val label: String, val positiveWeight: Int = 0, @StringRes val labelRes: Int) {
    APP_OPENED("App Opened", 0, R.string.event_app_opened),
    DECK_FORGED("Deck Forged", 4, R.string.event_deck_forged),
    MANUAL_DECK_CREATED("Deck Created", 2, R.string.event_deck_created),
    STUDY_SESSION_COMPLETED("Study Completed", 4, R.string.event_study_completed),
    CARD_REVIEWED("Card Reviewed", 1, R.string.event_card_reviewed),
    QUIZ_TURN_COMPLETED("Quizmaster Turn", 2, R.string.event_quiz_turn),
    MATCH_BLITZ_COMPLETED("Match Blitz Finished", 3, R.string.event_match_blitz_finished),
    FACT_CHECK_COMPLETED("Card Fact-Checked", 2, R.string.event_card_fact_checked),
    ERROR_OCCURRED("Error Encountered", 0, R.string.event_error_occurred),
    QUOTA_HIT("Quota Limit Reached", 0, R.string.event_quota_hit),
    POPUP_SHOWN("Popup Shown", 0, R.string.event_popup_shown),
    POPUP_ACCEPTED("Popup Accepted", 0, R.string.event_popup_accepted),
    POPUP_DISMISSED("Popup Dismissed", 0, R.string.event_popup_dismissed),
    POPUP_NEVER_ASK("Popup Opted Out", 0, R.string.event_popup_opted_out),
    MANUAL_UPDATE_CHECK("Manual Update Check", 0, R.string.event_manual_update_check),
    MANUAL_SHARE("Manual Share", 0, R.string.event_manual_share),
    MANUAL_RATE("Manual Rate", 0, R.string.event_manual_rate),
    DONATION_OPENED("Support Opened", 0, R.string.event_support_opened),
    DONATION_BANNER_DISMISSED("Support Banner Dismissed", 0, R.string.event_support_banner_dismissed),
    DONATION_COMPLETED("Supporter Verified", 0, R.string.event_supporter_verified),
    MILESTONE_REACHED("Milestone Reached", 0, R.string.event_milestone_reached)
}

enum class AutoPopupType {
    UPDATE_APP,
    RATE_APP,
    SHARE_APP,
    DONATE_SUPPORT
}

enum class PopupTriggerMoment {
    APP_START,
    DECK_FORGED,
    STUDY_COMPLETED,
    MATCH_BLITZ_HIGH_SCORE,
    QUIZ_MILESTONE,
    IDLE_HOME
}

data class AppEventRecord(
    val id: String,
    val type: AppEventType,
    val timestampMs: Long,
    val detail: String = "",
    val value: Int = 0
)

data class AutoPopupSuggestion(
    val type: AutoPopupType,
    val reason: String,
    val targetVersionCode: Int? = null,
    val targetVersionName: String? = null,
    val milestoneCount: Int? = null
)

data class EngagementTelemetryState(
    val firstInstallTimestampMs: Long = 0L,
    val sessionCount: Int = 0,
    val distinctActiveDays: Int = 0,
    val lastActiveDayIso: String = "",
    val positiveScore: Int = 0,
    val decksForged: Int = 0,
    val studySessionsCompleted: Int = 0,
    val matchBlitzGamesCompleted: Int = 0,
    val lastErrorTimestampMs: Long = 0L,
    val lastAnyPopupShownMs: Long = 0L,
    val ratePromptCount: Int = 0,
    val lastRatePromptMs: Long = 0L,
    val rateCompleted: Boolean = false,
    val rateOptedOut: Boolean = false,
    val sharePromptCount: Int = 0,
    val lastSharePromptMs: Long = 0L,
    val shareCompleted: Boolean = false,
    val shareOptedOut: Boolean = false,
    val skippedUpdateVersionCode: Int = 0,
    val lastUpdatePromptMs: Long = 0L,
    val latestKnownVersionCode: Int = 0,
    val latestKnownVersionName: String = "",
    val isActiveSupporter: Boolean = false,
    val supporterSinceMs: Long = 0L,
    val donationPromptCount: Int = 0,
    val lastDonationPromptMs: Long = 0L,
    val donationOptedOut: Boolean = false,
    val lastDonationBannerDismissedMs: Long = 0L,
    val lastCelebratedMilestone: Int = 0,
    val recentEvents: List<AppEventRecord> = emptyList()
) {
    /** Total verified core value completions (forged decks + study sessions + Match Blitz). */
    val successfulCoreOps: Int
        get() = decksForged + studySessionsCompleted + matchBlitzGamesCompleted
}

/**
 * Pure, testable decision engine for auto-popups (Update, Rate App, Share App).
 *
 * Anti-annoyance guardrails:
 * 1. NEVER triggers Rate or Share on cold start (`APP_START`) or within the first 45s of a session.
 * 2. Max 1 auto-popup per app session (`promptShownThisSession`).
 * 3. Minimum 72-hour global cooldown between ANY two auto-popups.
 * 4. 24-hour frustration guard after any app error or quota limit before showing Rate or Share.
 * 5. Exponential backoff cooldowns per prompt type + strict lifetime prompt caps (max 3).
 * 6. Permanent respect for "Don't ask again", "Already rated", and "Skip this version".
 */
object EngagementPromptEngine {
    const val MIN_SESSION_AGE_MS = 45_000L
    const val GLOBAL_COOLDOWN_MS = 72L * 60L * 60L * 1000L          // 3 days between any two popups
    const val ERROR_FREE_COOLDOWN_MS = 24L * 60L * 60L * 1000L      // 24h after any error/quota hit
    const val UPDATE_SNOOZE_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1000L // 7 days between update reminders
    const val MAX_LIFETIME_RATE_PROMPTS = 3
    const val MAX_LIFETIME_SHARE_PROMPTS = 3
    const val MAX_LIFETIME_DONATION_PROMPTS = 3
    const val DONATION_BANNER_COOLDOWN_DAYS = 14L
    const val DONATION_AUTO_PROMPT_COOLDOWN_DAYS = 30L
    const val POST_OP_SUPPORT_COOLDOWN_DAYS = 7L
    const val MIN_INSTALL_DAYS_FOR_DONATION = 2L
    val DONATION_MILESTONES = listOf(10, 25, 50)

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * Home Screen Compact Banner eligibility:
     * Shown ONLY after verified utility (3+ core ops, 3+ launches, 2+ days since install),
     * never within 24h of an error, never within 14d of dismissal, and never to Active Supporters.
     */
    fun shouldShowHomeDonationBanner(
        state: EngagementTelemetryState,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (state.isActiveSupporter || state.donationOptedOut) return false
        if (state.successfulCoreOps < 3 || state.sessionCount < 3) return false
        if (!hasInstalledLongEnough(state, nowMs, MIN_INSTALL_DAYS_FOR_DONATION)) return false
        if (isInFrustrationWindow(state, nowMs)) return false
        if (state.lastDonationBannerDismissedMs > 0L &&
            (nowMs - state.lastDonationBannerDismissedMs) < DONATION_BANNER_COOLDOWN_DAYS * DAY_MS
        ) {
            return false
        }
        return true
    }

    /**
     * Post-Operation Success Card eligibility (embedded subtly below action buttons after
     * forging a deck or completing a study session — Value Realization Moment).
     */
    fun shouldShowPostOperationSupportCard(
        state: EngagementTelemetryState,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (state.isActiveSupporter || state.donationOptedOut) return false
        if (state.successfulCoreOps < 2) return false
        if (isInFrustrationWindow(state, nowMs)) return false
        if (state.lastDonationBannerDismissedMs > 0L &&
            (nowMs - state.lastDonationBannerDismissedMs) < POST_OP_SUPPORT_COOLDOWN_DAYS * DAY_MS
        ) {
            return false
        }
        return true
    }

    /**
     * Detects a newly reached milestone (10, 25, or 50 successful core operations).
     */
    fun detectUncelebratedMilestone(state: EngagementTelemetryState): Int? {
        return DONATION_MILESTONES
            .filter { it <= state.successfulCoreOps && it > state.lastCelebratedMilestone }
            .maxOrNull()
    }

    fun evaluateBestPopup(
        state: EngagementTelemetryState,
        moment: PopupTriggerMoment,
        sessionAgeMs: Long,
        promptShownThisSession: Boolean,
        currentVersionCode: Int,
        nowMs: Long = System.currentTimeMillis()
    ): AutoPopupSuggestion? {
        // Guardrail 1: Hard cap of 1 auto-popup per session
        if (promptShownThisSession) return null

        // Guardrail 2: Never show Rate/Share/Donate on app start; never interrupt within first 45s
        if (moment == PopupTriggerMoment.APP_START) return null
        if (sessionAgeMs < MIN_SESSION_AGE_MS) return null

        // Guardrail 3: Global cross-popup cooldown (72 hours)
        if (state.lastAnyPopupShownMs > 0L && (nowMs - state.lastAnyPopupShownMs) < GLOBAL_COOLDOWN_MS) {
            return null
        }

        // Priority 1: App Update (only when newer version metadata exists & wasn't skipped)
        if (shouldSuggestUpdate(state, currentVersionCode, nowMs)) {
            return AutoPopupSuggestion(
                type = AutoPopupType.UPDATE_APP,
                reason = "Newer version (${state.latestKnownVersionName.ifBlank { "v${state.latestKnownVersionCode}" }}) is available.",
                targetVersionCode = state.latestKnownVersionCode,
                targetVersionName = state.latestKnownVersionName.ifBlank { "v${state.latestKnownVersionCode}" }
            )
        }

        // Guardrail 4: Frustration guard — never ask to rate, share, or donate within 24h of an error or quota wall
        if (isInFrustrationWindow(state, nowMs)) {
            return null
        }

        // Priority 2: Rate App — only right after a genuine study victory & proven retention
        val isPositiveBreakpoint = moment == PopupTriggerMoment.STUDY_COMPLETED ||
            moment == PopupTriggerMoment.DECK_FORGED ||
            moment == PopupTriggerMoment.MATCH_BLITZ_HIGH_SCORE
        if (isPositiveBreakpoint && shouldSuggestRate(state, nowMs)) {
            return AutoPopupSuggestion(
                type = AutoPopupType.RATE_APP,
                reason = "High engagement (${state.positiveScore} pts, ${state.distinctActiveDays} active days) after positive milestone."
            )
        }

        // Priority 3: Share App — only at high-delight moments
        if (isPositiveBreakpoint && shouldSuggestShare(state, nowMs)) {
            return AutoPopupSuggestion(
                type = AutoPopupType.SHARE_APP,
                reason = "Natural share moment after milestone (${moment.name})."
            )
        }

        // Priority 4: Voluntary Donation / Milestone Celebration — only after 7+ core ops & 2+ days install, 30-day cooldown
        if (isPositiveBreakpoint && shouldSuggestDonation(state, nowMs)) {
            val milestone = detectUncelebratedMilestone(state)
            return AutoPopupSuggestion(
                type = AutoPopupType.DONATE_SUPPORT,
                reason = if (milestone != null) {
                    "Milestone achieved: $milestone study completions!"
                } else {
                    "Verified utility (${state.successfulCoreOps} core tasks completed)."
                },
                milestoneCount = milestone
            )
        }

        return null
    }

    private fun isInFrustrationWindow(state: EngagementTelemetryState, nowMs: Long): Boolean {
        return state.lastErrorTimestampMs > 0L && (nowMs - state.lastErrorTimestampMs) < ERROR_FREE_COOLDOWN_MS
    }

    private fun hasInstalledLongEnough(
        state: EngagementTelemetryState,
        nowMs: Long,
        minDays: Long
    ): Boolean {
        if (state.firstInstallTimestampMs <= 0L) return state.distinctActiveDays >= minDays.toInt()
        return (nowMs - state.firstInstallTimestampMs) >= minDays * DAY_MS ||
            state.distinctActiveDays >= minDays.toInt()
    }

    private fun shouldSuggestUpdate(
        state: EngagementTelemetryState,
        currentVersionCode: Int,
        nowMs: Long
    ): Boolean {
        if (state.latestKnownVersionCode <= currentVersionCode) return false
        if (state.skippedUpdateVersionCode == state.latestKnownVersionCode) return false
        if (state.lastUpdatePromptMs > 0L && (nowMs - state.lastUpdatePromptMs) < UPDATE_SNOOZE_COOLDOWN_MS) {
            return false
        }
        return true
    }

    private fun shouldSuggestRate(state: EngagementTelemetryState, nowMs: Long): Boolean {
        if (state.rateCompleted || state.rateOptedOut) return false
        if (state.ratePromptCount >= MAX_LIFETIME_RATE_PROMPTS) return false

        val hasRetention = state.distinctActiveDays >= 3 || state.sessionCount >= 4
        val hasRealValue = state.positiveScore >= 10 &&
            (state.decksForged >= 1 || state.studySessionsCompleted >= 2)
        if (!hasRetention || !hasRealValue) return false

        // Exponential backoff after dismissal: 14d -> 30d -> 60d
        val requiredCooldownDays = when (state.ratePromptCount) {
            0 -> 0L
            1 -> 14L
            2 -> 30L
            else -> 60L
        }
        if (state.lastRatePromptMs > 0L && (nowMs - state.lastRatePromptMs) < requiredCooldownDays * DAY_MS) {
            return false
        }
        return true
    }

    private fun shouldSuggestShare(state: EngagementTelemetryState, nowMs: Long): Boolean {
        if (state.shareOptedOut) return false
        if (state.sharePromptCount >= MAX_LIFETIME_SHARE_PROMPTS) return false

        val hasMomentum = state.sessionCount >= 2 && state.positiveScore >= 6
        if (!hasMomentum) return false

        // Longer cooldown if user already shared once, plus exponential backoff on dismiss
        val requiredCooldownDays = when {
            state.shareCompleted -> 60L
            state.sharePromptCount == 0 -> 0L
            state.sharePromptCount == 1 -> 21L
            else -> 45L
        }
        if (state.lastSharePromptMs > 0L && (nowMs - state.lastSharePromptMs) < requiredCooldownDays * DAY_MS) {
            return false
        }
        return true
    }

    private fun shouldSuggestDonation(state: EngagementTelemetryState, nowMs: Long): Boolean {
        if (state.isActiveSupporter || state.donationOptedOut) return false
        if (state.donationPromptCount >= MAX_LIFETIME_DONATION_PROMPTS) return false
        if (state.successfulCoreOps < 7) return false
        if (!hasInstalledLongEnough(state, nowMs, MIN_INSTALL_DAYS_FOR_DONATION)) return false
        if (state.lastDonationPromptMs > 0L &&
            (nowMs - state.lastDonationPromptMs) < DONATION_AUTO_PROMPT_COOLDOWN_DAYS * DAY_MS
        ) {
            return false
        }
        return true
    }
}

/**
 * Persistent app event logger & engagement telemetry tracker backed by Jetpack DataStore.
 */
class AppEventTracker(private val context: Context) {

    private object Keys {
        val FIRST_INSTALL_MS = longPreferencesKey("eng_first_install_ms")
        val SESSION_COUNT = intPreferencesKey("eng_session_count")
        val DISTINCT_ACTIVE_DAYS = intPreferencesKey("eng_distinct_active_days")
        val LAST_ACTIVE_DAY_ISO = stringPreferencesKey("eng_last_active_day_iso")
        val POSITIVE_SCORE = intPreferencesKey("eng_positive_score")
        val DECKS_FORGED = intPreferencesKey("eng_decks_forged")
        val STUDY_SESSIONS_COMPLETED = intPreferencesKey("eng_study_sessions_completed")
        val MATCH_BLITZ_COMPLETED = intPreferencesKey("eng_match_blitz_completed")
        val LAST_ERROR_MS = longPreferencesKey("eng_last_error_ms")
        val LAST_ANY_POPUP_MS = longPreferencesKey("eng_last_any_popup_ms")

        val RATE_PROMPT_COUNT = intPreferencesKey("eng_rate_prompt_count")
        val LAST_RATE_PROMPT_MS = longPreferencesKey("eng_last_rate_prompt_ms")
        val RATE_COMPLETED = booleanPreferencesKey("eng_rate_completed")
        val RATE_OPTED_OUT = booleanPreferencesKey("eng_rate_opted_out")

        val SHARE_PROMPT_COUNT = intPreferencesKey("eng_share_prompt_count")
        val LAST_SHARE_PROMPT_MS = longPreferencesKey("eng_last_share_prompt_ms")
        val SHARE_COMPLETED = booleanPreferencesKey("eng_share_completed")
        val SHARE_OPTED_OUT = booleanPreferencesKey("eng_share_opted_out")

        val SKIPPED_UPDATE_VERSION_CODE = intPreferencesKey("eng_skipped_update_ver")
        val LAST_UPDATE_PROMPT_MS = longPreferencesKey("eng_last_update_prompt_ms")
        val LATEST_KNOWN_VERSION_CODE = intPreferencesKey("eng_latest_known_ver_code")
        val LATEST_KNOWN_VERSION_NAME = stringPreferencesKey("eng_latest_known_ver_name")

        val IS_ACTIVE_SUPPORTER = booleanPreferencesKey("eng_is_active_supporter")
        val SUPPORTER_SINCE_MS = longPreferencesKey("eng_supporter_since_ms")
        val DONATION_PROMPT_COUNT = intPreferencesKey("eng_donation_prompt_count")
        val LAST_DONATION_PROMPT_MS = longPreferencesKey("eng_last_donation_prompt_ms")
        val DONATION_OPTED_OUT = booleanPreferencesKey("eng_donation_opted_out")
        val LAST_DONATION_BANNER_DISMISSED_MS = longPreferencesKey("eng_last_donation_banner_dismissed_ms")
        val LAST_CELEBRATED_MILESTONE = intPreferencesKey("eng_last_celebrated_milestone")

        val RECENT_EVENTS_JSON = stringPreferencesKey("eng_recent_events_json")
    }

    val telemetryState: Flow<EngagementTelemetryState> = context.engagementDataStore.data.map { prefs ->
        EngagementTelemetryState(
            firstInstallTimestampMs = prefs[Keys.FIRST_INSTALL_MS] ?: 0L,
            sessionCount = prefs[Keys.SESSION_COUNT] ?: 0,
            distinctActiveDays = prefs[Keys.DISTINCT_ACTIVE_DAYS] ?: 0,
            lastActiveDayIso = prefs[Keys.LAST_ACTIVE_DAY_ISO] ?: "",
            positiveScore = prefs[Keys.POSITIVE_SCORE] ?: 0,
            decksForged = prefs[Keys.DECKS_FORGED] ?: 0,
            studySessionsCompleted = prefs[Keys.STUDY_SESSIONS_COMPLETED] ?: 0,
            matchBlitzGamesCompleted = prefs[Keys.MATCH_BLITZ_COMPLETED] ?: 0,
            lastErrorTimestampMs = prefs[Keys.LAST_ERROR_MS] ?: 0L,
            lastAnyPopupShownMs = prefs[Keys.LAST_ANY_POPUP_MS] ?: 0L,
            ratePromptCount = prefs[Keys.RATE_PROMPT_COUNT] ?: 0,
            lastRatePromptMs = prefs[Keys.LAST_RATE_PROMPT_MS] ?: 0L,
            rateCompleted = prefs[Keys.RATE_COMPLETED] ?: false,
            rateOptedOut = prefs[Keys.RATE_OPTED_OUT] ?: false,
            sharePromptCount = prefs[Keys.SHARE_PROMPT_COUNT] ?: 0,
            lastSharePromptMs = prefs[Keys.LAST_SHARE_PROMPT_MS] ?: 0L,
            shareCompleted = prefs[Keys.SHARE_COMPLETED] ?: false,
            shareOptedOut = prefs[Keys.SHARE_OPTED_OUT] ?: false,
            skippedUpdateVersionCode = prefs[Keys.SKIPPED_UPDATE_VERSION_CODE] ?: 0,
            lastUpdatePromptMs = prefs[Keys.LAST_UPDATE_PROMPT_MS] ?: 0L,
            latestKnownVersionCode = prefs[Keys.LATEST_KNOWN_VERSION_CODE] ?: 0,
            latestKnownVersionName = prefs[Keys.LATEST_KNOWN_VERSION_NAME] ?: "",
            isActiveSupporter = prefs[Keys.IS_ACTIVE_SUPPORTER] ?: false,
            supporterSinceMs = prefs[Keys.SUPPORTER_SINCE_MS] ?: 0L,
            donationPromptCount = prefs[Keys.DONATION_PROMPT_COUNT] ?: 0,
            lastDonationPromptMs = prefs[Keys.LAST_DONATION_PROMPT_MS] ?: 0L,
            donationOptedOut = prefs[Keys.DONATION_OPTED_OUT] ?: false,
            lastDonationBannerDismissedMs = prefs[Keys.LAST_DONATION_BANNER_DISMISSED_MS] ?: 0L,
            lastCelebratedMilestone = prefs[Keys.LAST_CELEBRATED_MILESTONE] ?: 0,
            recentEvents = decodeEvents(prefs[Keys.RECENT_EVENTS_JSON])
        )
    }

    suspend fun getSnapshot(): EngagementTelemetryState = telemetryState.first()

    /**
     * Records a structured event, updates engagement counters & active-day tracking,
     * and logs cleanly to Logcat for debugging/analytics visibility.
     */
    suspend fun logEvent(
        type: AppEventType,
        detail: String = "",
        value: Int = 0,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val todayIso = formatDayIso(nowMs)
        Log.d(TAG, "Event[${type.name}] detail=$detail value=$value")

        context.engagementDataStore.edit { prefs ->
            // Record first-install timestamp once so donation prompts respect install-age guardrails
            if ((prefs[Keys.FIRST_INSTALL_MS] ?: 0L) == 0L) {
                prefs[Keys.FIRST_INSTALL_MS] = nowMs
            }

            // Track distinct active calendar days
            if (prefs[Keys.LAST_ACTIVE_DAY_ISO] != todayIso) {
                prefs[Keys.LAST_ACTIVE_DAY_ISO] = todayIso
                prefs[Keys.DISTINCT_ACTIVE_DAYS] = (prefs[Keys.DISTINCT_ACTIVE_DAYS] ?: 0) + 1
            }

            if (type.positiveWeight > 0) {
                prefs[Keys.POSITIVE_SCORE] = (prefs[Keys.POSITIVE_SCORE] ?: 0) + type.positiveWeight
            }

            when (type) {
                AppEventType.APP_OPENED -> {
                    prefs[Keys.SESSION_COUNT] = (prefs[Keys.SESSION_COUNT] ?: 0) + 1
                }
                AppEventType.DECK_FORGED -> {
                    prefs[Keys.DECKS_FORGED] = (prefs[Keys.DECKS_FORGED] ?: 0) + 1
                }
                AppEventType.STUDY_SESSION_COMPLETED -> {
                    prefs[Keys.STUDY_SESSIONS_COMPLETED] = (prefs[Keys.STUDY_SESSIONS_COMPLETED] ?: 0) + 1
                }
                AppEventType.MATCH_BLITZ_COMPLETED -> {
                    prefs[Keys.MATCH_BLITZ_COMPLETED] = (prefs[Keys.MATCH_BLITZ_COMPLETED] ?: 0) + 1
                }
                AppEventType.ERROR_OCCURRED, AppEventType.QUOTA_HIT -> {
                    prefs[Keys.LAST_ERROR_MS] = nowMs
                }
                else -> Unit
            }

            val existing = decodeEvents(prefs[Keys.RECENT_EVENTS_JSON]).toMutableList()
            existing.add(
                0,
                AppEventRecord(
                    id = UUID.randomUUID().toString().take(12),
                    type = type,
                    timestampMs = nowMs,
                    detail = detail.take(140),
                    value = value
                )
            )
            prefs[Keys.RECENT_EVENTS_JSON] = encodeEvents(existing.take(MAX_STORED_EVENTS))
        }
    }

    suspend fun recordPopupShown(
        type: AutoPopupType,
        milestoneCount: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        context.engagementDataStore.edit { prefs ->
            prefs[Keys.LAST_ANY_POPUP_MS] = nowMs
            when (type) {
                AutoPopupType.RATE_APP -> {
                    prefs[Keys.RATE_PROMPT_COUNT] = (prefs[Keys.RATE_PROMPT_COUNT] ?: 0) + 1
                    prefs[Keys.LAST_RATE_PROMPT_MS] = nowMs
                }
                AutoPopupType.SHARE_APP -> {
                    prefs[Keys.SHARE_PROMPT_COUNT] = (prefs[Keys.SHARE_PROMPT_COUNT] ?: 0) + 1
                    prefs[Keys.LAST_SHARE_PROMPT_MS] = nowMs
                }
                AutoPopupType.UPDATE_APP -> {
                    prefs[Keys.LAST_UPDATE_PROMPT_MS] = nowMs
                }
                AutoPopupType.DONATE_SUPPORT -> {
                    prefs[Keys.DONATION_PROMPT_COUNT] = (prefs[Keys.DONATION_PROMPT_COUNT] ?: 0) + 1
                    prefs[Keys.LAST_DONATION_PROMPT_MS] = nowMs
                    if (milestoneCount != null && milestoneCount > (prefs[Keys.LAST_CELEBRATED_MILESTONE] ?: 0)) {
                        prefs[Keys.LAST_CELEBRATED_MILESTONE] = milestoneCount
                    }
                }
            }
        }
        logEvent(
            type = AppEventType.POPUP_SHOWN,
            detail = if (milestoneCount != null) "${type.name}_M$milestoneCount" else type.name,
            value = milestoneCount ?: 0,
            nowMs = nowMs
        )
    }

    suspend fun recordPopupAccepted(type: AutoPopupType, nowMs: Long = System.currentTimeMillis()) {
        context.engagementDataStore.edit { prefs ->
            when (type) {
                AutoPopupType.RATE_APP -> prefs[Keys.RATE_COMPLETED] = true
                AutoPopupType.SHARE_APP -> prefs[Keys.SHARE_COMPLETED] = true
                AutoPopupType.UPDATE_APP -> prefs[Keys.LAST_UPDATE_PROMPT_MS] = nowMs
                AutoPopupType.DONATE_SUPPORT -> prefs[Keys.LAST_DONATION_PROMPT_MS] = nowMs
            }
        }
        logEvent(AppEventType.POPUP_ACCEPTED, detail = type.name, nowMs = nowMs)
    }

    suspend fun recordPopupDismissed(type: AutoPopupType, nowMs: Long = System.currentTimeMillis()) {
        logEvent(AppEventType.POPUP_DISMISSED, detail = type.name, nowMs = nowMs)
    }

    suspend fun recordPopupOptOut(
        type: AutoPopupType,
        skippedVersionCode: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        context.engagementDataStore.edit { prefs ->
            when (type) {
                AutoPopupType.RATE_APP -> prefs[Keys.RATE_OPTED_OUT] = true
                AutoPopupType.SHARE_APP -> prefs[Keys.SHARE_OPTED_OUT] = true
                AutoPopupType.DONATE_SUPPORT -> prefs[Keys.DONATION_OPTED_OUT] = true
                AutoPopupType.UPDATE_APP -> {
                    if (skippedVersionCode != null && skippedVersionCode > 0) {
                        prefs[Keys.SKIPPED_UPDATE_VERSION_CODE] = skippedVersionCode
                    }
                }
            }
        }
        logEvent(AppEventType.POPUP_NEVER_ASK, detail = type.name, value = skippedVersionCode ?: 0, nowMs = nowMs)
    }

    suspend fun recordDonationBannerDismissed(nowMs: Long = System.currentTimeMillis()) {
        context.engagementDataStore.edit { prefs ->
            prefs[Keys.LAST_DONATION_BANNER_DISMISSED_MS] = nowMs
        }
        logEvent(AppEventType.DONATION_BANNER_DISMISSED, detail = "14d_cooldown", nowMs = nowMs)
    }

    suspend fun markActiveSupporter(channelDetail: String, nowMs: Long = System.currentTimeMillis()) {
        context.engagementDataStore.edit { prefs ->
            prefs[Keys.IS_ACTIVE_SUPPORTER] = true
            if ((prefs[Keys.SUPPORTER_SINCE_MS] ?: 0L) == 0L) {
                prefs[Keys.SUPPORTER_SINCE_MS] = nowMs
            }
        }
        logEvent(AppEventType.DONATION_COMPLETED, detail = channelDetail, nowMs = nowMs)
    }

    /**
     * Stores the latest known release version metadata (e.g. from Remote Config / release check)
     * so the update suggestion algorithm can notify users politely when an update is available.
     */
    suspend fun setLatestAvailableVersion(versionCode: Int, versionName: String) {
        context.engagementDataStore.edit { prefs ->
            prefs[Keys.LATEST_KNOWN_VERSION_CODE] = versionCode
            prefs[Keys.LATEST_KNOWN_VERSION_NAME] = versionName
        }
    }

    private fun formatDayIso(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
    }

    private fun encodeEvents(events: List<AppEventRecord>): String {
        val arr = JSONArray()
        events.forEach { ev ->
            arr.put(
                JSONObject()
                    .put("id", ev.id)
                    .put("type", ev.type.name)
                    .put("ts", ev.timestampMs)
                    .put("detail", ev.detail)
                    .put("value", ev.value)
            )
        }
        return arr.toString()
    }

    private fun decodeEvents(raw: String?): List<AppEventRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val typeName = obj.optString("type", "")
                    val type = runCatching { AppEventType.valueOf(typeName) }.getOrNull() ?: continue
                    add(
                        AppEventRecord(
                            id = obj.optString("id", "$i"),
                            type = type,
                            timestampMs = obj.optLong("ts", 0L),
                            detail = obj.optString("detail", ""),
                            value = obj.optInt("value", 0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
