package com.macrobase.app.feature.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.widget.Toast

object WidgetPinHelper {
    fun pinWidgetToHomeScreen(context: Context) {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
        if (appWidgetManager == null) {
            Toast.makeText(context, "Widget manager is unavailable on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val provider = ComponentName(context, MacroBaseWidgetProvider::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
            // Passing null for successCallback lets the OS trigger onUpdate automatically for the new widget ID
            appWidgetManager.requestPinAppWidget(provider, null, null)
        } else {
            Toast.makeText(
                context,
                "Your launcher does not support direct pinning. Please touch and hold your home screen to add the MacroBase widget manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
