package com.elicapo.launcher.ui

import android.os.Build
import android.os.Process
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import com.elicapo.launcher.MainViewModel
import com.elicapo.launcher.R
import com.elicapo.launcher.data.AppModel
import com.elicapo.launcher.data.Constants
import com.elicapo.launcher.data.Prefs
import com.elicapo.launcher.databinding.FragmentAppDrawerBinding
import com.elicapo.launcher.helper.deletePinnedShortcut
import com.elicapo.launcher.helper.hideKeyboard
import com.elicapo.launcher.helper.isEinkDisplay
import com.elicapo.launcher.helper.isSystemApp
import com.elicapo.launcher.helper.openAppInfo
import com.elicapo.launcher.helper.openSearch
import com.elicapo.launcher.helper.openUrl
import com.elicapo.launcher.helper.showKeyboard
import com.elicapo.launcher.helper.showToast
import com.elicapo.launcher.helper.uninstall

class AppDrawerController(
    private val binding: FragmentAppDrawerBinding,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModel: MainViewModel,
    private val prefs: Prefs,
    private val onCloseRequested: (DrawerReturnTarget) -> Unit,
) {

    private val context = binding.root.context
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked = true
    private var currentPrivateSpaceAvailable = false
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
        initialized = true
    }

    fun configure(request: AppDrawerRequest) {
        initialize()
        flag = request.flag
        canRename = request.canRename
        adapter.setFlag(flag)
        adapter.setAppLabelGravity(prefs.appLabelAlignment)
        updateSearchHint()
        binding.search.findViewById<TextView>(R.id.search_src_text)?.gravity = prefs.appLabelAlignment
        binding.appRename.visibility = View.GONE
        binding.search.setQuery("", false)
        binding.search.clearFocus()

        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.value?.let { adapter.setAppList(it.toMutableList()) }
        } else {
            updateCombinedAppList()
        }
    }

    fun onDrawerOpened() {
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    fun onDrawerClosed() {
        binding.search.hideKeyboard()
    }

    private fun initViews() {
        updateSearchHint()
        try {
            binding.search.findViewById<TextView>(R.id.search_src_text)?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateSearchHint() {
        binding.search.queryHint = when {
            flag == Constants.FLAG_HIDDEN_APPS -> context.getString(R.string.hidden_apps)
            flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP
                    || flag == Constants.FLAG_SET_DOUBLE_TAP_APP -> "Please select an app"
            else -> " ___"
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query?.startsWith("!") == true)
                    context.openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    context.openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    adapter.filter.filter(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    onCloseRequested(DrawerReturnTarget.HOME)
                else
                    onCloseRequested(DrawerReturnTarget.CURRENT)
            },
            appInfoListener = {
                openAppInfo(context, it.user, it.appPackage)
                onCloseRequested(DrawerReturnTarget.HOME)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            context.deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(context, appModel.user, appModel.appPackage)
                        } else if (context.isSystemApp(appModel.appPackage, appModel.user)) {
                            context.showToast(context.getString(R.string.system_app_cannot_delete))
                            openAppInfo(context, appModel.user, appModel.appPackage)
                        } else {
                            context.uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList(forceRefresh = true)
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    context.showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    onCloseRequested(DrawerReturnTarget.CURRENT)
                viewModel.getAppList(forceRefresh = true)
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList(forceRefresh = true)
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                onCloseRequested(DrawerReturnTarget.HOME)
            }
        )

        linearLayoutManager = object : LinearLayoutManager(context) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    exitOnTopOverscroll()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (context.isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun initObservers() {
        viewModel.hiddenApps.observe(lifecycleOwner) {
            if (flag == Constants.FLAG_HIDDEN_APPS)
                it?.let { adapter.setAppList(it.toMutableList()) }
        }
        viewModel.appList.observe(lifecycleOwner) {
            currentAppList = it
            if (flag != Constants.FLAG_HIDDEN_APPS)
                updateCombinedAppList()
        }
        viewModel.privateSpaceAvailable.observe(lifecycleOwner) {
            currentPrivateSpaceAvailable = it
            if (flag == Constants.FLAG_LAUNCH_APP) updateCombinedAppList()
        }
        viewModel.privateSpaceLocked.observe(lifecycleOwner) {
            currentPrivateSpaceLocked = it
            if (flag == Constants.FLAG_LAUNCH_APP) updateCombinedAppList()
        }
        viewModel.privateSpaceApps.observe(lifecycleOwner) {
            currentPrivateSpaceApps = it
            if (flag == Constants.FLAG_LAUNCH_APP) updateCombinedAppList()
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked)
                currentPrivateSpaceApps?.let { combined.addAll(it) }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                context.showToast(context.getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            onCloseRequested(DrawerReturnTarget.CURRENT)
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop)
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    private fun exitOnTopOverscroll() {
        onCloseRequested(DrawerReturnTarget.CURRENT)
    }

}
