package com.elicapo.launcher.ui

data class AppDrawerRequest(
    val flag: Int,
    val canRename: Boolean = false,
    val includeHiddenApps: Boolean = false,
)

enum class DrawerReturnTarget {
    CURRENT,
    HOME,
}

interface AppDrawerHost {
    fun showAppDrawer(request: AppDrawerRequest)

    fun closeAppDrawer(
        target: DrawerReturnTarget = DrawerReturnTarget.CURRENT,
        animated: Boolean = true,
    )
}
