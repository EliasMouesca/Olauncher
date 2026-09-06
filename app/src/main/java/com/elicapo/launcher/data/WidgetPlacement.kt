package com.elicapo.launcher.data

import android.content.ComponentName

/**
 * The launcher-owned location of one AppWidgetHost instance.
 *
 * The provider and user are stored as strings because neither ComponentName nor UserHandle is
 * directly serializable by SharedPreferences. The user string intentionally follows the same
 * representation already used by the app and shortcut preferences.
 */
data class WidgetPlacement(
    val appWidgetId: Int,
    val providerPackage: String,
    val providerClass: String,
    val user: String,
    var cellX: Int,
    var cellY: Int,
    var spanX: Int,
    var spanY: Int,
) {
    val provider: ComponentName
        get() = ComponentName(providerPackage, providerClass)
}
