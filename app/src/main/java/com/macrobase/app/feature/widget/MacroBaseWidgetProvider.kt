package com.macrobase.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.macrobase.app.MainActivity
import com.macrobase.app.R
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MacroBaseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (widgetId in appWidgetIds) {
                    renderWidget(context, appWidgetManager, widgetId)
                }
            } catch (e: Throwable) {
                Log.e("MacroBaseWidget", "Error updating widget IDs: ${appWidgetIds.joinToString()}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun renderWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_macrobase)
        val today = LocalDate.now()

        // 1. Safe dependency lookup via Koin
        var summary: DailyNutritionSummary? = null
        var goal = Goal()
        try {
            val koin = GlobalContext.getOrNull()
            val diaryRepo = koin?.getOrNull<DiaryRepository>()
            val goalsRepo = koin?.getOrNull<GoalsRepository>()

            summary = diaryRepo?.getDiaryForDate(today)
            goal = goalsRepo?.observeGoals()?.first() ?: Goal()
        } catch (e: Throwable) {
            Log.w("MacroBaseWidget", "Could not fetch data for widget, using defaults", e)
        }

        // 2. Format Date
        val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
        views.setTextViewText(R.id.tv_widget_date, today.format(dateFormatter))

        // 3. Dynamic Calorie Intake & Status Calculation
        val intake = summary?.totalCaloriesIntake ?: 0.0
        val targetCal = goal.dailyCalorieGoal
        val diff = targetCal - intake
        val (statusText, statusColor) = when {
            targetCal <= 0.0 -> {
                "${intake.roundToInt()} kcal" to 0xFF121212.toInt()
            }
            intake > targetCal + 25.0 -> {
                val overAmount = (intake - targetCal).roundToInt()
                "$overAmount kcal over" to 0xFFB71C1C.toInt() // Subtle dark crimson
            }
            kotlin.math.abs(diff) <= 50.0 && intake > 0.0 -> {
                "Target Hit \u2713" to 0xFF1B5E20.toInt() // Deep forest green
            }
            else -> {
                val remaining = diff.coerceAtLeast(0.0).roundToInt()
                "$remaining kcal remaining" to 0xFF121212.toInt() // Charcoal black
            }
        }
        views.setTextViewText(R.id.tv_widget_intake, "${intake.roundToInt()} kcal intake")
        views.setTextViewText(R.id.tv_widget_remaining, statusText)
        views.setTextColor(R.id.tv_widget_remaining, statusColor)

        // 4. Progress Bar & Percent
        val pct = if (targetCal > 0.0) {
            ((intake / targetCal) * 100.0).roundToInt().coerceAtLeast(0)
        } else 0
        views.setProgressBar(R.id.pb_widget_calories, 100, pct.coerceAtMost(100), false)
        views.setTextViewText(R.id.tv_widget_percentage, "$pct%")

        // 5. Macronutrients Text with Protein Goal Indicator
        val pIntake = summary?.totalProteinGrams ?: 0.0
        val cIntake = summary?.totalCarbsGrams ?: 0.0
        val fIntake = summary?.totalFatGrams ?: 0.0

        val pGoal = goal.proteinGrams
        val cGoal = goal.carbGrams
        val fGoal = goal.fatGrams

        val proteinText = if (pGoal > 0.0 && pIntake >= pGoal) {
            String.format(Locale.US, "Protein: %d/%dg \u2713", pIntake.roundToInt(), pGoal.roundToInt())
        } else {
            String.format(Locale.US, "Protein: %d/%dg", pIntake.roundToInt(), pGoal.roundToInt())
        }
        val macrosText = String.format(
            Locale.US,
            "%s   Carb: %d/%dg   Fat: %d/%dg",
            proteinText,
            cIntake.roundToInt(), cGoal.roundToInt(),
            fIntake.roundToInt(), fGoal.roundToInt()
        )
        views.setTextViewText(R.id.tv_widget_macros, macrosText)

        // 6. Root Click Action: Opens MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_WIDGET_LOG_FOOD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingAppIntent = PendingIntent.getActivity(
            context,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingAppIntent)

        // 7. Commit update
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        const val ACTION_WIDGET_LOG_FOOD = "com.macrobase.app.ACTION_WIDGET_LOG_FOOD"

        fun updateAllWidgets(context: Context) {
            runCatching {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                val componentName = ComponentName(context, MacroBaseWidgetProvider::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                if (ids != null && ids.isNotEmpty()) {
                    val intent = Intent(context, MacroBaseWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            }
        }
    }
}
