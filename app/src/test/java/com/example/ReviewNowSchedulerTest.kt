package com.example

import com.example.data.local.Flashcard
import com.example.ui.viewmodel.ReviewNowScheduler
import com.example.ui.viewmodel.ReviewUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewNowSchedulerTest {

    private val dayMs = ReviewNowScheduler.DAY_MILLIS
    private val tenMinMs = ReviewNowScheduler.TEN_MINUTES_MILLIS
    private val nowMs = 1_750_000_000_000L

    private fun card(
        id: String,
        lastReviewDate: Long,
        intervalDays: Int = 1,
        repetitions: Int = 1,
        studyProgress: Int = 65
    ): Flashcard = Flashcard(
        id = id,
        userId = "u1",
        deckId = "d1",
        cardType = "CONCEPT_EXPLAIN",
        question = "Q $id",
        answer = "A $id",
        lastReviewDate = lastReviewDate,
        intervalDays = intervalDays,
        repetitions = repetitions,
        studyProgress = studyProgress
    )

    @Test
    fun neverReviewedCardIsFlaggedForReviewNowImmediately() {
        val unreviewed = card(id = "c_new", lastReviewDate = 0L, repetitions = 0, intervalDays = 0, studyProgress = 0)
        val flag = ReviewNowScheduler.evaluateFlag(unreviewed, nowMs)

        assertTrue(flag.isReviewNow)
        assertEquals(ReviewUrgency.NEVER_REVIEWED, flag.urgency)
        assertEquals("Review Now · Never reviewed", flag.statusLabel)
    }

    @Test
    fun overdueAndUpcomingCardsAreClassifiedFromLastReviewDate() {
        val overdueCard = card(
            id = "c_overdue",
            lastReviewDate = nowMs - (4L * dayMs),
            intervalDays = 1,
            repetitions = 1,
            studyProgress = 45
        )
        val overdueFlag = ReviewNowScheduler.evaluateFlag(overdueCard, nowMs)
        assertTrue(overdueFlag.isReviewNow)
        assertEquals(ReviewUrgency.OVERDUE, overdueFlag.urgency)
        assertEquals(3L * dayMs, overdueFlag.overdueMillis)
        assertEquals("Review Now · 3d overdue", overdueFlag.statusLabel)

        val freshCard = card(
            id = "c_fresh",
            lastReviewDate = nowMs - (1L * dayMs),
            intervalDays = 6,
            repetitions = 2,
            studyProgress = 100
        )
        val freshFlag = ReviewNowScheduler.evaluateFlag(freshCard, nowMs)
        assertFalse(freshFlag.isReviewNow)
        assertEquals(ReviewUrgency.UPCOMING, freshFlag.urgency)
        assertEquals("Fresh · Next in 5d", freshFlag.statusLabel)
    }

    @Test
    fun lapsedCardBecomesReviewNowAfterTenMinuteReinforcementWindow() {
        val justMissed = card(
            id = "c_lapsed_wait",
            lastReviewDate = nowMs - (3L * 60_000L),
            intervalDays = 0,
            repetitions = 0,
            studyProgress = 20
        )
        assertFalse(ReviewNowScheduler.evaluateFlag(justMissed, nowMs).isReviewNow)

        val readyToReinforce = justMissed.copy(lastReviewDate = nowMs - tenMinMs - 1_000L)
        val flag = ReviewNowScheduler.evaluateFlag(readyToReinforce, nowMs)
        assertTrue(flag.isReviewNow)
        assertEquals(ReviewUrgency.CRITICAL_LAPSE, flag.urgency)
        assertEquals("Review Now · Reinforce", flag.statusLabel)
    }

    @Test
    fun buildReviewNowQueueOrdersByReinforcementPriorityAndExcludesFreshCards() {
        val lapsed = card("lapsed", lastReviewDate = nowMs - (2L * tenMinMs), intervalDays = 0, repetitions = 0, studyProgress = 20)
        val veryOverdue = card("very_overdue", lastReviewDate = nowMs - (8L * dayMs), intervalDays = 2, repetitions = 2, studyProgress = 80)
        val slightlyOverdue = card("slightly_overdue", lastReviewDate = nowMs - (3L * dayMs), intervalDays = 2, repetitions = 2, studyProgress = 80)
        val unreviewed = card("new_card", lastReviewDate = 0L, intervalDays = 0, repetitions = 0, studyProgress = 0)
        val fresh = card("fresh", lastReviewDate = nowMs - dayMs, intervalDays = 6, repetitions = 2, studyProgress = 100)

        val queue = ReviewNowScheduler.buildReviewNowQueue(
            cards = listOf(fresh, unreviewed, slightlyOverdue, veryOverdue, lapsed),
            nowMillis = nowMs
        )

        assertEquals(
            listOf("lapsed", "very_overdue", "slightly_overdue", "new_card"),
            queue.map { it.id }
        )
    }
}
