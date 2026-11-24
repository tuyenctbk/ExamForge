package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.model.ReviewRating
import com.example.data.model.SpacedRepetitionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FlashcardAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (widgetId in appWidgetIds) {
                    renderWidget(context, appWidgetManager, widgetId, advanceCard = false, reveal = false)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, FlashcardAppWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(component)
        if (widgetIds.isEmpty()) return

        when (action) {
            Intent.ACTION_USER_PRESENT -> {
                // Every time the user unlocks their phone, put 1 fresh quick flashcard on the home screen
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        for (widgetId in widgetIds) {
                            renderWidget(context, appWidgetManager, widgetId, advanceCard = true, reveal = false)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_REVEAL -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val pendingResult = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            renderWidget(context, appWidgetManager, widgetId, advanceCard = false, reveal = true)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            ACTION_NEXT -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val pendingResult = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try {
                            renderWidget(context, appWidgetManager, widgetId, advanceCard = true, reveal = false)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            ACTION_RATE_GOOD, ACTION_RATE_AGAIN -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val cardId = intent.getStringExtra(EXTRA_CARD_ID)
                val rating = if (action == ACTION_RATE_GOOD) ReviewRating.GOOD else ReviewRating.AGAIN
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        if (!cardId.isNullOrBlank()) {
                            val db = AppDatabase.getInstance(context)
                            val entity = db.flashcardDao().getCardById(cardId)
                            if (entity != null) {
                                val domain = entity.toDomain()
                                val next = SpacedRepetitionEngine.scheduleNextReview(domain, rating)
                                val updated = domain.copy(
                                    easeFactor = next.easeFactor,
                                    intervalDays = next.intervalDays,
                                    repetitions = next.repetitions,
                                    dueAtMillis = next.dueAtMillis,
                                    lastReviewedAtMillis = next.lastReviewedAtMillis,
                                    pendingSync = true
                                )
                                db.flashcardDao().upsertCard(updated.toEntity(pendingSync = true))
                                val deckCards = db.flashcardDao().getAllCardsSnapshot(domain.userId).filter { it.deckId == domain.deckId }
                                val masteredCount = deckCards.count { it.toDomain().isMastered() }
                                db.deckDao().getDeckById(domain.deckId)?.let { existingDeck ->
                                    db.deckDao().upsertDeck(
                                        existingDeck.copy(
                                            cardCount = deckCards.size,
                                            masteredCount = masteredCount,
                                            updatedAtMillis = System.currentTimeMillis()
                                        )
                                    )
                                }
                                PreferencesRepository(context).recordLocalReviewStat()
                            }
                        }
                        val ratedRemoved = !cardId.isNullOrBlank()
                        val targetIds = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(widgetId) else widgetIds
                        for (id in targetIds) {
                            renderWidget(
                                context = context,
                                manager = appWidgetManager,
                                widgetId = id,
                                advanceCard = !ratedRemoved,
                                reveal = false,
                                previousCardId = cardId
                            )
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private suspend fun renderWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        advanceCard: Boolean,
        reveal: Boolean,
        previousCardId: String? = null
    ) {
        val db = AppDatabase.getInstance(context)
        val prefs = PreferencesRepository(context)
        val activeUid = prefs.getActiveUserId()
        val now = System.currentTimeMillis()

        val allCards = if (!activeUid.isNullOrBlank()) {
            db.flashcardDao().getAllCardsSnapshot(activeUid)
        } else {
            db.flashcardDao().getAnyCardsForWidget()
        }

        val dueCards = allCards.filter { it.dueAtMillis <= now }
        val pool = if (dueCards.isNotEmpty()) dueCards else allCards

        val widgetPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val lastIndex = widgetPrefs.getInt(KEY_INDEX_PREFIX + widgetId, 0)
        val selectedIndex = when {
            pool.isEmpty() -> 0
            previousCardId != null -> {
                val stillAt = pool.indexOfFirst { it.id == previousCardId }
                if (stillAt >= 0) (stillAt + 1) % pool.size
                else lastIndex.coerceIn(0, pool.lastIndex)
            }
            advanceCard -> (lastIndex + 1) % pool.size
            else -> lastIndex.coerceIn(0, pool.lastIndex)
        }
        widgetPrefs.edit().putInt(KEY_INDEX_PREFIX + widgetId, selectedIndex).apply()

        val views = RemoteViews(context.packageName, R.layout.widget_flashcard)

        // Tap header/question to launch ExamForge
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            (widgetId shl 3) or 0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_brand, openAppPending)
        views.setOnClickPendingIntent(R.id.widget_question, openAppPending)

        if (pool.isEmpty()) {
            views.setTextViewText(R.id.widget_brand, context.getString(R.string.widget_header_default))
            views.setTextViewText(R.id.widget_due_badge, context.getString(R.string.widget_due_default))
            views.setTextViewText(R.id.widget_question, context.getString(R.string.widget_empty_question))
            views.setViewVisibility(R.id.widget_answer, View.GONE)
            views.setViewVisibility(R.id.widget_actions_hidden, View.GONE)
            views.setViewVisibility(R.id.widget_actions_revealed, View.GONE)
            manager.updateAppWidget(widgetId, views)
            return
        }

        val card = pool[selectedIndex].toDomain()
        val deck = db.deckDao().getDeckById(card.deckId)
        val headerLabel = deck?.subject?.uppercase()?.take(18) ?: context.getString(R.string.widget_header_default)

        views.setTextViewText(
            R.id.widget_brand,
            "$headerLabel · ${context.getString(card.cardType.shortBadgeRes)}"
        )
        views.setTextViewText(
            R.id.widget_due_badge,
            context.getString(R.string.study_session_due_badge, dueCards.size)
        )
        views.setTextViewText(R.id.widget_question, card.question)
        views.setTextViewText(
            R.id.widget_answer,
            "${context.getString(R.string.flashcard_a_badge)}: ${card.answer}"
        )

        if (reveal) {
            views.setViewVisibility(R.id.widget_answer, View.VISIBLE)
            views.setViewVisibility(R.id.widget_actions_hidden, View.GONE)
            views.setViewVisibility(R.id.widget_actions_revealed, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_answer, View.GONE)
            views.setViewVisibility(R.id.widget_actions_hidden, View.VISIBLE)
            views.setViewVisibility(R.id.widget_actions_revealed, View.GONE)
        }

        views.setOnClickPendingIntent(
            R.id.btn_widget_reveal,
            buildActionPendingIntent(context, ACTION_REVEAL, widgetId, card.id, (widgetId shl 3) or 1)
        )
        views.setOnClickPendingIntent(
            R.id.btn_widget_next,
            buildActionPendingIntent(context, ACTION_NEXT, widgetId, card.id, (widgetId shl 3) or 2)
        )
        views.setOnClickPendingIntent(
            R.id.btn_widget_again,
            buildActionPendingIntent(context, ACTION_RATE_AGAIN, widgetId, card.id, (widgetId shl 3) or 3)
        )
        views.setOnClickPendingIntent(
            R.id.btn_widget_good,
            buildActionPendingIntent(context, ACTION_RATE_GOOD, widgetId, card.id, (widgetId shl 3) or 4)
        )

        manager.updateAppWidget(widgetId, views)
    }

    private fun buildActionPendingIntent(
        context: Context,
        action: String,
        widgetId: Int,
        cardId: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, FlashcardAppWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(EXTRA_CARD_ID, cardId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_REVEAL = "com.example.widget.ACTION_REVEAL"
        const val ACTION_RATE_GOOD = "com.example.widget.ACTION_RATE_GOOD"
        const val ACTION_RATE_AGAIN = "com.example.widget.ACTION_RATE_AGAIN"
        const val ACTION_NEXT = "com.example.widget.ACTION_NEXT"
        private const val EXTRA_CARD_ID = "extra_card_id"
        private const val WIDGET_PREFS = "examforge_widget_state"
        private const val KEY_INDEX_PREFIX = "widget_idx_"
    }
}

object FlashcardWidgetUpdater {
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val component = ComponentName(appContext, FlashcardAppWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        val updateIntent = Intent(appContext, FlashcardAppWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        appContext.sendBroadcast(updateIntent)
    }
}
