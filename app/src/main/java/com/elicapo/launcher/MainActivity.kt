package com.elicapo.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import com.elicapo.launcher.data.Constants
import com.elicapo.launcher.data.Prefs
import com.elicapo.launcher.databinding.ActivityMainBinding
import com.elicapo.launcher.helper.getColorFromAttr
import com.elicapo.launcher.helper.hasBeenHours
import com.elicapo.launcher.helper.isDarkThemeOn
import com.elicapo.launcher.helper.isDefaultLauncher
import com.elicapo.launcher.helper.isEinkDisplay
import com.elicapo.launcher.helper.isTablet
import com.elicapo.launcher.helper.resetLauncherViaFakeActivity
import com.elicapo.launcher.helper.setPlainWallpaper
import com.elicapo.launcher.helper.showLauncherSelector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding
    private var timerJob: Job? = null
    private var profileReceiver: BroadcastReceiver? = null
    private var launcherAppsCallback: LauncherApps.Callback? = null

//    override fun onBackPressed() {
//        if (navController.currentDestination?.id != R.id.mainFragment)
//            super.onBackPressed()
//    }

    override fun attachBaseContext(context: Context) {
        val newConfig = Configuration(context.resources.configuration)
        newConfig.fontScale = Prefs(context).textSizeScale
        applyOverrideConfiguration(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (isEinkDisplay()) prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        super.onCreate(savedInstanceState)
        if (prefs.boldFont) theme.applyStyle(R.style.BoldFontOverlay, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navController = this.findNavController(R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.mainFragment) {
                    // then we might want to finish the activity or disable this callback.
                    if (navController.popBackStack()) {
                        // Successfully popped back
                    } else {
                        // if you want other system/activity level handling
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (prefs.firstOpen) {
            prefs.firstOpen = false
            prefs.firstOpenTime = System.currentTimeMillis()
            viewModel.setDefaultClockApp()
            viewModel.resetLauncherLiveData.call()
        }
        if (prefs.firstOpenTime == 0L)
            prefs.firstOpenTime = System.currentTimeMillis()

        initObservers(viewModel)
        viewModel.getAppList()
        registerShortcutCallback()
        setupOrientation()

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            profileReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    viewModel.isPrivateSpaceToggling = false
                    viewModel.getPrivateSpaceAppList()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            }
            registerReceiver(profileReceiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        restartLauncherOrCheckTheme()
    }

    override fun onResume() {
        super.onResume()
        viewModel.isPrivateSpaceToggling = false
        viewModel.getAppList()
    }

    private fun registerShortcutCallback() {
        val launcherApps = getSystemService(LauncherApps::class.java)
        launcherAppsCallback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) = Unit
            override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = Unit
            override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = Unit
            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: android.os.UserHandle,
                replacing: Boolean,
            ) = Unit

            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: android.os.UserHandle,
                replacing: Boolean,
            ) = Unit

            override fun onShortcutsChanged(
                packageName: String,
                shortcuts: MutableList<ShortcutInfo>,
                user: android.os.UserHandle,
            ) {
                viewModel.getAppList()
            }
        }
        launcherApps.registerCallback(launcherAppsCallback!!)
    }

    override fun onStop() {
        backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent?) {
        // Home button for recents feature disabled
        // val alreadyHome = navController.currentDestination?.id == R.id.mainFragment
        backToHomeScreen()
        // if (alreadyHome && prefs.homeButtonShowRecents)
        //     viewModel.showRecentApps.call()
        super.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        if (prefs.dailyWallpaper && AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            setPlainWallpaper()
            viewModel.setWallpaperWorker()
            recreate()
        }
    }

    private fun initObservers(viewModel: MainViewModel) {
        viewModel.launcherResetFailed.observe(this) {
            openLauncherChooser(it)
        }
        viewModel.resetLauncherLiveData.observe(this) {
            if (isDefaultLauncher() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                resetLauncherViaFakeActivity()
            else
                showLauncherSelector(Constants.REQUEST_CODE_LAUNCHER_SELECTOR)
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun setupOrientation() {
        if (isTablet(this) || Build.VERSION.SDK_INT == Build.VERSION_CODES.O)
            return
        // In Android 8.0, windowIsTranslucent cannot be used with screenOrientation=portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun backToHomeScreen() {
        if (viewModel.isPrivateSpaceToggling) return
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
    }

    private fun setPlainWallpaper() {
        if (this.isDarkThemeOn())
            setPlainWallpaper(this, android.R.color.black)
        else setPlainWallpaper(this, android.R.color.white)
    }

    private fun openLauncherChooser(resetFailed: Boolean) {
        if (resetFailed) {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun restartLauncherOrCheckTheme(forceRestart: Boolean = false) {
        if (forceRestart || prefs.launcherRestartTimestamp.hasBeenHours(4)) {
            prefs.launcherRestartTimestamp = System.currentTimeMillis()
            cacheDir.deleteRecursively()
            recreate()
        } else
            checkTheme()
    }

    private fun checkTheme() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            delay(200)
            if ((prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.white))
                || (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_NO && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.black))
            )
                restartLauncherOrCheckTheme(true)
        }
    }

    override fun onDestroy() {
        launcherAppsCallback?.let {
            getSystemService(LauncherApps::class.java).unregisterCallback(it)
        }
        profileReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            Constants.REQUEST_CODE_ENABLE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK)
                    prefs.lockModeOn = true
            }

            Constants.REQUEST_CODE_LAUNCHER_SELECTOR -> {
                if (resultCode == Activity.RESULT_OK)
                    resetLauncherViaFakeActivity()
            }
        }
    }
}
