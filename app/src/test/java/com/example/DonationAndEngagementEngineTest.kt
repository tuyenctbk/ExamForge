package com.example

import com.example.data.analytics.AutoPopupType
import com.example.data.analytics.EngagementPromptEngine
import com.example.data.analytics.EngagementTelemetryState
import com.example.data.analytics.PopupTriggerMoment
import com.example.data.donation.DonationConfig
import com.example.data.donation.DonationTier
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationAndEngagementEngineTest {

    private val dayMs = 24L * 60L * 60L * 1000L
    private val nowMs = 1_750_000_000_000L

    @Test
    fun payPalAndKofiUrlBuildersFormatCorrectly() {
        assertEquals(
            "https://paypal.me/${DonationConfig.PAYPAL_USERNAME}/5USD",
            DonationConfig.buildPayPalUrl("5")
        )
        assertEquals(
            "https://paypal.me/${DonationConfig.PAYPAL_USERNAME}/7.50USD",
            DonationConfig.buildPayPalUrl("$7.5")
        )
        assertEquals(
            "https://paypal.me/${DonationConfig.PAYPAL_USERNAME}",
            DonationConfig.buildPayPalUrl(null)
        )
        assertEquals(
            "https://paypal.me/${DonationConfig.PAYPAL_USERNAME}",
            DonationConfig.buildPayPalUrl("0")
        )
        assertEquals(
            "https://ko-fi.com/${DonationConfig.KOFI_USERNAME}",
            DonationConfig.buildKofiUrl()
        )
        assertEquals(4, DonationTier.entries.size)
    }

    @Test
    fun zxingQrEncoderProducesValidMatrixForPayPalUrl() {
        val url = DonationConfig.buildPayPalUrl("5")
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 256, 256)
        assertNotNull(matrix)
        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
    }

    @Test
    fun homeDonationBannerRespectsSatisfactionAndCooldownGuardrails() {
        val freshUser = EngagementTelemetryState(
            firstInstallTimestampMs = nowMs - (1 * dayMs),
            sessionCount = 4,
            decksForged = 2,
            studySessionsCompleted = 2
        )
        assertFalse(
            "New user (<2 days) must not see home banner",
            EngagementPromptEngine.shouldShowHomeDonationBanner(freshUser, nowMs)
        )

        val satisfiedUser = freshUser.copy(
            firstInstallTimestampMs = nowMs - (3 * dayMs),
            sessionCount = 3,
            decksForged = 2,
            studySessionsCompleted = 1
        )
        assertTrue(
            "Satisfied user (3+ ops, 3+ sessions, 2+ days) should see home banner",
            EngagementPromptEngine.shouldShowHomeDonationBanner(satisfiedUser, nowMs)
        )

        val dismissedRecently = satisfiedUser.copy(
            lastDonationBannerDismissedMs = nowMs - (5 * dayMs)
        )
        assertFalse(
            "Dismissing banner must silence it for 14 days",
            EngagementPromptEngine.shouldShowHomeDonationBanner(dismissedRecently, nowMs)
        )

        val dismissedLongAgo = satisfiedUser.copy(
            lastDonationBannerDismissedMs = nowMs - (15 * dayMs)
        )
        assertTrue(
            "Banner may return after 14-day cooldown",
            EngagementPromptEngine.shouldShowHomeDonationBanner(dismissedLongAgo, nowMs)
        )

        val supporter = satisfiedUser.copy(isActiveSupporter = true)
        assertFalse(
            "Active Supporter must NEVER see donation banner",
            EngagementPromptEngine.shouldShowHomeDonationBanner(supporter, nowMs)
        )
        assertFalse(
            "Active Supporter must NEVER see post-op support card",
            EngagementPromptEngine.shouldShowPostOperationSupportCard(supporter, nowMs)
        )
    }

    @Test
    fun donationAutoPromptNeverShowsAtAppStartAndEnforcesAllGuards() {
        val milestoneUser = EngagementTelemetryState(
            firstInstallTimestampMs = nowMs - (5 * dayMs),
            sessionCount = 6,
            distinctActiveDays = 4,
            positiveScore = 30,
            decksForged = 4,
            studySessionsCompleted = 4,
            matchBlitzGamesCompleted = 2, // 10 total core ops -> Milestone 10!
            rateCompleted = true,
            shareOptedOut = true
        )

        // NEVER at app start
        assertNull(
            EngagementPromptEngine.evaluateBestPopup(
                state = milestoneUser,
                moment = PopupTriggerMoment.APP_START,
                sessionAgeMs = 120_000L,
                promptShownThisSession = false,
                currentVersionCode = 1,
                nowMs = nowMs
            )
        )

        // NEVER within first 45s
        assertNull(
            EngagementPromptEngine.evaluateBestPopup(
                state = milestoneUser,
                moment = PopupTriggerMoment.STUDY_COMPLETED,
                sessionAgeMs = 10_000L,
                promptShownThisSession = false,
                currentVersionCode = 1,
                nowMs = nowMs
            )
        )

        // NEVER within 24h of an error
        assertNull(
            EngagementPromptEngine.evaluateBestPopup(
                state = milestoneUser.copy(lastErrorTimestampMs = nowMs - (2 * 60 * 60 * 1000L)),
                moment = PopupTriggerMoment.STUDY_COMPLETED,
                sessionAgeMs = 120_000L,
                promptShownThisSession = false,
                currentVersionCode = 1,
                nowMs = nowMs
            )
        )

        // Fires cleanly at Milestone 10 after positive study victory
        val suggestion = EngagementPromptEngine.evaluateBestPopup(
            state = milestoneUser,
            moment = PopupTriggerMoment.STUDY_COMPLETED,
            sessionAgeMs = 120_000L,
            promptShownThisSession = false,
            currentVersionCode = 1,
            nowMs = nowMs
        )
        assertNotNull(suggestion)
        assertEquals(AutoPopupType.DONATE_SUPPORT, suggestion!!.type)
        assertEquals(10, suggestion.milestoneCount)

        // 30-day cooldown respected after showing once
        assertNull(
            EngagementPromptEngine.evaluateBestPopup(
                state = milestoneUser.copy(
                    donationPromptCount = 1,
                    lastDonationPromptMs = nowMs - (15 * dayMs),
                    lastAnyPopupShownMs = nowMs - (15 * dayMs)
                ),
                moment = PopupTriggerMoment.STUDY_COMPLETED,
                sessionAgeMs = 120_000L,
                promptShownThisSession = false,
                currentVersionCode = 1,
                nowMs = nowMs
            )
        )

        // Active Supporter permanently disables auto-prompts
        assertNull(
            EngagementPromptEngine.evaluateBestPopup(
                state = milestoneUser.copy(isActiveSupporter = true),
                moment = PopupTriggerMoment.STUDY_COMPLETED,
                sessionAgeMs = 120_000L,
                promptShownThisSession = false,
                currentVersionCode = 1,
                nowMs = nowMs
            )
        )
    }
}
