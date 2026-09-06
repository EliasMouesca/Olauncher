package com.elicapo.launcher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.elicapo.launcher.R
import com.elicapo.launcher.data.Constants
import com.elicapo.launcher.data.Prefs
import com.elicapo.launcher.data.WidgetPlacement
import kotlin.math.ceil

class PinItemActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var appWidgetHost: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(null)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.invalid_pin_request)
            finish()
            return
        }

        val launcherApps = getSystemService(LauncherApps::class.java)
        val pinItemRequest = launcherApps.getPinItemRequest(intent)

        if (pinItemRequest == null) {
            showToast(R.string.invalid_pin_request)
            finish()
            return
        }

        appWidgetHost = AppWidgetHost(this, Constants.APP_WIDGET_HOST_ID)
        runCatching { appWidgetHost.startListening() }
        handleRequestType(pinItemRequest)
        runCatching { appWidgetHost.stopListening() }
        finish()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleRequestType(pinItemRequest: LauncherApps.PinItemRequest) {
        when (pinItemRequest.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ->
                handleShortcutRequest(pinItemRequest)

            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> handleWidgetRequest(pinItemRequest)

            else -> showToast(R.string.pin_action_not_supported)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleShortcutRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        val shortcutInfo = pinItemRequest.shortcutInfo
        if (shortcutInfo != null) {
            val success = runCatching { pinItemRequest.accept() }.getOrDefault(false)
            val message = when (success) {
                true -> R.string.shortcut_pinned
                false -> R.string.shortcut_pin_failed
            }
            showToast(message)
        } else {
            showToast(R.string.invalid_shortcut)
        }
    }

    private fun handleWidgetRequest(pinItemRequest: LauncherApps.PinItemRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.widgets_not_supported)
            return
        }

        val providerInfo = pinItemRequest.getAppWidgetProviderInfo(this)
        if (providerInfo == null) {
            showToast(R.string.widget_add_failed)
            return
        }

        val appWidgetId = runCatching { appWidgetHost.allocateAppWidgetId() }.getOrNull()
        if (appWidgetId == null) {
            showToast(R.string.widget_add_failed)
            return
        }

        val accepted = runCatching {
            pinItemRequest.accept(Bundle().apply {
                putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            })
        }.getOrDefault(false)

        if (!accepted) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            showToast(R.string.widget_add_failed)
            return
        }

        val (spanX, spanY) = calculateSpans(providerInfo.minWidth, providerInfo.minHeight)
        val (cellX, cellY) = findAvailablePosition(spanX, spanY)
        prefs.upsertWidgetPlacement(
            WidgetPlacement(
                appWidgetId = appWidgetId,
                providerPackage = providerInfo.provider.packageName,
                providerClass = providerInfo.provider.className,
                user = resolveProviderUser(providerInfo.provider).toString(),
                cellX = cellX,
                cellY = cellY,
                spanX = spanX,
                spanY = spanY,
            )
        )
        showToast(R.string.widget_added)
    }

    private fun resolveProviderUser(provider: android.content.ComponentName): UserHandle {
        val manager = getSystemService(AppWidgetManager::class.java)
        val userManager = getSystemService(UserManager::class.java)
        return userManager.userProfiles.firstOrNull { user ->
            runCatching {
                manager.getInstalledProvidersForProfile(user).any { it.provider == provider }
            }.getOrDefault(false)
        } ?: Process.myUserHandle()
    }

    private fun calculateSpans(minWidthDp: Int, minHeightDp: Int): Pair<Int, Int> {
        val spanX = ceil(minWidthDp.coerceAtLeast(1) / 80f).toInt().coerceIn(1, 4)
        val spanY = ceil(minHeightDp.coerceAtLeast(1) / 80f).toInt().coerceAtLeast(1)
        return spanX to spanY
    }

    private fun findAvailablePosition(spanX: Int, spanY: Int): Pair<Int, Int> {
        val saved = prefs.widgetPlacements
        val maxY = maxOf(6, saved.maxOfOrNull { it.cellY + it.spanY } ?: 0) + 12
        for (y in 0..maxY) {
            for (x in 0..4 - spanX) {
                val occupied = saved.any { other ->
                    x < other.cellX + other.spanX &&
                            x + spanX > other.cellX &&
                            y < other.cellY + other.spanY &&
                            y + spanY > other.cellY
                }
                if (!occupied) return x to y
            }
        }
        return 0 to maxY + 1
    }
}
