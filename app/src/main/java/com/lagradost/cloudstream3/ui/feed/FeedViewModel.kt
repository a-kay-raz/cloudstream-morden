package com.lagradost.cloudstream3.ui.feed

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.FamilyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One feed card: a provider search result plus the provider it
 * came from. Phase 8.91: [sections] keeps the RAW homepage shelf
 * names the item arrived under (provenance for the Feed's
 * category rows — see FeedSectionTags); an item shelved by
 * several providers/sections carries all of them. Search-built
 * entries have none.
 */
data class FeedEntry(
    val response: SearchResponse,
    val apiName: String,
    val sections: List<String> = emptyList()
)

/** Phase 8.91: fold [next] into the entry already stored for
 *  its URL, unioning the section provenance instead of dropping
 *  the later copy's shelves. */
private fun unionSections(
    map: LinkedHashMap<String, FeedEntry>,
    next: FeedEntry
) {
    val prev = map[next.response.url]
    map[next.response.url] = if (prev == null) {
        next
    } else {
        prev.copy(
            sections = (prev.sections + next.sections).distinct()
        )
    }
}

/** The last built feed, shared with the watch page's "Up next" list. */
object FeedPool {
    @Volatile
    var entries: List<FeedEntry> = emptyList()

    /** Phase 8.22: drop an item everywhere at once (hide action). */
    fun hide(url: String) {
        entries = entries.filterNot { it.response.url == url }
    }
}

/**
 * Phase 8.96 — the full (UNCAPPED) item list behind every
 * category row the Feed currently shows, keyed by the row's
 * name (a tag, or "provider:<apiName>" for a provider row).
 * FeedFragment's chrome pass already computes these lists to
 * build the rows (which display only the first 20); it stores
 * them here so a row's "View All" can open the full category
 * page (FeedCategoryFragment) with exactly the row's own
 * membership and order — no second, possibly divergent,
 * computation. Replaced wholesale on every chrome post.
 */
object FeedCategorySession {
    @Volatile
    var lists: Map<String, List<FeedEntry>> = emptyMap()
}

/**
 * Phase 8.18/8.19 — builds the Feed: the homepages of every installed
 * provider with a main page, flattened, interleaved round-robin so no
 * provider dominates, deduplicated by URL. Liked types/providers (see
 * FeedLikes) are woven in more densely, which is how the feed learns
 * taste. Pull-to-refresh advances to the NEXT homepage page of every
 * provider, so refreshing surfaces genuinely different content; when a
 * page comes back empty the cycle wraps to page 1. The pool is kept in
 * memory for the watch page.
 */
class FeedViewModel(application: Application) : AndroidViewModel(application) {
    val feed = MutableLiveData<Resource<List<FeedEntry>>>()
    private var building = false
    private var page = 1
    private var firstBuild = true

    fun loadIfNeeded() {
        if (building) return
        // Phase 8.99: a Success that came from the disk cache is
        // a PAINT, not a build — the fresh build still has to
        // run behind it (stale-while-revalidate).
        if (feed.value is Resource.Success && !cachePaintOnly) return
        build()
    }

    // Phase 8.99: stale-while-revalidate state. [freshPublishSeen]
    // flips on the first REAL publish so a cache read that lands
    // late can never overwrite fresher pool state.
    /** Batch 2: serialises the cache paint's check-assign-post
     *  against publish()'s assign-post, so a publish can never
     *  land between the paint's fresh check and its post and
     *  leave stale paint as the final state. */
    private val paintPublishMutex = Mutex()

    @Volatile
    private var freshPublishSeen = false
    private var cachePaintRequested = false

    @Volatile
    private var cachePaintOnly = false

    /**
     * Phase 8.99 — paint the last build's pool from disk on a
     * cold open, before (and independently of) the fresh build.
     * The observer treats the paint like any Success: grid +
     * chrome pass render immediately, then the fresh build's
     * publishes merge in behind it. Paint-time filtering mirrors
     * publish/fetch-time filtering: hidden items are dropped
     * (as in [publish]) and, when Family Mode is on NOW, adult
     * providers/items/shelves are dropped (as in buildFeed) —
     * the cache stores unfiltered display data, so a cache
     * written with Family Mode off cannot leak into a session
     * where it is on.
     */
    fun paintCachedPoolIfCold() {
        if (cachePaintRequested || building) return
        if (feed.value is Resource.Success || FeedPool.entries.isNotEmpty()) return
        cachePaintRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            val cached = FeedPoolCache.read(getApplication())
            if (cached.isEmpty()) return@launch
            val painted = applyPaintFilters(cached)
            if (painted.isEmpty()) return@launch
            // The fresh check, the pool assignment and the post
            // happen under the same lock publish() uses, so a
            // publish landing during the read/filter above is
            // seen here and aborts the paint instead of posting
            // stale entries after the fresh post.
            paintPublishMutex.withLock {
                if (freshPublishSeen) return@withLock
                FeedPool.entries = painted
                cachePaintOnly = true
                feed.postValue(Resource.Success(painted))
            }
        }
    }

    /** The display-time half of the fetch-time filters, for
     *  cache-painted entries (see [paintCachedPoolIfCold]). */
    private fun applyPaintFilters(entries: List<FeedEntry>): List<FeedEntry> {
        var out = entries
        val hidden = FeedHidden.getAll(getApplication())
        if (hidden.isNotEmpty()) {
            out = out.filterNot { it.response.url in hidden }
        }
        if (FamilyMode.isEnabled()) {
            out = out.filterNot { entry ->
                val api = APIHolder.getApiFromNameNull(entry.apiName)
                (api != null && FamilyMode.isAdultApi(api)) ||
                    FamilyMode.isAdultItem(entry.response) ||
                    // buildFeed drops adult SHELVES whole; a
                    // cached entry whose every shelf is adult
                    // would never have survived a fresh build.
                    (entry.sections.isNotEmpty() && entry.sections.all {
                        FamilyMode.isAdultSection(it)
                    })
            }
        }
        return out
    }

    private var prependNext = false
    private var pendingPrepend = false

    /** True while any feed build is running (spinner truthfulness). */
    val isBuilding: Boolean get() = building

    /** Whether the most recently finished build was a pull-refresh. */
    var lastBuildWasPrepend: Boolean = false
        private set

    /**
     * Pull-to-refresh: fetch the providers' next homepage page and
     * put it FIRST, so a refresh visibly changes the top of the
     * feed. (Phase 8.32's append-only refresh looked like nothing
     * happened.) A pull during a build is queued, not dropped.
     */
    fun refresh() {
        if (building) {
            pendingPrepend = true
            return
        }
        prependNext = true
        page += 1
        build()
    }

    /** Endless scroll: fetch the next page and append it. */
    fun loadMore() {
        if (building) return
        page += 1
        build()
    }

    private fun build() {
        if (building) return
        building = true
        cachePaintOnly = false
        feed.postValue(Resource.Loading())
        // Phase 8.92: the whole build runs on Dispatchers.Default.
        // It used to run in viewModelScope's Main dispatcher, and
        // buildFeed's amap fan-out inherits the caller's
        // dispatcher — so every provider's homepage PARSING (the
        // CPU-heavy half of getMainPage, after the suspending
        // network call returns) executed on the UI thread,
        // interleaved across all providers at once. On his
        // Android 8.1 phone the Feed froze for seconds during
        // every load (his "হ্যাং হয়ে আটকে থাকে" report).
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val prepend = prependNext
                prependNext = false
                if (firstBuild) {
                    // Phase 8.32: the pool ACCUMULATES — the
                    // first build pulls two homepage pages per
                    // provider. Phase 8.92: page 1 is PUBLISHED
                    // as soon as it lands instead of waiting for
                    // both pages of every provider (the old first
                    // paint waited for the slowest provider twice
                    // over); page 2 merges in behind it.
                    // Phase 8.99: page 1 now STREAMS — every
                    // provider completion publishes the pool-so-
                    // far, so first paint waits for the FIRST
                    // provider, not the slowest. The old shape
                    // (amap = unbounded async + awaitAll) held
                    // the first post until every homepage had
                    // arrived; on a ~33 KB/s link that was the
                    // 30 s+ cold load. Page 2 still starts only
                    // after page 1 has fully settled (all
                    // providers landed or timed out), so it never
                    // competes with first paint for bandwidth.
                    page = 2
                    val page1 = buildFeedStreaming(1) { partial ->
                        publish(partial, prepend, suppressEmpty = true)
                    }
                    publish(
                        page1, prepend, suppressEmpty = true,
                        completesFirstBuild = true
                    )
                    publish(buildFeed(2), prepend, suppressEmpty = false)
                } else {
                    publish(buildFeed(page), prepend, suppressEmpty = false)
                }
            } catch (e: Exception) {
                feed.postValue(Resource.Failure(false, e.message ?: "Feed failed"))
            } finally {
                building = false
                // Phase 8.99: snapshot the pool for the next
                // cold open (stale-while-revalidate). Off the
                // build thread, capped, best-effort — see
                // FeedPoolCache.
                if (FeedPool.entries.isNotEmpty()) {
                    val snapshot = FeedPool.entries
                    viewModelScope.launch(Dispatchers.IO) {
                        FeedPoolCache.save(getApplication(), snapshot)
                    }
                }
                // A pull that arrived mid-build runs now.
                if (pendingPrepend) {
                    pendingPrepend = false
                    refresh()
                }
            }
        }
    }

    /**
     * Merge [fresh] into the shared pool (unioning section
     * provenance per URL — Phase 8.91) and post the result.
     * [suppressEmpty] skips the post when the merge is still
     * empty, so an intermediate first-build page cannot flash
     * the failure/empty state before page 2 lands.
     * [completesFirstBuild] marks the first build's FINAL
     * page-1 publish: only it runs the liked-boost weave and
     * flips [firstBuild] — the 8.99 streaming partial publishes
     * before it must do neither (a boost search round per
     * provider landing would re-create the storm this batch
     * exists to remove, and the flag flip would strand the
     * final publish's own boost).
     */
    private suspend fun publish(
        fresh: List<FeedEntry>,
        prepend: Boolean,
        suppressEmpty: Boolean,
        completesFirstBuild: Boolean = false
    ) {
        var entries = if (prepend) {
            val merged = LinkedHashMap<String, FeedEntry>()
            (fresh + FeedPool.entries).forEach {
                unionSections(merged, it)
            }
            merged.values.toList()
        } else {
            val merged = LinkedHashMap<String, FeedEntry>()
            FeedPool.entries.forEach {
                unionSections(merged, it)
            }
            fresh.forEach { unionSections(merged, it) }
            merged.values.toList()
        }
        if (completesFirstBuild) {
            entries = withLikedBoost(entries)
            firstBuild = false
        }
        // Phase 8.22: hidden items never surface.
        val hidden = FeedHidden.getAll(getApplication())
        if (hidden.isNotEmpty()) {
            entries = entries.filterNot { it.response.url in hidden }
        }
        paintPublishMutex.withLock {
            FeedPool.entries = entries
            freshPublishSeen = true
            lastBuildWasPrepend = prepend
            if (!(entries.isEmpty() && suppressEmpty)) {
                feed.postValue(
                    if (entries.isEmpty()) {
                        Resource.Failure(false, "No feed content")
                    } else {
                        Resource.Success(entries)
                    }
                )
            }
        }
    }

    /**
     * Phase 8.21: liking should FETCH more of the liked kind, not just
     * reorder what the homepages happened to offer. Search providers
     * for the most-liked type and weave the fresh hits through the
     * front of the feed.
     */
    private suspend fun withLikedBoost(entries: List<FeedEntry>): List<FeedEntry> {
        if (entries.isEmpty()) return entries
        val liked = FeedLikes.getAll(getApplication())
        if (liked.isEmpty()) return entries
        val topType = liked.groupingBy { it.typeName }.eachCount()
            .maxByOrNull { it.value }?.key
        val label = when (topType) {
            "Anime" -> "Anime"
            "AsianDrama" -> "Drama"
            "TvSeries" -> "Series"
            "Movie" -> "Movie"
            "Cartoon" -> "Cartoon"
            "Documentary" -> "Documentary"
            else -> null
        } ?: return entries
        val have = entries.map { it.response.url }.toSet()
        val boosted = searchAll(label).filter { it.response.url !in have }
        if (boosted.isEmpty()) return entries
        val out = mutableListOf<FeedEntry>()
        var b = 0
        var r = 0
        while (b < boosted.size || r < entries.size) {
            if (b < boosted.size) out.add(boosted[b++])
            if (r < entries.size) out.add(entries[r++])
        }
        return out
    }

    /** Phase 8.19: search every provider at once (used by the feed search).
     *  Phase 8.92: runs on Dispatchers.Default — the caller is the
     *  fragment's Main lifecycleScope, and the amap fan-out would
     *  otherwise parse every provider's results on the UI thread
     *  (same hang class as the pool build). */
    suspend fun searchAll(query: String): List<FeedEntry> =
        withContext(Dispatchers.Default) { searchAllOnBackground(query) }

    private suspend fun searchAllOnBackground(query: String): List<FeedEntry> {
        val familyOn = FamilyMode.isEnabled()
        val providers = apis.filter {
            it.hasMainPage && it.name != "None" &&
                (!familyOn || !FamilyMode.isAdultApi(it))
        }
        val results: List<List<FeedEntry>> = providers.amap { api ->
            try {
                when (val res = APIRepository(api).search(query, 1)) {
                    is Resource.Success -> res.value.items.map { FeedEntry(it, api.name) }
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        val seen = mutableSetOf<String>()
        return results.flatten()
            .filterNot {
                FamilyMode.isEnabled() &&
                    FamilyMode.isAdultItem(it.response)
            }
            .filter { seen.add(it.response.url) }
    }

    /** Providers eligible for the feed pool (shared by every
     *  build path). */
    private fun homepageProviders(familyOn: Boolean): List<MainAPI> =
        apis.filter {
            it.hasMainPage && it.name != "None" &&
                (!familyOn || !FamilyMode.isAdultApi(it))
        }

    /**
     * One provider's homepage page, as feed entries. Phase 8.99:
     * wrapped in [FEED_PROVIDER_TIMEOUT_MS] — the repository's
     * own getMainPage timeout defaults to 120 s
     * (APIRepository.DEFAULT_TIMEOUT), so one hung or
     * slow-dripping provider could previously gate the whole
     * await-all build for up to two minutes. A timed-out (or
     * throwing) provider is skipped for this round and logged;
     * its content rejoins on the next refresh/load-more round
     * or from the disk cache painted at cold open.
     */
    private suspend fun fetchProviderPage(
        api: MainAPI,
        page: Int,
        familyOn: Boolean
    ): List<FeedEntry> {
        val res = withTimeoutOrNull(FEED_PROVIDER_TIMEOUT_MS) {
            try {
                APIRepository(api).getMainPage(page, null)
            } catch (e: Exception) {
                null
            }
        }
        if (res == null) {
            Log.w(
                "FeedViewModel",
                "Skipping ${api.name} page $page " +
                    "(>${FEED_PROVIDER_TIMEOUT_MS}ms or error)"
            )
            return emptyList()
        }
        return when (res) {
            is Resource.Success -> res.value
                .filterNotNull()
                .flatMap { it.items }
                // Phase 8.51: drop adult-labelled shelves
                // whole, then judge items by title too.
                .filterNot {
                    familyOn && FamilyMode.isAdultSection(it.name)
                }
                // Phase 8.91: the shelf name rides along
                // with each item (it used to be flattened
                // away here) — it is the provenance the
                // Feed's category rows are built from.
                .flatMap { shelf ->
                    shelf.list.map { item -> item to shelf.name }
                }
                .filterNot {
                    familyOn && FamilyMode.isAdultItem(it.first)
                }
                .take(40)
                .map { (item, shelf) ->
                    FeedEntry(item, api.name, listOf(shelf))
                }

            else -> emptyList()
        }
    }

    /**
     * Phase 8.99: bounded fan-out. The old code used the
     * library `amap` (unbounded async + awaitAll): on a slow
     * link EVERY provider's homepage trickled in at once, each
     * getting a sliver of the bandwidth, so nothing finished
     * early and the await-all landed with the last byte of the
     * slowest provider. [FEED_PROVIDER_PARALLELISM] fetches at
     * a time lets the front of the queue actually COMPLETE —
     * which is what first paint (and each streaming partial)
     * feeds on — without overwhelming a low-end phone's CPU
     * with a dozen simultaneous homepage parses.
     */
    private suspend fun <T> mapProvidersBounded(
        providers: List<MainAPI>,
        block: suspend (MainAPI) -> T
    ): List<T> = coroutineScope {
        val semaphore = Semaphore(FEED_PROVIDER_PARALLELISM)
        providers.map { api ->
            async { semaphore.withPermit { block(api) } }
        }.awaitAll()
    }

    private suspend fun buildFeed(page: Int): List<FeedEntry> {
        val familyOn = FamilyMode.isEnabled()
        val providers = homepageProviders(familyOn)
        val liked = FeedLikes.getAll(getApplication())
        val perProvider = mapProvidersBounded(providers) { api ->
            fetchProviderPage(api, page, familyOn)
        }
        return interleaveAndBoost(perProvider, liked)
    }

    /**
     * Phase 8.99: the first build's page 1, streaming. Same
     * fetch/interleave as [buildFeed], but after EVERY provider
     * completion the pool-so-far (interleaved over the providers
     * that have landed, in provider order) is handed to
     * [onPartial] for an immediate publish — first paint comes
     * from the first finished provider instead of the slowest.
     * Returns the complete page once every provider has settled
     * or timed out; the caller publishes that as the round's
     * final word (boost + canonical order) before page 2 starts.
     * Partials are throttled: the first non-empty one always
     * goes out, later ones only when the pool grew by at least
     * [FEED_PARTIAL_MIN_GROWTH] entries, so a fast network
     * cannot turn the observer into a submit storm (the 8.97
     * ANR lesson) — on his slow link providers land seconds
     * apart and every landing qualifies anyway.
     */
    private suspend fun buildFeedStreaming(
        page: Int,
        onPartial: suspend (List<FeedEntry>) -> Unit
    ): List<FeedEntry> {
        val familyOn = FamilyMode.isEnabled()
        val providers = homepageProviders(familyOn)
        val liked = FeedLikes.getAll(getApplication())
        val slots = arrayOfNulls<List<FeedEntry>>(providers.size)
        val gate = Mutex()
        var lastPublishedCount = 0
        coroutineScope {
            val semaphore = Semaphore(FEED_PROVIDER_PARALLELISM)
            providers.mapIndexed { index, api ->
                async {
                    semaphore.withPermit {
                        val items = fetchProviderPage(api, page, familyOn)
                        slots[index] = items
                        gate.withLock {
                            val merged = interleaveAndBoost(
                                slots.filterNotNull(), liked
                            )
                            if (merged.isNotEmpty() &&
                                (lastPublishedCount == 0 ||
                                    merged.size - lastPublishedCount >=
                                    FEED_PARTIAL_MIN_GROWTH)
                            ) {
                                lastPublishedCount = merged.size
                                onPartial(merged)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return interleaveAndBoost(slots.filterNotNull(), liked)
    }

    /** Round-robin interleave + taste weave (extracted from the
     *  old buildFeed tail; semantics unchanged). */
    private fun interleaveAndBoost(
        perProvider: List<List<FeedEntry>>,
        liked: List<FeedLikes.Liked>
    ): List<FeedEntry> {
        // Round-robin interleave so the feed mixes providers evenly.
        // Phase 8.91: a URL shelved twice keeps its FIRST slot but
        // unions the later copy's section provenance.
        val interleaved = mutableListOf<FeedEntry>()
        val positions = HashMap<String, Int>()
        var index = 0
        var added = true
        while (added) {
            added = false
            for (list in perProvider) {
                val item = list.getOrNull(index) ?: continue
                added = true
                val pos = positions[item.response.url]
                if (pos == null) {
                    positions[item.response.url] = interleaved.size
                    interleaved.add(item)
                } else {
                    val prev = interleaved[pos]
                    interleaved[pos] = prev.copy(
                        sections = (prev.sections + item.sections)
                            .distinct()
                    )
                }
            }
            index++
        }

        // Taste boost: liked types/providers get every third slot.
        if (liked.isEmpty()) return interleaved
        val likedTypes = liked.map { it.typeName }.toSet()
        val likedApis = liked.map { it.apiName }.toSet()
        fun boosted(e: FeedEntry) =
            e.response.type?.name in likedTypes || e.apiName in likedApis
        val hot = interleaved.filter { boosted(it) }
        if (hot.isEmpty()) return interleaved
        val rest = interleaved.filter { !boosted(it) }
        val out = mutableListOf<FeedEntry>()
        var h = 0
        var r = 0
        while (h < hot.size || r < rest.size) {
            if (h < hot.size) out.add(hot[h++])
            repeat(2) { if (r < rest.size) out.add(rest[r++]) }
        }
        return out
    }

    private companion object {
        /** Phase 8.99: concurrent homepage fetches — enough to
         *  keep a slow link busy, few enough to let the front
         *  of the queue finish (and paint) early on a low-end
         *  phone. */
        const val FEED_PROVIDER_PARALLELISM = 4

        /** Phase 8.99: per-provider page cap inside a build
         *  round — a quarter of the repository's 120 s default;
         *  ~1 MB of homepage even at ~33 KB/s, far beyond a
         *  typical homepage, while a truly hung provider can no
         *  longer gate the round for minutes. */
        const val FEED_PROVIDER_TIMEOUT_MS = 30_000L

        /** Phase 8.99: minimum pool growth between streaming
         *  partial publishes. */
        const val FEED_PARTIAL_MIN_GROWTH = 10
    }
}
