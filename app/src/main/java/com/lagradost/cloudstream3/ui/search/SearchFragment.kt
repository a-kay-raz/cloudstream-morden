package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.graphics.Color
import android.content.Intent
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentSearchBinding
import com.lagradost.cloudstream3.databinding.HomeSelectMainpageBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.SectionEntranceAnimator
import com.lagradost.cloudstream3.ui.feed.FeedPool
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.bindChips
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.currentSpan
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.loadHomepageList
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.home.ParentItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.ownHide
import com.lagradost.cloudstream3.utils.AppContextUtils.ownShow
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.applyFloatingNavBottomMargin
import com.lagradost.cloudstream3.utils.UIHelper.applyFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomMargin
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BaseFragment.BindingCreator.Bind(FragmentSearchBinding::bind)
) {
    companion object {
        fun List<SearchResponse>.filterSearchResponse(): List<SearchResponse> {
            return this.filter { response ->
                if (response is AnimeSearchResponse) {
                    val status = response.dubStatus
                    (status.isNullOrEmpty()) || (status.any {
                        APIRepository.dubStatusActive.contains(it)
                    })
                } else {
                    true
                }
            }
        }

        const val SEARCH_QUERY = "search_query"

        fun newInstance(query: String): Bundle {
            return Bundle().apply {
                if (query.isNotBlank()) putString(SEARCH_QUERY, query)
            }
        }
    }

    private val searchViewModel: SearchViewModel by activityViewModels()
    private var bottomSheetDialog: BottomSheetDialog? = null

    /** Phase 8.8: whether the suggestions panel was shown at the last
     * suggestions update, so its entrance layout animation is scheduled
     * only on the hidden -> shown flip, never per keystroke. */
    private var suggestionsPanelWasShown = false

    // ---------- Phase 8.87: content-type tabs + smart landing ----------

    /**
     * The content-type tabs over the results (All | Movies | Series |
     * Anime). TvType sets follow the app's own chip convention
     * (HomeFragment.getPairList: Anime = Anime + OVA + AnimeMovie);
     * AsianDrama folds into Series here — the reference's 4-tab bar
     * has no separate Asian tab.
     */
    private enum class SearchTypeTab(val types: Set<TvType>?) {
        ALL(null),
        MOVIES(setOf(TvType.Movie)),
        SERIES(setOf(TvType.TvSeries, TvType.AsianDrama)),
        ANIME(setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA))
    }

    private var selectedTypeTab = SearchTypeTab.ALL

    /** Last raw payloads from the view model. A tab switch
     *  re-filters and re-renders from these copies — no provider is
     *  re-queried. */
    private var lastSearchMap: Map<String, ExpandableSearchList> = emptyMap()
    private var lastGridList: List<SearchResponse> = emptyList()
    private var lastHistory: List<SearchHistoryItem> = emptyList()

    /** Programmatic phone-only views (the TV layout shares this
     *  fragment + binding and keeps its existing landing, so the
     *  new views are built in code, not XML). */
    private var typeTabViews = mutableListOf<TextView>()
    private var landingView: View? = null
    private var trendingChips: ChipGroup? = null
    private var clearHistoryView: TextView? = null
    private var landingHasContent = false

    private fun SearchResponse.matchesTypeTab(): Boolean {
        val types = selectedTypeTab.types ?: return true
        val itemType = type ?: return false
        return itemType in types
    }

    private val speechRecognizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    binding?.mainSearch?.setQuery(recognizedText, true)
                }
            }
        }

    override fun pickLayout(): Int? =
        if (isLayout(TV or EMULATOR)) R.layout.fragment_search_tv else R.layout.fragment_search

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        bottomSheetDialog?.ownShow()
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onDestroyView() {
        hideKeyboard()
        bottomSheetDialog?.ownHide()
        activity?.detachBackPressedCallback("SearchFragment")
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += ::reloadRepos
        // Phase 8.87: the feed pool may have been built since this
        // page was last shown — refresh the trending chips' titles.
        refreshTrendingChips()
    }

    override fun onStop() {
        super.onStop()
        afterPluginsLoadedEvent -= ::reloadRepos
    }

    var selectedSearchTypes = mutableListOf<TvType>()
    var selectedApis = mutableSetOf<String>()

    /**
     * Will filter all providers by preferred media and selectedSearchTypes.
     * If that results in no available providers then only filter
     * providers by preferred media
     **/
    fun search(query: String?) {
        if (query == null) return
        // don't resume state from prev search
        (binding?.searchMasterRecycler?.adapter as? BaseAdapter<*, *>)?.clearState()
        context?.let { ctx ->
            val default = enumValues<TvType>().sorted().filter { it != TvType.NSFW }
                .map { it.ordinal.toString() }.toSet()
            val preferredTypes = (PreferenceManager.getDefaultSharedPreferences(ctx)
                .getStringSet(this.getString(R.string.prefer_media_type_key), default)
                ?.ifEmpty { default } ?: default)
                .mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }

            val settings = ctx.getApiSettings()

            val notFilteredBySelectedTypes = selectedApis.filter { name ->
                settings.contains(name)
            }.map { name ->
                name to getApiFromNameNull(name)?.supportedTypes
            }.filter { (_, types) ->
                types?.any { preferredTypes.contains(it.ordinal) } == true
            }

            searchViewModel.searchAndCancel(
                query = query,
                providersActive = notFilteredBySelectedTypes.filter { (_, types) ->
                    types?.any { selectedSearchTypes.contains(it) } == true
                }.ifEmpty { notFilteredBySelectedTypes }.map { it.first }.toSet()
            )
        }
    }

    // Null if defined as a variable
    // This needs to be run after view created

    private fun reloadRepos(success: Boolean = false) = main {
        searchViewModel.reloadRepos()
        // Phase 8.9: the tag chips no longer live on the search page —
        // the provider/tag selection is edited only in the filter sheet
        // and stored in DataStoreHelper with exclusion semantics
        // (everything is included unless the user unchecks it there).
        // Refresh the cached sets so providers installed since this
        // fragment was created are searched too, without the user
        // having to reopen the sheet.
        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()
        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()
    }

    // ---------- Phase 8.87: tabs + landing helpers ----------

    private fun selectTypeTab(tab: SearchTypeTab) {
        if (selectedTypeTab == tab) return
        selectedTypeTab = tab
        styleTypeTabs()
        // Re-render the results already on screen with the new
        // filter — the search itself is NOT re-run.
        submitMasterResults()
        submitGridResults()
    }

    private fun styleTypeTabs() {
        typeTabViews.forEachIndexed { index, view ->
            val selected = SearchTypeTab.entries[index] == selectedTypeTab
            if (selected) {
                view.setBackgroundResource(R.drawable.feed_chip_selected)
                view.setTextColor(
                    ContextCompat.getColor(view.context, android.R.color.black)
                )
            } else {
                view.setBackgroundResource(R.drawable.feed_search_bg)
                view.setTextColor(
                    ContextCompat.getColor(view.context, android.R.color.white)
                )
            }
        }
    }

    /**
     * Builds the provider sections for the master results list from
     * the last raw currentSearch payload. This was the
     * currentSearch observer's body; extracted so a content-type
     * tab switch re-renders the same data with the tab filter
     * applied instead of re-running the search.
     */
    private fun submitMasterResults() {
        val binding = binding ?: return
        val pinnedOrder = DataStoreHelper.pinnedProviders.reversedArray()

        val sortedList = lastSearchMap.toList().sortedWith(compareBy { (providerName, _) ->
            val index = pinnedOrder.indexOf(providerName)
            if (index == -1) Int.MAX_VALUE else index
        })

        (binding.searchMasterRecycler.adapter as? ParentItemAdapter)?.apply {
            val newItems = sortedList.mapNotNull { (providerName, providerData) ->
                val dataList = providerData.list
                val dataListFiltered =
                    context?.filterSearchResultByFilmQuality(dataList) ?: dataList

                // Phase 8.9: providers finish one by one, and a
                // provider whose search came back with zero
                // (visible) results used to stay behind as an
                // empty grey section bar. Only build a section
                // once it has results; when a later update
                // empties it, the submitted list drops it again.
                // Search only — the home screen builds its own
                // items in HomeFragment and is untouched.
                // Phase 8.87: the content-type tab filters items
                // inside the section first — a section the tab
                // empties drops out by the same rule.
                val tabFiltered = dataListFiltered.filter { it.matchesTypeTab() }
                if (tabFiltered.isEmpty()) return@mapNotNull null

                val homePageList = HomePageList(
                    providerName,
                    tabFiltered
                )

                HomeViewModel.ExpandableHomepageList(
                    homePageList,
                    providerData.currentPage,
                    providerData.hasNext
                )
            }

            submitList(newItems)
        }
    }

    /**
     * Renders the flat (non-advanced) results grid from the last
     * raw searchResponse payload with the content-type tab applied.
     * Unlike the pre-8.87 observer this also submits an empty list —
     * a tab with no matches must clear the grid, not leave the
     * previous tab's results standing.
     */
    private fun submitGridResults() {
        val filtered = lastGridList.filter { it.matchesTypeTab() }
        (binding?.searchAutofitResults?.adapter as? SearchAdapter)?.submitList(filtered)
    }

    /**
     * Phase 8.87 (phone only): builds the content-type tabs row and
     * the smart landing (trending chip cloud) and inserts them into
     * the page's vertical stack under the search bar. Built in code
     * because the TV layout shares this fragment and its binding —
     * TV keeps its existing history-list landing untouched.
     */
    private fun setupSmartLanding(binding: FragmentSearchBinding) {
        val ctx = binding.root.context
        val parent = binding.searchTopBar.parent as? ViewGroup ?: return
        val insertAt = parent.indexOfChild(binding.searchTopBar) + 1

        // Content-type tabs: All | Movies | Series | Anime, visible
        // on the landing and over results alike.
        val tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 12.toPx
                rightMargin = 12.toPx
            }
        }
        SearchTypeTab.entries.forEachIndexed { index, tab ->
            val chip = TextView(ctx).apply {
                text = getString(
                    when (tab) {
                        SearchTypeTab.ALL -> R.string.all
                        SearchTypeTab.MOVIES -> R.string.movies
                        SearchTypeTab.SERIES -> R.string.tv_series
                        SearchTypeTab.ANIME -> R.string.anime
                    }
                )
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(10.toPx, 9.toPx, 10.toPx, 9.toPx)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (index > 0) leftMargin = 8.toPx
                }
                setOnClickListener { selectTypeTab(tab) }
            }
            typeTabViews += chip
            tabsRow.addView(chip)
        }
        parent.addView(tabsRow, insertAt)
        styleTypeTabs()

        // The landing: flame + "Trending searches" heading over a
        // wrapping chip cloud (chips are (re)built by
        // refreshTrendingChips).
        val landing = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.toPx, 14.toPx, 16.toPx, 24.toPx)
        }
        val heading = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_whatshot_24)
            imageTintList =
                ContextCompat.getColorStateList(ctx, R.color.colorPrimaryRed)
            layoutParams = LinearLayout.LayoutParams(20.toPx, 20.toPx)
        })
        heading.addView(TextView(ctx).apply {
            text = getString(R.string.search_trending)
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.BOLD
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 8.toPx }
        })
        landing.addView(heading)
        val chips = ChipGroup(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.toPx }
            chipSpacingHorizontal = 8.toPx
            chipSpacingVertical = 8.toPx
        }
        trendingChips = chips
        landing.addView(chips)
        // Clear-all history survives the landing swap (it was the
        // old history footer's button): a quiet text row under the
        // chips, shown only while recents exist, running the same
        // confirmation flow.
        val clearHistory = TextView(ctx).apply {
            text = getString(R.string.clear_history)
            textSize = 13f
            setTextColor(Color.rgb(158, 158, 158))
            setPadding(0, 16.toPx, 0, 0)
            isVisible = false
            setOnClickListener { showClearHistoryConfirm() }
        }
        clearHistoryView = clearHistory
        landing.addView(clearHistory)
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(landing)
            isVisible = false
        }
        landingView = scroll
        parent.addView(scroll, insertAt + 1)

        // The phone landing replaces the old full-screen history
        // list as the pre-query presentation. The list view itself
        // stays (it is the TV landing, and the history storage +
        // updateHistory flow are untouched — they feed the chips).
        binding.searchHistoryRecycler.isVisible = false
        refreshTrendingChips()
    }

    /**
     * Phase 8.87: rebuilds the landing's trending chips — the
     * user's own recent searches first (from the untouched history
     * store, newest first), then deduplicated titles from the feed
     * pool's top entries (pool order is the popularity proxy: the
     * feed build interleaves providers evenly and weaves liked
     * types/providers forward). Capped at 12 chips total. Recent
     * chips keep their remove affordance via the chip close icon.
     * When both sources are empty the landing hides entirely.
     */
    private fun refreshTrendingChips() {
        val group = trendingChips ?: return
        val ctx = context ?: return
        group.removeAllViews()
        val seen = mutableSetOf<String>()
        val items = mutableListOf<Pair<String, String?>>()
        for (item in lastHistory) {
            val text = item.searchText.trim()
            if (text.isEmpty() || !seen.add(text.lowercase())) continue
            items += text to item.key
            if (items.size >= 12) break
        }
        if (items.size < 12) {
            for (entry in FeedPool.entries) {
                val title = SearchResultBuilder.cleanPosterTitle(entry.response.name)
                    .trim()
                if (title.isEmpty() || TitleResolver.isIdLike(title)) continue
                if (!seen.add(title.lowercase())) continue
                items += title to null
                if (items.size >= 12) break
            }
        }
        for ((chipText, historyKey) in items) {
            group.addView(Chip(ctx).apply {
                text = chipText
                isCheckable = false
                isClickable = true
                chipBackgroundColor = ContextCompat.getColorStateList(
                    ctx, R.color.extButtonBackground
                )
                setTextColor(Color.WHITE)
                chipStrokeWidth = 0f
                if (historyKey != null) {
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        removeKey("$currentAccount/$SEARCH_HISTORY_KEY", historyKey)
                        searchViewModel.updateHistory()
                    }
                }
                setOnClickListener {
                    binding?.mainSearch?.setQuery(chipText, true)
                }
            })
        }
        landingHasContent = items.isNotEmpty()
        clearHistoryView?.isVisible = lastHistory.isNotEmpty()
        updateLandingVisibility()
    }

    /** The landing shows exactly when the old history list did —
     *  an empty query — and only when it has chips to show. */
    private fun updateLandingVisibility() {
        val showHistory = binding?.mainSearch?.query?.toString().isNullOrBlank()
        landingView?.isVisible = showHistory && landingHasContent
    }

    /**
     * The clear-all-history confirmation. Pre-8.87 this lived in
     * the history footer's click callback; extracted so the phone
     * landing's "Clear history" row runs the exact same flow.
     */
    private fun showClearHistoryConfirm() {
        activity?.let { ctx ->
            val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
            val dialogClickListener =
                DialogInterface.OnClickListener { _, which ->
                    when (which) {
                        DialogInterface.BUTTON_POSITIVE -> {
                            removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
                            searchViewModel.updateHistory()
                        }

                        DialogInterface.BUTTON_NEGATIVE -> {
                        }
                    }
                }

            try {
                builder.setTitle(R.string.clear_history).setMessage(
                    ctx.getString(R.string.delete_message).format(
                        ctx.getString(R.string.history)
                    )
                )
                    .setPositiveButton(R.string.sort_clear, dialogClickListener)
                    .setNegativeButton(R.string.cancel, dialogClickListener)
                    .show().setDefaultFocus()
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
        // Re-audit: re-evaluate floating clearance on rotation.
        // Suggestions use a margin, not padding: it is a
        // wrap_content overlay whose background would otherwise
        // paint a blank 88dp+ band below the last suggestion.
        view.findViewById<View>(R.id.search_autofit_results)
            ?.refreshFloatingNavBottomPadding()
        view.findViewById<View>(R.id.search_master_recycler)
            ?.refreshFloatingNavBottomPadding()
        view.findViewById<View>(R.id.search_history_recycler)
            ?.refreshFloatingNavBottomPadding()
        view.findViewById<View>(R.id.search_suggestions_recycler)
            ?.refreshFloatingNavBottomMargin()

        // Fix grid
        currentSpan = view.context.getSpanCount()
        binding?.searchAutofitResults?.spanCount = currentSpan
        HomeFragment.configEvent.invoke()
    }

    override fun onBindingCreated(
        binding: FragmentSearchBinding,
        savedInstanceState: Bundle?
    ) {
        // Phase 8.8: the search top bar (search field + filter chips)
        // fades and drops in softly when the screen opens. Phone layout
        // only — the TV search screen keeps its own focus-driven flow.
        // Presentation only; runs once per view creation.
        if (isLayout(PHONE)) {
            binding.searchTopBar.apply {
                alpha = 0f
                translationY = (-12).toPx.toFloat()
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        reloadRepos()
        binding.apply {
            val adapter =
                SearchAdapter(
                    searchAutofitResults,
                ) { callback ->
                    SearchHelper.handleSearchClickCallback(callback)
                }

            searchRoot.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.tag =
                "tv_no_focus_tag"
            searchAutofitResults.setRecycledViewPool(SearchAdapter.sharedPool)
            searchAutofitResults.adapter = adapter
            // Batch 4: runtime floating-pill clearance (88dp +
            // systemBars bottom) on all three lists plus the
            // suggestions overlay, kept in sync — replacing the
            // hardcoded 92dp values, which were short by the
            // system inset; the overlay previously had no
            // bottom padding at all and grew under the pill.
            searchAutofitResults.applyFloatingNavBottomPadding()
            searchMasterRecycler.applyFloatingNavBottomPadding()
            searchHistoryRecycler.applyFloatingNavBottomPadding()
            // Re-audit: suggestions are a wrap_content overlay, so
            // a bottom margin caps its height instead of painting
            // a blank padded band below the last suggestion.
            searchSuggestionsRecycler.applyFloatingNavBottomMargin()
            searchLoadingBar.alpha = 0f
        }

        binding.voiceSearch.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                try {
                    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                        showToast(R.string.speech_recognition_unavailable)
                    } else {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                            putExtra(
                                RecognizerIntent.EXTRA_PROMPT,
                                ctx.getString(R.string.begin_speaking)
                            )
                        }
                        speechRecognizerLauncher.launch(intent)
                    }
                } catch (_: Throwable) {
                    // launch may throw
                    showToast(R.string.speech_recognition_unavailable)
                }
            }
        }

        val searchExitIcon =
            binding.mainSearch.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)

        selectedApis = DataStoreHelper.searchPreferenceProviders.toMutableSet()

        binding.searchFilter.setOnClickListener { searchView ->
            searchView?.context?.let { ctx ->
                val validAPIs = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
                var currentValidApis = listOf<MainAPI>()
                val currentSelectedApis = if (selectedApis.isEmpty()) validAPIs.map { it.name }
                    .toMutableSet() else selectedApis

                val builder =
                    BottomSheetDialog(ctx)

                builder.behavior.state = BottomSheetBehavior.STATE_EXPANDED

                val selectMainpageBinding: HomeSelectMainpageBinding =
                    HomeSelectMainpageBinding.inflate(
                        builder.layoutInflater,
                        null,
                        false
                    )
                builder.setContentView(selectMainpageBinding.root)
                builder.show()
                builder.let { dialog ->
                    val previousSelectedApis = selectedApis.toSet()
                    val previousSelectedSearchTypes = selectedSearchTypes.toSet()

                    val isMultiLang = ctx.getApiProviderLangSettings().let { set ->
                        set.size > 1 || set.contains(AllLanguagesName)
                    }

                    val cancelBtt = dialog.findViewById<MaterialButton>(R.id.cancel_btt)
                    val applyBtt = dialog.findViewById<MaterialButton>(R.id.apply_btt)

                    val listView = dialog.findViewById<ListView>(R.id.listview1)
                    val arrayAdapter = ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)
                    listView?.adapter = arrayAdapter
                    listView?.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE

                    listView?.setOnItemClickListener { _, _, i, _ ->
                        if (currentValidApis.isNotEmpty()) {
                            val api = currentValidApis[i].name
                            if (currentSelectedApis.contains(api)) {
                                listView.setItemChecked(i, false)
                                currentSelectedApis -= api
                            } else {
                                listView.setItemChecked(i, true)
                                currentSelectedApis += api
                            }
                        }
                    }

                    fun updateList(types: List<TvType>) {
                        DataStoreHelper.searchPreferenceTags = types

                        arrayAdapter.clear()
                        currentValidApis = validAPIs.filter { api ->
                            api.supportedTypes.any {
                                types.contains(it)
                            }
                        }.sortedBy { it.name.lowercase() }

                        val names = currentValidApis.map {
                            if (isMultiLang) "${
                                SubtitleHelper.getFlagFromIso(
                                    it.lang
                                )?.plus(" ") ?: ""
                            }${it.name}" else it.name
                        }
                        for ((index, api) in currentValidApis.map { it.name }.withIndex()) {
                            listView?.setItemChecked(index, currentSelectedApis.contains(api))
                        }

                        //arrayAdapter.notifyDataSetChanged()
                        arrayAdapter.addAll(names)
                        arrayAdapter.notifyDataSetChanged()
                    }

                    bindChips(
                        selectMainpageBinding.tvtypesChipsScroll.tvtypesChips,
                        selectedSearchTypes,
                        validAPIs.flatMap { api -> api.supportedTypes }.distinct()
                    ) { list ->
                        updateList(list)

                        // Phase 8.9: no page chips to mirror into
                        // anymore — the field (persisted by updateList)
                        // is the selection now.
                        if (selectedSearchTypes.toSet() != list.toSet()) {
                            selectedSearchTypes.clear()
                            selectedSearchTypes.addAll(list)
                        }
                    }


                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    cancelBtt?.setOnClickListener {
                        dialog.dismissSafe()
                    }

                    applyBtt?.setOnClickListener {
                        //if (currentApiName != selectedApiName) {
                        //    currentApiName?.let(callback)
                        //}
                        dialog.dismissSafe()
                    }

                    dialog.setOnDismissListener {
                        DataStoreHelper.searchPreferenceProviders = currentSelectedApis.toList()
                        selectedApis = currentSelectedApis

                        // run search when dialog is close
                        if (previousSelectedApis != selectedApis.toSet() || previousSelectedSearchTypes != selectedSearchTypes.toSet()) {
                            search(binding.mainSearch.query.toString())
                        }
                    }
                    updateList(selectedSearchTypes.toList())
                }
            }
        }

        val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        val isAdvancedSearch = settingsManager?.getBoolean("advanced_search", true) ?: true
        val isSearchSuggestionsEnabled = settingsManager?.getBoolean("search_suggestions_enabled", true) ?: true

        selectedSearchTypes = DataStoreHelper.searchPreferenceTags.toMutableList()

        if (!isLayout(PHONE)) {
            binding.searchFilter.isFocusable = true
            binding.searchFilter.isFocusableInTouchMode = true
        }

        // Hide suggestions when search view loses focus (phone only)
        if (isLayout(PHONE)) {
            binding.mainSearch.setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchViewModel.clearSuggestions()
                }
            }
        }


        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                search(query)
                searchViewModel.clearSuggestions()

                binding.mainSearch.let {
                    hideKeyboard(it)
                }

                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                //searchViewModel.quickSearch(newText)
                val showHistory = newText.isBlank()
                if (showHistory) {
                    searchViewModel.clearSearch()
                    searchViewModel.updateHistory()
                    searchViewModel.clearSuggestions()
                } else {
                    // Fetch suggestions when user is typing (if enabled)
                    if (isSearchSuggestionsEnabled) {
                        searchViewModel.fetchSuggestions(newText)
                    }
                }
                binding.apply {
                    // Phase 8.87: on the phone the pre-query landing
                    // is the trending chip cloud now; the history
                    // list remains the landing only on TV.
                    searchHistoryRecycler.isVisible = showHistory && !isLayout(PHONE)
                    searchMasterRecycler.isVisible = !showHistory && isAdvancedSearch
                    searchAutofitResults.isVisible = !showHistory && !isAdvancedSearch
                    // Hide suggestions when showing history or showing search results
                    searchSuggestionsRecycler.isVisible = !showHistory && isSearchSuggestionsEnabled
                }
                if (isLayout(PHONE)) {
                    if (showHistory) refreshTrendingChips()
                    updateLandingVisibility()
                }

                return true
            }
        })

        observe(searchViewModel.searchResponse) {
            when (it) {
                is Resource.Success -> {
                    // Phase 8.87: keep the raw list — the grid render
                    // applies the content-type tab, and a tab switch
                    // re-renders from this copy without a new search.
                    lastGridList = it.value.list
                    submitGridResults()
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon?.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon?.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        val listLock = ReentrantLock()
        observe(searchViewModel.currentSearch) { list ->
            try {
                // https://stackoverflow.com/questions/6866238/concurrent-modification-exception-adding-to-an-arraylist
                listLock.lock()

                // Phase 8.87: keep the raw per-provider map and let
                // submitMasterResults build the sections (it applies
                // the film-quality filter and the content-type tab).
                lastSearchMap = list
                submitMasterResults()
            } catch (e: Exception) {
                logError(e)
            } finally {
                listLock.unlock()
            }
        }


        /*main_search.setOnQueryTextFocusChangeListener { _, b ->
            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                showInputMethod(view.findFocus())
            }
        }*/
        //main_search.onActionViewExpanded()*/

        val masterAdapter =
            ParentItemAdapter(id = "masterAdapter".hashCode(), { callback ->
                SearchHelper.handleSearchClickCallback(callback)
            }, { item ->
                bottomSheetDialog = activity?.loadHomepageList(item, dismissCallback = {
                    bottomSheetDialog = null
                }, expandCallback = { name -> searchViewModel.expandAndReturn(name) })
            }, expandCallback = { name ->
                ioSafe {
                    searchViewModel.expandAndReturn(name)
                }
            })

        val historyAdapter = SearchHistoryAdaptor { click ->
            val searchItem = click.item
            when (click.clickAction) {
                SEARCH_HISTORY_OPEN -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    searchViewModel.clearSearch()
                    if (searchItem.type.isNotEmpty()) {
                        // Phase 8.9: apply the entry's types directly —
                        // the page chips, whose checked-change listener
                        // used to forward them, are gone.
                        selectedSearchTypes.clear()
                        selectedSearchTypes.addAll(searchItem.type)
                        DataStoreHelper.searchPreferenceTags = searchItem.type
                    }
                    binding.mainSearch.setQuery(searchItem.searchText, true)
                }

                SEARCH_HISTORY_REMOVE -> {
                    if (searchItem == null) return@SearchHistoryAdaptor
                    removeKey("$currentAccount/$SEARCH_HISTORY_KEY", searchItem.key)
                    searchViewModel.updateHistory()
                }

                SEARCH_HISTORY_CLEAR -> {
                    // Show confirmation dialog (from footer button)
                    showClearHistoryConfirm()
                }

                else -> {
                    // wth are you doing???
                }
            }
        }

        val suggestionAdapter = SearchSuggestionAdapter { callback ->
            when (callback.clickAction) {
                SEARCH_SUGGESTION_CLICK -> {
                    // Search directly
                    binding.mainSearch.setQuery(callback.suggestion, true)
                    searchViewModel.clearSuggestions()
                }
                SEARCH_SUGGESTION_FILL -> {
                    // Fill the search box without searching
                    binding.mainSearch.setQuery(callback.suggestion, false)
                }
                SEARCH_SUGGESTION_CLEAR -> {
                    // Clear suggestions (from footer button)
                    searchViewModel.clearSuggestions()
                }
            }
        }

        binding.apply {
            searchHistoryRecycler.adapter = historyAdapter
            searchHistoryRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)
            //searchHistoryRecycler.layoutManager = GridLayoutManager(context, 1)

            // Setup suggestions RecyclerView
            searchSuggestionsRecycler.adapter = suggestionAdapter
            searchSuggestionsRecycler.layoutManager = LinearLayoutManager(context)

            searchMasterRecycler.setRecycledViewPool(ParentItemAdapter.sharedPool)
            searchMasterRecycler.adapter = masterAdapter

            // Phase 8.10 (phone only): provider sections glide in as they
            // scroll into view instead of popping, matching Home. The
            // animator resets itself when a fresh result list starts.
            if (isLayout(PHONE)) {
                SectionEntranceAnimator().attachTo(searchMasterRecycler)
            }
            //searchMasterRecycler.setLinearListLayout(isHorizontal = false, nextRight = FOCUS_SELF)

            searchMasterRecycler.layoutManager = GridLayoutManager(context, 1)

            // Automatically search the specified query, this allows the app search to launch from intent
            var sq =
                arguments?.getString(SEARCH_QUERY) ?: savedInstanceState?.getString(SEARCH_QUERY)
            if (sq.isNullOrBlank()) {
                sq = MainActivity.nextSearchQuery
            }

            sq?.let { query ->
                if (query.isBlank()) return@let

                // Queries are dropped if you are submitted before layout finishes
                mainSearch.doOnLayout {
                    mainSearch.setQuery(query, true)
                }
                // Clear the query as to not make it request the same query every time the page is opened
                arguments?.remove(SEARCH_QUERY)
                savedInstanceState?.remove(SEARCH_QUERY)
                MainActivity.nextSearchQuery = null
            }
        }

        observe(searchViewModel.currentHistory) { list ->
            // Phase 8.87: the same history data also feeds the
            // phone landing's trending chips (recents first).
            lastHistory = list
            (binding.searchHistoryRecycler.adapter as? SearchHistoryAdaptor?)?.submitList(list)
             // Scroll to top to show newest items (list is sorted by newest first)
            if (list.isNotEmpty()) {
                binding.searchHistoryRecycler.scrollToPosition(0)
            }
            if (isLayout(PHONE)) refreshTrendingChips()
        }

        // Observe search suggestions
        observe(searchViewModel.searchSuggestions) { suggestions ->
            val hasSuggestions = suggestions.isNotEmpty()
            binding.searchSuggestionsRecycler.isVisible = hasSuggestions
            (binding.searchSuggestionsRecycler.adapter as? SearchSuggestionAdapter?)?.submitList(suggestions)

            // Phase 8.8: when the suggestions panel appears, play its
            // staggered fade+rise layout animation (the controller the
            // results lists already use) exactly once for that
            // appearance. It is scheduled only on the hidden -> shown
            // flip, so the submitList updates that arrive per keystroke
            // never replay it and the panel cannot strobe while typing.
            if (isLayout(PHONE)) {
                if (hasSuggestions && !suggestionsPanelWasShown) {
                    binding.searchSuggestionsRecycler.scheduleLayoutAnimation()
                }
                suggestionsPanelWasShown = hasSuggestions
            }

            // On non-phone layouts, redirect focus and handle back button
            if (!isLayout(PHONE)) {
                if (hasSuggestions) {
                    // Phase 8.9: the chips row this used to hang off is
                    // gone; the filter icon is the focus anchor above
                    // the lists now.
                    binding.searchFilter.nextFocusDownId = R.id.search_suggestions_recycler
                    // Attach back button callback to clear suggestions
                    activity?.attachBackPressedCallback("SearchFragment") {
                        searchViewModel.clearSuggestions()
                    }
                } else {
                    // Reset to default focus target (history)
                    binding.searchFilter.nextFocusDownId = R.id.search_history_recycler
                    // Detach back button callback when no suggestions
                    activity?.detachBackPressedCallback("SearchFragment")
                }
            }
        }

        // Phase 8.87: the phone gets the content-type tabs + the
        // smart landing (trending chips); TV keeps its landing.
        if (isLayout(PHONE)) {
            setupSmartLanding(binding)
        }

        searchViewModel.updateHistory()
    }
}
