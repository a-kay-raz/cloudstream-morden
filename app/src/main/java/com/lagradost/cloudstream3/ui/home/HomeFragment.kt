package com.lagradost.cloudstream3.ui.home

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EdgeEffect
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Collections
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentHomeBinding
import com.lagradost.cloudstream3.databinding.HomeEpisodesExpandedBinding
import com.lagradost.cloudstream3.databinding.HomeProviderSwitcherBinding
import com.lagradost.cloudstream3.databinding.HomeProviderSwitcherRowBinding
import com.lagradost.cloudstream3.databinding.TvtypesChipsBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.SectionEntranceAnimator
import com.lagradost.cloudstream3.nuvio.NuvioProvider
import com.lagradost.cloudstream3.sky.SkyPluginProvider
import com.lagradost.cloudstream3.stremio.StremioAddonProvider
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.account.AccountViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.hapticTick
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.EmptyEvent
import com.lagradost.cloudstream3.utils.SubtitleHelper.getFlagFromIso
import com.lagradost.cloudstream3.utils.TvChannelUtils
import com.lagradost.cloudstream3.utils.UIHelper.applyFloatingNavBottomMargin
import com.lagradost.cloudstream3.utils.UIHelper.applyFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomMargin
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIconsAndNoStringRes
import com.lagradost.cloudstream3.utils.UIHelper.toPx

private const val TAG = "HomeFragment"

class HomeFragment : BaseFragment<FragmentHomeBinding>(
    BindingCreator.Bind(FragmentHomeBinding::bind)
) {
    companion object {
        // Used for configuration changed events to fix any popups that are not attached to a fragment
        val configEvent = EmptyEvent()
        var currentSpan = 1

        private val errorProfilePics = listOf(
            R.drawable.monke_benene,
            R.drawable.monke_burrito,
            R.drawable.monke_coco,
            R.drawable.monke_cookie,
            R.drawable.monke_flusdered,
            R.drawable.monke_funny,
            R.drawable.monke_like,
            R.drawable.monke_party,
            R.drawable.monke_sob,
            R.drawable.monke_drink,
        )

        val errorProfilePic = errorProfilePics.random()

        fun Context.getDisplayName(apiName: String?): String? {
            return when (apiName) {
                noneApi.name -> getString(R.string.none)
                randomApi.name -> getString(R.string.home_random)
                else -> apiName
            }
        }

        //fun Activity.loadHomepageList(
        //    item: HomePageList,
        //    deleteCallback: (() -> Unit)? = null,
        //) {
        //    loadHomepageList(
        //        expand = HomeViewModel.ExpandableHomepageList(item, 1, false),
        //        deleteCallback = deleteCallback,
        //        expandCallback = null
        //    )
        //}

        // returns a BottomSheetDialog that will be hidden with OwnHidden upon hide, and must be saved to be able call ownShow in onCreateView

        fun Activity.loadHomepageList(
            expand: HomeViewModel.ExpandableHomepageList,
            deleteCallback: (() -> Unit)? = null,
            expandCallback: (suspend (String) -> HomeViewModel.ExpandableHomepageList?)? = null,
            dismissCallback: (() -> Unit),
        ): BottomSheetDialog {
            val context = this
            val bottomSheetDialogBuilder = BottomSheetDialog(context)
            val binding: HomeEpisodesExpandedBinding = HomeEpisodesExpandedBinding.inflate(
                bottomSheetDialogBuilder.layoutInflater,
                null,
                false
            )
            bottomSheetDialogBuilder.setContentView(binding.root)
            //val title = bottomSheetDialogBuilder.findViewById<TextView>(R.id.home_expanded_text)!!

            //title.findViewTreeLifecycleOwner().lifecycle.addObserver()

            val item = expand.list
            binding.homeExpandedText.text = item.name
            // val recycle =
            //    bottomSheetDialogBuilder.findViewById<AutofitRecyclerView>(R.id.home_expanded_recycler)!!
            //val titleHolder =
            //    bottomSheetDialogBuilder.findViewById<FrameLayout>(R.id.home_expanded_drag_down)!!

            // main {
            //(bottomSheetDialogBuilder.ownerActivity as androidx.fragment.app.FragmentActivity?)?.supportFragmentManager?.fragments?.lastOrNull()?.viewLifecycleOwner?.apply {
            //    println("GOT LIFE: lifecycle $this")
            //    this.lifecycle.addObserver(object : DefaultLifecycleObserver {
            //        override fun onResume(owner: LifecycleOwner) {
            //            super.onResume(owner)
            //            println("onResume!!!!")
            //            bottomSheetDialogBuilder?.ownShow()
            //        }

            //        override fun onStop(owner: LifecycleOwner) {
            //            super.onStop(owner)
            //            bottomSheetDialogBuilder?.ownHide()
            //        }
            //    })
            //}
            // }
            //val delete = bottomSheetDialogBuilder.home_expanded_delete
            binding.homeExpandedDelete.isGone = deleteCallback == null
            if (deleteCallback != null) {
                binding.homeExpandedDelete.setOnClickListener {
                    try {
                        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
                        val dialogClickListener =
                            DialogInterface.OnClickListener { _, which ->
                                when (which) {
                                    DialogInterface.BUTTON_POSITIVE -> {
                                        deleteCallback.invoke()
                                        bottomSheetDialogBuilder.dismissSafe(this)
                                    }

                                    DialogInterface.BUTTON_NEGATIVE -> {}
                                }
                            }

                        builder.setTitle(R.string.clear_history)
                            .setMessage(
                                context.getString(R.string.delete_message).format(
                                    item.name
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }
            binding.homeExpandedDragDown.setOnClickListener {
                bottomSheetDialogBuilder.dismissSafe(this)
            }


            // Span settings
            binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
            binding.homeExpandedRecycler.setRecycledViewPool(SearchAdapter.sharedPool)
            binding.homeExpandedRecycler.adapter =
                SearchAdapter(binding.homeExpandedRecycler,item.isHorizontalImages) { callback ->
                    handleSearchClickCallback(callback)
                    if (callback.action == SEARCH_ACTION_LOAD || callback.action == SEARCH_ACTION_PLAY_FILE) {
                        bottomSheetDialogBuilder.ownHide() // we hide here because we want to resume it later
                        //bottomSheetDialogBuilder.dismissSafe(this)
                    }
                }.apply {
                    submitList(item.list)
                    hasNext = expand.hasNext
                }

            binding.homeExpandedRecycler.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = expand.list.name

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is SearchAdapter) return

                    val count = adapter.itemCount
                    val currentHasNext = adapter.hasNext
                    //!recyclerView.canScrollVertically(1)
                    if (!recyclerView.isRecyclerScrollable() && currentHasNext && expandCount != count) {
                        expandCount = count
                        ioSafe {
                            expandCallback?.invoke(name)?.let { newExpand ->
                                (recyclerView.adapter as? SearchAdapter?)?.apply {
                                    hasNext = newExpand.hasNext
                                    submitList(newExpand.list.list)
                                }
                            }
                        }
                    }
                }
            })

            val spanListener = Runnable {
                binding.homeExpandedRecycler.spanCount = context.getSpanCount(item.isHorizontalImages)
                // We want to rebind everything to update the UI, however we also want to avoid
                // any animations ect, this is the easiest way to do this, and the most correct
                @SuppressLint("NotifyDataSetChanged")
                binding.homeExpandedRecycler.adapter?.notifyDataSetChanged()
            }

            configEvent += spanListener

            bottomSheetDialogBuilder.setOnDismissListener {
                dismissCallback.invoke()
                configEvent -= spanListener
            }

            //(recycle.adapter as SearchAdapter).notifyDataSetChanged()

            bottomSheetDialogBuilder.show()
            return bottomSheetDialogBuilder
        }

        private fun getPairList(
            anime: Chip?,
            cartoons: Chip?,
            tvs: Chip?,
            docs: Chip?,
            movies: Chip?,
            asian: Chip?,
            livestream: Chip?,
            torrent: Chip?,
            nsfw: Chip?,
            others: Chip?,
        ): List<Pair<Chip?, List<TvType>>> {
            // This list should be same order as home screen to aid navigation
            return listOf(
                Pair(movies, listOf(TvType.Movie)),
                Pair(tvs, listOf(TvType.TvSeries)),
                Pair(anime, listOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
                Pair(asian, listOf(TvType.AsianDrama)),
                Pair(cartoons, listOf(TvType.Cartoon)),
                Pair(docs, listOf(TvType.Documentary)),
                Pair(livestream, listOf(TvType.Live)),
                Pair(torrent, listOf(TvType.Torrent)),
                Pair(nsfw, listOf(TvType.NSFW)),
                Pair(others, listOf(TvType.Others)),
            )
        }

        private fun getPairList(header: TvtypesChipsBinding) = getPairList(
            header.homeSelectAnime,
            header.homeSelectCartoons,
            header.homeSelectTvSeries,
            header.homeSelectDocumentaries,
            header.homeSelectMovies,
            header.homeSelectAsian,
            header.homeSelectLivestreams,
            header.homeSelectTorrents,
            header.homeSelectNsfw,
            header.homeSelectOthers
        )

        fun validateChips(header: TvtypesChipsBinding?, validTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
            }
        }

        fun updateChips(header: TvtypesChipsBinding?, selectedTypes: List<TvType>) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                button?.isChecked =
                    button.isVisible && selectedTypes.any { types.contains(it) }
            }
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit
        ) {
            bindChips(header, selectedTypes, validTypes, callback, null, null)
        }

        fun bindChips(
            header: TvtypesChipsBinding?,
            selectedTypes: List<TvType>,
            validTypes: List<TvType>,
            callback: (List<TvType>) -> Unit,
            nextFocusDown: Int?,
            nextFocusUp: Int?
        ) {
            if (header == null) return
            val pairList = getPairList(header)
            for ((button, types) in pairList) {
                val isValid = validTypes.any { types.contains(it) }
                button?.isVisible = isValid
                button?.isChecked = isValid && selectedTypes.any { types.contains(it) }
                button?.isFocusable = true
                if (isLayout(TV)) {
                    button?.isFocusableInTouchMode = true
                }

                if (nextFocusDown != null)
                    button?.nextFocusDownId = nextFocusDown

                if (nextFocusUp != null)
                    button?.nextFocusUpId = nextFocusUp

                button?.setOnCheckedChangeListener { _, _ ->
                    val list = ArrayList<TvType>()
                    for ((sbutton, vvalidTypes) in pairList) {
                        if (sbutton?.isChecked == true)
                            list.addAll(vvalidTypes)
                    }
                    callback(list)
                }
            }
        }

        fun Context.selectHomepage(
            selectedApiName: String?,
            onCustomSelect: ((HomeCustomLists.CustomList) -> Unit)? = null,
            callback: (String) -> Unit
        ) {
            val validAPIs = filterProviderByPreferredMedia().toMutableList()

            validAPIs.add(0, randomApi)
            validAPIs.add(0, noneApi)

            // Phase 8.83: SkyStream-style smart switcher sheet —
            // title, type-filter chips (All + the tv-type chips this
            // picker has always had), and a radio list whose rows
            // carry source tags (repository name / bridge source).
            // Every data rule below is the pre-8.83 logic unchanged
            // (pinned first via DataStoreHelper.pinnedProviders,
            // custom lists appended with "custom:<id>" pin keys,
            // Family Mode filter); only the presentation moved from
            // a ListView to this RecyclerView sheet. SearchFragment
            // keeps the old home_select_mainpage layout untouched.
            val dialog = BottomSheetDialog(this, R.style.AppBottomSheetDialogTheme)
            val binding: HomeProviderSwitcherBinding =
                HomeProviderSwitcherBinding.inflate(
                    dialog.layoutInflater,
                    null,
                    false
                )
            dialog.setContentView(binding.root)
            // Phase 8.84: the sheet opened STATE_EXPANDED to the full
            // screen and read as "too big" — cap it at ~72% of the
            // screen height. The list is wrap_content and scrolls
            // internally past the cap; the filter chips stay pinned
            // above it (they are outside the RecyclerView).
            dialog.behavior.maxHeight =
                (resources.displayMetrics.heightPixels * 0.72).toInt()
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

            // Phase 8.47: his custom lists are appended to this
            // same picker (his request) so providers and custom
            // lists are managed in one place.
            val customLists = HomeCustomLists.getLists(this)
            // Phase 8.74: the customs as DISPLAYED (pinned ones first);
            // updateList() recomputes it, click/pin handlers read it.
            var displayCustoms: List<HomeCustomLists.CustomList> = customLists

            val isMultiLang = getApiProviderLangSettings().let { set ->
                set.size > 1 || set.contains(AllLanguagesName)
            }

            var currentApiName = selectedApiName

            var currentValidApis: MutableList<MainAPI> = mutableListOf()
            val preSelectedTypes = DataStoreHelper.homePreference.toMutableList()
            val validTypesAll = validAPIs.flatMap { it.supportedTypes }.distinct()

            var pinnedphashset = DataStoreHelper.pinnedProviders.toHashSet()

            // ---- Phase 8.83: row model + source tags ----

            data class SwitcherTag(val text: String, val cloud: Boolean)

            class SwitcherRow(
                val api: MainAPI?,
                val custom: HomeCustomLists.CustomList?,
                val title: String,
                val tags: List<SwitcherTag>,
                val selected: Boolean,
                val pinned: Boolean,
                val settingsPlugin: Plugin?,
            )

            class SwitcherHolder(val rowBinding: HomeProviderSwitcherRowBinding) :
                RecyclerView.ViewHolder(rowBinding.root)

            /** Repository display name for a downloaded native
             *  plugin: its file lives in a folder named after the
             *  sanitized repository URL (PluginManager.getPluginPath),
             *  matched against the stored + prebuilt repositories. */
            fun repositoryTagFor(api: MainAPI): String? {
                val path = api.sourcePlugin ?: return null
                val folder = File(path).parentFile?.name ?: return null
                val repos = RepositoryManager.getRepositories().toList() +
                    RepositoryManager.PREBUILT_REPOSITORIES.toList()
                return repos.firstOrNull {
                    PluginManager.getPluginSanitizedFileName(it.url) == folder
                }?.name
            }

            fun tagsFor(api: MainAPI): List<SwitcherTag> {
                if (api.name == noneApi.name || api.name == randomApi.name) {
                    return emptyList()
                }
                return when {
                    api is SkyPluginProvider -> listOf(
                        SwitcherTag(
                            if (api.sub != null) {
                                api.stored.name
                            } else {
                                getString(R.string.switcher_tag_skystream)
                            },
                            false
                        )
                    )

                    api is StremioAddonProvider -> listOf(
                        SwitcherTag(
                            getString(R.string.switcher_tag_stremio),
                            false
                        )
                    )

                    api is NuvioProvider -> emptyList()

                    api.sourcePlugin != null -> buildList {
                        repositoryTagFor(api)?.let { add(SwitcherTag(it, false)) }
                        add(
                            SwitcherTag(
                                getString(R.string.switcher_tag_cloudstream),
                                true
                            )
                        )
                    }

                    else -> listOf(
                        SwitcherTag(
                            getString(R.string.switcher_tag_cloudstream),
                            true
                        )
                    )
                }
            }

            fun titleFor(api: MainAPI): String {
                // Sky sub-providers headline with the sub name; the
                // plugin name moves to the source tag (SkyStream's
                // Select Provider rows work the same way).
                val base = if (api is SkyPluginProvider && api.sub != null) {
                    api.sub.name
                } else {
                    getDisplayName(api.name) ?: api.name
                }
                return if (isMultiLang) {
                    "${getFlagFromIso(api.lang)?.plus(" ") ?: ""}$base"
                } else {
                    base
                }
            }

            val rows = mutableListOf<SwitcherRow>()
            lateinit var switcherAdapter: RecyclerView.Adapter<SwitcherHolder>

            fun updateList() {
                DataStoreHelper.homePreference = preSelectedTypes
                val pinnedp = DataStoreHelper.pinnedProviders.toList()
                pinnedphashset = pinnedp.toHashSet()
                val sortedApis = validAPIs
                    .filter {
                        val isPinned = pinnedphashset.contains(it.name)

                        // Hide pinned NSFW when NSFW not selected. NSFW is distracting when not chosen.
                        if (isPinned && !preSelectedTypes.contains(TvType.NSFW)) {
                            if (it.supportedTypes.all { type -> type == TvType.NSFW }) return@filter false
                        }

                        it.hasMainPage && (isPinned || it.supportedTypes.any(
                            preSelectedTypes::contains
                        ))
                    }
                    .sortedBy { it.name.lowercase() }

                val sortedApiMap = LinkedHashMap<String, MainAPI>().apply {
                    sortedApis.forEach { put(it.name, it) }
                }

                val pinnedApis = pinnedp.asReversed().mapNotNull { name ->
                    sortedApiMap[name]
                }

                val remainingApis = sortedApis.filterNot { pinnedphashset.contains(it.name) }

                currentValidApis = mutableListOf<MainAPI>().apply {
                    addAll(validAPIs.take(2))
                    addAll(pinnedApis)
                    addAll(remainingApis)
                }

                // Phase 8.50: Family Mode hides adult providers
                // from the picker entirely.
                if (FamilyMode.isEnabled()) {
                    currentValidApis.removeAll {
                        FamilyMode.isAdultApi(it)
                    }
                }

                // Phase 8.74: pinned customs join the top group
                // like pinned providers (same store, "custom:<id>"
                // keys, most-recent pin first), and the star
                // prefix beside custom names is gone (his call).
                val pinnedCustoms = pinnedp.asReversed()
                    .mapNotNull { key ->
                        if (key.startsWith("custom:")) {
                            customLists.firstOrNull {
                                "custom:" + it.id == key
                            }
                        } else {
                            null
                        }
                    }
                displayCustoms = pinnedCustoms + customLists.filterNot {
                    pinnedphashset.contains("custom:" + it.id)
                }

                // The old ListView seeded its checked row from the
                // active custom list first, the current api second;
                // the rows carry that same selection state now.
                val activeCustom =
                    HomeCustomLists.activeList(this@selectHomepage)
                        ?.takeIf { active ->
                            displayCustoms.any { it.id == active.id }
                        }

                rows.clear()
                for (api in currentValidApis) {
                    val pluginInstance =
                        api.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                    val settingsPlugin =
                        if (pluginInstance?.openSettings != null && !isLayout(TV)) {
                            pluginInstance
                        } else {
                            null
                        }
                    rows += SwitcherRow(
                        api = api,
                        custom = null,
                        title = titleFor(api),
                        tags = tagsFor(api),
                        selected = activeCustom == null &&
                            api.name == currentApiName,
                        pinned = pinnedphashset.contains(api.name),
                        settingsPlugin = settingsPlugin,
                    )
                }
                for (custom in displayCustoms) {
                    rows += SwitcherRow(
                        api = null,
                        custom = custom,
                        title = custom.name,
                        tags = emptyList(),
                        selected = activeCustom?.id == custom.id,
                        pinned = pinnedphashset.contains("custom:" + custom.id),
                        settingsPlugin = null,
                    )
                }
                switcherAdapter.notifyDataSetChanged()

                // Keep the All chip in sync with the tv-type chips.
                binding.switcherChipAll.isChecked =
                    validTypesAll.isNotEmpty() &&
                        preSelectedTypes.containsAll(validTypesAll)
            }

            switcherAdapter = object : RecyclerView.Adapter<SwitcherHolder>() {
                override fun onCreateViewHolder(
                    parent: ViewGroup,
                    viewType: Int
                ): SwitcherHolder {
                    val rowBinding = HomeProviderSwitcherRowBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    return SwitcherHolder(rowBinding)
                }

                override fun getItemCount(): Int = rows.size

                override fun onBindViewHolder(holder: SwitcherHolder, position: Int) {
                    val row = rows[position]
                    val rb = holder.rowBinding
                    val rowContext = holder.itemView.context

                    rb.switcherRowRadio.setImageResource(
                        if (row.selected) {
                            R.drawable.ic_switcher_radio_checked
                        } else {
                            R.drawable.ic_switcher_radio_unchecked
                        }
                    )
                    rb.switcherRowTitle.text = row.title
                    rb.switcherRowTitle.typeface = if (row.selected) {
                        Typeface.DEFAULT_BOLD
                    } else {
                        Typeface.DEFAULT
                    }
                    rb.switcherRowTitle.setTextColor(
                        rowContext.getResourceColor(
                            if (row.selected) R.attr.textColor
                            else R.attr.grayTextColor
                        )
                    )

                    rb.switcherRowTags.removeAllViews()
                    row.tags.forEachIndexed { index, tag ->
                        val chip = TextView(rowContext).apply {
                            text = tag.text
                            textSize = 11f
                            setTextColor(
                                ContextCompat.getColor(
                                    rowContext,
                                    if (tag.cloud) R.color.switcherTagCloudText
                                    else R.color.switcherTagText
                                )
                            )
                            setBackgroundResource(
                                if (tag.cloud) R.drawable.bg_switcher_tag_cloud
                                else R.drawable.bg_switcher_tag
                            )
                            setPadding(8.toPx, 3.toPx, 8.toPx, 3.toPx)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                if (index > 0) marginStart = 6.toPx
                            }
                        }
                        rb.switcherRowTags.addView(chip)
                    }

                    rb.switcherRowPin.visibility =
                        if (row.pinned) View.VISIBLE else View.GONE

                    val settingsPlugin = row.settingsPlugin
                    rb.switcherRowSettings.visibility =
                        if (settingsPlugin != null) View.VISIBLE else View.GONE
                    if (settingsPlugin != null) {
                        rb.switcherRowSettings.setOnClickListener { view ->
                            try {
                                val activityContext =
                                    view.context.getActivity() ?: view.context
                                settingsPlugin.openSettings?.invoke(activityContext)
                            } catch (e: Throwable) {
                                logError(e)
                            }
                        }
                    } else {
                        rb.switcherRowSettings.setOnClickListener(null)
                    }

                    rb.root.setOnClickListener {
                        val pos = holder.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                        val clicked = rows[pos]
                        if (clicked.api != null) {
                            currentApiName = clicked.api.name
                            //to switch to apply simply remove this
                            currentApiName?.let(callback)
                            dialog.dismissSafe()
                        } else if (clicked.custom != null && onCustomSelect != null) {
                            onCustomSelect(clicked.custom)
                            dialog.dismissSafe()
                        }
                    }

                    // pin provider on hold
                    rb.root.setOnLongClickListener {
                        val pos = holder.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) {
                            return@setOnLongClickListener true
                        }
                        val clicked = rows[pos]
                        val pinnedp =
                            DataStoreHelper.pinnedProviders.toMutableList()
                        if (clicked.api != null) {
                            // none/random (the first two rows) cannot be pinned
                            if (currentValidApis.indexOf(clicked.api) > 1) {
                                val thisapi = clicked.api.name
                                if (pinnedp.contains(thisapi)) {
                                    pinnedp.remove(thisapi)
                                } else {
                                    pinnedp.add(thisapi)
                                }
                                DataStoreHelper.pinnedProviders =
                                    pinnedp.toTypedArray()
                                updateList()
                            }
                        } else if (clicked.custom != null) {
                            // Phase 8.74: custom lists pin/unpin on hold
                            // exactly like providers.
                            val key = "custom:" + clicked.custom.id
                            if (pinnedp.contains(key)) {
                                pinnedp.remove(key)
                            } else {
                                pinnedp.add(key)
                            }
                            DataStoreHelper.pinnedProviders =
                                pinnedp.toTypedArray()
                            updateList()
                        }
                        true
                    }
                }
            }

            binding.switcherRecycler.layoutManager = LinearLayoutManager(this)
            binding.switcherRecycler.adapter = switcherAdapter

            // Phase 8.89: dismissal moved to the title row's ✕
            // (standard sheet chrome); the old bottom Close row
            // was removed from the layout.
            binding.switcherCloseBtt.setOnClickListener {
                dialog.dismissSafe()
            }

            // The All chip drives the same selection the tv-type
            // chips write: on = every type present, off = none.
            binding.switcherChipAll.isFocusable = true
            if (isLayout(TV)) {
                binding.switcherChipAll.isFocusableInTouchMode = true
            }
            binding.switcherChipAll.setOnClickListener {
                if (binding.switcherChipAll.isChecked) {
                    preSelectedTypes.clear()
                    preSelectedTypes.addAll(validTypesAll)
                } else {
                    preSelectedTypes.clear()
                }
                updateChips(binding.switcherTvtypesChips, preSelectedTypes)
                updateList()
            }

            bindChips(
                binding.switcherTvtypesChips,
                preSelectedTypes,
                validTypesAll
            ) { list ->
                preSelectedTypes.clear()
                preSelectedTypes.addAll(list)
                updateList()
            }
            updateList()

            dialog.show()
            // The dialog's own sheet frame stays transparent — the
            // sheet root's rounded_dialog background is the only
            // surface (same recipe as the 8.82 settings sheet).
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val accountViewModel: AccountViewModel by activityViewModels()

    fun addMovies(cards: List<SearchResponse>) {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting addMovies")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")

                val programCards = cards

                TvChannelUtils.addPrograms(
                    context = ctx,
                    channelId = existingId,
                    items = programCards
                )
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding movies: $e")
        }
    }

    private fun deleteAll() {
        val ctx = context ?: run {
            Log.e(TAG, "Context is null, aborting deleteAll")
            return
        }

        try {
            val existingId = TvChannelUtils.getChannelId(ctx, getString(R.string.app_name))
            if (existingId != null) {
                Log.d(TAG, "Channel ID: $existingId")
                TvChannelUtils.deleteStoredPrograms(ctx)
            } else {
                Log.d(TAG, "Channel does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting programs: ${e.message}")
        }
    }

    override fun pickLayout(): Int? =
        if (isLayout(PHONE)) R.layout.fragment_home else R.layout.fragment_home_tv

    /** Phase 8.33: a background-resolved title refreshes the rows.
     *  Phase 8.34: DEBOUNCED — titles resolve in bursts while
     *  scrolling, and a full rebind per title made home scroll
     *  shake (কাপাকাপি). One rebind after a quiet gap is enough. */
    private val titleNotifyRunnable = object : Runnable {
        override fun run() {
            // Phase 8.41: a full rebind mid-scroll still shudders
            // (posters reload + crossfade) -- wait for the list to
            // come to rest, then refresh once.
            val recycler = binding?.homeMasterRecycler
            if (recycler != null &&
                recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE
            ) {
                homeAutoRotateHandler.postDelayed(this, 600L)
                return
            }
            // Phase 8.76: this used to rebind the WHOLE master
            // adapter — hero, strips and every row — each time a
            // burst of titles/ratings landed, which he still felt
            // as the screen shaking while posters loaded and while
            // scrolling. Refresh only the adapters actually on
            // screen (row child lists + the header strips); the
            // hero and off-screen rows are left alone.
            if (recycler == null) {
                homeMasterAdapter?.notifyDataSetChanged()
            } else {
                refreshVisibleHomeLists(recycler)
            }
        }
    }

    /** Phase 8.76: notify only the HomeChildItemAdapter /
     *  HomeScrollAdapter instances inside the master list's
     *  currently attached (visible) children. The hero ViewPager2's
     *  internal list is deliberately not matched — its adapter is
     *  neither type — so the hero never rebinds from here. */
    private fun refreshVisibleHomeLists(recycler: RecyclerView) {
        fun walk(view: View) {
            if (view is RecyclerView) {
                val childAdapter = view.adapter
                if (childAdapter is HomeChildItemAdapter ||
                    childAdapter is HomeScrollAdapter
                ) {
                    childAdapter.notifyDataSetChanged()
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        for (i in 0 until recycler.childCount) {
            walk(recycler.getChildAt(i))
        }
    }

    private val titleResolveListener: (String, String) -> Unit = { _, _ ->
        homeAutoRotateHandler.removeCallbacks(titleNotifyRunnable)
        homeAutoRotateHandler.postDelayed(titleNotifyRunnable, 1_200L)
    }

    /** Phase 8.56: a universal rating landing deserves the same
     *  debounced, idle-only rebind as a resolved title. */
    private val metadataResolveListener: (String, String) -> Unit = { _, _ ->
        homeAutoRotateHandler.removeCallbacks(titleNotifyRunnable)
        homeAutoRotateHandler.postDelayed(titleNotifyRunnable, 1_200L)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        bottomSheetDialog?.ownShow()
        TitleResolver.addListener(titleResolveListener)
        MetadataResolver.addListener(metadataResolveListener)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        TitleResolver.removeListener(titleResolveListener)
        MetadataResolver.removeListener(metadataResolveListener)
        homeAutoRotateHandler.removeCallbacks(titleNotifyRunnable)
        // Phase 8.73: never leave the activity's bottom nav translated
        // away when Home's view goes away.
        homeChromeHidden = false
        homeChromeDy = 0
        // Phase 8.99d: the translated chrome bar is the floating overlay
        // on the phone layout; nav_view is its invisible holder there.
        (activity?.findViewById<View>(R.id.floating_nav_view)
            ?: activity?.findViewById<View>(R.id.nav_view))?.let {
            it.animate().cancel()
            it.translationY = 0f
        }
        (activity as? ComponentActivity)?.detachBackPressedCallback("HomeFragment_BackPress")
        bottomSheetDialog?.ownHide()
        // Phase 8.17: the auto-rotation timer must not outlive the view.
        homeAutoRotateHandler.removeCallbacks(homeAutoRotateRunnable)
        // Never leave a pull-to-refresh spinner behind on a destroyed view
        binding?.homeSwipeRefresh?.isRefreshing = false
        super.onDestroyView()
    }

    private val apiChangeClickListener = View.OnClickListener { view ->
        // Phase 8.46: back to the plain provider picker (his
        // correction — custom lists live in Settings → Content →
        // Categories and show on Home when active, not here).
        view.context.selectHomepage(
            currentApiName,
            onCustomSelect = { list ->
                homeViewModel.activateCustomList(list)
            }
        ) { api ->
            // Picking a provider leaves custom mode (the custom
            // list itself is managed in Settings, not here).
            homeViewModel.exitCustomMode()
            homeViewModel.loadAndCancel(api, forceReload = true, fromUI = true)
        }
        /*val validAPIs = view.context?.filterProviderByPreferredMedia()?.toMutableList() ?: mutableListOf()

        validAPIs.add(0, randomApi)
        validAPIs.add(0, noneApi)
        view.popupMenuNoIconsAndNoStringRes(validAPIs.mapIndexed { index, api -> Pair(index, api.name) }) {
            homeViewModel.loadAndCancel(validAPIs[itemId].name)
        }*/
    }

    private var currentApiName: String? = null
    private var toggleRandomButton = false

    private var bottomSheetDialog: BottomSheetDialog? = null
    private var homeMasterAdapter: HomeParentItemAdapterPreview? = null

    // Phase 8.17: the category rows as last submitted (fragment-side
    // copies), in display order — drag-reorder swaps these, and the saved
    // order is re-applied to every fresh load. Plus the live auto-rotation
    // timer that steps the visible rows while the home screen sits open.
    private var homeCategoryItems: MutableList<HomeViewModel.ExpandableHomepageList> =
        mutableListOf()
    private val homeCategoryOrderKey = "home_category_order"

    // Phase 8.35: categories the viewer hid (e.g. adult shelves).
    // Hidden names persist; lastHomeCopies keeps the unfiltered
    // list so hide/unhide re-applies instantly, with no reload.
    private val homeHiddenCategoriesKey = "home_hidden_categories"
    private var lastHomeCopies: List<HomeViewModel.ExpandableHomepageList> = emptyList()
    private var categoryDragSwapped = false

    private fun hiddenCategoryNames(): Set<String> {
        val ctx = context ?: return emptySet()
        val raw = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(homeHiddenCategoriesKey, null) ?: return emptySet()
        return raw.split('\u001F').filter { it.isNotEmpty() }.toSet()
    }

    private fun saveHiddenCategoryNames(names: Set<String>) {
        val ctx = context ?: return
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(homeHiddenCategoriesKey, names.joinToString("\u001F"))
            .apply()
    }

    private fun applyHomeCategories() {
        // Phase 8.44: hidden-category rules belong to the
        // per-provider home; a custom list shows what it holds.
        val visible = if (homeViewModel.isCustomActive) {
            lastHomeCopies
        } else {
            // Phase 8.73: match hidden names NORMALIZED (trim +
            // case-insensitive) — provider shelf names are scraped
            // text and can vary in stray whitespace/casing between
            // loads, which made Hide appear to do nothing.
            val hidden = hiddenCategoryNames()
                .map { it.trim().lowercase() }.toSet()
            lastHomeCopies.filterNot {
                it.list.name.trim().lowercase() in hidden
            }
        }
        // Phase 8.50: Family Mode strips NSFW items from every
        // row (rows left empty disappear) on all home variants.
        // Phase 8.51: adult-labelled shelves go entirely, and
        // items are judged by title as well as declared type —
        // mixed providers file porn under plain "Movie".
        val visibleFiltered = if (FamilyMode.isEnabled()) {
            visible
                .filterNot { FamilyMode.isAdultSection(it.list.name) }
                .mapNotNull { row ->
                    val items = row.list.list.filterNot {
                        FamilyMode.isAdultItem(it)
                    }
                    if (items.isEmpty()) null
                    else row.copy(list = row.list.copy(list = items))
                }
        } else {
            visible
        }
        homeCategoryItems = visibleFiltered.toMutableList()
        (binding?.homeMasterRecycler?.adapter as? ParentItemAdapter)
            ?.submitList(visibleFiltered)
    }

    private fun showCategoryMenu(item: HomeViewModel.ExpandableHomepageList) {
        val act = activity ?: return
        val name = item.list.name
        val options = mutableListOf(getString(R.string.home_hide_category))
        if (hiddenCategoryNames().isNotEmpty()) {
            options.add(getString(R.string.home_unhide_categories))
        }
        AlertDialog.Builder(act)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    saveHiddenCategoryNames(hiddenCategoryNames() + name)
                    applyHomeCategories()
                    showToast(R.string.home_category_hidden, Toast.LENGTH_SHORT)
                } else {
                    showUnhideCategoriesDialog()
                }
            }
            .show()
    }

    private fun showUnhideCategoriesDialog() {
        val act = activity ?: return
        val hidden = hiddenCategoryNames().sorted()
        if (hidden.isEmpty()) return
        val checked = BooleanArray(hidden.size)
        AlertDialog.Builder(act)
            .setTitle(R.string.home_unhide_categories)
            .setMultiChoiceItems(hidden.toTypedArray(), checked) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val restore = hidden.filterIndexed { index, _ -> checked[index] }
                if (restore.isNotEmpty()) {
                    saveHiddenCategoryNames(hiddenCategoryNames() - restore.toSet())
                    applyHomeCategories()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    private val homeAutoRotateHandler = Handler(Looper.getMainLooper())

    // Phase 8.71: hero pull-grow state (see setupHeroScrollCollapse).
    @Volatile
    private var heroPullGrow: Float = 0f
    private var heroPullAnimator: ValueAnimator? = null
    private val homeAutoRotateRunnable = object : Runnable {
        override fun run() {
            rotateVisibleHomeRows()
            homeAutoRotateHandler.postDelayed(this, 60_000L)
        }
    }

    private fun savedCategoryOrderIndex(name: String): Int {
        val idx = savedCategoryOrder().indexOf(name)
        return if (idx >= 0) idx else Int.MAX_VALUE
    }

    private fun savedCategoryOrder(): List<String> {
        val ctx = context ?: return emptyList()
        val raw = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString(homeCategoryOrderKey, null) ?: return emptyList()
        return raw.split('\u001F').filter { it.isNotEmpty() }
    }

    private fun saveCategoryOrder() {
        val ctx = context ?: return
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putString(
                homeCategoryOrderKey,
                homeCategoryItems.joinToString("\u001F") { it.list.name }
            )
            .apply()
    }

    /**
     * Phase 8.17: one auto-rotation step — every category row
     * drops its first poster to the back of the row, so coming
     * back to a row feels fresh.
     * Phase 8.56: rotate only rows that are NOT on screen. The
     * 8.17 version rotated the visible rows themselves, animated
     * by the row's item animator — on an idle screen the whole
     * page visibly shuffled every 20 seconds (his কাপাকাপি
     * report). Off-screen rows now rotate silently through the
     * parent adapter; visible rows keep still.
     */
    private fun rotateVisibleHomeRows() {
        if (!isResumed) return
        val recycler = binding?.homeMasterRecycler ?: return
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val adapter = homeMasterAdapter ?: return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        // Adapter position 0 is the hero header; data index i sits
        // at layout position i + 1.
        val firstVisibleRow = lm.findFirstVisibleItemPosition() - 1
        val lastVisibleRow = lm.findLastVisibleItemPosition() - 1
        val items = adapter.immutableCurrentList
        var changed = false
        val rotated = items.mapIndexed { index, row ->
            val posters = row.list.list
            if (index in firstVisibleRow..lastVisibleRow || posters.size < 4) {
                row
            } else {
                changed = true
                row.copy(
                    list = com.lagradost.cloudstream3.HomePageList(
                        row.list.name,
                        posters.drop(1) + posters.first(),
                        row.list.isHorizontalImages
                    )
                )
            }
        }
        if (changed) adapter.submitList(rotated)
    }

    var lastSavedHomepage: String? = null

    fun saveHomepageToTV(page: Map<String, HomeViewModel.ExpandableHomepageList>) {
        // No need to update for phone
        if (isLayout(PHONE)) {
            return
        }
        val (name, data) = page.entries.firstOrNull() ?: return
        // Modifying homepage is an expensive operation, and therefore we avoid it at all cost
        if (name == lastSavedHomepage) {
            return
        }
        Log.i(TAG, "Adding programs $name to TV")
        lastSavedHomepage = name
        ioSafe {
            // empty the channel
            deleteAll()
            // insert the program from first array
            addMovies(data.list.list)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padTop = false,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
        // Re-audit: re-evaluate floating clearance on rotation.
        view.findViewById<View>(R.id.home_master_recycler)
            ?.refreshFloatingNavBottomPadding()
        view.findViewById<View>(R.id.home_random)
            ?.refreshFloatingNavBottomMargin()

        // Fix grid
        configEvent.invoke()
    }

    /**
     * Phase 8.10: hero scroll-collapse (phone only; wired in
     * [onBindingCreated]). As the home list scrolls, the hero frame
     * (`home_preview_viewpager_text` — photo, bottom fade, search row and
     * the None/Play/Info row as one visual unit) continuously shrinks,
     * fades and lags slightly behind the scroll, dissolving into the
     * page; scrolling back to the top restores it exactly, because every
     * value is a pure function of the current scroll offset. Visual
     * transforms only — no layout heights change, so there is no
     * re-layout jank, and the 8.8 bottom fade / clear photo / auto-slide
     * are untouched.
     *
     * This never fights [HomeScrollTransformer]: the transformer runs on
     * the pager's *horizontal* page changes and only writes page
     * alpha/translationX, while this writes scale/alpha/translationY on
     * the outer frame from the *vertical* list scroll — disjoint
     * properties on disjoint views.
     */
    // Phase 8.73: bottom-nav auto-hide state (8.84: the provider
    // switcher left the chrome — it is the top-bar pill now).
    private var homeChromeHidden = false
    private var homeChromeDy = 0

    /**
     * Phase 8.73 (his request): while scrolling down through Home, the
     * bottom navigation slides away; a slight scroll back up brings it
     * back. Accumulated-delta thresholds so slow drags count too, not
     * just flings. Transforms only. (8.84: the provider switcher is no
     * longer floating chrome — it moved to the top bar — so only the
     * nav is animated here.)
     */
    private fun setupHomeChromeAutoHide(recyclerView: RecyclerView) {
        if (!isLayout(PHONE)) return
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                // Phase 8.74: he found hide AND show both laggy — the
                // accumulator kept the opposite direction's backlog,
                // so reversing direction first had to "pay it off".
                // Reset on any direction flip and use small thresholds.
                if ((dy > 0) != (homeChromeDy > 0)) homeChromeDy = 0
                homeChromeDy += dy
                if (homeChromeDy > 24 && !homeChromeHidden) {
                    homeChromeHidden = true
                    homeChromeDy = 0
                    applyHomeChromeVisibility()
                } else if (homeChromeDy < -12 && homeChromeHidden) {
                    homeChromeHidden = false
                    homeChromeDy = 0
                    applyHomeChromeVisibility()
                }
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    homeChromeDy = 0
                }
            }
        })
    }

    private fun applyHomeChromeVisibility() {
        val nav = activity?.findViewById<View>(R.id.floating_nav_view)
            ?: activity?.findViewById<View>(R.id.nav_view)
        if (homeChromeHidden) {
            if (nav != null) {
                val lp = nav.layoutParams as? ViewGroup.MarginLayoutParams
                val shift = nav.height + (lp?.bottomMargin ?: 0) + 8.toPx
                nav.animate().translationY(shift.toFloat())
                    .setDuration(150).start()
            }
        } else {
            nav?.animate()?.translationY(0f)?.setDuration(150)?.start()
        }
    }

    private fun setupHeroScrollCollapse(recyclerView: RecyclerView) {
        fun applyHeroCollapse() {
            val header = recyclerView.findViewHolderForAdapterPosition(0)?.itemView ?: return
            val frame = header.findViewById<View>(R.id.home_preview_viewpager_text) ?: return
            // No preview loaded: the frame is hidden and the alternate
            // account row shows instead — nothing to collapse.
            if (!frame.isVisible) return
            val height = frame.height
            if (height <= 0) return
            // Layout positions only (itemView top within the list plus the
            // frame's top within the header); transforms never feed back
            // into them, so the mapping stays exact in both directions.
            val scrolled = -(header.top + frame.top)
            val ratio = (scrolled.toFloat() / height).coerceIn(0f, 1f)
            // Phase 8.71: stronger, clearly visible curve (his ask) —
            // shrink to 85% with a gentler fade, anchored to the top
            // edge; plus a pull-grow factor from the top edge effect
            // (heroPullGrow) so dragging down at the very top swells
            // the hero slightly before it springs back.
            frame.pivotY = 0f
            frame.pivotX = frame.width / 2f
            val scale = (1f - 0.15f * ratio) * (1f + heroPullGrow)
            frame.scaleX = scale
            frame.scaleY = scale
            frame.alpha = 1f - 0.6f * ratio
            // Slight parallax lag: the frame drifts down against the
            // scroll by 20% of the scrolled distance while it dissolves.
            frame.translationY = scrolled * 0.2f
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyHeroCollapse()
            }
        })
        // Phase 8.71: pull-grow. The top edge effect reports overscroll
        // pulls while the list is already at the top; each pull swells
        // the hero a little (capped at +6%), and releasing springs it
        // back over 250 ms. Pure transform state — the scroll mapping
        // above remains the single writer via applyHeroCollapse().
        runCatching {
            recyclerView.edgeEffectFactory =
                object : RecyclerView.EdgeEffectFactory() {
                    override fun createEdgeEffect(
                        view: RecyclerView,
                        direction: Int
                    ): EdgeEffect {
                        val base = super.createEdgeEffect(view, direction)
                        if (direction != DIRECTION_TOP) return base
                        return object : EdgeEffect(view.context) {
                            override fun onPull(deltaDistance: Float) {
                                super.onPull(deltaDistance)
                                grow(deltaDistance)
                            }

                            override fun onPull(
                                deltaDistance: Float,
                                displacement: Float
                            ) {
                                super.onPull(deltaDistance, displacement)
                                grow(deltaDistance)
                            }

                            override fun onRelease() {
                                super.onRelease()
                                springBack()
                            }

                            override fun onAbsorb(velocity: Int) {
                                super.onAbsorb(velocity)
                                springBack()
                            }

                            private fun grow(delta: Float) {
                                // Phase 8.73: only swell at the very
                                // top — an edge absorb mid-fling used
                                // to fight the scroll and read as
                                // screen shake.
                                if (view.computeVerticalScrollOffset() != 0) {
                                    return
                                }
                                heroPullAnimator?.cancel()
                                heroPullGrow =
                                    (heroPullGrow + delta * 0.10f)
                                        .coerceAtMost(0.06f)
                                applyHeroCollapse()
                            }

                            private fun springBack() {
                                if (heroPullGrow <= 0f) return
                                heroPullAnimator?.cancel()
                                heroPullAnimator = ValueAnimator.ofFloat(
                                    heroPullGrow, 0f
                                ).apply {
                                    duration = 250L
                                    addUpdateListener { anim ->
                                        heroPullGrow =
                                            anim.animatedValue as Float
                                        applyHeroCollapse()
                                    }
                                    start()
                                }
                            }
                        }
                    }
                }
        }
        // Re-apply when the header scrolls back into view: while it is
        // detached there is no holder to transform, and the frame may
        // still carry the values from when it left the screen.
        recyclerView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (recyclerView.getChildAdapterPosition(view) == 0) {
                        view.post { applyHeroCollapse() }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
    }

    @SuppressLint("SetTextI18n")
    // Phase 8.50: returning from Settings re-applies the row
    // filters, so a Family Mode toggle takes effect at once.
    override fun onResume() {
        super.onResume()
        if (lastHomeCopies.isNotEmpty()) {
            applyHomeCategories()
        }
    }

    override fun onBindingCreated(binding: FragmentHomeBinding) {
        context?.let { HomeChildItemAdapter.updatePosterSize(it) }
        (activity as? ComponentActivity)?.attachBackPressedCallback("HomeFragment_BackPress") {
            handleTvBackPress(this)
        }
        binding.apply {
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            //homeChangeApiLoading.setOnClickListener(apiChangeClickListener)
            // Phase 8.84: the floating provider pill (home_api_fab) is
            // gone — switching lives on the top-bar pill in the head
            // (HeaderViewHolder) and homeChangeApi below. Its FAB-only
            // reload-on-long-press went with it.
            homeChangeApi.setOnClickListener(apiChangeClickListener)
            homeSwitchAccount.setOnClickListener {
                activity?.showAccountSelectLinear()
            }

            homeMasterAdapter = HomeParentItemAdapterPreview(
                homeViewModel, accountViewModel
            )
            homeMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            homeMasterRecycler.adapter = homeMasterAdapter
            // Batch 4: runtime floating-pill clearance (88dp +
            // systemBars bottom) replaces the hardcoded 92dp XML
            // padding, which was short by the system inset.
            homeMasterRecycler.applyFloatingNavBottomPadding()
            // Batch 4: same runtime formula for the Random FAB
            // (was a fixed 104dp margin: 12dp gap on gesture nav,
            // 20dp overlap on 3-button nav).
            homeRandom.applyFloatingNavBottomMargin()
            // Phase 8.56: no item animator on the master list —
            // change animations on rebinds (title/rating refresh,
            // rotation) are a visible shudder (কাপাকাপি) source.
            homeMasterRecycler.itemAnimator = null

            // Phase 8.10 (phone only): provider sections glide in as they
            // scroll into view instead of popping, and the hero shrinks /
            // dissolves as it scrolls off. TV and emulator layouts keep
            // the stock behaviour.
            if (isLayout(PHONE)) {
                SectionEntranceAnimator(firstAnimatedPosition = 1)
                    .attachTo(homeMasterRecycler)
                setupHeroScrollCollapse(homeMasterRecycler)
                setupHomeChromeAutoHide(homeMasterRecycler)

                // Phase 8.17: long-press a category's title strip and drag
                // it up or down to reorder the home categories; the order
                // is saved on drop and re-applied on every load. The hero
                // header (position 0) never moves. Poster touches belong
                // to the rows' own lists, so poster long-press menus are
                // untouched — the drag only starts from the row itself.
                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                ) {
                    override fun isLongPressDragEnabled(): Boolean = true

                    override fun getMovementFlags(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder
                    ): Int {
                        if (viewHolder.bindingAdapterPosition <= 0) return 0
                        return super.getMovementFlags(recyclerView, viewHolder)
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean {
                        val from = viewHolder.bindingAdapterPosition
                        val to = target.bindingAdapterPosition
                        if (from <= 0 || to <= 0) return false
                        // Adapter positions include the header at 0.
                        val fromIndex = from - 1
                        val toIndex = to - 1
                        if (fromIndex !in homeCategoryItems.indices ||
                            toIndex !in homeCategoryItems.indices
                        ) return false
                        Collections.swap(homeCategoryItems, fromIndex, toIndex)
                        (recyclerView.adapter as? ParentItemAdapter)
                            ?.notifyItemMoved(from, to)
                        categoryDragSwapped = true
                        return true
                    }

                    override fun onSwiped(
                        viewHolder: RecyclerView.ViewHolder,
                        direction: Int
                    ) = Unit

                    override fun onSelectedChanged(
                        viewHolder: RecyclerView.ViewHolder?,
                        actionState: Int
                    ) {
                        super.onSelectedChanged(viewHolder, actionState)
                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                            categoryDragSwapped = false
                            viewHolder?.itemView?.hapticTick()
                        }
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        saveCategoryOrder()
                        // Phase 8.35: a hold that never became a drag
                        // (press, no move, release) opens the category
                        // menu instead — the reorder gesture itself
                        // is exactly as before.
                        if (!categoryDragSwapped) {
                            val pos = viewHolder.bindingAdapterPosition
                            if (pos > 0) {
                                // Phase 8.73: resolve the row from the
                                // ADAPTER's current list, not the
                                // fragment-side copy — ParentItemAdapter
                                // re-sorts (empty rows last), so the two
                                // orders can differ and the menu hid the
                                // WRONG category (his "hide হয় না").
                                val item = homeMasterAdapter
                                    ?.immutableCurrentList
                                    ?.getOrNull(pos - 1)
                                    ?: homeCategoryItems.getOrNull(pos - 1)
                                item?.let { showCategoryMenu(it) }
                            }
                        }
                    }
                }).attachToRecyclerView(homeMasterRecycler)

                // Phase 8.17: while the home screen sits open, the visible
                // category rows step one poster forward every 20 seconds —
                // a viewer watching the screen sees the shelves change on
                // their own, animated by the rows' item animators.
                homeAutoRotateHandler.removeCallbacks(homeAutoRotateRunnable)
                homeAutoRotateHandler.postDelayed(homeAutoRotateRunnable, 60_000L)
            }


            homePreviewReloadProvider.setOnClickListener { view ->
                view.hapticTick()
                homeViewModel.loadAndCancel(
                    homeViewModel.apiName.value ?: noneApi.name,
                    forceReload = true,
                    fromUI = true
                )
                showToast(R.string.action_reload, Toast.LENGTH_SHORT)
            }

            // Phase 6A-2: pull-to-refresh on the home feed (phone only; the TV
            // layout mirrors the wrapper for the shared binding but is never
            // wired). A pull runs the exact same reload as the reload-provider
            // button above, and the spinner is stopped by the page observer
            // below, in the same places that hide the loading shimmer.
            if (isLayout(PHONE)) {
                homeSwipeRefresh.apply {
                    setColorSchemeColors(
                        ContextCompat.getColor(context, R.color.white),
                        ContextCompat.getColor(context, R.color.grayTextColor)
                    )
                    setProgressBackgroundColorSchemeColor(
                        ContextCompat.getColor(context, R.color.primaryGrayBackground)
                    )
                    // Soft, unhurried feel: the spinner starts slightly above
                    // the top edge, settles lower, and needs a deep pull
                    // before it lets go.
                    setProgressViewOffset(false, (-40).toPx, 72.toPx)
                    setDistanceToTriggerSync(120.toPx)
                    setOnRefreshListener {
                        homeSwipeRefresh.hapticTick()
                        // 8.71: a manual refresh also advances the row
                        // posters one step (his request) — rotation is no
                        // longer a 20 s timer shake; it runs every 60 s
                        // and whenever he pulls to refresh.
                        rotateVisibleHomeRows()
                        homeViewModel.loadAndCancel(
                            homeViewModel.apiName.value ?: noneApi.name,
                            forceReload = true,
                            fromUI = true
                        )
                    }
                }
            }

            homePreviewSearchButton.setOnClickListener { _ ->
                // Open blank screen.
                homeViewModel.queryTextSubmit("")
            }

            homePreviewSettingsButton.setOnClickListener { view ->
                val apiName = homeViewModel.apiName.value
                val plugin = APIHolder.getApiFromNameNull(apiName)
                    ?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                val openSettings = plugin?.openSettings
                if (openSettings != null) {
                    try {
                        val activityContext = view.context.getActivity() ?: view.context
                        openSettings.invoke(activityContext)
                    } catch (e: Throwable) {
                        logError(e)
                    }
                }
            }

            // Load value for toggling Tv layout real time clock. Hide by default at startup
            // set visibility first, to apply a scroll effect later
            context?.let {
                if (isLayout(TV)) {
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
                    val toggleClock =
                        settingsManager.getBoolean(
                            getString(R.string.tv_layout_clock_key),
                            false
                        )
                    binding.homeClock.isVisible = toggleClock
                } else {
                    binding.homeClock.isVisible = false
                }
            }


            homeMasterRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (isLayout(PHONE)) {
                        // Fab is only relevant to Phone. Phase 8.84:
                        // only the Random FAB remains floating (the
                        // provider switcher is the top-bar pill now);
                        // it keeps its shrink/extend on scroll.
                        if (dy > 0) { //check for scroll down
                            homeRandom.shrink()
                        } else if (dy < -5) {
                            if (isLayout(PHONE)) {
                                homeRandom.extend()
                            }
                        }
                    } else {
                        // Header scrolling is only relevant to TV/Emulator

                        val view = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
                        val scrollParent = binding.homeApiHolder

                        if (view == null) {
                            // The first view is not visible, so we can assume we have scrolled past it
                            scrollParent.isVisible = false
                        } else {
                            // A bit weird, but this is a major limitation we are working around here
                            // 1. We cant have a real parent to the recyclerview as android cant layout that without lagging
                            // 2. We cant put the view in the recyclerview, as it should always be shown
                            // 3. We cant mirror the view in the recyclerview as then it causes focus issues when swaping out the mirror view
                            //
                            // This means that if we want to have a parent view to the recyclerview we are out of luck
                            // Instead this uses getLocationInWindow to calculate how much the view should be scrolled
                            // as recyclerView has no scrollY (always 0)
                            //
                            // Then it manually "scrolls" it to the correct position
                            //
                            // Hopefully getLocationInWindow acts correctly on all devices
                            val rect = IntArray(2)
                            view.getLocationInWindow(rect)
                            scrollParent.isVisible = true
                            scrollParent.translationY = rect[1].toFloat() - 60.toPx

                            // Move the TV layout real time clock out of the way too
                            // We check if we have the correct layout and if the clock is enabled
                            if(isLayout(TV) && binding.homeClock.isVisible) {
                                val scrollParent = binding.homeClock

                                val rect = IntArray(2)
                                view.getLocationInWindow(rect)
                                scrollParent.isVisible = true
                                scrollParent.translationY = rect[1].toFloat() - 60.toPx
                            }
                        }
                    }
                    super.onScrolled(recyclerView, dx, dy)
                }
            })

        }

        //Load value for toggling Random button. Hide at startup
        context?.let {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it)
            toggleRandomButton =
                settingsManager.getBoolean(
                    getString(R.string.random_button_key),
                    false
                )
            binding.homeRandom.visibility = View.GONE
            binding.homeRandomButtonTv.visibility = View.GONE
        }

        observe(homeViewModel.apiName) { apiName ->
            currentApiName = apiName
            val displayApiName = context?.getDisplayName(apiName) ?: apiName
            binding.apply {
                homeChangeApi.text = displayApiName
                homePreviewReloadProvider.isGone = (apiName == noneApi.name)
                homePreviewSearchButton.isGone = (apiName == noneApi.name)
            }
        }

        observe(homeViewModel.page) { data ->
            binding.apply {
                if (isLayout(TV or EMULATOR)) {
                    val plugin = APIHolder.getApiFromNameNull(homeViewModel.apiName.value)
                        ?.sourcePlugin?.let { PluginManager.plugins[it] } as? Plugin
                    homePreviewSettingsButton.isGone = plugin?.openSettings == null
                }
                
                when (data) {
                    is Resource.Success -> {
                        val d = data.value
                        // Phase 8.17: re-apply the viewer's saved category
                        // order; categories the save does not know keep
                        // their load order after the known ones.
                        val copies = d.values.map {
                            it.copy(
                                list = it.list.copy(list = it.list.list.toMutableList())
                            )
                        }
                        // Phase 8.44: a custom list keeps ITS
                        // order — the saved-order sort belongs to
                        // the per-provider home.
                        val orderedCopies =
                            if (homeViewModel.isCustomActive) {
                                copies
                            } else {
                                copies.sortedBy {
                                    savedCategoryOrderIndex(it.list.name)
                                }
                            }
                        lastHomeCopies = orderedCopies
                        applyHomeCategories()

                        saveHomepageToTV(d)

                        // Phase 8.16: the skeleton crossfades into the
                        // content instead of cutting, when it was on screen.
                        if (homeLoading.isVisible) {
                            homeLoading.animate().alpha(0f).setDuration(250)
                                .withEndAction {
                                    homeLoading.isVisible = false
                                    homeLoading.alpha = 1f
                                }.start()
                            homeMasterRecycler.alpha = 0f
                            homeMasterRecycler.isVisible = true
                            homeMasterRecycler.animate().alpha(1f)
                                .setDuration(250).start()
                        } else {
                            homeLoading.isVisible = false
                            homeMasterRecycler.alpha = 1f
                            homeMasterRecycler.isVisible = true
                        }
                        homeLoadingError.isVisible = false
                        homeLoadingShimmer.stopShimmer()
                        homeSwipeRefresh.isRefreshing = false

                        // Phase 8.73: re-assert the switcher label
                        // (homeChangeApi; 8.84: the head's top-bar pill
                        // labels itself from the same apiName stream)
                        // from the actually-loaded provider on every
                        // load — after switching back to the default it
                        // could keep showing the previous provider's name.
                        val loadedApiName = homeViewModel.apiName.value
                        val loadedDisplay =
                            context?.getDisplayName(loadedApiName)
                                ?: loadedApiName
                        homeChangeApi.text = loadedDisplay

                        //home_loaded?.isVisible = true
                        if (toggleRandomButton) {
                            val distinct = d.values
                                .flatMap { it.list.list }
                                .distinctBy { it.url }
                            val hasItems = distinct.isNotEmpty()
                            val isPhone = isLayout(PHONE)
                            val randomClickListener = View.OnClickListener {
                                distinct.randomOrNull()?.let { activity.loadSearchResult(it) }
                            }

                            homeRandom.isVisible = isPhone && hasItems
                            homeRandom.setOnClickListener(randomClickListener)
                            homeRandomButtonTv.isVisible = !isPhone && hasItems
                            homeRandomButtonTv.setOnClickListener(randomClickListener)
                        } else {
                            homeRandom.isGone = true
                            homeRandomButtonTv.isGone = true
                        }
                    }
                    //Open browser directly, without a menu.
                    is Resource.Failure -> {
                        homeLoadingShimmer.stopShimmer()
                        homeReloadConnectionerror.setOnClickListener(apiChangeClickListener)
                        homeReloadConnectionOpenInBrowser.setOnClickListener {
                            val currentApi = currentApiName?.let { getApiFromNameNull(it) }
                                ?: homeViewModel.apiName.value?.let { getApiFromNameNull(it) }
                            val mainUrl = currentApi?.mainUrl
                            if (!mainUrl.isNullOrBlank()) {
                                context?.openBrowser(mainUrl)
                            }
                        }

                        homeLoading.isVisible = false
                        homeLoadingError.isVisible = true
                        homeMasterRecycler.isInvisible = true
                        homeSwipeRefresh.isRefreshing = false

                        // Based on https://github.com/recloudstream/cloudstream/pull/1438
                        val hasNoNetworkConnection = context?.isNetworkAvailable() == false
                        val isNetworkError = data.isNetworkError

                        // Show the downloads button if we have any sort of network shenanigans
                        homeReloadConnectionGoToDownloads.isVisible =
                            hasNoNetworkConnection || isNetworkError

                        // Only hide the open in browser button if we know this is not network shenanigans related to cs3
                        homeReloadConnectionOpenInBrowser.isGone = hasNoNetworkConnection

                        resultErrorText.text = if (hasNoNetworkConnection) {
                            getString(R.string.no_internet_connection)
                        } else {
                            data.errorString
                        }

                        homeReloadConnectionGoToDownloads.setOnClickListener {
                            activity.navigate(R.id.navigation_downloads)
                        }

                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                    }

                    is Resource.Loading -> {
                        homeLoadingShimmer.startShimmer()
                        homeLoading.isVisible = true
                        homeLoadingError.isVisible = false
                        homeMasterRecycler.isInvisible = true
                        (homeMasterRecycler.adapter as? ParentItemAdapter)?.apply {
                            submitList(null)
                            clearState()
                        }
                        //home_loaded?.isVisible = false
                    }
                }
            }
        }

        observeNullable(homeViewModel.popup) { item ->
            if (item == null) {
                bottomSheetDialog?.dismissSafe()
                bottomSheetDialog = null
                return@observeNullable
            }

            // don't recreate
            if (bottomSheetDialog != null) {
                return@observeNullable
            }

            val (items, delete) = item

            bottomSheetDialog = activity?.loadHomepageList(items, expandCallback = {
                homeViewModel.expandAndReturn(it)
            }, dismissCallback = {
                homeViewModel.popup(null)
                bottomSheetDialog = null
            }, deleteCallback = delete)
        }

        homeViewModel.reloadStored()
        homeViewModel.loadAndCancel(DataStoreHelper.currentHomePage, false)
        //loadHomePage(false)

        // nice profile pic on homepage
        //home_profile_picture_holder?.isVisible = false
        // just in case

        //TODO READD THIS
        /*for (syncApi in OAuth2Apis) {
            val login = SyncAPI2.loginInfo()
            val pic = login?.profilePicture
            if (home_profile_picture?.setImage(
                    pic,
                    errorImageDrawable = errorProfilePic
                ) == true
            ) {
                home_profile_picture_holder?.isVisible = true
                break
            }
        }*/
    }

    private fun handleTvBackPress(helper: BackPressedCallbackHelper.CallbackHelper) {
        // Only apply custom behavior on TV interface
        if (!isLayout(TV)) {
            helper.runDefault()
            return
        }
        val currentFocus = activity?.currentFocus ?: run {
            helper.runDefault()
            return
        }
        // isInsideRecycle is true when focus is inside home_master_recycler
        var parent = currentFocus.parent
        var isInsideRecycler = false
        while (parent != null) {
            if (parent is View && parent.id == R.id.home_master_recycler) {
                isInsideRecycler = true
                break
            }
            parent = parent.parent
        }
        when {
            // Case 1: Focus is within plugin content -> Move to plugin selector
            isInsideRecycler -> {
                binding?.homeMasterRecycler?.scrollToPosition(0)
                // Defer focus request until after scroll ends
                binding?.homeChangeApi?.post {
                    binding?.homeChangeApi?.requestFocus()
                }
            }
            // Case 2: Focus is on plugin selector or nearby buttons -> Move to home navigation
            currentFocus.id == R.id.home_change_api ||
            currentFocus.id == R.id.home_preview_reload_provider ||
            currentFocus.id == R.id.home_preview_search_button -> {
                activity?.findViewById<View>(R.id.navigation_home)?.requestFocus()
            }
            // Case 3: Any other location -> Use default back behavior
            else -> helper.runDefault()
        }
    }
}
