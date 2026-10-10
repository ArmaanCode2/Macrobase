package com.macrobase.app

import android.app.Application
import android.util.Log
import com.macrobase.app.core.di.appModules
import com.macrobase.app.data.repository.DiaryFoodLinkRepair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class MacroBaseApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val koin = startKoin {
            androidLogger(Level.INFO)
            androidContext(this@MacroBaseApplication)
            modules(appModules)
        }.koin

        // Re-point diary entries mis-linked by backup restores in earlier versions, once per
        // install. Touches only food ids; a failure must never block app start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val maintenance = getSharedPreferences(MAINTENANCE_PREFS, MODE_PRIVATE)
                koin.get<DiaryFoodLinkRepair>().runOnce(
                    isDone = { maintenance.getBoolean(KEY_DIARY_LINK_REPAIR_V1, false) },
                    markDone = { maintenance.edit().putBoolean(KEY_DIARY_LINK_REPAIR_V1, true).apply() }
                )
            } catch (e: Exception) {
                Log.w("MacroBaseApplication", "Diary food link repair skipped: ${e.message}")
            }
        }
    }

    private companion object {
        const val MAINTENANCE_PREFS = "macrobase_maintenance"
        const val KEY_DIARY_LINK_REPAIR_V1 = "diary_food_link_repair_v1"
    }
}
