package com.elicapo.launcher.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.elicapo.launcher.MainViewModel
import com.elicapo.launcher.R
import com.elicapo.launcher.data.AppModel
import com.elicapo.launcher.data.Constants
import com.elicapo.launcher.data.Prefs
import com.elicapo.launcher.data.WidgetPlacement
import com.elicapo.launcher.databinding.FragmentHomeBinding
import com.elicapo.launcher.helper.appUsagePermissionGranted
import com.elicapo.launcher.helper.dpToPx
import com.elicapo.launcher.helper.expandNotificationDrawer
import com.elicapo.launcher.helper.getChangedAppTheme
import com.elicapo.launcher.helper.getUserHandleFromString
import com.elicapo.launcher.helper.isPackageInstalled
import com.elicapo.launcher.helper.isPrivateSpaceLocked
import com.elicapo.launcher.helper.isPrivateSpaceProfile
import com.elicapo.launcher.helper.openAlarmApp
import com.elicapo.launcher.helper.openCalendar
import com.elicapo.launcher.helper.openCameraApp
import com.elicapo.launcher.helper.openDialerApp
import com.elicapo.launcher.helper.openSearch
import com.elicapo.launcher.helper.setPlainWallpaperByTheme
import com.elicapo.launcher.helper.showToast
import com.elicapo.launcher.listener.OnSwipeTouchListener
import com.elicapo.launcher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private data class WidgetProviderChoice(
        val info: AppWidgetProviderInfo,
        val user: UserHandle,
    )

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: LauncherAppWidgetHost

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetProvider: WidgetProviderChoice? = null
    private var configuringWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        appWidgetManager = requireContext().getSystemService(AppWidgetManager::class.java)
        appWidgetHost = LauncherAppWidgetHost(
            requireContext().applicationContext,
            Constants.APP_WIDGET_HOST_ID,
        )
        binding.widgetCanvas.setWidgetLongClickListener(::showWidgetOptions)
        binding.widgetCanvas.setPlacementChangedListener { placement ->
            prefs.upsertWidgetPlacement(placement)
        }
        binding.widgetScrollView.setOnLongClickListener {
            showHomeLongPressMenu()
            true
        }

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        restoreWidgets()
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onStart() {
        super.onStart()
        runCatching { appWidgetHost.startListening() }
        binding.widgetCanvas.post { restoreWidgets() }
    }

    override fun onStop() {
        runCatching { appWidgetHost.stopListening() }
        super.onStop()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
    }

    private fun restoreWidgets() {
        if (!::appWidgetHost.isInitialized || _binding == null) return

        val savedPlacements = prefs.widgetPlacements
        binding.widgetCanvas.clearWidgetViews()
        binding.widgetCanvas.setPlacements(savedPlacements)

        savedPlacements.forEach { placement ->
            val providerInfo = runCatching { appWidgetManager.getAppWidgetInfo(placement.appWidgetId) }
                .getOrNull()
                ?.takeIf { it.provider == placement.provider }

            if (providerInfo == null) {
                binding.widgetCanvas.addWidgetView(placement, createUnavailableWidgetView())
                return@forEach
            }

            val hostView = runCatching {
                appWidgetHost.createView(
                    requireContext().applicationContext,
                    placement.appWidgetId,
                    providerInfo,
                )
            }.getOrNull()
            if (hostView != null) {
                binding.widgetCanvas.addWidgetView(placement, hostView)
            } else {
                binding.widgetCanvas.addWidgetView(placement, createUnavailableWidgetView())
            }
        }
    }

    private fun createUnavailableWidgetView(): TextView = TextView(requireContext()).apply {
        styleTextSmall()
        text = getString(R.string.widget_unavailable)
        gravity = Gravity.CENTER
        setPadding(12.dpToPx())
        isClickable = false
    }

    private fun TextView.styleTextSmall() {
        setTextAppearance(requireContext(), R.style.TextSmall)
    }

    private fun showHomeLongPressMenu() {
        val actions = arrayOf(
            getString(R.string.add_widget),
            getString(R.string.change_wallpaper),
            getString(R.string.widget_settings),
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_screen)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showWidgetPicker()
                    1 -> openWallpaperPicker()
                    2 -> openHomeSettings()
                }
            }
            .show()
    }

    private fun openWallpaperPicker() {
        runCatching {
            startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
        }.onFailure {
            requireContext().showToast(R.string.wallpaper_picker_unavailable)
        }
    }

    private fun openHomeSettings() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showWidgetPicker() {
        val choices = getWidgetProviderChoices()
        if (choices.isEmpty()) {
            requireContext().showToast(R.string.no_widgets_available)
            return
        }

        val labels = choices.map { choice ->
            val widgetLabel = choice.info.loadLabel(requireContext().packageManager).toString()
            val providerLabel = choice.info.provider.packageName
            "$widgetLabel · $providerLabel"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.widgets)
            .setItems(labels) { _, which -> startWidgetAdd(choices[which]) }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun getWidgetProviderChoices(): List<WidgetProviderChoice> {
        val userManager = requireContext().getSystemService(UserManager::class.java)
        return userManager.userProfiles
            .filterNot { isPrivateSpaceProfile(requireContext(), it) && isPrivateSpaceLocked(requireContext(), it) }
            .flatMap { user ->
                runCatching {
                    appWidgetManager.getInstalledProvidersForProfile(user)
                        .map { WidgetProviderChoice(it, user) }
                }.getOrDefault(emptyList())
            }
            .sortedWith(compareBy { it.info.loadLabel(requireContext().packageManager).toString().lowercase(Locale.getDefault()) })
    }

    private fun startWidgetAdd(choice: WidgetProviderChoice) {
        pendingWidgetProvider = choice
        pendingWidgetId = runCatching { appWidgetHost.allocateAppWidgetId() }
            .getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)
        if (pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            pendingWidgetProvider = null
            requireContext().showToast(R.string.widget_add_failed)
            return
        }

        val options = Bundle()
        val bound = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(
                pendingWidgetId,
                choice.user,
                choice.info.provider,
                options,
            )
        }.getOrDefault(false)

        if (bound) {
            completePendingWidget()
            return
        }

        val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, choice.info.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, choice.user)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
        }
        runCatching {
            startActivityForResult(bindIntent, Constants.REQUEST_CODE_WIDGET_BIND)
        }.onFailure {
            deletePendingWidget()
            requireContext().showToast(R.string.widget_bind_failed)
        }
    }

    private fun completePendingWidget() {
        val widgetId = pendingWidgetId
        val choice = pendingWidgetProvider
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || choice == null) return

        val providerInfo = appWidgetManager.getAppWidgetInfo(widgetId) ?: choice.info
        val configurationActivity = providerInfo.configure
        if (configurationActivity != null) {
            // The host API supports providers whose configuration activity is not exported.
            runCatching {
                appWidgetHost.startAppWidgetConfigureActivityForResult(
                    requireActivity(),
                    widgetId,
                    0,
                    Constants.REQUEST_CODE_WIDGET_CONFIG,
                    null,
                )
            }.onFailure {
                deletePendingWidget()
                requireContext().showToast(R.string.widget_config_failed)
            }
        } else {
            savePendingWidget(providerInfo)
        }
    }

    private fun savePendingWidget(providerInfo: AppWidgetProviderInfo) {
        val widgetId = pendingWidgetId
        val choice = pendingWidgetProvider ?: return
        val (spanX, spanY) = binding.widgetCanvas.calculateSpans(
            providerInfo.minWidth,
            providerInfo.minHeight,
        )
        val (cellX, cellY) = binding.widgetCanvas.findAvailablePosition(spanX, spanY)
        val placement = WidgetPlacement(
            appWidgetId = widgetId,
            providerPackage = providerInfo.provider.packageName,
            providerClass = providerInfo.provider.className,
            user = choice.user.toString(),
            cellX = cellX,
            cellY = cellY,
            spanX = spanX,
            spanY = spanY,
        )
        prefs.upsertWidgetPlacement(placement)
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        pendingWidgetProvider = null
        restoreWidgets()
        requireContext().showToast(R.string.widget_added)
    }

    private fun deletePendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { appWidgetHost.deleteAppWidgetId(pendingWidgetId) }
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        pendingWidgetProvider = null
    }

    private fun showWidgetOptions(placement: WidgetPlacement) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(placement.appWidgetId)
            ?.takeIf { it.provider == placement.provider }
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (providerInfo != null) {
            actions += getString(R.string.widget_move) to {
                binding.widgetCanvas.beginInteraction(
                    placement.appWidgetId,
                    WidgetCanvasView.InteractionMode.MOVE,
                )
            }
            if (providerInfo.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) {
                actions += getString(R.string.widget_resize) to {
                    binding.widgetCanvas.beginInteraction(
                        placement.appWidgetId,
                        WidgetCanvasView.InteractionMode.RESIZE,
                    )
                }
            }
            if (providerInfo.configure != null) {
                actions += getString(R.string.widget_configure) to {
                    configureExistingWidget(placement.appWidgetId, providerInfo)
                }
            }
        }
        actions += getString(R.string.widget_remove) to { removeWidget(placement) }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.widget_options)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun configureExistingWidget(appWidgetId: Int, providerInfo: AppWidgetProviderInfo) {
        val configurationActivity = providerInfo.configure ?: return
        configuringWidgetId = appWidgetId
        runCatching {
            appWidgetHost.startAppWidgetConfigureActivityForResult(
                requireActivity(),
                appWidgetId,
                0,
                Constants.REQUEST_CODE_WIDGET_CONFIG,
                null,
            )
        }.onFailure {
            configuringWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            requireContext().showToast(R.string.widget_config_failed)
        }
    }

    private fun removeWidget(placement: WidgetPlacement) {
        runCatching { appWidgetHost.deleteAppWidgetId(placement.appWidgetId) }
        prefs.removeWidgetPlacement(placement.appWidgetId)
        binding.widgetCanvas.removeWidgetView(placement.appWidgetId)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleActivityResult(requestCode, resultCode, data)
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            Constants.REQUEST_CODE_WIDGET_BIND -> {
                if (resultCode == Activity.RESULT_OK) {
                    completePendingWidget()
                } else {
                    deletePendingWidget()
                    requireContext().showToast(R.string.widget_bind_failed)
                }
            }

            Constants.REQUEST_CODE_WIDGET_CONFIG -> {
                when {
                    pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID -> {
                        if (resultCode == Activity.RESULT_OK) {
                            val choice = pendingWidgetProvider
                            val providerInfo = appWidgetManager.getAppWidgetInfo(pendingWidgetId)
                                ?: choice?.info
                            if (providerInfo != null) savePendingWidget(providerInfo)
                            else deletePendingWidget()
                        } else {
                            deletePendingWidget()
                            requireContext().showToast(R.string.widget_config_cancelled)
                        }
                    }

                    configuringWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID -> {
                        configuringWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                        restoreWidgets()
                    }
                }
            }
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                textView.text = ""
                return false
            }
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun openDoubleTapApp() {
        if (!prefs.doubleTapAppEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameDoubleTap,
            packageName = prefs.appPackageDoubleTap,
            activityClassName = prefs.appActivityClassNameDoubleTap,
            shortcutId = prefs.shortcutIdDoubleTap,
            isShortcut = prefs.isShortcutDoubleTap,
            userString = prefs.appUserDoubleTap,
        )
    }

    private fun doubleTapAction() {
        if (prefs.doubleTapAppEnabled) {
            openDoubleTapApp()
            return
        }
        if (!prefs.lockModeOn) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            binding.lock.performClick()
        else
            lockPhone()
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        (requireActivity() as AppDrawerHost).showAppDrawer(
            AppDrawerRequest(
                flag = flag,
                canRename = rename,
                includeHiddenApps = includeHiddenApps,
            )
        )
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                showHomeLongPressMenu()
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                doubleTapAction()
            }

            override fun onClick() {
                super.onClick()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                doubleTapAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
