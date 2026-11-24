package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "PreferencesRepository"
private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val BYOK_KEY_ALIAS = "examforge_byok_aes_key"

private val Context.examForgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "examforge_prefs"
)

/**
 * Snapshot of ExamForge's AI access tier, daily usage counters, and fallback preferences.
 */
data class AiUsagePlanState(
    val hasByokKey: Boolean = false,
    val maskedByokKey: String = "",
    val preferOfflineParser: Boolean = false,
    val ecoLiteMode: Boolean = false,
    val quotaDateKey: String = "",
    val dailyForgeUsed: Int = 0,
    val dailyForgeLimit: Int = FREE_DAILY_FORGE_LIMIT,
    val dailyQuizUsed: Int = 0,
    val dailyQuizLimit: Int = FREE_DAILY_QUIZ_LIMIT,
    val dailyFactCheckUsed: Int = 0,
    val dailyFactCheckLimit: Int = FREE_DAILY_FACT_CHECK_LIMIT,
    val bonusGraceCredits: Int = 0,
    val graceClaimedToday: Boolean = false,
    val fallbackCascadeCount: Int = 0
) {
    val activeTierLabel: String
        get() = when {
            preferOfflineParser -> "Offline Zero-Cost Mode"
            hasByokKey -> "BYOK Unlimited (Your Key)"
            ecoLiteMode -> "Eco Lite AI (3× Quota)"
            else -> "Smart Free Plan"
        }

    @StringRes
    fun activeTierLabelRes(): Int = when {
        preferOfflineParser -> R.string.ai_tier_offline
        hasByokKey -> R.string.ai_tier_byok
        ecoLiteMode -> R.string.ai_tier_eco_lite
        else -> R.string.ai_tier_smart_free
    }

    val remainingForges: Int
        get() = if (hasByokKey) Int.MAX_VALUE else (dailyForgeLimit - dailyForgeUsed).coerceAtLeast(0) + bonusGraceCredits

    val remainingQuizTurns: Int
        get() = if (hasByokKey) Int.MAX_VALUE else (dailyQuizLimit - dailyQuizUsed).coerceAtLeast(0) + bonusGraceCredits

    val remainingFactChecks: Int
        get() = if (hasByokKey) Int.MAX_VALUE else (dailyFactCheckLimit - dailyFactCheckUsed).coerceAtLeast(0) + bonusGraceCredits

    val forgeUsageRatio: Float
        get() = if (hasByokKey || dailyForgeLimit <= 0) 0f else (dailyForgeUsed.toFloat() / dailyForgeLimit.toFloat()).coerceIn(0f, 1f)

    val quizUsageRatio: Float
        get() = if (hasByokKey || dailyQuizLimit <= 0) 0f else (dailyQuizUsed.toFloat() / dailyQuizLimit.toFloat()).coerceIn(0f, 1f)

    val factCheckUsageRatio: Float
        get() = if (hasByokKey || dailyFactCheckLimit <= 0) 0f else (dailyFactCheckUsed.toFloat() / dailyFactCheckLimit.toFloat()).coerceIn(0f, 1f)

    val peakUsageRatio: Float
        get() = maxOf(forgeUsageRatio, quizUsageRatio, factCheckUsageRatio)

    /** Stage 1 trigger: approaching >= 80% on Forge or Quizmaster without BYOK/Offline. */
    val isApproachingForgeLimit: Boolean
        get() = !hasByokKey && !preferOfflineParser && forgeUsageRatio >= 0.80f && remainingForges > 0

    val isApproachingQuizLimit: Boolean
        get() = !hasByokKey && !preferOfflineParser && quizUsageRatio >= 0.80f && remainingQuizTurns > 0

    val canClaimGraceCredits: Boolean
        get() = !hasByokKey && !graceClaimedToday

    val totalInteractionsToday: Int
        get() = dailyForgeUsed + dailyQuizUsed + dailyFactCheckUsed

    fun timeUntilResetParts(nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val diffMinutes = ((cal.timeInMillis - nowMs) / 60_000L).coerceAtLeast(1L)
        return (diffMinutes / 60L) to (diffMinutes % 60L)
    }

    fun timeUntilResetLabel(nowMs: Long = System.currentTimeMillis()): String {
        val (hours, mins) = timeUntilResetParts(nowMs)
        return if (hours > 0) "Resets in ${hours}h ${mins}m" else "Resets in ${mins}m"
    }

    fun timeUntilResetLabel(context: Context, nowMs: Long = System.currentTimeMillis()): String {
        val (hours, mins) = timeUntilResetParts(nowMs)
        return if (hours > 0) {
            context.getString(R.string.ai_quota_resets_hm, hours, mins)
        } else {
            context.getString(R.string.ai_quota_resets_m, mins)
        }
    }

    companion object {
        const val FREE_DAILY_FORGE_LIMIT = 5
        const val FREE_DAILY_QUIZ_LIMIT = 12
        const val FREE_DAILY_FACT_CHECK_LIMIT = 5
        const val DAILY_GRACE_CREDITS = 3
    }
}

enum class AiQuotaAction {
    FORGE,
    QUIZMASTER,
    FACT_CHECK
}

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ORAL_AUTO_SPEAK = booleanPreferencesKey("oral_auto_speak")
        val ACTIVE_USER_ID = stringPreferencesKey("active_user_id")
        val BYOK_KEY_CIPHER = stringPreferencesKey("byok_key_cipher")
        val BYOK_KEY_HINT = stringPreferencesKey("byok_key_hint")
        val PREFER_OFFLINE_PARSER = booleanPreferencesKey("prefer_offline_parser")
        val ECO_LITE_MODE = booleanPreferencesKey("eco_lite_mode")
        val QUOTA_DATE_KEY = stringPreferencesKey("quota_date_key")
        val DAILY_FORGE_USED = intPreferencesKey("daily_forge_used")
        val DAILY_QUIZ_USED = intPreferencesKey("daily_quiz_used")
        val DAILY_FACT_CHECK_USED = intPreferencesKey("daily_fact_check_used")
        val BONUS_GRACE_CREDITS = intPreferencesKey("bonus_grace_credits")
        val GRACE_CLAIMED_TODAY = booleanPreferencesKey("grace_claimed_today")
        val FALLBACK_CASCADE_COUNT = intPreferencesKey("fallback_cascade_count")
        val MATCH_BLITZ_BEST_SCORE = intPreferencesKey("match_blitz_best_score")
        val LOCAL_GUEST_MODE = booleanPreferencesKey("local_guest_mode")
        val LOCAL_STREAK_DAYS = intPreferencesKey("local_streak_days")
        val LOCAL_TOTAL_REVIEWED = intPreferencesKey("local_total_reviewed")
        val LOCAL_LAST_STUDY_DAY_KEY = stringPreferencesKey("local_last_study_day_key")
    }

    val themeMode: Flow<ThemeMode> = context.examForgeDataStore.data.map { prefs ->
        ThemeMode.fromRaw(prefs[Keys.THEME_MODE])
    }

    val oralAutoSpeak: Flow<Boolean> = context.examForgeDataStore.data.map { prefs ->
        prefs[Keys.ORAL_AUTO_SPEAK] ?: true
    }

    val matchBlitzBestScore: Flow<Int> = context.examForgeDataStore.data.map { prefs ->
        prefs[Keys.MATCH_BLITZ_BEST_SCORE] ?: 0
    }

    val localGuestMode: Flow<Boolean> = context.examForgeDataStore.data.map { prefs ->
        prefs[Keys.LOCAL_GUEST_MODE] ?: false
    }

    fun observeLocalStudyProfile(userId: String, fallbackDisplayName: String): Flow<com.example.data.model.UserProfile> =
        context.examForgeDataStore.data.map { prefs ->
            com.example.data.model.UserProfile(
                userId = userId,
                displayName = fallbackDisplayName.ifBlank { context.getString(R.string.profile_examforge_student) },
                streakDays = prefs[Keys.LOCAL_STREAK_DAYS] ?: 0,
                totalReviewed = prefs[Keys.LOCAL_TOTAL_REVIEWED] ?: 0,
                lastStudyDayKey = prefs[Keys.LOCAL_LAST_STUDY_DAY_KEY]
            )
        }

    val aiUsagePlan: Flow<AiUsagePlanState> = context.examForgeDataStore.data.map { prefs ->
        val today = currentDayKey()
        val storedDay = prefs[Keys.QUOTA_DATE_KEY] ?: ""
        val sameDay = storedDay == today
        val cipherText = prefs[Keys.BYOK_KEY_CIPHER]
        AiUsagePlanState(
            hasByokKey = !cipherText.isNullOrBlank(),
            maskedByokKey = prefs[Keys.BYOK_KEY_HINT] ?: "",
            preferOfflineParser = prefs[Keys.PREFER_OFFLINE_PARSER] ?: false,
            ecoLiteMode = prefs[Keys.ECO_LITE_MODE] ?: false,
            quotaDateKey = today,
            dailyForgeUsed = if (sameDay) (prefs[Keys.DAILY_FORGE_USED] ?: 0) else 0,
            dailyQuizUsed = if (sameDay) (prefs[Keys.DAILY_QUIZ_USED] ?: 0) else 0,
            dailyFactCheckUsed = if (sameDay) (prefs[Keys.DAILY_FACT_CHECK_USED] ?: 0) else 0,
            bonusGraceCredits = if (sameDay) (prefs[Keys.BONUS_GRACE_CREDITS] ?: 0) else 0,
            graceClaimedToday = if (sameDay) (prefs[Keys.GRACE_CLAIMED_TODAY] ?: false) else false,
            fallbackCascadeCount = if (sameDay) (prefs[Keys.FALLBACK_CASCADE_COUNT] ?: 0) else 0
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.examForgeDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setOralAutoSpeak(enabled: Boolean) {
        context.examForgeDataStore.edit { prefs ->
            prefs[Keys.ORAL_AUTO_SPEAK] = enabled
        }
    }

    suspend fun setActiveUserId(userId: String?) {
        context.examForgeDataStore.edit { prefs ->
            if (userId.isNullOrBlank()) {
                prefs.remove(Keys.ACTIVE_USER_ID)
            } else {
                prefs[Keys.ACTIVE_USER_ID] = userId
            }
        }
    }

    suspend fun getActiveUserId(): String? {
        return context.examForgeDataStore.data.first()[Keys.ACTIVE_USER_ID]
    }

    suspend fun setPreferOfflineParser(enabled: Boolean) {
        context.examForgeDataStore.edit { prefs ->
            prefs[Keys.PREFER_OFFLINE_PARSER] = enabled
        }
    }

    suspend fun setEcoLiteMode(enabled: Boolean) {
        context.examForgeDataStore.edit { prefs ->
            prefs[Keys.ECO_LITE_MODE] = enabled
        }
    }

    suspend fun saveMatchBlitzBestScore(score: Int) {
        context.examForgeDataStore.edit { prefs ->
            val currentBest = prefs[Keys.MATCH_BLITZ_BEST_SCORE] ?: 0
            if (score > currentBest) {
                prefs[Keys.MATCH_BLITZ_BEST_SCORE] = score
            }
        }
    }

    suspend fun setLocalGuestMode(enabled: Boolean) {
        context.examForgeDataStore.edit { prefs ->
            prefs[Keys.LOCAL_GUEST_MODE] = enabled
        }
    }

    suspend fun recordLocalReviewStat(nowMillis: Long = System.currentTimeMillis()) {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayKey = formatter.format(Date(nowMillis))
        val yesterdayKey = formatter.format(Date(nowMillis - 86_400_000L))
        context.examForgeDataStore.edit { prefs ->
            val prevTotal = prefs[Keys.LOCAL_TOTAL_REVIEWED] ?: 0
            val prevStreak = prefs[Keys.LOCAL_STREAK_DAYS] ?: 0
            val prevDay = prefs[Keys.LOCAL_LAST_STUDY_DAY_KEY]
            val nextStreak = when (prevDay) {
                todayKey -> prevStreak.coerceAtLeast(1)
                yesterdayKey -> prevStreak + 1
                else -> 1
            }
            prefs[Keys.LOCAL_STREAK_DAYS] = nextStreak.coerceAtMost(10000)
            prefs[Keys.LOCAL_TOTAL_REVIEWED] = (prevTotal + 1).coerceAtMost(1000000)
            prefs[Keys.LOCAL_LAST_STUDY_DAY_KEY] = todayKey
        }
    }

    /**
     * Encrypts and stores an end-user supplied Gemini API key locally using Android Keystore.
     * Never leaves the device except when authenticating directly with Gemini on the user's behalf.
     */
    suspend fun saveByokGeminiKey(rawKey: String) {
        val cleaned = rawKey.trim()
        if (cleaned.isBlank()) {
            clearByokGeminiKey()
            return
        }
        val encrypted = encryptForLocalStorage(cleaned)
        val masked = maskKeyForDisplay(cleaned)
        context.examForgeDataStore.edit { prefs ->
            prefs[Keys.BYOK_KEY_CIPHER] = encrypted
            prefs[Keys.BYOK_KEY_HINT] = masked
        }
    }

    suspend fun clearByokGeminiKey() {
        context.examForgeDataStore.edit { prefs ->
            prefs.remove(Keys.BYOK_KEY_CIPHER)
            prefs.remove(Keys.BYOK_KEY_HINT)
        }
    }

    suspend fun getDecryptedByokGeminiKey(): String? {
        val cipherPayload = context.examForgeDataStore.data.first()[Keys.BYOK_KEY_CIPHER]
        if (cipherPayload.isNullOrBlank()) return null
        return decryptFromLocalStorage(cipherPayload)
    }

    suspend fun getAiUsagePlanSnapshot(): AiUsagePlanState {
        return aiUsagePlan.first()
    }

    /**
     * Grants one-time +3 daily Grace Credits so mid-task students never hit a hard wall.
     * Returns true if newly granted today, false if already claimed today.
     */
    suspend fun claimDailyGraceCredits(): Boolean {
        val today = currentDayKey()
        var granted = false
        context.examForgeDataStore.edit { prefs ->
            ensureTodayRollover(prefs, today)
            val alreadyClaimed = prefs[Keys.GRACE_CLAIMED_TODAY] ?: false
            if (!alreadyClaimed) {
                prefs[Keys.GRACE_CLAIMED_TODAY] = true
                prefs[Keys.BONUS_GRACE_CREDITS] =
                    (prefs[Keys.BONUS_GRACE_CREDITS] ?: 0) + AiUsagePlanState.DAILY_GRACE_CREDITS
                granted = true
            }
        }
        return granted
    }

    suspend fun recordFallbackCascade() {
        val today = currentDayKey()
        context.examForgeDataStore.edit { prefs ->
            ensureTodayRollover(prefs, today)
            prefs[Keys.FALLBACK_CASCADE_COUNT] = (prefs[Keys.FALLBACK_CASCADE_COUNT] ?: 0) + 1
        }
    }

    /**
     * Records a successful free-tier AI interaction. Uses base daily allowance first,
     * then consumes bonus Grace Credits seamlessly once base allowance is exhausted.
     */
    suspend fun recordFreeTierUsage(action: AiQuotaAction) {
        val today = currentDayKey()
        context.examForgeDataStore.edit { prefs ->
            ensureTodayRollover(prefs, today)
            val (key, baseLimit) = when (action) {
                AiQuotaAction.FORGE -> Pair(Keys.DAILY_FORGE_USED, AiUsagePlanState.FREE_DAILY_FORGE_LIMIT)
                AiQuotaAction.QUIZMASTER -> Pair(Keys.DAILY_QUIZ_USED, AiUsagePlanState.FREE_DAILY_QUIZ_LIMIT)
                AiQuotaAction.FACT_CHECK -> Pair(Keys.DAILY_FACT_CHECK_USED, AiUsagePlanState.FREE_DAILY_FACT_CHECK_LIMIT)
            }
            val currentUsed = prefs[key] ?: 0
            if (currentUsed < baseLimit) {
                prefs[key] = currentUsed + 1
            } else {
                val grace = prefs[Keys.BONUS_GRACE_CREDITS] ?: 0
                if (grace > 0) {
                    prefs[Keys.BONUS_GRACE_CREDITS] = grace - 1
                }
                prefs[key] = currentUsed + 1
            }
        }
    }

    /**
     * Refunds a credit if a previously charged AI interaction fails due to network/timeout.
     */
    suspend fun refundFreeTierUsage(action: AiQuotaAction) {
        val today = currentDayKey()
        context.examForgeDataStore.edit { prefs ->
            if (prefs[Keys.QUOTA_DATE_KEY] != today) return@edit
            val (key, baseLimit) = when (action) {
                AiQuotaAction.FORGE -> Pair(Keys.DAILY_FORGE_USED, AiUsagePlanState.FREE_DAILY_FORGE_LIMIT)
                AiQuotaAction.QUIZMASTER -> Pair(Keys.DAILY_QUIZ_USED, AiUsagePlanState.FREE_DAILY_QUIZ_LIMIT)
                AiQuotaAction.FACT_CHECK -> Pair(Keys.DAILY_FACT_CHECK_USED, AiUsagePlanState.FREE_DAILY_FACT_CHECK_LIMIT)
            }
            val currentUsed = prefs[key] ?: 0
            if (currentUsed > 0) {
                prefs[key] = currentUsed - 1
                if (currentUsed > baseLimit) {
                    prefs[Keys.BONUS_GRACE_CREDITS] = (prefs[Keys.BONUS_GRACE_CREDITS] ?: 0) + 1
                }
            }
        }
    }

    private fun ensureTodayRollover(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        today: String
    ) {
        if (prefs[Keys.QUOTA_DATE_KEY] != today) {
            prefs[Keys.QUOTA_DATE_KEY] = today
            prefs[Keys.DAILY_FORGE_USED] = 0
            prefs[Keys.DAILY_QUIZ_USED] = 0
            prefs[Keys.DAILY_FACT_CHECK_USED] = 0
            prefs[Keys.BONUS_GRACE_CREDITS] = 0
            prefs[Keys.GRACE_CLAIMED_TODAY] = false
            prefs[Keys.FALLBACK_CASCADE_COUNT] = 0
        }
    }

    private fun currentDayKey(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return formatter.format(Date())
    }

    private fun maskKeyForDisplay(key: String): String {
        if (key.length <= 8) return "••••" + key.takeLast(2)
        return "${key.take(4)}••••••••${key.takeLast(4)}"
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(BYOK_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            BYOK_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptForLocalStorage(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "v1:$ivB64:$dataB64"
        } catch (e: Exception) {
            Log.w(TAG, "Keystore encryption unavailable; falling back to local obfuscation", e)
            "raw:" + Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decryptFromLocalStorage(payload: String): String? {
        return try {
            when {
                payload.startsWith("v1:") -> {
                    val parts = payload.split(":")
                    if (parts.size != 3) return null
                    val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                    val data = Base64.decode(parts[2], Base64.NO_WRAP)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(data), Charsets.UTF_8)
                }

                payload.startsWith("raw:") -> {
                    String(Base64.decode(payload.removePrefix("raw:"), Base64.NO_WRAP), Charsets.UTF_8)
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed decrypting stored BYOK key", e)
            null
        }
    }
}
