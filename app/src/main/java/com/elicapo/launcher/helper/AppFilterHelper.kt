package com.elicapo.launcher.helper

import com.elicapo.launcher.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}