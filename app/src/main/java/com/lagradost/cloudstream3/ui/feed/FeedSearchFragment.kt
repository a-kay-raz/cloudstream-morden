package com.lagradost.cloudstream3.ui.feed

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedSearchBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase 8.96 (Job 1) — the FEED-scoped search page, pushed over
 * the Feed by its header search button. His settled word
 * (2026-10-30 11:15): "feed search feed এ কাজ করবে" — search
 * on the Feed must work ON THE FEED. The 8.94 page reused the
 * main SearchFragment (global provider-section search); he
 * rejected that, so this page is feed-native:
 *
 * - The primary source is the loaded feed pool (FeedPool),
 *   filtered and ranked locally by match quality — title
 *   prefix, then word-start, then plain contains (the old
 *   in-feed search was contains-only; ranking is the only
 *   addition). Hits render in the Feed's own 3-column card
 *   grid (the same FeedAdapter) and open the feed watch
 *   screen, exactly like a grid tap on the Feed itself.
 * - Deep results keep the pre-8.92 in-feed behaviour: for
 *   queries of 3+ characters the providers are searched too
 *   (FeedViewModel.searchAll, the same call the old in-feed
 *   search merged in), and their hits append below the pool
 *   hits in the same grid — they open the watch screen the
 *   same way, as the pre-8.92 merged results did.
 * - The landing (empty query) is trending chips from the
 *   pool's own top titles (the smart Search landing's pool
 *   recipe). There are no feed-search recents to show: the
 *   feed search has never kept a history store (verified —
 *   no prefs key exists for it), so none is invented here.
 *
 * The main Search tab and the SEARCH_QUERY / filmography path
 * are untouched — they target the navigation_search
 * destination, never this one.
 */
@UnstableApi
class FeedSearchFragment : BaseFragment<FragmentFeedSearchBinding>(
    BindingCreator.Inflate(FragmentFeedSearchBinding::inflate)
) {
    /** Only searchAll is used from this fragment's own
     *  instance — it is stateless (reads the global provider
     *  list via APIRepository), so it serves the deep search
     *  without touching the Feed tab's ViewModel or its pool
     *  build. */
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

    private val handler = Handler(Looper.getMainLooper())
    private var pendingDeep: Runnable? = null
    private var deepJob: Job? = null
    private var currentQuery: String = ""

    /** Batch 2: a resolved title can turn a previously
     *  unmatchable pool entry into a hit (or a new trending
     *  chip) — re-run the current search, or rebuild the
     *  landing chips when no query is typed. The adapter
     *  override refreshes the visible card title itself. */
    private val searchTitleListener: (String, String) -> Unit = { url, title ->
        adapter.setTitleOverride(url, title)
        if (currentQuery.isEmpty()) {
            binding?.let { buildTrendingChips(it) }
        } else {
            runSearch(currentQuery)
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentFeedSearchBinding) {
        binding.feedSearchRecycler.layoutManager = GridLayoutManager(context, 3)
        binding.feedSearchRecycler.adapter = adapter
        binding.feedSearchRecycler.itemAnimator = null

        binding.feedSearchBack.setOnClickListener {
            findNavController().popBackStack()
        }
        binding.feedSearchClear.setOnClickListener {
            binding.feedSearchInput.text?.clear()
        }
        binding.feedSearchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                binding.feedSearchClear.isVisible = query.isNotEmpty()
                runSearch(query)
            }

            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) = Unit
        })
        binding.feedSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                activity?.hideKeyboard()
                true
            } else {
                false
            }
        }

        buildTrendingChips(binding)
        TitleResolver.addListener(searchTitleListener)
        // Focus the field so the page opens ready to type.
        binding.feedSearchInput.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Batch 2 (mirrors SearchFragment.refreshTrendingChips
        // on onResume): the pool may have finished building
        // since this page was opened, so rebuild the chips
        // instead of showing a blank landing all visit.
        binding?.let { buildTrendingChips(it) }
    }

    override fun onDestroyView() {
        pendingDeep?.let { handler.removeCallbacks(it) }
        deepJob?.cancel()
        TitleResolver.removeListener(searchTitleListener)
        super.onDestroyView()
    }

    /** Trending chips from the pool's top titles — the smart
     *  Search landing's pool recipe (cleaned titles, deduped,
     *  id-like placeholders skipped, capped at 12). A tap runs
     *  that search on THIS page. */
    private fun buildTrendingChips(binding: FragmentFeedSearchBinding) {
        val ctx = context ?: return
        val group = binding.feedSearchTrending
        group.removeAllViews()
        val seen = mutableSetOf<String>()
        var count = 0
        for (entry in FeedPool.entries) {
            if (count >= 12) break
            val title = cleanPosterTitle(entry.response.name).trim()
            if (title.isEmpty() || TitleResolver.isIdLike(title)) continue
            if (!seen.add(title.lowercase())) continue
            count++
            group.addView(Chip(ctx).apply {
                text = title
                isCheckable = false
                isClickable = true
                chipBackgroundColor = ContextCompat.getColorStateList(
                    ctx, R.color.extButtonBackground
                )
                setTextColor(Color.WHITE)
                chipStrokeWidth = 0f
                setOnClickListener {
                    binding.feedSearchInput.setText(title)
                    binding.feedSearchInput.setSelection(title.length)
                }
            })
        }
        binding.feedSearchLanding.isVisible = count > 0 &&
            currentQuery.isEmpty()
    }

    /**
     * Local pass: rank the pool by match quality against
     * [query] — tier 0 title starts with the query, tier 1 a
     * word in the title starts with it, tier 2 the title
     * merely contains it; pool order survives inside a tier.
     * Deep pass (3+ chars, 600 ms debounce, the pre-8.92
     * timing): providers are searched and their new hits
     * append below the local ones.
     */
    private fun runSearch(query: String) {
        val binding = binding ?: return
        currentQuery = query
        pendingDeep?.let { handler.removeCallbacks(it) }
        deepJob?.cancel()
        if (query.isEmpty()) {
            binding.feedSearchLoading.isVisible = false
            binding.feedSearchEmpty.isVisible = false
            binding.feedSearchRecycler.isVisible = false
            binding.feedSearchLanding.isVisible =
                binding.feedSearchTrending.childCount > 0
            adapter.submitList(emptyList())
            return
        }
        binding.feedSearchLanding.isVisible = false
        binding.feedSearchRecycler.isVisible = true

        val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
        val poolNow = FeedPool.entries
            .filterNot { it.response.url in hidden }
        val local = rankMatches(poolNow, query)
        if (local.isNotEmpty()) {
            binding.feedSearchLoading.isVisible = false
            binding.feedSearchEmpty.isVisible = false
            adapter.submitList(local)
        } else if (query.length >= 3) {
            // Same rule as the old in-feed search: "empty" is
            // only true once every source has answered — while
            // the provider search is pending, the spinner shows.
            binding.feedSearchEmpty.isVisible = false
            binding.feedSearchLoading.isVisible = true
            adapter.submitList(local)
        } else {
            binding.feedSearchLoading.isVisible = false
            binding.feedSearchEmpty.isVisible = true
            adapter.submitList(local)
            return
        }
        if (query.length < 3) return

        val task = Runnable {
            deepJob = viewLifecycleOwner.lifecycleScope.launch {
                val remote = viewModel.searchAll(query)
                if (currentQuery != query) return@launch
                val b = binding ?: return@launch
                val seen = local.map { it.response.url }.toMutableSet()
                val merged = local + remote.filter {
                    it.response.url !in hidden && seen.add(it.response.url)
                }
                b.feedSearchLoading.isVisible = false
                b.feedSearchEmpty.isVisible = merged.isEmpty()
                adapter.submitList(merged)
            }
        }
        pendingDeep = task
        handler.postDelayed(task, 600L)
    }

    /** Match-quality tiers over the pool (see runSearch). */
    private fun rankMatches(
        pool: List<FeedEntry>,
        query: String
    ): List<FeedEntry> {
        val q = query.lowercase()
        fun tier(name: String): Int {
            val n = name.lowercase()
            return when {
                n.startsWith(q) -> 0
                n.split(WORD_SPLIT)
                    .any { it.startsWith(q) } -> 1
                n.contains(q) -> 2
                else -> -1
            }
        }
        val ctx = context
        return pool.mapNotNull { entry ->
            // Batch 2: match the title the card actually
            // shows — the resolved title when one landed, else
            // the card's display title — as well as the raw
            // provider name, and keep the best tier found.
            val candidates = listOfNotNull(
                entry.response.name,
                TitleResolver.cached(ctx, entry.response.url),
                FeedAdapter.displayTitle(entry.response.name, entry.response.url)
            )
            val t = candidates.mapNotNull { tier(it).takeIf { it >= 0 } }
                .minOrNull() ?: return@mapNotNull null
            entry to t
        }.sortedBy { it.second }.map { it.first }
    }

    /** Long-press a card → hide it for good (the Feed grid's
     *  own behaviour, mirrored so the page's cards act like
     *  the Feed's). */
    private fun confirmHide(entry: FeedEntry) {
        val context = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(FeedAdapter.displayTitle(entry.response.name, entry.response.url))
            .setMessage(R.string.feed_hide_message)
            .setPositiveButton(R.string.feed_hide) { _, _ ->
                FeedHidden.hide(context, entry.response.url)
                FeedPool.hide(entry.response.url)
                runSearch(currentQuery)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private companion object {
        val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    }
}
