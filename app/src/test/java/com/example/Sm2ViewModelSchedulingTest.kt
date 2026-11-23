package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DeckEntity
import com.example.data.local.Flashcard
import com.example.data.model.DeckSourceType
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import com.example.data.repository.FlashcardLocalRepository
import com.example.ui.viewmodel.FlashcardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Sm2ViewModelSchedulingTest {

    private lateinit var database: AppDatabase
    private lateinit var localRepository: FlashcardLocalRepository
    private lateinit var viewModel: FlashcardViewModel

    private val userId = "test_user_sm2"
    private val deckId = "test_deck_sm2"
    private val baseNowMs = 1_760_000_000_000L
    private val dayMs = SpacedRepetitionEngine.DAY_MILLIS
    private val tenMinMs = SpacedRepetitionEngine.TEN_MINUTES_MILLIS

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directExecutor = Executor { runnable -> runnable.run() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        localRepository = FlashcardLocalRepository(database = database, appContext = null)
        viewModel = FlashcardViewModel(
            currentUserId = userId,
            localRepository = localRepository,
            studyRepository = null
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedDeckAndCard(
        cardId: String = "card_sm2_1",
        easeFactor: Double = 2.5,
        intervalDays: Int = 0,
        repetitions: Int = 0,
        dueAtMillis: Long = baseNowMs,
        lastReviewDate: Long = 0L,
        studyProgress: Int = 0
    ): Flashcard {
        database.deckDao().upsertDeck(
            DeckEntity(
                id = deckId,
                userId = userId,
                title = "Neuroscience",
                subject = "Biology",
                description = "SM-2 test deck",
                sourceType = DeckSourceType.MANUAL.name,
                accentIndex = 0,
                cardCount = 1,
                masteredCount = 0,
                updatedAtMillis = baseNowMs
            )
        )
        val entity = Flashcard(
            id = cardId,
            userId = userId,
            deckId = deckId,
            cardType = "CONCEPT_EXPLAIN",
            question = "What does the SM-2 algorithm optimize?",
            answer = "Spaced repetition review intervals based on recall quality.",
            easeFactor = easeFactor,
            intervalDays = intervalDays,
            repetitions = repetitions,
            dueAtMillis = dueAtMillis,
            lastReviewDate = lastReviewDate,
            studyProgress = studyProgress
        )
        database.flashcardDao().upsertCard(entity)
        return entity
    }

    @Test
    fun calculateSm2Schedule_progressesIntervalsAcrossRepetitionsAndResetsOnLapse() {
        val initial = Flashcard(
            id = "c1",
            userId = userId,
            deckId = deckId,
            cardType = "CONCEPT_EXPLAIN",
            question = "Q",
            answer = "A",
            easeFactor = 2.5,
            intervalDays = 0,
            repetitions = 0,
            dueAtMillis = baseNowMs,
            lastReviewDate = 0L
        )

        // Rep 1 with GOOD (q=4): interval = 1d, EF stays 2.5
        val rep1 = viewModel.calculateSm2Schedule(initial, ReviewRating.GOOD, baseNowMs)
        assertEquals(1, rep1.repetitions)
        assertEquals(1, rep1.intervalDays)
        assertEquals(2.5, rep1.easeFactor, 0.001)
        assertEquals(baseNowMs + 1L * dayMs, rep1.dueAtMillis)
        assertEquals(baseNowMs, rep1.lastReviewedAtMillis)

        // Rep 2 with GOOD (q=4): interval = 6d
        val afterRep1 = initial.copy(
            easeFactor = rep1.easeFactor,
            intervalDays = rep1.intervalDays,
            repetitions = rep1.repetitions,
            dueAtMillis = rep1.dueAtMillis,
            lastReviewDate = rep1.lastReviewedAtMillis
        )
        val rep2Time = baseNowMs + dayMs
        val rep2 = viewModel.calculateSm2Schedule(afterRep1, ReviewRating.GOOD, rep2Time)
        assertEquals(2, rep2.repetitions)
        assertEquals(6, rep2.intervalDays)
        assertEquals(rep2Time + 6L * dayMs, rep2.dueAtMillis)

        // Rep 3 with EASY (q=5): interval scales by EF' and ease factor rises
        val afterRep2 = afterRep1.copy(
            easeFactor = rep2.easeFactor,
            intervalDays = rep2.intervalDays,
            repetitions = rep2.repetitions,
            dueAtMillis = rep2.dueAtMillis,
            lastReviewDate = rep2.lastReviewedAtMillis
        )
        val rep3Time = rep2Time + 6L * dayMs
        val rep3 = viewModel.calculateSm2Schedule(afterRep2, ReviewRating.EASY, rep3Time)
        assertEquals(3, rep3.repetitions)
        assertEquals(2.6, rep3.easeFactor, 0.001)
        assertTrue(rep3.intervalDays > 6)
        assertEquals(rep3Time + rep3.intervalDays.toLong() * dayMs, rep3.dueAtMillis)

        // Lapse with AGAIN (q=1): repetitions -> 0, interval -> 0, due in 10 minutes, lower EF >= 1.3
        val afterRep3 = afterRep2.copy(
            easeFactor = rep3.easeFactor,
            intervalDays = rep3.intervalDays,
            repetitions = rep3.repetitions
        )
        val lapseTime = rep3Time + rep3.intervalDays.toLong() * dayMs
        val lapsed = viewModel.calculateSm2Schedule(afterRep3, ReviewRating.AGAIN, lapseTime)
        assertEquals(0, lapsed.repetitions)
        assertEquals(0, lapsed.intervalDays)
        assertEquals(lapseTime + tenMinMs, lapsed.dueAtMillis)
        assertTrue(lapsed.easeFactor in 1.3..2.5)
    }

    @Test
    fun calculateAndSaveSm2Review_persistsCalculatedIntervalAndNextDueDateToRoom() = runTest {
        val seeded = seedDeckAndCard(
            cardId = "card_persist_1",
            easeFactor = 2.5,
            intervalDays = 1,
            repetitions = 1,
            dueAtMillis = baseNowMs - dayMs,
            lastReviewDate = baseNowMs - 2L * dayMs,
            studyProgress = 45
        )

        val outcome = viewModel.calculateAndSaveSm2Review(
            cardId = seeded.id,
            quality = 5,
            nowMillis = baseNowMs
        )

        assertNotNull(outcome)
        outcome!!
        assertEquals(ReviewRating.EASY, outcome.rating)
        assertEquals(2, outcome.repetitions)
        assertEquals(8, outcome.intervalDays)
        assertEquals(2.6, outcome.easeFactor, 0.001)
        assertEquals(baseNowMs + 8L * dayMs, outcome.nextReviewAtMillis)
        assertEquals("8d", outcome.intervalLabel)

        val stored = database.flashcardDao().getCardById(seeded.id)
        assertNotNull(stored)
        stored!!
        assertEquals(8, stored.intervalDays)
        assertEquals(2, stored.repetitions)
        assertEquals(2.6, stored.easeFactor, 0.001)
        assertEquals(baseNowMs + 8L * dayMs, stored.dueAtMillis)
        assertEquals(baseNowMs, stored.lastReviewDate)
        assertEquals(100, stored.studyProgress)
        assertTrue(stored.pendingSync)
    }

    @Test
    fun submitReviewNowRating_savesSm2IntervalToRoomAndRequeuesLapsedCards() = runTest {
        seedDeckAndCard(
            cardId = "card_session_good",
            easeFactor = 2.5,
            intervalDays = 0,
            repetitions = 0,
            lastReviewDate = 0L,
            studyProgress = 0
        )
        database.flashcardDao().upsertCard(
            Flashcard(
                id = "card_session_again",
                userId = userId,
                deckId = deckId,
                cardType = "CONCEPT_EXPLAIN",
                question = "Second question",
                answer = "Second answer",
                easeFactor = 2.5,
                intervalDays = 1,
                repetitions = 1,
                dueAtMillis = baseNowMs - 2L * dayMs,
                lastReviewDate = baseNowMs - 3L * dayMs,
                studyProgress = 45
            )
        )

        viewModel.startReviewNowCycle(deckId = deckId, nowMillis = baseNowMs)
        val initialSession = viewModel.reviewNowSession.value
        assertEquals(2, initialSession.queue.size)

        // First card in queue (higher overdue score) is card_session_again -> rate AGAIN
        val firstCard = initialSession.currentCard!!
        viewModel.revealReviewNowAnswer()
        viewModel.submitReviewNowRating(ReviewRating.AGAIN, nowMillis = baseNowMs)

        val missedInDb = database.flashcardDao().getCardById(firstCard.id)!!
        assertEquals(0, missedInDb.intervalDays)
        assertEquals(0, missedInDb.repetitions)
        assertEquals(baseNowMs + tenMinMs, missedInDb.dueAtMillis)
        assertEquals(baseNowMs, missedInDb.lastReviewDate)

        // Missed card should stay in queue after remaining card
        val afterFirstRating = viewModel.reviewNowSession.value
        assertFalse(afterFirstRating.isCompleted)
        assertEquals(2, afterFirstRating.queue.size)
        assertNotNull(afterFirstRating.lastScheduledOutcome)
        assertEquals("10m", afterFirstRating.lastScheduledOutcome!!.intervalLabel)

        // Next card -> rate GOOD -> interval 1d saved to Room and removed from queue
        val secondCard = afterFirstRating.currentCard!!
        viewModel.revealReviewNowAnswer()
        viewModel.submitReviewNowRating(ReviewRating.GOOD, nowMillis = baseNowMs)

        val goodInDb = database.flashcardDao().getCardById(secondCard.id)!!
        assertEquals(1, goodInDb.intervalDays)
        assertEquals(1, goodInDb.repetitions)
        assertEquals(baseNowMs + dayMs, goodInDb.dueAtMillis)
        assertEquals(baseNowMs, goodInDb.lastReviewDate)

        val afterSecondRating = viewModel.reviewNowSession.value
        assertFalse(afterSecondRating.isCompleted)
        assertEquals(1, afterSecondRating.queue.size)
        assertEquals(firstCard.id, afterSecondRating.currentCard?.id)
        assertEquals("1d", afterSecondRating.lastScheduledOutcome?.intervalLabel)
    }
}
