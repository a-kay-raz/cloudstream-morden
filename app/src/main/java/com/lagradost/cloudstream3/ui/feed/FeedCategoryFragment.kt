package com.lagradost.cloudstream3.ui.feed

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedCategoryBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getFeedSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.launch

/**
 * Phase 8.96 (Job 3) — the full category page behind a Feed
 * row's "View All" (his design: a row loads only its 20 items;
 * the full category loads on demand, in the category itself).
 *
 * The list is the row's OWN list: FeedFragment's chrome pass
 * stores every row's full uncapped items in
 * [FeedCategorySession] as it builds the rows, so the page can
 * never disagree with the row it was opened from (the failure
 * mode of the old View All, which re-filtered the grid through
 * a second rule — see FeedFragment Phase 8.96). Provider rows
 * ("provider:<apiName>") additionally fetch ONE more homepage
 * page of that provider on open (page 3 — the standard first
 * pool build already carries pages 1–2) and append whatever
 * is genuinely new; a loading footer shows during the fetch
 * and any failure simply ends it. Cards are the Feed's own
 * (FeedAdapter) and open the feed watch screen.
 */
@UnstableApi
class FeedCategoryFragment : BaseFragment<FragmentFeedCategoryBinding>(
    BindingCreator.Inflate(FragmentFeedCategoryBinding::inflate)
) {
    private val adapter = FeedAdapter(
        onPlay = { entry -> openWatch(entry) },
        onHide = { entry -> confirmHide(entry) },
        onTitleNeeded = { entry ->
            context?.let { ctx ->
                TitleResolver.resolve(ctx, entry.apiName, entry.response.url, entry.response.name)
            }
        }
    )

    private var items: List<FeedEntry> = emptyList()

    override fun fixLayout(view: View) {
        // Batch 4: padLeft was TV/EMULATOR-only — see
        // FeedSearchFragment for the landscape-cutout reason.
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = true
        )
        (view.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.feed_category_recycler
        )?.layoutManager as? GridLayoutManager)?.let {
            it.spanCount = view.context.getFeedSpanCount()
        }
    }

    override fun onBindingCreated(binding: FragmentFeedCategoryBinding) {
        val tag = arguments?.getString(ARG_TAG).orEmpty()
        binding.feedCategoryTitle.text =
            arguments?.getString(ARG_TITLE).orEmpty()
        binding.feedCategoryBack.setOnClickListener {
            findNavController().popBackStack()
        }
        binding.feedCategoryRecycler.layoutManager =
            GridLayoutManager(context, context?.getFeedSpanCount() ?: 3)
        binding.feedCategoryRecycler.adapter = adapter
        binding.feedCategoryRecycler.itemAnimator = null

        items = when {
            tag.isNotEmpty() -> FeedCategorySession.lists[tag]
                ?: fallbackItems(tag)
            else -> emptyList()
        }
        binding.feedCategoryEmpty.isVisible = items.isEmpty()
        adapter.submitList(items)

        // Provider rows: pull one more homepage page of that
        // provider and append the genuinely new items.
        if (tag.startsWith(PROVIDER_PREFIX)) {
            fetchMoreFromProvider(binding, tag.removePrefix(PROVIDER_PREFIX))
        }
    }

    /** If the session was lost (process death while the page
     *  sat on the back stack), rebuild a provider row's list
     *  from the live pool; tag rows have no second source and
     *  stay empty rather than inventing a different rule. */
    private fun fallbackItems(tag: String): List<FeedEntry> =
        if (tag.startsWith(PROVIDER_PREFIX)) {
            val api = tag.removePrefix(PROVIDER_PREFIX)
            FeedPool.entries.filter { it.apiName == api }
        } else {
            emptyList()
        }

    private fun fetchMoreFromProvider(
        binding: FragmentFeedCategoryBinding,
        apiName: String
    ) {
        val api = APIHolder.getApiFromNameNull(apiName) ?: return
        binding.feedCategoryLoading.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = try {
                when (val res = APIRepository(api).getMainPage(EXTRA_PAGE, null)) {
                    is Resource.Success -> {
                        val familyOn = FamilyMode.isEnabled()
                        res.value.filterNotNull()
                            .flatMap { it.items }
                            .filterNot {
                                familyOn && FamilyMode.isAdultSection(it.name)
                            }
                            .flatMap { shelf ->
                                shelf.list.map { item -> item to shelf.name }
                            }
                            .filterNot {
                                familyOn && FamilyMode.isAdultItem(it.first)
                            }
                            .map { (item, shelf) ->
                                FeedEntry(item, api.name, listOf(shelf))
                            }
                    }
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
            val b = this@FeedCategoryFragment.binding ?: return@launch
            b.feedCategoryLoading.isVisible = false
            if (fresh.isEmpty()) return@launch
            val hidden = context?.let { FeedHidden.getAll(it) } ?: emptySet()
            val seen = items.map { it.response.url }.toMutableSet()
            val appended = fresh.filter {
                it.response.url !in hidden && seen.add(it.response.url)
            }
            if (appended.isEmpty()) return@launch
            items = items + appended
            b.feedCategoryEmpty.isVisible = false
            adapter.submitList(items)
        }
    }

    /** Long-press a card → hide it for good (mirrors the Feed
     *  grid; the row on the Feed rebuilds without it on the
     *  next chrome post). */
    private fun confirmHide(entry: FeedEntry) {
        val context = context ?: return
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(FeedAdapter.displayTitle(entry.response.name, entry.response.url))
            .setMessage(R.string.feed_hide_message)
            .setPositiveButton(R.string.feed_hide) { _, _ ->
                FeedHidden.hide(context, entry.response.url)
                FeedPool.hide(entry.response.url)
                items = items.filterNot { it.response.url == entry.response.url }
                binding?.feedCategoryEmpty?.isVisible = items.isEmpty()
                adapter.submitList(items)
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

    companion object {
        const val ARG_TAG = "tag"
        const val ARG_TITLE = "title"

        /** Row names for provider rows carry this prefix (the
         *  same one FeedFragment builds them with). */
        const val PROVIDER_PREFIX = "provider:"

        /** The first pool build already carries homepage pages
         *  1–2 of every provider, so the page's one extra fetch
         *  is page 3. Results are URL-deduped against what the
         *  page already shows, so a pool that advanced further
         *  (pull-to-refresh pages) simply yields fewer — never
         *  duplicate — additions. */
        const val EXTRA_PAGE = 3
    }
}
