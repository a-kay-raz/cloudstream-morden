package com.lagradost.cloudstream3.ui.feed

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FragmentFeedBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.applyFloatingNavBottomMargin
import com.lagradost.cloudstream3.utils.UIHelper.applyFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getFeedSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Implemented by the Feed tab so MainActivity can ask it to scroll
 * back to the top without referencing the fragment class itself
 * (the class is @UnstableApi-marked because of the player engine).
 */
interface FeedScrollTop {
    fun scrollToTop()
}

/**
 * Phase 8.18–8.20 — the Feed tab: every provider's content in one
 * category-free 2-column grid under a collapsible header (search +
 * genre chips that hide on scroll). Cards play directly in the shared
 * [FeedPlayer]; when a video is minimised it keeps playing in the mini
 * player floating over this grid.
 */
@UnstableApi
class FeedFragment : BaseFragment<FragmentFeedBinding>(
    BindingCreator.Inflate(FragmentFeedBinding::inflate)
), FeedScrollTop {
    private val viewModel: FeedViewModel by viewModels()
    private val adapter = FeedAdapter(
        onPlay = { entry -> openWatch(entry) },
        onHide = { entry -> confirmHide(entry) },
        onTitleNeeded = { entry ->
            context?.let { ctx ->
                TitleResolver.resolve(ctx, entry.apiName, entry.response.url, entry.response.name)
            }
        }
    )

    /** A resolved real title lands here and swaps into its card. */
    private val titleListener: (String, String) -> Unit = { url, title ->
        // Phase 8.56: titles resolve in bursts while scrolling and
        // a rebind per title shakes the grid — queue them and
        // apply once the list comes to rest (same cure as Home).
        pendingTitleOverrides[url] = title
        searchHandler.removeCallbacks(titleApplyRunnable)
        searchHandler.postDelayed(titleApplyRunnable, 900L)
    }

    private val pendingTitleOverrides = mutableMapOf<String, String>()

    private val titleApplyRunnable = object : Runnable {
        override fun run() {
            val rv = binding?.feedRecycler
            if (rv != null &&
                rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
            ) {
                searchHandler.postDelayed(this, 600L)
                return
            }
            val pending = pendingTitleOverrides.toMap()
            pendingTitleOverrides.clear()
            pending.forEach { (url, title) ->
                adapter.setTitleOverride(url, title)
            }
        }
    }

    /** Phase 8.56: universal ratings land here as they resolve. */
    private val ratingListener: (String, String) -> Unit = { key, rating ->
        // Phase 8.85: the fragment keeps its own copy — the Filters
        // sheet's Rating tab reads resolved scores from here.
        fragmentRatings[key] = rating
        adapter.setRatingOverride(key, rating)
        // Phase 8.92: a fresh rating can change the Top /
        // must-watch curations, but the memoised tag items are
        // NOT dropped here anymore — ratings land in bursts
        // while scrolling, and clearing per rating forced a
        // full cold recompute of every tag on the MAIN thread
        // at the next chrome pass (a verified hang source). The
        // generation bump invalidates lazily, once, on next use;
        // the rows refresh via the same debounced pass as meta.
        tagGeneration++
        searchHandler.removeCallbacks(metaApplyRunnable)
        searchHandler.postDelayed(metaApplyRunnable, 900L)
    }

    private val fragmentRatings = mutableMapOf<String, String>()

    // ---------- Phase 8.85: advanced filters state ----------
    // Phase 8.92: the Feed hero is GONE (his verdict — it looked
    // unstable and carried an odd shadow). The ConcatAdapter is
    // now category rows + the For You grid, nothing else. The
    // Home hero is a separate surface and is untouched.

    /** Phase 8.92: the background pass that computes the
     *  category rows + chip names off the main thread (see
     *  scheduleChromeUpdate). */
    private var chromeJob: Job? = null

    /** Phase 8.94: chrome scheduler state. A pass in flight is
     *  NEVER cancelled by a later trigger — cancelling was the
     *  8.92 missing-rows/chips regression (see
     *  scheduleChromeUpdate). [chromeDirty] records that the
     *  inputs moved on while a pass ran, so exactly one
     *  follow-up pass runs when it finishes. */
    private var chromeRunning = false
    private var chromeDirty = false

    /** Phase 8.96: the pool instance the PHASE-1 chrome pass
     *  has already posted for (identity-compared). A pass over
     *  any other pool instance runs phase 1 — the fast,
     *  pool-internal pass (sections + provider scores, NO
     *  resolver calls) that ends the skeleton as soon as page 1
     *  lands; a pass over this same instance runs phase 2, the
     *  full metadata pass, whose rows merge in behind it. */
    private var chromePhase1Pool: List<FeedEntry>? = null

    /** Phase 8.96: true while the visible chrome (rows +
     *  chips) has not been posted for the current browsing
     *  state — drives the mirror skeleton (in-list skeleton
     *  rows + the header's chips skeleton). Set at view
     *  creation and whenever the rows are torn down
     *  (hideCategoryRows); cleared when a pass posts. */
    private var chromeAwaitingPost = true

    /** Phase 8.96: the pool head whose metadata pre-kick is
     *  deferred until the phase-1 chrome pass has posted (see
     *  the feed observer) — the resolver burst no longer
     *  competes with the first chrome computation. */
    private var pendingPreKick: List<FeedEntry> = emptyList()

    /** Signature of the rows currently submitted to
     *  [categoryRowsAdapter], so a recompute that changes
     *  nothing never touches the adapter (no relayout, no
     *  flash). */
    private var lastRowsSignature: List<Pair<String, List<String>>> =
        emptyList()

    /** The result of one background chrome pass. [fullLists]
     *  Phase 8.96: every row's UNCAPPED item list, stored into
     *  FeedCategorySession for the full category page. */
    private data class ChromeResult(
        val rows: List<FeedCategoryRowsAdapter.CategoryRow>,
        val tabNames: List<String>,
        val fullLists: Map<String, List<FeedEntry>>
    )

    /** Advanced filter selections (Filters sheet). Null = All. */
    private var filterGenre: String? = null
    private var filterYear: Int? = null
    private var filterRatingMin: Double? = null
    private var filterSource: String? = null

    /** Phase 8.88: the smart category chosen on the tile row.
     *  Phase 8.90: also carries the special tags (Trending /
     *  Top 250 / Emotional) chosen on the tag row — one filter
     *  slot for every non-genre tag. (Phase 8.96: a category
     *  row's View All no longer lands here — it opens the
     *  full category page; only chips filter.) */
    private var filterCategory: String? = null

    /** The feed-local text query. Phase 8.94 (Job 3): no UI
     *  writes this any more — text search lives on the pushed
     *  Search page now (his final word 2026-10-30) — but the
     *  filter machinery ([applySearch], the chrome scheduler's
     *  searching branch) still keys off it, and the reset /
     *  refresh paths clear it via [clearFeedQuery]. */
    private var searchQuery: String = ""

    private val filtersActive: Boolean
        get() = filterGenre != null || filterYear != null ||
            filterRatingMin != null || filterSource != null ||
            filterCategory != null

    /** Phase 8.90: the category SECTIONS, ConcatAdapter items
     *  between the hero header and the cards — Home-style rows
     *  (title + red underline + View All pill + poster strip)
     *  replacing the 8.88 tile row he could not find. A poster
     *  tap plays directly. Phase 8.96: View All opens the full
     *  category PAGE (his design — the row holds 20, the rest
     *  loads on demand in the page); it no longer re-filters
     *  the grid. Chips still filter the grid (selectTab). */
    private val categoryRowsAdapter = FeedCategoryRowsAdapter(
        onPlay = { entry -> openWatch(entry) },
        onViewAll = { name -> openCategoryPage(name) }
    )

    /** Phase 8.96: mirror-skeleton rows, ConcatAdapter items
     *  between the category rows and the grid while the chrome
     *  pass runs (see chromeAwaitingPost). */
    private val chromeSkeletonAdapter = FeedChromeSkeletonAdapter()

    /** Phase 8.97: the "For You" header as a ConcatAdapter
     *  item directly above the grid (see
     *  FeedForYouHeaderAdapter) — it was the bottom block of
     *  the floating header until 8.96, visibly detached from
     *  the grid it titles. Shown exactly while the Feed is
     *  browsing and the grid holds cards (see submitFeedList). */
    private val forYouHeaderAdapter = FeedForYouHeaderAdapter()

    /** Phase 8.97: every grid submit in this fragment goes
     *  through here so the For You header item tracks the
     *  grid's content 1:1 — the header exists in the adapter
     *  chain only while browsing (no live query/filter) with
     *  a non-empty grid, and can never float over a void. */
    private fun submitFeedList(
        list: List<FeedEntry>,
        commitCallback: (() -> Unit)? = null
    ) {
        val showHeader = searchQuery.isEmpty() && !filtersActive &&
            list.isNotEmpty()
        forYouHeaderAdapter.setShowing(showHeader)
        if (commitCallback != null) {
            adapter.submitList(list, commitCallback)
        } else {
            adapter.submitList(list)
        }
    }

    /** Phase 8.88: resolved metadata lands here — queue a calm
     *  refresh of the category surfaces (same debounce-until-idle
     *  pattern as resolved titles, so a burst of resolutions
     *  never shakes the grid). */
    private val metaListener: (String) -> Unit = {
        searchHandler.removeCallbacks(metaApplyRunnable)
        searchHandler.postDelayed(metaApplyRunnable, 900L)
    }

    private val metaApplyRunnable = object : Runnable {
        override fun run() {
            val rv = binding?.feedRecycler
            if (rv != null &&
                rv.scrollState != RecyclerView.SCROLL_STATE_IDLE
            ) {
                searchHandler.postDelayed(this, 600L)
                return
            }
            // Phase 8.92: rows + chips recompute OFF the main
            // thread (scheduleChromeUpdate) — this runnable only
            // coalesces the burst and re-runs an active filter,
            // whose match set may have grown with the new meta.
            scheduleChromeUpdate()
            if (filterGenre != null || filterCategory != null) {
                applySearch(searchQuery)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Set by a pull-to-refresh: the next feed value starts at the top. */
    private var scrollTopOnNextFeed = false

    /** RecyclerView position, kept across watch-page trips. */
    private var listState: android.os.Parcelable? = null

    private var pool: List<FeedEntry> = emptyList()
    private var searchJob: Job? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    private var headerFullHeight = 0
    private var headerShown = true
    private var headerAnimator: ValueAnimator? = null

    /** Phase 8.90: the tag names currently built into the chip
     *  row — rebuilt only when the pool-derived set changes. */
    private var currentTabNames: List<String> = emptyList()

    private val miniListener: () -> Unit = { updateMiniBar() }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
        // Batch 4: rotation without view recreation must re-span
        // the grid (the lookup below reads spanCount live).
        (view.findViewById<RecyclerView>(R.id.feed_recycler)
            ?.layoutManager as? GridLayoutManager)?.let {
            it.spanCount = view.context.getFeedSpanCount()
        }
    }

    override fun onBindingCreated(binding: FragmentFeedBinding) {
        // Phase 8.97: a fresh view starts from the XML padding,
        // so the change-only padding guards (updateHeaderPadding)
        // must not carry a previous view's values into it.
        lastHeaderPad = -1
        lastProgressOffset = -1
        // Phase 8.85: 3 columns like Home's grid (was 2).
        // Phase 8.92: the ConcatAdapter is category rows + cards
        // — the hero header item is gone with the hero itself.
        // Batch 4: width-aware like Search — 2 below ~360dp,
        // 6 in landscape — instead of a hardcoded 3.
        val gridLayoutManager =
            GridLayoutManager(context, context?.getFeedSpanCount() ?: 3)
        gridLayoutManager.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                // Category rows, their skeleton placeholders
                // (one adapter item PER ROW) and the For You
                // section header span the full grid width;
                // cards take one column.
                override fun getSpanSize(position: Int): Int {
                    val headers = categoryRowsAdapter.itemCount +
                        chromeSkeletonAdapter.itemCount +
                        forYouHeaderAdapter.itemCount
                    return if (position < headers) gridLayoutManager.spanCount else 1
                }
            }
        binding.feedRecycler.layoutManager = gridLayoutManager
        // Batch 4: runtime floating-pill clearance (88dp +
        // systemBars bottom) replaces the hardcoded 76dp XML
        // padding, which was short by the system inset.
        binding.feedRecycler.applyFloatingNavBottomPadding()
        binding.feedMini.feedMiniRoot.applyFloatingNavBottomMargin()
        binding.feedRecycler.adapter = ConcatAdapter(
            categoryRowsAdapter, chromeSkeletonAdapter,
            forYouHeaderAdapter, adapter
        )
        // Fixed size + no change animations: the grid must never
        // relayout or flash mid-scroll (Phase 8.23 jitter fix).
        binding.feedRecycler.setHasFixedSize(true)
        binding.feedRecycler.itemAnimator = null
        buildCategoryTabs(binding, listOf("All"))
        setupTopBar(binding)
        // Phase 8.96: from the first frame the chrome is
        // skeleton (chips pills in the header; the row
        // placeholders join once the pool lands) until the
        // phase-1 pass posts.
        updateChromeSkeleton()
        binding.feedEmptyReset.setOnClickListener {
            // Back to the unfiltered feed: clear the advanced
            // filters (8.85), the tag selection and any in-feed
            // query, then re-run the pool through the (now
            // empty) filter set.
            clearFilters()
            clearFeedQuery()
            scheduleChromeUpdate()
            applySearch("")
        }
        setupHeaderCollapse(binding)
        setupMiniBar(binding)

        TitleResolver.addListener(titleListener)
        MetadataResolver.addListener(ratingListener)
        MetadataResolver.addMetaListener(metaListener)

        // Phase 8.37: the refresh ring used to hide behind the
        // floating header — rest it below the search bar + chips.
        binding.feedSwipeRefresh.setProgressViewOffset(
            false, 0, (132 * resources.displayMetrics.density).toInt()
        )
        binding.feedSwipeRefresh.setOnRefreshListener {
            // A refresh means "show me something else": drop any
            // active query and load the providers' next homepage
            // page — it lands at the TOP of the feed
            // (FeedViewModel.refresh).
            clearFeedQuery()
            scrollTopOnNextFeed = true
            viewModel.refresh()
        }

        observe(viewModel.feed) { resource ->
            when (resource) {
                is Resource.Success -> {
                    // Phase 8.35: the spinner belongs to the build
                    // chain, not to whichever build finishes first —
                    // a queued pull-refresh keeps it spinning until
                    // ITS build lands.
                    binding.feedSwipeRefresh.isRefreshing = viewModel.isBuilding
                    binding.feedLoading.isVisible = false
                    hideSkeleton(binding)
                    // Hidden items stay hidden even in a replayed value.
                    val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
                    pool = resource.value.filterNot { it.response.url in hidden }
                    // Phase 8.88: kick background metadata
                    // resolution for the pool's head — the
                    // Categories row counts grow as results land
                    // (cards deeper in the pool resolve on bind).
                    // Phase 8.92: the pre-kick is cut 60 → 24 —
                    // the old burst saturated the shared resolver
                    // semaphore while the page was still loading
                    // and every landing fed the (then main-thread)
                    // full recompute; the hang report traces here
                    // too. Deeper cards still resolve on bind.
                    // Phase 8.96: the kick itself is DEFERRED —
                    // it is stored here and fired by
                    // runPendingPreKick only once the phase-1
                    // chrome pass has posted (or immediately
                    // when no pass will run, e.g. a filter is
                    // live). Firing 24 resolver jobs the moment
                    // the pool lands made the first chrome pass
                    // compete with the resolver's Semaphore(3)
                    // storm — and every landing re-armed this
                    // scheduler — during the exact seconds the
                    // page felt hung and the rows were late.
                    pendingPreKick = pool.take(24)
                    scheduleChromeUpdate()
                    val query = searchQuery
                    if (query.isEmpty()) {
                        val filtered = applyFeedFilters(pool)
                        binding.setFeedEmpty( filtered.isEmpty() )
                        // Phase 8.43: scroll to the top AFTER the
                        // prepended list commits. Scrolling before
                        // submitList let the layout manager anchor
                        // to the old first item, which the prepend
                        // pushed far down — a refresh flung the
                        // list toward the end (his report).
                        val goTop = scrollTopOnNextFeed &&
                            viewModel.lastBuildWasPrepend
                        if (goTop) {
                            scrollTopOnNextFeed = false
                            listState = null
                        }
                        submitFeedList(filtered) {
                            if (goTop) {
                                binding.feedRecycler.scrollToPosition(0)
                            } else {
                                listState?.let { state ->
                                    binding.feedRecycler.layoutManager
                                        ?.onRestoreInstanceState(state)
                                    listState = null
                                }
                            }
                        }
                    } else {
                        applySearch(query)
                    }
                }

                is Resource.Failure -> {
                    binding.feedSwipeRefresh.isRefreshing = false
                    binding.feedLoading.isVisible = false
                    hideSkeleton(binding)
                    binding.setFeedEmpty( true )
                }

                is Resource.Loading -> {
                    if (adapter.itemCount == 0 && pool.isEmpty()) {
                        showSkeleton(binding)
                    }
                }

                else -> Unit
            }
        }

        FeedPlayer.warmUp(requireContext())
        FeedPlayer.addListener(miniListener)
        updateMiniBar()
        // Phase 8.99: stale-while-revalidate — on a cold open,
        // paint the disk-cached pool (grid + a chrome pass over
        // the cached entries) as soon as it reads, while the
        // fresh build below swaps real data in behind it. With
        // no cache this is a no-op and the skeleton path runs
        // exactly as before.
        viewModel.paintCachedPoolIfCold()
        viewModel.loadIfNeeded()
    }

    override fun onDestroyView() {
        // Remember where the list was scrolled to — opening a video and
        // coming back must not throw the viewer back to the top.
        listState = binding?.feedRecycler?.layoutManager?.onSaveInstanceState()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchHandler.removeCallbacks(titleApplyRunnable)
        chromeJob?.cancel()
        chromeRunning = false
        chromeDirty = false
        searchJob?.cancel()
        headerAnimator?.cancel()
        FeedPlayer.removeListener(miniListener)
        TitleResolver.removeListener(titleListener)
        MetadataResolver.removeListener(ratingListener)
        MetadataResolver.removeMetaListener(metaListener)
        searchHandler.removeCallbacks(metaApplyRunnable)
        super.onDestroyView()
    }

    /** Phase 8.22: long-press a card → offer to hide it for good. */
    private fun confirmHide(entry: FeedEntry) {
        val context = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(FeedAdapter.displayTitle(entry.response.name, entry.response.url))
            .setMessage(R.string.feed_hide_message)
            .setPositiveButton(R.string.feed_hide) { _, _ ->
                FeedHidden.hide(context, entry.response.url)
                FeedPool.hide(entry.response.url)
                pool = pool.filterNot { it.response.url == entry.response.url }
                scheduleChromeUpdate()
                val query = searchQuery
                if (query.isEmpty()) {
                    val filtered = applyFeedFilters(pool)
                    binding?.setFeedEmpty( filtered.isEmpty() )
                    submitFeedList(filtered)
                } else {
                    applySearch(query)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Called by MainActivity when the Feed nav item is re-tapped. */
    override fun scrollToTop() {
        val binding = binding ?: return
        binding.feedRecycler.smoothScrollToPosition(0)
        animateHeader(binding, show = true)
    }

    // ---------- header: chips + collapse on scroll ----------

    /** Phase 8.36: the empty state is a designed box (art +
     *  message + a Show-all exit), not a lone grey line. */
    private fun FragmentFeedBinding.setFeedEmpty(visible: Boolean) {
        feedEmptyBox.isVisible = visible
    }

    /** Phase 8.36: genre chips with zero matches in the current
     *  pool render dimmed, so an empty tag is visible before it
     *  is tapped (provider search can still find more).
     *  Phase 8.90: restyles the whole tag row — the selected tag
     *  (white pill) follows the active filter, and presence is
     *  judged per tag kind (special tags, smart categories,
     *  Emotional, genres). */
    private fun refreshTabStyles(binding: FragmentFeedBinding) {
        val selected = effectiveTabSelection()
        for (i in 0 until binding.feedChips.childCount) {
            val chip = binding.feedChips.getChildAt(i) as? TextView ?: continue
            val tag = chip.tag as? String ?: continue
            styleChip(chip, selected = tag == selected)
            // Phase 8.91: one presence rule for every tag kind.
            // Phase 8.97: presence is read from
            // [currentTabNames] — the tag list the last chrome
            // pass POSTED, which computePresentTags built as
            // exactly "tags with at least one pool item". The
            // 8.91 form called the memoised tagItems(tag) per
            // chip, on the MAIN thread: every rating landing
            // bumps tagGeneration, which lazily invalidates
            // that memo, so a refresh during the load window
            // recomputed tag lists over the whole pool on the
            // main thread (per entry: a TitleResolver prefs
            // read + a MetadataResolver prefs read + JSON
            // parse attempt, misses never negatively cached).
            // Ratings land in bursts while the pool loads, and
            // this refresh runs on every applySearch / chip tap
            // — a proven main-thread storm in exactly the
            // window where taps ANR'd (his 12:09 report). The
            // posted list carries the same semantics at O(1).
            chip.alpha = when {
                tag == "All" || tag == selected -> 1f
                tag == TAG_TRENDING || tag == TAG_TOP_RATED -> 1f
                else ->
                    if (tag in currentTabNames) 1f else 0.45f
            }
        }
    }

    /**
     * Phase 8.85: the feed's genre vocabulary. SearchResponse
     * itself carries no genre field, so genres started as
     * title-keyword matches — the chip row and the Filters
     * sheet's Genre tab share this one list.
     * Phase 8.87: widened from 9 to the standard 18 genres, each
     * with the title keywords that mark it. A genre matches when
     * ANY of its keywords appears in the title (case-insensitive
     * contains — the rule as it always was, now with spelling
     * variants like "Sci Fi" / "Science Fiction" covered).
     * Phase 8.88: keyword matching is now the FALLBACK half of
     * the entry-level rule (genreMatchesEntry) — resolved
     * metadata genres win first where they exist.
     */
    private val genreKeywords: List<Pair<String, List<String>>> = listOf(
        "Action" to listOf("Action"),
        "Adventure" to listOf("Adventure"),
        "Animation" to listOf("Animation", "Anime", "Animated"),
        "Comedy" to listOf("Comedy"),
        "Crime" to listOf("Crime"),
        "Documentary" to listOf("Documentary"),
        "Drama" to listOf("Drama"),
        "Family" to listOf("Family"),
        "Fantasy" to listOf("Fantasy"),
        "History" to listOf("History", "Historical"),
        "Horror" to listOf("Horror"),
        "Music" to listOf("Music", "Musical"),
        "Mystery" to listOf("Mystery"),
        "Romance" to listOf("Romance", "Romantic"),
        "Sci-Fi" to listOf("Sci-Fi", "Sci Fi", "Science Fiction"),
        "Thriller" to listOf("Thriller"),
        "War" to listOf("War"),
        "Western" to listOf("Western")
    )

    /** Genre names in display order (chip row + Filters sheet). */
    private val genreTags: List<String> = genreKeywords.map { it.first }

    private companion object {
        /** Phase 8.90 special tags (his vocabulary): the pool's
         *  own head, its highest-rated items, and the
         *  Drama/Romance-driven mood tag. */
        const val TAG_TRENDING = "Trending"
        const val TAG_TOP_RATED = "Top 250"
        const val TAG_EMOTIONAL = "Emotional"

        /** Phase 8.90 curated rows from the reference app:
         *  must-watch (high-rated first) and per-region Top
         *  rows (rating-sorted). Plain names are the filter
         *  tags; the emoji live only in the row titles. */
        const val TAG_MUST_WATCH_DRAMA = "Must-watch Drama"
        const val TAG_MUST_WATCH_SERIES = "Must-watch Series"
        const val TAG_TOP_ANIME = "Top Anime"
        const val TAG_TOP_KDRAMA = "Top K-Drama"
        const val TAG_TOP_CDRAMA = "Top C-Drama"
        const val TAG_NEW_KDRAMA = "New Release K-Drama"
        const val TAG_NEW_CDRAMA = "New Release C-Drama"

        /** Curated tags from the reference app. Phase 8.91
         *  re-slots them around the provider-section rows (see
         *  computeChrome): the must-watch pair still
         *  leads its block, the region variants (Top, then New
         *  Release, per region) now follow the section rows.
         *  "Ongoing Series" is no longer missing: it arrives
         *  as a provider SECTION tag (MovieLinkBD shelves one
         *  literally), which is exact data — the 8.90 gap was
         *  only ever about faking it from metadata. */
        val CURATED_TAGS: List<String> = listOf(
            TAG_MUST_WATCH_DRAMA, TAG_MUST_WATCH_SERIES,
            TAG_TOP_ANIME, TAG_TOP_KDRAMA, TAG_NEW_KDRAMA,
            TAG_TOP_CDRAMA, TAG_NEW_CDRAMA
        )

        /** A must-watch row is "high-rated" from this score. */
        const val MUST_WATCH_MIN_SCORE = 7.0

        /** Tags whose grid-filter match is membership of their
         *  computed item list (the curated/special set
         *  [categoryMatchesEntry] switches on) — the filter
         *  builds a URL set once instead of scanning the list
         *  per entry (Phase 8.92, was O(pool²)). */
        val CURATED_MATCH_TAGS: Set<String> = setOf(
            TAG_TRENDING, TAG_TOP_RATED, TAG_EMOTIONAL,
            TAG_MUST_WATCH_DRAMA, TAG_MUST_WATCH_SERIES,
            TAG_TOP_ANIME, TAG_TOP_KDRAMA, TAG_TOP_CDRAMA,
            TAG_NEW_KDRAMA, TAG_NEW_CDRAMA
        )

        /** Category-row bounds. Phase 8.92 (his verdict — too
         *  few rows formed and content below sat uncategorised):
         *  a candidate needs at least 3 pool items (was 4), a
         *  row shows at most 20 (was 15), and at most 20 tag
         *  rows render (was 12). The computation runs off the
         *  main thread now, so the wider bounds are affordable. */
        const val ROW_MIN_ITEMS = 3
        const val ROW_ITEM_CAP = 20
        const val ROW_CAP = 20

        /** Phase 8.92: per-provider rows close the coverage gap
         *  — a provider with at least 6 pool items gets a row
         *  of its own at the end of the sections, so nearly all
         *  pool content appears in SOME row even when no tag
         *  claims it. Row names carry this prefix so View All
         *  can route to the Source filter slot. */
        const val PROVIDER_TAG_PREFIX = "provider:"
        const val PROVIDER_ROW_MIN_ITEMS = 6
        const val PROVIDER_ROW_CAP = 10
    }

    /** The one genre-matching rule, shared by the chip row, the
     *  Filters sheet's Genre tab and the genre filter itself. */
    private fun genreMatches(title: String, genre: String): Boolean {
        val keywords = genreKeywords.firstOrNull { it.first == genre }?.second
            ?: return false
        return keywords.any { title.contains(it, ignoreCase = true) }
    }

    // ---------- Phase 8.88: resolved-metadata categories ----------

    /** Phase 8.94: while a background chrome pass runs, metaOf
     *  answers from this pass-local memo. The pass evaluates
     *  ~40–70 tags over the whole pool and metaOf is the hot
     *  call inside all of them — without the memo, every
     *  (tag × entry) check re-read SharedPreferences on a
     *  cache miss and re-ran cleanPosterTitle + the two
     *  normalize() Regex instantiations, which is what made a
     *  single pass take seconds on the 8.1 phone. Null
     *  outside a pass: main-thread callers use the resolver's
     *  own caches exactly as before.
     *  Phase 8.97: the memo lives in a ThreadLocal, set only
     *  on the pass thread (computeChrome installs it; the
     *  pass body has no suspension points, so it never hops
     *  threads). The 8.95 form was a plain @Volatile field
     *  holding a Collections.synchronizedMap — while a pass
     *  ran, MAIN-thread metaOf callers (grid binds, filters)
     *  also routed through that shared map. Its per-call
     *  locks were never held across the slow resolver read
     *  (containsKey → read → put are separate calls), so it
     *  was not itself an ANR mechanism — but confining the
     *  memo to the pass thread removes the contention class
     *  entirely: the main thread now NEVER waits on any pass
     *  structure, and its metaOf calls use the resolver's
     *  own caches exactly as they did before 8.94. */
    private val passMetaCache =
        ThreadLocal<MutableMap<String, MetadataResolver.Meta?>>()

    /** The item's resolved metadata (genres / language /
     *  country), when MetadataResolver has it cached. */
    private fun metaOf(entry: FeedEntry): MetadataResolver.Meta? {
        val cache = passMetaCache.get()
        if (cache != null) {
            val url = entry.response.url
            if (cache.containsKey(url)) return cache[url]
            val meta = context?.let {
                MetadataResolver.metaCachedFor(it, entry.response)
            }
            cache[url] = meta
            return meta
        }
        return context?.let {
            MetadataResolver.metaCachedFor(it, entry.response)
        }
    }

    /** All categories an item belongs to: the smart categories
     *  (K-Drama, Anime, …) plus each resolved genre by name.
     *  The type is the details-page type where one was learned
     *  (the same preference the cards use), else the listing's. */
    private fun categoriesOfEntry(entry: FeedEntry): Set<String> {
        val type = context?.let {
            TitleResolver.cachedType(it, entry.response.url)
        } ?: entry.response.type
        return FeedCategories.categoriesOf(type, metaOf(entry))
    }

    /**
     * The genre rule for entries, UNION of both worlds: an item
     * matches a genre when its RESOLVED genres (canonicalised
     * onto the chip vocabulary) contain it, OR the 8.87
     * title-keyword match hits. Resolved data is checked first —
     * it is real metadata; keywords remain the fallback for
     * items no source could resolve.
     */
    private fun genreMatchesEntry(entry: FeedEntry, genre: String): Boolean {
        val resolved = FeedCategories.canonicalGenres(metaOf(entry))
        if (resolved.any { it.equals(genre, ignoreCase = true) }) {
            return true
        }
        return genreMatches(entry.response.name, genre)
    }

    // ---------- Phase 8.90: tag vocabulary (chips + rows) ----------

    /**
     * The plain predicate behind the simple tags. Phase 8.91:
     * the PROVIDER SECTION tags read FIRST (exact, provider-
     * curated data), the 8.88/8.90 metadata derivations second —
     * a tag matches when either world claims the entry. Only
     * Trending / Top 250 keep purely derived meanings here
     * (their item lists are computed in computeTagItems).
     */
    private fun tagPredicate(
        entry: FeedEntry,
        tag: String,
        poolSnap: List<FeedEntry> = pool,
        ratingsSnap: Map<String, String> = fragmentRatings
    ): Boolean =
        when (tag) {
            TAG_TRENDING -> trendingUrls(poolSnap).contains(entry.response.url)
            TAG_TOP_RATED -> scoreOf(entry, ratingsSnap) != null
            TAG_EMOTIONAL -> tag in sectionTagsOf(entry) ||
                emotionalMatchesEntry(entry)
            in FeedCategories.SMART_CATEGORIES ->
                tag in sectionTagsOf(entry) ||
                    tag in categoriesOfEntry(entry)
            else -> tag in sectionTagsOf(entry) ||
                genreMatchesEntry(entry, tag)
        }

    /** Trending = the pool's own head, in pool order. */
    private fun trendingUrls(
        poolSnap: List<FeedEntry> = pool
    ): Set<String> =
        poolSnap.take(30).map { it.response.url }.toSet()

    /**
     * The Emotional tag (his list): Drama/Romance by the union
     * genre rule, a resolved melodrama genre, or one of the
     * emotional keywords in the title.
     */
    private fun emotionalMatchesEntry(entry: FeedEntry): Boolean {
        if (genreMatchesEntry(entry, "Drama") ||
            genreMatchesEntry(entry, "Romance")
        ) {
            return true
        }
        val meta = metaOf(entry)
        if (meta?.genres?.any {
                it.contains("melodrama", ignoreCase = true)
            } == true
        ) {
            return true
        }
        val title = entry.response.name
        return listOf("emotional", "tearjerker", "melodrama", "heartfelt")
            .any { title.contains(it, ignoreCase = true) }
    }

    /** Series-ness from the same type source the cards use. */
    private fun isSeriesEntry(entry: FeedEntry): Boolean {
        val type = context?.let {
            TitleResolver.cachedType(it, entry.response.url)
        } ?: entry.response.type
        return type == TvType.TvSeries || type == TvType.AsianDrama ||
            type == TvType.Anime || type == TvType.OVA
    }

    /** Rating-descending order; unrated items sink to the end
     *  keeping pool order (the sort is stable). */
    private fun byRatingDesc(
        items: List<FeedEntry>,
        ratingsSnap: Map<String, String> = fragmentRatings
    ): List<FeedEntry> =
        items.sortedWith(
            compareByDescending { scoreOf(it, ratingsSnap) ?: -1.0 }
        )

    /**
     * A must-watch curation: the base set's high-rated items
     * (score at least [MUST_WATCH_MIN_SCORE]) best-first when
     * there are enough of them; otherwise the base set itself
     * in pool order (a pool with no ratings still gets its
     * must-watch row).
     */
    private fun mustWatchItems(
        base: List<FeedEntry>,
        ratingsSnap: Map<String, String> = fragmentRatings
    ): List<FeedEntry> {
        val high = base
            .filter {
                (scoreOf(it, ratingsSnap) ?: 0.0) >= MUST_WATCH_MIN_SCORE
            }
            .sortedByDescending { scoreOf(it, ratingsSnap) }
        return if (high.size >= ROW_MIN_ITEMS) high else base
    }

    // ---------- Phase 8.91: provider-section tags ----------

    /** Canonical section tags per entry URL, memoised per pool
     *  instance like tagItems below. Section provenance is
     *  fixed for a pool's lifetime, so this never needs the
     *  rating listener's invalidation. */
    private var sectionTagsCache = mutableMapOf<String, Set<String>>()
    private var sectionTagsCachePool: List<FeedEntry>? = null

    /** Phase 8.94: sectionTagsOf is called from the main
     *  thread (grid filter) AND from the background chrome
     *  pass; the plain HashMap cache is guarded so the two
     *  never mutate it concurrently. Holds are brief (a map
     *  lookup or one entry's canonicalisation). */
    private val sectionTagsCacheLock = Any()

    /** The canonical tags of the provider shelves this entry
     *  arrived under (FeedSectionTags mapping of the raw names
     *  FeedViewModel kept). */
    private fun sectionTagsOf(entry: FeedEntry): Set<String> =
        synchronized(sectionTagsCacheLock) {
            if (sectionTagsCachePool !== pool) {
                sectionTagsCache.clear()
                sectionTagsCachePool = pool
            }
            sectionTagsCache.getOrPut(entry.response.url) {
                entry.sections
                    .mapNotNull { FeedSectionTags.canonicalTag(it) }
                    .toSet()
            }
        }

    /** Every canonical section tag present anywhere in the pool. */
    private fun poolSectionTags(
        poolSnap: List<FeedEntry> = pool
    ): Set<String> {
        val out = mutableSetOf<String>()
        poolSnap.forEach { out += sectionTagsOf(it) }
        return out
    }

    /** The user's hidden Feed Categories (settings page). */
    private fun hiddenTags(ctx: Context? = context): Set<String> =
        ctx?.let { FeedCategoryPrefs.hidden(it) } ?: emptySet()

    /**
     * The pool's section tags in display order: the user's
     * saved order first (Settings → Feed Categories), else the
     * curated FeedSectionTags.CANONICAL_ORDER; tags neither list
     * mentions (new passthrough labels) append by item count.
     */
    private fun orderedSectionTags(
        poolSnap: List<FeedEntry> = pool,
        ctx: Context? = context,
        itemsOf: (String) -> List<FeedEntry> = { tagItems(it) }
    ): List<String> {
        val present = poolSectionTags(poolSnap)
        if (present.isEmpty()) return emptyList()
        val saved = ctx?.let { FeedCategoryPrefs.order(it) }
            ?: emptyList()
        val base = saved.ifEmpty { FeedSectionTags.CANONICAL_ORDER }
        val listed = base.filter { it in present }
        val unlisted = (present - listed.toSet())
            .sortedByDescending { itemsOf(it).size }
        return listed + unlisted
    }

    /** tagItems results, memoised per pool instance — the row
     *  builder, the chip presence checks and the grid filter
     *  all ask repeatedly. Phase 8.92: main-thread callers only
     *  (the background chrome pass computes into its own memo
     *  via computeTagItems directly). Late-arriving ratings no
     *  longer clear it per rating (see ratingListener) — the
     *  generation check drops it lazily, once per burst. */
    private var tagItemsCache = mutableMapOf<String, List<FeedEntry>>()
    private var tagItemsCachePool: List<FeedEntry>? = null

    @Volatile
    private var tagGeneration = 0
    private var tagItemsGeneration = -1

    /** Every item of a tag in the current pool, in row order,
     *  uncapped (callers cap for display). */
    private fun tagItems(tag: String): List<FeedEntry> {
        if (tagItemsCachePool !== pool ||
            tagItemsGeneration != tagGeneration
        ) {
            tagItemsCache.clear()
            tagItemsCachePool = pool
            tagItemsGeneration = tagGeneration
        }
        return tagItemsCache.getOrPut(tag) { computeTagItems(tag) }
    }

    private fun computeTagItems(
        tag: String,
        poolSnap: List<FeedEntry> = pool,
        ratingsSnap: Map<String, String> = fragmentRatings
    ): List<FeedEntry> = when (tag) {
        // Phase 8.91: a real provider Trending shelf (MovieBox
        // and Rtally both declare one) is exact data and wins;
        // the 8.90 pool-head derivation remains the fallback
        // for pools where no provider shelves Trending.
        TAG_TRENDING -> {
            val shelved = poolSnap.filter {
                FeedSectionTags.TRENDING in sectionTagsOf(it)
            }
            if (shelved.size >= ROW_MIN_ITEMS) {
                shelved
            } else {
                poolSnap.take(30)
            }
        }
        TAG_TOP_RATED -> byRatingDesc(
            poolSnap.filter { scoreOf(it, ratingsSnap) != null },
            ratingsSnap
        )
        TAG_MUST_WATCH_DRAMA -> mustWatchItems(
            poolSnap.filter { genreMatchesEntry(it, "Drama") },
            ratingsSnap
        )
        TAG_MUST_WATCH_SERIES -> mustWatchItems(
            poolSnap.filter { isSeriesEntry(it) },
            ratingsSnap
        )
        TAG_TOP_ANIME -> byRatingDesc(
            poolSnap.filter { "Anime" in categoriesOfEntry(it) },
            ratingsSnap
        )
        TAG_TOP_KDRAMA -> byRatingDesc(
            poolSnap.filter { "K-Drama" in categoriesOfEntry(it) },
            ratingsSnap
        )
        TAG_TOP_CDRAMA -> byRatingDesc(
            poolSnap.filter { "C-Drama" in categoriesOfEntry(it) },
            ratingsSnap
        )
        TAG_NEW_KDRAMA -> newReleaseItems(
            poolSnap.filter { "K-Drama" in categoriesOfEntry(it) }
        )
        TAG_NEW_CDRAMA -> newReleaseItems(
            poolSnap.filter { "C-Drama" in categoriesOfEntry(it) }
        )
        else -> poolSnap.filter {
            tagPredicate(it, tag, poolSnap, ratingsSnap)
        }
    }

    /**
     * A New Release curation: the region's items that carry a
     * year (the search-response year, via MetadataResolver),
     * most recent first. Current/previous-year items are
     * preferred — when at least a row's worth of them exists
     * they ARE the row; otherwise every year-known item,
     * still year-descending.
     */
    private fun newReleaseItems(base: List<FeedEntry>): List<FeedEntry> {
        val withYear = base.filter {
            MetadataResolver.yearOf(it.response) != null
        }
        val currentYear = java.util.Calendar.getInstance()
            .get(java.util.Calendar.YEAR)
        val recent = withYear
            .filter {
                (MetadataResolver.yearOf(it.response) ?: 0) >= currentYear - 1
            }
            .sortedByDescending { MetadataResolver.yearOf(it.response) }
        return if (recent.size >= ROW_MIN_ITEMS) {
            recent
        } else {
            withYear.sortedByDescending {
                MetadataResolver.yearOf(it.response)
            }
        }
    }

    /**
     * Does this entry belong to the named tag? One rule shared
     * by the category rows, the tag-row filter and the grid
     * filter: curated/special tags match by membership of
     * their computed item list (so a row and its View All
     * filter always agree), simple tags by predicate (which
     * also covers merged search results outside the pool).
     */
    private fun categoryMatchesEntry(entry: FeedEntry, tag: String): Boolean =
        when (tag) {
            TAG_TRENDING, TAG_TOP_RATED, TAG_EMOTIONAL,
            TAG_MUST_WATCH_DRAMA, TAG_MUST_WATCH_SERIES,
            TAG_TOP_ANIME, TAG_TOP_KDRAMA, TAG_TOP_CDRAMA,
            TAG_NEW_KDRAMA, TAG_NEW_CDRAMA ->
                tagItems(tag).any { it.response.url == entry.response.url }
            else -> tagPredicate(entry, tag)
        }

    /**
     * The row header's title for a tag — the reference app's
     * emoji decoration lives here and ONLY here (chips stay
     * plain text): 🔥 Trending, ⭐ Top 250, ✨ Emotional,
     * 🏆/✨ Must-watch pair, ⚡/🔥 Top rows, 💘 Romance. Plain
     * genre/region rows keep clean titles.
     */
    private fun displayTitleFor(tag: String): String = when (tag) {
        TAG_TRENDING -> "🔥 Trending"
        TAG_TOP_RATED -> "⭐ Top 250"
        TAG_EMOTIONAL -> "✨ Emotional"
        TAG_MUST_WATCH_DRAMA -> "🏆 Must-watch Drama 🏆"
        TAG_MUST_WATCH_SERIES -> "✨ Must-watch Series ✨"
        TAG_TOP_ANIME -> "⚡ Top Anime"
        TAG_TOP_KDRAMA -> "🔥 Top K-Drama"
        TAG_TOP_CDRAMA -> "🔥 Top C-Drama"
        TAG_NEW_KDRAMA -> "🆕 New Release K-Drama"
        TAG_NEW_CDRAMA -> "🆕 New Release C-Drama"
        "Romance" -> "💘 Romance"
        else -> tag
    }

    // The category ROWS vocabulary (Phase 8.91 — rows v2): the
    // PROVIDER SECTIONS are the primary source (the 8.90 rows
    // were metadata-only). Slot order: 🔥 Trending (the
    // providers' own Trending shelf when one exists, pool head
    // otherwise) → ⭐ Top 250 → Must-watch Drama → Must-watch
    // Series → the section-tag rows in the user's Feed
    // Categories order (else item count) → the rating-sorted
    // Top / New Release region rows → metadata-fallback rows
    // ONLY for smart categories and genres that got no section
    // data at all → per-provider rows (Phase 8.92). "Ongoing
    // Series" exists in this world: providers shelf it by that
    // exact name, so the row is provider-labelled and accurate
    // — nothing is faked from years or episode titles. Tags
    // the user hid on the Feed Categories settings page
    // produce no row. Counts run over the fragment pool, which
    // the ViewModel already Family-Mode/hidden filtered.
    // Hidden while a search/filter is active — the rows belong
    // to browsing. Assembly lives in computeChrome below.

    /** Phase 8.92: rows vanish synchronously the moment a
     *  search/filter takes over (they belong to browsing);
     *  rebuilding them is the background pass's job. Phase
     *  8.96: the teardown also re-arms the chrome skeleton —
     *  when browsing resumes, the mirror rows show until the
     *  rebuild posts (updateChromeSkeleton keeps them hidden
     *  while the filter itself is live). */
    private fun hideCategoryRows() {
        lastRowsSignature = emptyList()
        categoryRowsAdapter.submit(emptyList())
        chromeAwaitingPost = true
        updateChromeSkeleton()
    }

    /** Phase 8.96: the mirror skeleton's visibility, derived
     *  in ONE place from the chrome state: skeleton rows (in
     *  the list, once there is a pool to build rows from) and
     *  the header's chips pills show exactly while a chrome
     *  post is outstanding AND the Feed is browsing (no live
     *  query/filter). The real chips view goes INVISIBLE under
     *  its skeleton — never GONE — so the floating header's
     *  measured height, and with it the grid's top padding,
     *  is identical before and after the swap. */
    private fun updateChromeSkeleton() {
        val b = binding ?: return
        val browsing = searchQuery.isEmpty() && !filtersActive
        val show = chromeAwaitingPost && browsing
        chromeSkeletonAdapter.setShowing(show && pool.isNotEmpty())
        if (show) {
            b.feedChips.visibility = View.INVISIBLE
            b.feedChipsSkeleton.isVisible = true
            b.feedChipsSkeleton.startShimmer()
        } else {
            b.feedChips.visibility = View.VISIBLE
            b.feedChipsSkeleton.stopShimmer()
            b.feedChipsSkeleton.isVisible = false
        }
    }

    /** Phase 8.96: fire the deferred metadata pre-kick (see
     *  the feed observer). Runs on the main thread at chrome
     *  post time — the burst now lands AFTER the first rows
     *  are on screen instead of racing them. */
    private fun runPendingPreKick() {
        if (pendingPreKick.isEmpty()) return
        val kick = pendingPreKick
        pendingPreKick = emptyList()
        val ctx = context ?: return
        kick.forEach { MetadataResolver.resolveMeta(ctx, it.response) }
    }

    /**
     * Phase 8.92: entry point for every rows+chips refresh.
     * The 8.90/8.91 code recomputed the whole tag vocabulary
     * over the whole pool ON THE MAIN THREAD at every pool
     * post and every debounced metadata landing — each pass is
     * O(tags × pool) predicates whose per-entry cost includes
     * SharedPreferences lookups and regex title normalisation
     * (MetadataResolver.metaCached / TitleResolver.cachedType),
     * which on the 8.1 phone read as a hard freeze. The pass
     * now runs on Dispatchers.Default over a pool snapshot and
     * posts back only finished models; a stale pass (pool moved
     * on meanwhile) is discarded, and an unchanged result never
     * reaches the adapter.
     */
    private fun scheduleChromeUpdate() {
        val b = binding ?: return
        if (searchQuery.isNotEmpty() || filtersActive || pool.isEmpty()) {
            chromeJob?.cancel()
            chromeRunning = false
            chromeDirty = false
            hideCategoryRows()
            if (pool.isEmpty()) {
                buildCategoryTabs(b, listOf("All"))
            }
            // Phase 8.96: no chrome pass will post in this
            // state, so the deferred pre-kick fires now instead
            // of waiting for one.
            runPendingPreKick()
            updateChromeSkeleton()
            return
        }
        updateChromeSkeleton()
        if (chromeRunning) {
            // Phase 8.94: a pass is already computing over a
            // snapshot and the inputs just moved (a pool post,
            // or another metadata/rating landing). Do NOT
            // cancel it. The 8.92 code cancelled the in-flight
            // pass on EVERY trigger, and computeChrome has no
            // suspension points, so the cancelled pass burned
            // the whole compute and then died at the
            // withContext(Main) boundary without posting —
            // while the Feed itself generates the next trigger
            // (the observer's 24-item resolveMeta pre-kick plus
            // a resolve per uncached card bind; every NEW store
            // in MetadataResolver re-arms metaApplyRunnable,
            // which lands here). On the 8.1 phone a pass takes
            // longer than the gap between landings during and
            // after load, so no pass ever posted: rows stayed
            // empty and the chips stayed at the "All"-only
            // initial build from onBindingCreated. Let the
            // running pass finish; the completion block runs
            // exactly one follow-up with fresh snapshots.
            chromeDirty = true
            return
        }
        launchChromePass()
    }

    /** One background rows+chips pass over the CURRENT pool /
     *  ratings snapshots. Always posts its result when the
     *  snapshot is still current and the Feed is still
     *  browsing; when the world moved on mid-pass it
     *  re-schedules instead of dropping the result on the
     *  floor (the other half of the 8.92 regression: the old
     *  `if (pool !== poolSnap) return` discarded the page-1
     *  pass when page 2 published, with no retry). */
    private fun launchChromePass() {
        val poolSnap = pool
        val ratingsSnap = fragmentRatings.toMap()
        val appCtx = context?.applicationContext ?: return
        // Phase 8.96: TWO-PHASE chrome. The first pass over a
        // pool is phase 1 — pool-internal data only (provider
        // sections + the responses' own scores; NO resolver
        // calls), so it is fast enough to end the skeleton the
        // moment page 1 lands instead of seconds later. The
        // follow-up pass over the SAME pool is phase 2 — the
        // full computation (metadata genres/regions, resolved
        // ratings) — and merges in behind phase 1's rows: the
        // signature gate below means an unchanged model never
        // touches the adapter, so the merge cannot flicker.
        val phase = if (chromePhase1Pool === poolSnap) 2 else 1
        chromeRunning = true
        chromeDirty = false
        chromeJob = viewLifecycleOwner.lifecycleScope
            .launch(Dispatchers.Default) {
                val result = computeChrome(poolSnap, ratingsSnap, appCtx, phase)
                withContext(Dispatchers.Main) {
                    chromeRunning = false
                    val bb = binding
                    if (bb != null && pool === poolSnap &&
                        searchQuery.isEmpty() && !filtersActive
                    ) {
                        if (phase == 1) chromePhase1Pool = poolSnap
                        chromeAwaitingPost = false
                        FeedCategorySession.lists = result.fullLists
                        val signature = result.rows.map { row ->
                            row.name to row.items.map { it.response.url }
                        }
                        if (signature != lastRowsSignature) {
                            lastRowsSignature = signature
                            categoryRowsAdapter.submit(result.rows)
                        }
                        buildCategoryTabs(
                            bb, listOf("All") + result.tabNames
                        )
                        // Phase 8.96: the deferred metadata
                        // pre-kick fires only now — the first
                        // rows are on screen before the resolver
                        // burst starts (and phase 2, queued
                        // below, is what the burst then feeds).
                        runPendingPreKick()
                        updateChromeSkeleton()
                        if (chromeDirty || phase == 1) {
                            // A phase-1 post ALWAYS queues its
                            // phase-2 follow-up (unless the
                            // scheduler's state changed above).
                            chromeDirty = false
                            scheduleChromeUpdate()
                        }
                    } else if (bb != null) {
                        // Pool changed mid-pass, or a search /
                        // filter took over: route through the
                        // scheduler, which hides the rows in the
                        // search/filter case and launches a
                        // fresh pass over the new pool otherwise.
                        chromeDirty = false
                        scheduleChromeUpdate()
                    }
                }
            }
    }

    /**
     * One full rows+chips computation over [poolSnap] — the
     * 8.91 rows v2 assembly, unchanged in vocabulary and slot
     * order, plus the 8.92 provider rows at the end. Runs on a
     * background dispatcher; touches no views. [itemsOf] is
     * this pass's own memo — the shared main-thread memo
     * (tagItems) is never touched from here.
     */
    private fun computeChrome(
        poolSnap: List<FeedEntry>,
        ratingsSnap: Map<String, String>,
        appCtx: Context,
        phase: Int
    ): ChromeResult {
        // Phase 8.96: phase 1 is pool-internal only — it never
        // touches a resolver, so the per-pass meta memo below
        // is neither installed nor needed there.
        if (phase == 1) return computeChromePhase1(poolSnap, appCtx)
        // Phase 8.94: install the per-pass meta memo (see
        // passMetaCache) for the duration of the pass.
        // Phase 8.95 (crash fix): the memo map must tolerate
        // NULL values — "no metadata cached for this entry" is
        // the COMMON case and metaOf memoises it too.
        // ConcurrentHashMap rejects null values with an NPE,
        // which crashed the pass (and the app) on the first
        // uncached pool entry.
        // Phase 8.97: the memo is a plain HashMap again — it
        // is confined to this pass thread by the ThreadLocal,
        // so no synchronization is needed at all and no other
        // thread can ever contend on it.
        passMetaCache.set(mutableMapOf())
        try {
            return computeChromePass(poolSnap, ratingsSnap, appCtx)
        } finally {
            passMetaCache.remove()
        }
    }

    /**
     * Phase 8.96 — PHASE 1 of the chrome: everything derivable
     * from the pool entries THEMSELVES. Provider section tags
     * (FeedSectionTags over the entries' shelf provenance),
     * the responses' own provider scores (Top 250 / must-watch
     * / the region Top rows, score-ordered), title-keyword
     * genres, and the per-provider rows. NO MetadataResolver
     * and NO TitleResolver calls happen anywhere below — the
     * metadata-derived rows (smart categories by resolved
     * language/country, canonical resolved genres, New Release
     * by resolved year, resolved-rating orderings) are phase
     * 2's contribution and merge in behind this model.
     * Same slot order as phase 2 wherever the two overlap.
     */
    private fun computeChromePhase1(
        poolSnap: List<FeedEntry>,
        appCtx: Context
    ): ChromeResult {
        val memo = mutableMapOf<String, List<FeedEntry>>()
        fun itemsOf(tag: String): List<FeedEntry> =
            memo.getOrPut(tag) { computeTagItemsPhase1(tag, poolSnap) }
        fun rowOf(tag: String) = FeedCategoryRowsAdapter.CategoryRow(
            tag, displayTitleFor(tag), itemsOf(tag).take(ROW_ITEM_CAP)
        )
        val hidden = hiddenTags(appCtx)
        fun wanted(tag: String) =
            tag !in hidden && itemsOf(tag).size >= ROW_MIN_ITEMS
        val rows = mutableListOf<FeedCategoryRowsAdapter.CategoryRow>()
        // Fixed head + the must-watch pair.
        for (tag in listOf(
            TAG_TRENDING, TAG_TOP_RATED,
            TAG_MUST_WATCH_DRAMA, TAG_MUST_WATCH_SERIES
        )) {
            if (wanted(tag)) rows += rowOf(tag)
        }
        // Provider-section rows, in the user's order.
        for (tag in orderedSectionTags(poolSnap, appCtx, { t -> itemsOf(t) })) {
            if (tag == TAG_TRENDING) continue
            if (wanted(tag)) rows += rowOf(tag)
        }
        // Region Top rows from section membership, ordered by
        // the responses' own scores (phase 2 re-orders by
        // resolved data and adds the New Release variants).
        for (tag in listOf(TAG_TOP_ANIME, TAG_TOP_KDRAMA, TAG_TOP_CDRAMA)) {
            if (wanted(tag)) rows += rowOf(tag)
        }
        // Genre rows from title keywords ∪ section tags, for
        // genres no earlier slot already rowed.
        val rowed = rows.map { it.name }.toSet()
        val genreRows = genreTags
            .filter { it !in rowed }
            .map { tag -> tag to itemsOf(tag).size }
            .filter { it.first !in hidden && it.second >= ROW_MIN_ITEMS }
            .sortedByDescending { it.second }
        for ((tag, _) in genreRows) {
            rows += rowOf(tag)
        }
        val tagRows = rows.take(ROW_CAP)
        // Per-provider rows (same construction as phase 2).
        val providerGroups = poolSnap.groupBy { it.apiName }
            .filterValues { it.size >= PROVIDER_ROW_MIN_ITEMS }
            .entries.sortedByDescending { it.value.size }
            .take(PROVIDER_ROW_CAP)
        val providerRows = providerGroups.map { (api, items) ->
            FeedCategoryRowsAdapter.CategoryRow(
                PROVIDER_TAG_PREFIX + api, api,
                items.take(ROW_ITEM_CAP)
            )
        }
        val fullLists = mutableMapOf<String, List<FeedEntry>>()
        tagRows.forEach { fullLists[it.name] = itemsOf(it.name) }
        providerGroups.forEach { (api, items) ->
            fullLists[PROVIDER_TAG_PREFIX + api] = items
        }
        return ChromeResult(
            tagRows + providerRows,
            computePresentTagsPhase1(poolSnap, appCtx, { t -> itemsOf(t) }, hidden),
            fullLists
        )
    }

    /** Phase 8.96: the phase-1 item rule — [computeTagItems]
     *  restricted to pool-internal data (sections, response
     *  scores, response types, title keywords). */
    private fun computeTagItemsPhase1(
        tag: String,
        poolSnap: List<FeedEntry>
    ): List<FeedEntry> = when (tag) {
        TAG_TRENDING -> {
            val shelved = poolSnap.filter {
                FeedSectionTags.TRENDING in sectionTagsOf(it)
            }
            if (shelved.size >= ROW_MIN_ITEMS) {
                shelved
            } else {
                poolSnap.take(30)
            }
        }
        TAG_TOP_RATED -> byProviderScoreDesc(
            poolSnap.filter { providerScoreOf(it) != null }
        )
        TAG_MUST_WATCH_DRAMA -> mustWatchPhase1(
            poolSnap.filter {
                "Drama" in sectionTagsOf(it) ||
                    genreMatches(it.response.name, "Drama")
            }
        )
        TAG_MUST_WATCH_SERIES -> mustWatchPhase1(
            poolSnap.filter { isSeriesType(it.response.type) }
        )
        TAG_TOP_ANIME -> byProviderScoreDesc(
            poolSnap.filter {
                FeedSectionTags.ANIME in sectionTagsOf(it) ||
                    it.response.type == TvType.Anime ||
                    it.response.type == TvType.OVA
            }
        )
        TAG_TOP_KDRAMA -> byProviderScoreDesc(
            poolSnap.filter { FeedSectionTags.K_DRAMA in sectionTagsOf(it) }
        )
        TAG_TOP_CDRAMA -> byProviderScoreDesc(
            poolSnap.filter { FeedSectionTags.C_DRAMA in sectionTagsOf(it) }
        )
        else -> poolSnap.filter {
            tag in sectionTagsOf(it) ||
                genreMatches(it.response.name, tag)
        }
    }

    /** The response's own provider score, normalised the same
     *  way scoreOf reads it — the only score phase 1 may use
     *  (resolved universal ratings are phase 2 data). */
    private fun providerScoreOf(entry: FeedEntry): Double? =
        entry.response.score?.toDouble(10)?.takeIf { it > 0.0 }

    /** Provider-score-descending order; unscored items sink
     *  to the end keeping pool order (stable sort). */
    private fun byProviderScoreDesc(
        items: List<FeedEntry>
    ): List<FeedEntry> =
        items.sortedWith(
            compareByDescending { providerScoreOf(it) ?: -1.0 }
        )

    /** Phase 8.96: must-watch from provider scores only (the
     *  phase-1 twin of mustWatchItems). */
    private fun mustWatchPhase1(base: List<FeedEntry>): List<FeedEntry> {
        val high = base
            .filter {
                (providerScoreOf(it) ?: 0.0) >= MUST_WATCH_MIN_SCORE
            }
            .sortedByDescending { providerScoreOf(it) }
        return if (high.size >= ROW_MIN_ITEMS) high else base
    }

    /** Series-ness from the LISTING's declared type only —
     *  isSeriesEntry without its TitleResolver lookup. */
    private fun isSeriesType(type: TvType?): Boolean =
        type == TvType.TvSeries || type == TvType.AsianDrama ||
            type == TvType.Anime || type == TvType.OVA

    /** Phase 8.96: the phase-1 chip set — computePresentTags
     *  restricted to pool-internal presence: sections,
     *  provider scores, title keywords. Metadata-derived tags
     *  (smart categories by resolved language/country, the
     *  region Top/New variants) join in phase 2. */
    private fun computePresentTagsPhase1(
        poolSnap: List<FeedEntry>,
        appCtx: Context,
        itemsOf: (String) -> List<FeedEntry>,
        hidden: Set<String>
    ): List<String> {
        if (poolSnap.isEmpty()) return emptyList()
        val tags = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun add(tag: String) {
            if (tag !in hidden && seen.add(tag)) tags += tag
        }
        add(TAG_TRENDING)
        if (poolSnap.any { providerScoreOf(it) != null }) {
            add(TAG_TOP_RATED)
        }
        for (tag in listOf(TAG_MUST_WATCH_DRAMA, TAG_MUST_WATCH_SERIES)) {
            if (itemsOf(tag).isNotEmpty()) add(tag)
        }
        orderedSectionTags(poolSnap, appCtx, itemsOf).forEach { tag ->
            if (itemsOf(tag).isNotEmpty()) add(tag)
        }
        for (tag in listOf(TAG_TOP_ANIME, TAG_TOP_KDRAMA, TAG_TOP_CDRAMA)) {
            if (itemsOf(tag).isNotEmpty()) add(tag)
        }
        if (poolSnap.any { entry ->
                genreMatches(entry.response.name, "Drama") ||
                    genreMatches(entry.response.name, "Romance")
            }
        ) {
            add(TAG_EMOTIONAL)
        }
        genreTags.forEach { genre ->
            if (itemsOf(genre).isNotEmpty()) add(genre)
        }
        return tags
    }

    private fun computeChromePass(
        poolSnap: List<FeedEntry>,
        ratingsSnap: Map<String, String>,
        appCtx: Context
    ): ChromeResult {
        val memo = mutableMapOf<String, List<FeedEntry>>()
        fun itemsOf(tag: String): List<FeedEntry> =
            memo.getOrPut(tag) {
                computeTagItems(tag, poolSnap, ratingsSnap)
            }
        fun rowOf(tag: String) = FeedCategoryRowsAdapter.CategoryRow(
            tag, displayTitleFor(tag), itemsOf(tag).take(ROW_ITEM_CAP)
        )
        val hidden = hiddenTags(appCtx)
        fun wanted(tag: String) =
            tag !in hidden && itemsOf(tag).size >= ROW_MIN_ITEMS
        val rows = mutableListOf<FeedCategoryRowsAdapter.CategoryRow>()
        // Fixed head + the must-watch pair.
        for (tag in listOf(
            TAG_TRENDING, TAG_TOP_RATED,
            TAG_MUST_WATCH_DRAMA, TAG_MUST_WATCH_SERIES
        )) {
            if (wanted(tag)) rows += rowOf(tag)
        }
        // Provider-section rows, in the user's order.
        val sectionPresent = poolSectionTags(poolSnap)
        for (tag in orderedSectionTags(poolSnap, appCtx, { t -> itemsOf(t) })) {
            if (tag == TAG_TRENDING) continue
            if (wanted(tag)) rows += rowOf(tag)
        }
        // Rating-sorted region variants (metadata world).
        for (tag in listOf(
            TAG_TOP_ANIME, TAG_TOP_KDRAMA, TAG_NEW_KDRAMA,
            TAG_TOP_CDRAMA, TAG_NEW_CDRAMA
        )) {
            if (wanted(tag)) rows += rowOf(tag)
        }
        // Metadata fallback: smart categories and genres that
        // no provider shelved — the 8.88/8.90 derivation keeps
        // covering pools whose providers don't curate by name.
        val fallback = (listOf(TAG_EMOTIONAL) +
            FeedCategories.SMART_CATEGORIES + genreTags)
            .distinct()
            .filter { it !in sectionPresent }
            .map { tag -> tag to itemsOf(tag).size }
            .filter { it.first !in hidden && it.second >= ROW_MIN_ITEMS }
            .sortedByDescending { it.second }
        for ((tag, _) in fallback) {
            rows += rowOf(tag)
        }
        // Dedupe across the variants of one region (priority:
        // Top > New Release > plain): when two variant rows are
        // ≥80% identical (of the smaller row), the lower-
        // priority one is dropped. Top vs New Release naturally
        // differ (rating order vs year order); pairs under 80%
        // are all allowed to stay.
        val byName = rows.associateBy { it.name }
        val regionVariants = listOf(
            listOf(TAG_TOP_ANIME, "Anime"),
            listOf(TAG_TOP_KDRAMA, TAG_NEW_KDRAMA, "K-Drama"),
            listOf(TAG_TOP_CDRAMA, TAG_NEW_CDRAMA, "C-Drama")
        )
        val drop = mutableSetOf<String>()
        regionVariants.forEach { variants ->
            for (higher in variants.indices) {
                for (lower in higher + 1 until variants.size) {
                    val highRow = byName[variants[higher]]
                    val lowRow = byName[variants[lower]]
                    if (highRow == null || lowRow == null) continue
                    if (variants[lower] in drop) continue
                    val highUrls = highRow.items
                        .map { it.response.url }.toSet()
                    val lowUrls = lowRow.items
                        .map { it.response.url }.toSet()
                    val overlap = (highUrls intersect lowUrls).size
                    val smaller = minOf(highUrls.size, lowUrls.size)
                    if (smaller > 0 &&
                        overlap.toDouble() / smaller >= 0.8
                    ) {
                        drop += variants[lower]
                    }
                }
            }
        }
        val tagRows = rows.filter { it.name !in drop }.take(ROW_CAP)
        // Phase 8.92: per-provider rows close the list — every
        // provider with enough pool items gets a row under its
        // own name, so content no tag claimed still appears in
        // a row (his "নীচে গেলে অনেক কন্টেন্ট ক্যাটাগরি ছাড়া
        // আছে"). They are not tags: the Feed Categories
        // settings page does not govern them. Phase 8.96:
        // their View All opens the full category page like
        // every other row (the Source-filter route is gone).
        val providerGroups = poolSnap.groupBy { it.apiName }
            .filterValues { it.size >= PROVIDER_ROW_MIN_ITEMS }
            .entries.sortedByDescending { it.value.size }
            .take(PROVIDER_ROW_CAP)
        val providerRows = providerGroups.map { (api, items) ->
            FeedCategoryRowsAdapter.CategoryRow(
                PROVIDER_TAG_PREFIX + api, api,
                items.take(ROW_ITEM_CAP)
            )
        }
        // Phase 8.96: the rows' full uncapped lists, for the
        // category page (FeedCategorySession) — the same memo
        // the rows were cut from, so the page can never
        // disagree with its row.
        val fullLists = mutableMapOf<String, List<FeedEntry>>()
        tagRows.forEach { fullLists[it.name] = itemsOf(it.name) }
        providerGroups.forEach { (api, items) ->
            fullLists[PROVIDER_TAG_PREFIX + api] = items
        }
        return ChromeResult(
            tagRows + providerRows,
            computePresentTags(
                poolSnap, appCtx, { t -> itemsOf(t) }, hidden, ratingsSnap
            ),
            fullLists
        )
    }

    /** The tag the row should show as selected: the category
     *  filter wins, then the genre filter, else All. */
    private fun effectiveTabSelection(): String =
        filterCategory ?: filterGenre ?: "All"

    /**
     * The tags actually present in the pool, in chip order —
     * Phase 8.91: Trending, Top 250, the curated tags, then the
     * PROVIDER-SECTION tags in the user's Feed Categories order,
     * then the metadata smart categories / Emotional / genres
     * not already covered. Same vocabulary as the category
     * rows; a tag with no pool items never gets a chip, and a
     * tag the user hid on the settings page gets none either.
     * Curated tags keep their PLAIN names here (the emoji live
     * in the row titles only).
     */
    private fun computePresentTags(
        poolSnap: List<FeedEntry>,
        appCtx: Context,
        itemsOf: (String) -> List<FeedEntry>,
        hidden: Set<String>,
        ratingsSnap: Map<String, String>
    ): List<String> {
        if (poolSnap.isEmpty()) return emptyList()
        val tags = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun add(tag: String) {
            if (tag !in hidden && seen.add(tag)) tags += tag
        }
        add(TAG_TRENDING)
        if (poolSnap.any { scoreOf(it, ratingsSnap) != null }) {
            add(TAG_TOP_RATED)
        }
        CURATED_TAGS.forEach { tag ->
            if (itemsOf(tag).isNotEmpty()) add(tag)
        }
        orderedSectionTags(poolSnap, appCtx, itemsOf).forEach { tag ->
            if (itemsOf(tag).isNotEmpty()) add(tag)
        }
        FeedCategories.SMART_CATEGORIES.forEach { cat ->
            if (poolSnap.any { cat in categoriesOfEntry(it) }) add(cat)
        }
        if (poolSnap.any { emotionalMatchesEntry(it) }) {
            add(TAG_EMOTIONAL)
        }
        genreTags.forEach { genre ->
            if (poolSnap.any { genreMatchesEntry(it, genre) }) add(genre)
        }
        return tags
    }

    /**
     * (Re)build the header tag row from the pool. Rebuilt only
     * when the present-tag set changes (pool loads, metadata
     * landings); otherwise just the styles refresh, so the
     * row's scroll position survives.
     */
    private fun buildCategoryTabs(
        binding: FragmentFeedBinding,
        names: List<String>
    ) {
        // The active filter's tag stays listed even if a pool
        // change dropped it below presence, so it can be
        // tapped off again.
        val selected = effectiveTabSelection()
        val full = if (selected != "All" && selected !in names) {
            names + selected
        } else {
            names
        }
        if (full == currentTabNames &&
            binding.feedChips.childCount == full.size
        ) {
            refreshTabStyles(binding)
            return
        }
        currentTabNames = full
        binding.feedChips.removeAllViews()
        val context = binding.feedChips.context
        full.forEach { tag ->
            val chip = TextView(context).apply {
                text = tag
                textSize = 13f
                setPadding(30, 14, 30, 14)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 20 }
                setOnClickListener { selectTab(binding, tag) }
            }
            chip.tag = tag
            binding.feedChips.addView(chip)
        }
        refreshTabStyles(binding)
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        val context = chip.context
        if (selected) {
            chip.setBackgroundResource(R.drawable.feed_chip_selected)
            chip.setTextColor(ContextCompat.getColor(context, android.R.color.black))
        } else {
            chip.setBackgroundResource(R.drawable.feed_search_bg)
            chip.setTextColor(ContextCompat.getColor(context, android.R.color.white))
        }
    }

    /**
     * Phase 8.90: a tag tap filters the grid through the one
     * filter path (applyFeedFilters via applySearch) — genres
     * land in the genre slot, every other tag in the category
     * slot; tapping the selected tag again clears it back to
     * All. (Pre-8.90 the chips typed the tag into the search
     * field and title-searched it.)
     */
    private fun selectTab(binding: FragmentFeedBinding, tag: String) {
        applyCategoryTab(tag, scrollToGrid = false)
    }

    private fun applyCategoryTab(tag: String, scrollToGrid: Boolean) {
        val b = binding ?: return
        // Phase 8.92: a provider row's View All landed in the
        // Source filter slot. Phase 8.96: rows open the full
        // category page instead (openCategoryPage), so this
        // branch is unreachable from the rows now — kept for
        // the chip path's completeness (chips never carry the
        // provider: prefix).
        if (tag.startsWith(PROVIDER_TAG_PREFIX)) {
            val api = tag.removePrefix(PROVIDER_TAG_PREFIX)
            val already = filterSource == api &&
                filterCategory == null && filterGenre == null
            filterSource = if (already) null else api
            if (!already) {
                filterCategory = null
                filterGenre = null
            }
            refreshTabStyles(b)
            updateFilterButtonState()
            if (filtersActive) hideCategoryRows() else scheduleChromeUpdate()
            applySearch(searchQuery)
            if (scrollToGrid) {
                b.feedRecycler.post { b.feedRecycler.scrollToPosition(0) }
            }
            return
        }
        val target = if (tag == effectiveTabSelection()) "All" else tag
        when {
            target == "All" -> {
                filterCategory = null
                filterGenre = null
            }
            target in genreTags -> {
                filterGenre = target
                filterCategory = null
            }
            else -> {
                filterCategory = target
                filterGenre = null
            }
        }
        refreshTabStyles(b)
        updateFilterButtonState()
        // Rows belong to browsing: a live filter hides them
        // synchronously; clearing back to All rebuilds them in
        // the background pass.
        if (filtersActive || searchQuery.isNotEmpty()) {
            hideCategoryRows()
        } else {
            scheduleChromeUpdate()
        }
        applySearch(searchQuery)
        if (scrollToGrid) {
            // The filter just hid the rows, so the grid's first
            // card sits at the very top now.
            b.feedRecycler.post {
                b.feedRecycler.scrollToPosition(0)
            }
        }
    }

    private fun setupHeaderCollapse(binding: FragmentFeedBinding) {
        binding.feedHeader.post {
            headerFullHeight = binding.feedHeader.height
            // The header floats over the grid: content starts
            // below it.
            updateHeaderPadding(binding)
        }
        // Phase 8.97: keep the reserve honest for the header's
        // whole life, not just at setup. The one-shot post{}
        // measure above (and the animator's re-measure-if-zero)
        // could leave headerFullHeight describing a header
        // that no longer exists — the chips row rebuilds when
        // a chrome pass posts, and any height drift between
        // the measured value and the rendered header shows up
        // as dead space (or overlap) between the header's
        // bottom edge and the first list item. A layout
        // listener tracks the real height; updateHeaderPadding
        // itself is change-only (see below), so this costs
        // nothing when nothing changed.
        binding.feedHeader.addOnLayoutChangeListener {
                _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height > 0 && height != headerFullHeight &&
                height != oldBottom - oldTop
            ) {
                headerFullHeight = height
                updateHeaderPadding(binding)
            }
        }
        binding.feedRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                recyclerView: RecyclerView, newState: Int
            ) {
                // Phase 8.92: card binds stop kicking background
                // metadata/rating resolutions while the grid is
                // moving — the resolver storm competed with fling
                // rendering on the 8.1 phone (hang report). Cached
                // values still render; resolution resumes at idle.
                FeedAdapter.resolutionsPaused =
                    newState != RecyclerView.SCROLL_STATE_IDLE
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 12) {
                    animateHeader(binding, show = false)
                } else if (dy < -12) {
                    animateHeader(binding, show = true)
                }
                // Phase 8.32: endless scroll — near the end, pull
                // the providers' next homepage page into the pool.
                if (dy > 0) {
                    val lm = recyclerView.layoutManager as? GridLayoutManager
                    val total = recyclerView.adapter?.itemCount ?: 0
                    if (lm != null && total > 0 &&
                        lm.findLastVisibleItemPosition() >= total - 8
                    ) {
                        viewModel.loadMore()
                    }
                }
            }
        })
    }

    private fun animateHeader(binding: FragmentFeedBinding, show: Boolean) {
        // Phase 8.56: the height was measured once in a post{} at
        // setup; if that ran before layout it stayed 0 forever
        // and auto-hide silently never worked (his report).
        // Re-measure lazily whenever it is missing.
        if (headerFullHeight <= 0) {
            headerFullHeight = binding.feedHeader.height
            if (headerFullHeight <= 0) return
        }
        if (show == headerShown) return
        headerShown = show
        val header = binding.feedHeader
        // Slide, don't resize: animating the header's height forced a
        // full relayout of the grid on every frame — the scroll
        // jitter (কাপাকাপি) reported in Phase 8.23.
        val from = header.translationY
        val to = if (show) 0f else -headerFullHeight.toFloat()
        headerAnimator?.cancel()
        headerAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 180L
            addUpdateListener { animator ->
                header.translationY = animator.animatedValue as Float
                // Phase 8.96: the grid's top padding tracks the
                // header's live position (see
                // updateHeaderPadding) — content stays glued
                // to the header's bottom edge while it slides,
                // and no reserved band is left behind once it
                // is gone.
                updateHeaderPadding(binding)
            }
            start()
        }
    }

    // ---------- skeleton ----------

    private fun showSkeleton(binding: FragmentFeedBinding) {
        binding.feedRecycler.isVisible = false
        binding.feedSkeleton.isVisible = true
        binding.feedSkeleton.startShimmer()
    }

    private fun hideSkeleton(binding: FragmentFeedBinding) {
        binding.feedSkeleton.stopShimmer()
        binding.feedSkeleton.isVisible = false
        binding.feedRecycler.isVisible = true
    }

    // ---------- mini player ----------

    private fun setupMiniBar(binding: FragmentFeedBinding) {
        val mini = binding.feedMini
        FeedPlayer.warmUp(requireContext())
        mini.feedMiniPlayerView.player = FeedPlayer.exo
        mini.feedMiniPlayPause.setOnClickListener { FeedPlayer.togglePlay() }
        mini.feedMiniClose.setOnClickListener { FeedPlayer.stop() }

        var downY = 0f
        val miniTouch = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = event.rawY
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downY
                    when {
                        dy < -70f -> expandMiniPlayer()
                        dy > 70f -> FeedPlayer.stop()
                        else -> expandMiniPlayer()
                    }
                }
            }
            true
        }
        mini.feedMiniRoot.setOnTouchListener(miniTouch)
        // The video surface would otherwise swallow taps meant for the bar.
        mini.feedMiniPlayerView.setOnTouchListener(miniTouch)
    }

    private fun expandMiniPlayer() {
        val url = FeedPlayer.currentUrl ?: return
        FeedPlayer.minimized = false
        activity.navigate(
            R.id.navigation_feed_watch,
            Bundle().apply {
                putString("url", url)
                putString("apiName", FeedPlayer.currentApiName)
                putString("name", FeedPlayer.currentName)
                putString("posterUrl", FeedPlayer.currentPoster)
            }
        )
        updateMiniBar()
    }

    private fun updateMiniBar() {
        val binding = binding ?: return
        val mini = binding.feedMini
        val show = FeedPlayer.hasActiveItem && FeedPlayer.minimized
        mini.feedMiniRoot.isVisible = show
        if (show) {
            if (mini.feedMiniPlayerView.player == null) {
                mini.feedMiniPlayerView.player = FeedPlayer.exo
            }
            mini.feedMiniTitle.text =
                cleanPosterTitle(FeedPlayer.currentName ?: "")
            mini.feedMiniPlayPause.setImageResource(
                if (FeedPlayer.isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
            )
        }
    }

    // ---------- search ----------

    /**
     * Instant part: filter the loaded pool by title. Deep part (3+
     * chars, debounced): search all providers and merge new hits in.
     */
    private fun applySearch(query: String) {
        val binding = binding ?: return
        refreshTabStyles(binding)
        updateHeaderPadding(binding)
        // Phase 8.92: while a query is live the category rows
        // hide (they belong to browsing) — synchronously, so
        // they never linger over search results.
        if (query.isNotEmpty()) hideCategoryRows()
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchJob?.cancel()
        if (query.isEmpty()) {
            // Back to the full pool. Phase 8.85: the advanced
            // Filters still apply on top. Phase 8.90: the tag
            // selection IS a filter now (it no longer resets
            // here — the chips used to be driven by this text).
            binding.feedLoading.isVisible = false
            val filtered = applyFeedFilters(pool)
            binding.setFeedEmpty( filtered.isEmpty() )
            submitFeedList(filtered)
            return
        }
        val local = applyFeedFilters(pool.filter {
            it.response.name.contains(query, ignoreCase = true)
        })
        // Phase 8.56: the empty page used to appear the moment
        // LOCAL hits were zero — over the still-loading skeleton —
        // while the provider search was still running (his
        // screenshot). Empty is only true once every source has
        // answered; while a remote search is pending, the spinner
        // shows instead.
        if (local.isNotEmpty()) {
            binding.feedLoading.isVisible = false
            binding.setFeedEmpty( false )
            submitFeedList(local)
        } else if (query.length >= 3) {
            binding.setFeedEmpty( false )
            binding.feedLoading.isVisible = true
            submitFeedList(local)
        } else {
            binding.feedLoading.isVisible = false
            binding.setFeedEmpty( !viewModel.isBuilding )
            submitFeedList(local)
            return
        }
        if (query.length < 3) return

        val task = Runnable {
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                val remote = viewModel.searchAll(query)
                // Drop stale results if the query moved on meanwhile.
                if (searchQuery != query) {
                    return@launch
                }
                val seen = local.map { it.response.url }.toMutableSet()
                val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
                val merged = applyFeedFilters(local + remote.filter {
                    it.response.url !in hidden && seen.add(it.response.url)
                })
                binding.feedLoading.isVisible = false
                binding.setFeedEmpty( merged.isEmpty() )
                submitFeedList(merged)
            }
        }
        pendingSearch = task
        searchHandler.postDelayed(task, 600L)
    }

    // ---------- Phase 8.85: top bar, hero, filters ----------

    private fun setupTopBar(binding: FragmentFeedBinding) {
        binding.feedFilterButton.setOnClickListener { showFeedFilters() }
        binding.feedSearchButton.setOnClickListener {
            // Phase 8.94 (Job 3) — SETTLED behaviour, his final
            // word 2026-10-30 after the 8.90/8.92 flip-flop: the
            // button pushes a Search page (navigation_feed_search)
            // OVER the Feed on the back stack. Not a bottom-nav
            // tab switch (the Feed stays the selected destination
            // underneath; the nav bar hides while the page is
            // open, exactly like the watch page) and not the 8.92
            // in-feed expanding row (removed). Back pops straight
            // back to the Feed, whose grid scroll state is
            // saved/restored across the push by onDestroyView /
            // onBindingCreated (listState), the same mechanism
            // the watch page relies on.
            // Phase 8.96 (Job 1): the page is now the FEED-scoped
            // search (FeedSearchFragment) — the 8.94 page reused
            // the MAIN SearchFragment (global search), and his
            // correction (2026-10-30 11:15) settled that feed
            // search must work on the Feed itself: pool first,
            // feed grid, feed watch screen.
            activity?.navigate(
                R.id.action_navigation_feed_to_navigation_feed_search
            )
        }
        updateFilterButtonState()
    }

    /** Phase 8.94 (Job 3): the in-feed search row is gone, so
     *  resetting "search" is just dropping the query state and
     *  restoring the unfiltered feed — used by the empty-state
     *  reset and pull-to-refresh. */
    private fun clearFeedQuery() {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            scheduleChromeUpdate()
            applySearch("")
        }
    }

    /**
     * Reserves the floating header's space in the grid's top
     * padding (and mirrors it onto the skeleton + refresh
     * ring). Phase 8.92: what is left of the old hero chrome
     * function — the hero itself is gone.
     * Phase 8.96 (scroll-gap fix): the reserve is the header's
     * VISIBLE height — full height plus its (negative)
     * translationY. The old formula always reserved the full
     * height, so when auto-hide slid the header away, the
     * reserved band stayed behind as a large empty gap above
     * the first row whenever the list rested near the top
     * (his circled screenshot: a ~header-tall black band over
     * the Trending row). The animator now re-runs this on
     * every frame, so the padding follows the header exactly;
     * fully hidden, the reserve collapses to the plain 6dp.
     * Phase 8.97: the application is CHANGE-ONLY. View
     * .setPadding calls requestLayout() unconditionally —
     * it does not check whether the value changed — and
     * this function is called from the header animator's
     * every frame AND from applySearch, which the debounced
     * metadata runnable re-runs on every resolver burst
     * while a filter is live. During the load window that
     * was a stream of full RecyclerView layout passes for
     * a padding value that had not moved (a proven piece
     * of the load-window ANR, his 12:09 report). The
     * refresh-ring offset only depends on the full height,
     * so it is set when that changes, not per frame.
     */
    private var lastHeaderPad = -1
    private var lastProgressOffset = -1

    private fun updateHeaderPadding(binding: FragmentFeedBinding) {
        if (headerFullHeight > 0) {
            val topPad = (headerFullHeight +
                binding.feedHeader.translationY).toInt()
                .coerceAtLeast(0) + dp(6)
            if (topPad != lastHeaderPad) {
                lastHeaderPad = topPad
                binding.feedRecycler.setPadding(
                    binding.feedRecycler.paddingLeft, topPad,
                    binding.feedRecycler.paddingRight,
                    binding.feedRecycler.paddingBottom
                )
                binding.feedSkeleton.setPadding(
                    binding.feedSkeleton.paddingLeft, topPad,
                    binding.feedSkeleton.paddingRight,
                    binding.feedSkeleton.paddingBottom
                )
            }
            val progressOffset = headerFullHeight + dp(10)
            if (progressOffset != lastProgressOffset) {
                lastProgressOffset = progressOffset
                binding.feedSwipeRefresh.setProgressViewOffset(
                    false, 0, progressOffset
                )
            }
        }
    }

    // ---------- Phase 8.85: advanced filters ----------

    /** The item's score for the Rating tab: its own provider score
     *  first, then the resolved universal rating (fragment cache,
     *  then MetadataResolver's cache). Null = no score known.
     *  [ratingsSnap] lets the background chrome pass read a
     *  consistent snapshot instead of the live map. */
    private fun scoreOf(
        entry: FeedEntry,
        ratingsSnap: Map<String, String> = fragmentRatings
    ): Double? {
        entry.response.score?.toDouble(10)?.let {
            if (it > 0.0) return it
        }
        val key = MetadataResolver.keyFor(entry.response)
        val text = ratingsSnap[key]
            ?: context?.let { MetadataResolver.cachedFor(it, entry.response) }
        return text?.toDoubleOrNull()
    }

    /**
     * Phase 8.98: the ONE year source for the Filters Year tab
     * AND the year filter — they must never disagree (the 8.96
     * lesson: a row/tab built from one rule and a filter built
     * from another shows options that select to nothing).
     * Order: the response's own year field, else a year carried
     * in the title itself. Until now both sides read ONLY
     * [MetadataResolver.yearOf] (the response field), which his
     * providers leave null on homepage items — the tab showed
     * nothing but "All" (his screenshot) even though the pool
     * was full of dated titles.
     */
    private fun feedYearOf(entry: FeedEntry): Int? {
        MetadataResolver.yearOf(entry.response)?.let { return it }
        return titleYearOf(entry.response.name)
    }

    /** A year embedded in a title: "(2025)" first, else a bare
     *  19xx/20xx word. Sanity-banded to 1950..next year so
     *  stray numbers (resolutions, episode counts) stay out. */
    private fun titleYearOf(name: String): Int? {
        val maxYear = java.util.Calendar.getInstance()
            .get(java.util.Calendar.YEAR) + 1
        val paren = Regex("""\((\d{4})\)""").find(name)
            ?.groupValues?.get(1)?.toIntOrNull()
        val candidate = paren
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(name)
                ?.groupValues?.get(1)?.toIntOrNull()
        return candidate?.takeIf { it in 1950..maxYear }
    }

    private fun applyFeedFilters(list: List<FeedEntry>): List<FeedEntry> {
        var out = list
        // Phase 8.88: a tapped category tile filters through
        // this same path, ahead of the sheet's filters.
        // Phase 8.90: the tag can also be a special one
        // (Trending / Top 250 / Emotional) — categoryMatchesEntry
        // is the one rule for all of them.
        filterCategory?.let { category ->
            // Phase 8.92: curated/special tags match by computed
            // list membership — build the URL set ONCE. The old
            // per-entry categoryMatchesEntry scanned the whole
            // tag list for every pool entry: O(pool²) on the
            // main thread (another hang contributor).
            out = if (category in CURATED_MATCH_TAGS) {
                val allowed = tagItems(category)
                    .mapTo(HashSet()) { it.response.url }
                out.filter { it.response.url in allowed }
            } else {
                out.filter { categoryMatchesEntry(it, category) }
            }
        }
        filterGenre?.let { genre ->
            // Phase 8.96: the genre filter must use the SAME
            // membership rule as the genre ROWS (tagPredicate:
            // provider section tags ∪ resolved genres ∪ title
            // keywords). It previously used genreMatchesEntry
            // alone — an item shelved by its provider under a
            // genre shelf ("Animation", "Comedy", …) whose title
            // carries no genre keyword and which has no resolved
            // metadata appeared in the row but filtered to an
            // EMPTY grid (his "ক্যাটাগরির মধ্যে কিছুই থাকে না").
            // categoryMatchesEntry IS tagPredicate for
            // non-curated tags, so filter ≡ row by construction.
            out = out.filter {
                categoryMatchesEntry(it, genre)
            }
        }
        filterYear?.let { year ->
            // Phase 8.98: same source as the Year tab
            // (feedYearOf) — response year or title year.
            out = out.filter { feedYearOf(it) == year }
        }
        filterRatingMin?.let { min ->
            out = out.filter { scoreOf(it)?.let { s -> s >= min } == true }
        }
        filterSource?.let { source ->
            out = out.filter { it.apiName == source }
        }
        return out
    }

    private fun clearFilters() {
        filterGenre = null
        filterYear = null
        filterRatingMin = null
        filterSource = null
        filterCategory = null
        updateFilterButtonState()
    }

    private fun updateFilterButtonState() {
        val b = binding ?: return
        val ctx = context ?: return
        b.feedFilterButton.imageTintList = ContextCompat.getColorStateList(
            ctx, if (filtersActive) R.color.colorPrimaryRed else R.color.white
        )
    }

    /**
     * The Filters sheet (SkyStream Explore reference): Genre /
     * Year / Rating / Source tabs over the options actually present
     * in the loaded feed, applied together by Done. The reference's
     * 4th tab is Lang — feed items carry no reliable content
     * language, so the 4th tab is the item's provider (Source).
     * Genre matching is the union rule (genreMatchesEntry):
     * resolved metadata genres first, title keywords as the
     * fallback; Year reads feedYearOf (the response's year
     * field, else a year embedded in the title — Phase 8.98);
     * Rating reads the provider score / resolved rating.
     */
    private fun showFeedFilters() {
        val ctx = context ?: return
        var selGenre = filterGenre
        var selYear = filterYear
        var selRating = filterRatingMin
        var selSource = filterSource

        val dialog = BottomSheetDialog(ctx, R.style.AppBottomSheetDialogTheme)

        fun styleChips(container: ViewGroup, selected: Any?) {
            for (i in 0 until container.childCount) {
                val chip = container.getChildAt(i) as? TextView ?: continue
                chip.setBackgroundResource(
                    if (chip.tag == selected) R.drawable.bg_feed_filter_selected
                    else R.drawable.feed_search_bg
                )
            }
        }

        fun styleRows(container: ViewGroup, selected: Any?) {
            for (i in 0 until container.childCount) {
                val row = container.getChildAt(i) as? ViewGroup ?: continue
                val isSel = row.tag == selected
                row.setBackgroundResource(
                    if (isSel) R.drawable.bg_feed_filter_selected
                    else R.drawable.feed_search_bg
                )
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    if (child.tag == "check") child.isVisible = isSel
                }
            }
        }

        fun makeChip(text: String, tag: Any): TextView {
            return TextView(ctx).apply {
                this.text = text
                this.tag = tag
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(11), dp(8), dp(11))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
        }

        fun makeRow(
            tag: Any, title: String, subtitle: String?, goldStar: Boolean?
        ): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                this.tag = tag
                isClickable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            if (goldStar != null) {
                row.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_baseline_star_24)
                    imageTintList = ContextCompat.getColorStateList(
                        ctx,
                        if (goldStar) R.color.ratingStarGold else R.color.white
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = if (goldStar != null) dp(12) else 0 }
            }
            texts.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
            })
            if (subtitle != null) {
                texts.addView(TextView(ctx).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.rgb(158, 158, 158))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(2) }
                })
            }
            row.addView(texts)
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_baseline_check_24)
                imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
                this.tag = "check"
                isVisible = false
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
            return row
        }

        fun buildGenreTab(): View {
            val grid = GridLayout(ctx).apply {
                columnCount = 2
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // Phase 8.88: the tab lists the FULL genre vocabulary
            // like the chip row does — it used to list only
            // keyword-"present" genres, which with real-world
            // titles (they rarely contain genre words) left the
            // tab showing nothing but "All". Presence still
            // drives the dimming (Phase 8.36 rule), now computed
            // from resolved metadata first, keywords as fallback
            // (genreMatchesEntry's union).
            (listOf("") + genreTags).forEach { genre ->
                val chip = makeChip(
                    if (genre.isEmpty()) getString(R.string.all) else genre,
                    genre
                )
                chip.setOnClickListener {
                    selGenre = genre.ifEmpty { null }
                    styleChips(grid, selGenre ?: "")
                }
                grid.addView(chip)
            }
            styleChips(grid, selGenre ?: "")
            for (i in 0 until grid.childCount) {
                val chip = grid.getChildAt(i) as? TextView ?: continue
                val tag = chip.tag as? String
                chip.alpha = if (tag.isNullOrEmpty() || pool.any {
                        genreMatchesEntry(it, tag)
                    }
                ) {
                    1f
                } else {
                    0.45f
                }
            }
            return grid
        }

        fun buildYearTab(): View {
            val grid = GridLayout(ctx).apply {
                columnCount = 3
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // Phase 8.98: years from feedYearOf (response year
            // or the title's own year — see its doc), newest
            // first, capped at 24 chips; the sheet's scroll
            // area takes the rest of the sheet either way, and
            // pool years cluster on the recent end in practice.
            val years = pool.mapNotNull { feedYearOf(it) }
                .distinct().sortedDescending().take(24)
            (listOf(-1) + years).forEach { year ->
                val chip = makeChip(
                    if (year == -1) getString(R.string.all) else year.toString(),
                    year
                )
                chip.setOnClickListener {
                    selYear = year.takeIf { it != -1 }
                    styleChips(grid, selYear ?: -1)
                }
                grid.addView(chip)
            }
            styleChips(grid, selYear ?: -1)
            return grid
        }

        fun buildRatingTab(): View {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            listOf(-1.0, 5.0, 6.0, 7.0, 8.0).forEach { min ->
                val label = String.format(java.util.Locale.US, "%.1f", min)
                val row = makeRow(
                    tag = min,
                    title = if (min < 0) {
                        getString(R.string.feed_filter_any_rating)
                    } else {
                        getString(R.string.feed_filter_stars, label)
                    },
                    subtitle = if (min < 0) {
                        getString(R.string.feed_filter_any_sub)
                    } else {
                        getString(R.string.feed_filter_rating_sub, label)
                    },
                    goldStar = min >= 0
                )
                row.setOnClickListener {
                    selRating = min.takeIf { it >= 0 }
                    styleRows(col, selRating ?: -1.0)
                }
                col.addView(row)
            }
            styleRows(col, selRating ?: -1.0)
            return col
        }

        fun buildSourceTab(): View {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val sources = pool.map { it.apiName }.distinct().sorted()
            (listOf("") + sources).forEach { source ->
                val row = makeRow(
                    tag = source,
                    title = if (source.isEmpty()) {
                        getString(R.string.all)
                    } else {
                        source
                    },
                    subtitle = null,
                    goldStar = null
                )
                row.setOnClickListener {
                    selSource = source.ifEmpty { null }
                    styleRows(col, selSource ?: "")
                }
                col.addView(row)
            }
            styleRows(col, selSource ?: "")
            return col
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_dialog)
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        root.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.bg_feed_drag_handle)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        })
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_tune_24)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        titleRow.addView(TextView(ctx).apply {
            text = getString(R.string.feed_filters)
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(
                android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.BOLD
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(10) }
        })
        titleRow.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_baseline_close_24)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
            setBackgroundResource(R.drawable.bg_feed_circle_button)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(titleRow)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
            ).apply { topMargin = dp(14) }
        }

        var currentTab = 0
        val tabTexts = mutableListOf<TextView>()
        val tabLines = mutableListOf<View>()
        var renderTab: () -> Unit = {}
        renderTab = {
            tabTexts.forEachIndexed { i, tv ->
                tv.setTextColor(
                    if (i == currentTab) Color.WHITE
                    else Color.rgb(158, 158, 158)
                )
            }
            tabLines.forEachIndexed { i, line ->
                line.setBackgroundColor(
                    if (i == currentTab) {
                        ContextCompat.getColor(ctx, R.color.colorPrimaryRed)
                    } else {
                        Color.TRANSPARENT
                    }
                )
            }
            val content = when (currentTab) {
                0 -> buildGenreTab()
                1 -> buildYearTab()
                2 -> buildRatingTab()
                else -> buildSourceTab()
            }
            scroll.removeAllViews()
            scroll.addView(content)
            scroll.scrollTo(0, 0)
        }

        val tabLabels = listOf(
            getString(R.string.feed_filter_genre),
            getString(R.string.year),
            getString(R.string.rating),
            getString(R.string.feed_filter_source)
        )
        val tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        tabLabels.indices.forEach { i ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    currentTab = i
                    renderTab()
                }
            }
            val tv = TextView(ctx).apply {
                text = tabLabels[i]
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
            val line = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(3))
            }
            cell.addView(tv)
            cell.addView(line)
            tabTexts += tv
            tabLines += line
            tabsRow.addView(cell)
        }
        root.addView(tabsRow)
        root.addView(scroll)

        root.addView(MaterialButton(ctx).apply {
            text = getString(R.string.feed_filter_done)
            setTextColor(Color.WHITE)
            backgroundTintList = ContextCompat.getColorStateList(
                ctx, R.color.colorPrimaryRed
            )
            cornerRadius = dp(26)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                filterGenre = selGenre
                filterYear = selYear
                filterRatingMin = selRating
                filterSource = selSource
                dialog.dismiss()
                updateFilterButtonState()
                binding?.let { refreshTabStyles(it) }
                // Phase 8.92: rows follow the filter state —
                // hidden while any filter is live, rebuilt by
                // the background pass when the sheet clears all.
                if (filtersActive) {
                    hideCategoryRows()
                } else {
                    scheduleChromeUpdate()
                }
                applySearch(searchQuery)
            }
        })

        dialog.setContentView(root)
        dialog.show()
        // The dialog's own sheet frame stays transparent — the
        // sheet root's rounded_dialog background is the only surface
        // (the 8.82 recipe).
        dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(Color.TRANSPARENT)
        renderTab()
    }

    private fun openWatch(entry: FeedEntry) {
        activity.navigate(
            R.id.navigation_feed_watch,
            Bundle().apply {
                putString("url", entry.response.url)
                putString("apiName", entry.apiName)
                putString("name", entry.response.name)
                putString("posterUrl", entry.response.posterUrl)
            }
        )
    }

    /** Phase 8.96 (Job 3): a category row's View All opens the
     *  full category page with the row's complete list (from
     *  FeedCategorySession — the very list the row was cut
     *  from). The page's title is the row's own title; provider
     *  rows show the bare provider name. */
    private fun openCategoryPage(name: String) {
        val title = if (name.startsWith(PROVIDER_TAG_PREFIX)) {
            name.removePrefix(PROVIDER_TAG_PREFIX)
        } else {
            displayTitleFor(name)
        }
        activity.navigate(
            R.id.action_navigation_feed_to_navigation_feed_category,
            Bundle().apply {
                putString(FeedCategoryFragment.ARG_TAG, name)
                putString(FeedCategoryFragment.ARG_TITLE, title)
            }
        )
    }
}
