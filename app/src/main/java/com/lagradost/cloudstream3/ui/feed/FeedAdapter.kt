package com.lagradost.cloudstream3.ui.feed

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.FeedCardBinding
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import com.lagradost.cloudstream3.ui.search.goldStarText
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.addPressShrink
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.hapticTick

/**
 * Phase 8.18 — one feed card: a big portrait poster, the clean title and
 * provider/type line under it, and a heart on the side. The same adapter
 * drives the Feed tab and the watch page's "Up next" list.
 */
class FeedAdapter(
    private val onPlay: (FeedEntry) -> Unit,
    private val onLikeChanged: (FeedEntry, Boolean) -> Unit = { _, _ -> },
    private val onHide: (FeedEntry) -> Unit = {},
    private val onTitleNeeded: (FeedEntry) -> Unit = {}
) : ListAdapter<FeedEntry, FeedAdapter.ViewHolder>(Diff) {

    /** Real titles resolved in the background (TitleResolver). */
    private val titleOverrides = mutableMapOf<String, String>()

    fun setTitleOverride(url: String, title: String) {
        titleOverrides[url] = title
        val idx = currentList.indexOfFirst { it.response.url == url }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Phase 8.56: universal ratings resolved by MetadataResolver,
     *  keyed by its title+year cache key. */
    private val ratingOverrides = mutableMapOf<String, String>()

    /** Phase 8.97: rating key → first index in the current
     *  list, rebuilt once per list change. setRatingOverride
     *  used to find its item with an indexOfFirst that ran
     *  MetadataResolver.keyFor over every entry until the
     *  match — keyFor is cleanPosterTitle + a two-Regex
     *  normalize, so EACH rating landing cost an O(pool)
     *  regex-heavy scan on the main thread. Ratings land in
     *  bursts throughout the load window (the fragment's
     *  24-item pre-kick plus per-bind resolves), which made
     *  this a steady main-thread tax in exactly the seconds
     *  taps were ANR'ing (his 12:09 report). The map build
     *  pays the same keyFor cost ONCE per list, and each
     *  landing is now a map lookup + one notifyItemChanged. */
    private var ratingIndex: Map<String, Int> = emptyMap()

    override fun onCurrentListChanged(
        previousList: MutableList<FeedEntry>,
        currentList: MutableList<FeedEntry>
    ) {
        val index = HashMap<String, Int>(currentList.size)
        currentList.forEachIndexed { i, entry ->
            val key = MetadataResolver.keyFor(entry.response)
            if (!index.containsKey(key)) index[key] = i
        }
        ratingIndex = index
        retryUnresolvedTitles()
    }

    /** Phase 8.99b (job C): the context of the RecyclerView this
     *  adapter is attached to (application context — no view or
     *  activity is retained), captured for title retries. */
    private var attachedContext: Context? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedContext = recyclerView.context.applicationContext
        retryUnresolvedTitles()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        attachedContext = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /**
     * Phase 8.99b (job C): re-kick title resolution for every
     * listed item whose card would render blank (id-like raw
     * name, nothing resolved yet). A card's title kick used to
     * be one-shot — it fired only from bind(), and bind() does
     * not re-run for an item a later pool publish leaves
     * unchanged (DiffUtil: same url+apiName = contents same).
     * The 8.99 streaming build made that fatal for providers
     * whose homepage items carry id-like titles (his Hotstar /
     * Pixar cards): the first kicks fire from the cache paint
     * WHILE the streaming build, page 2 and the metadata
     * pre-kick saturate his slow link, the resolver's load
     * times out, and no later bind ever retries — the name
     * stays blank for the whole session. Re-kicking on every
     * list application (and on attach) keeps retrying across
     * publishes until a load lands; TitleResolver.resolve
     * self-guards (cached / in-flight), so repeat kicks for
     * resolved or running items are no-ops.
     */
    fun retryUnresolvedTitles() {
        val context = attachedContext ?: return
        for (entry in currentList) {
            val response = entry.response
            if (response.url in titleOverrides) continue
            if (!TitleResolver.isIdLike(response.name)) continue
            if (TitleResolver.cached(context, response.url) != null) continue
            onTitleNeeded(entry)
        }
    }

    fun setRatingOverride(key: String, rating: String) {
        ratingOverrides[key] = rating
        val idx = ratingIndex[key] ?: -1
        if (idx >= 0 && idx < currentList.size) notifyItemChanged(idx)
    }

    object Diff : DiffUtil.ItemCallback<FeedEntry>() {
        override fun areItemsTheSame(oldItem: FeedEntry, newItem: FeedEntry): Boolean =
            oldItem.response.url == newItem.response.url

        override fun areContentsTheSame(oldItem: FeedEntry, newItem: FeedEntry): Boolean =
            oldItem.response.url == newItem.response.url &&
                oldItem.apiName == newItem.apiName
    }

    inner class ViewHolder(val binding: FeedCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FeedEntry) {
            val context = binding.root.context
            val response = entry.response
            binding.feedCardPoster.loadImage(response.posterUrl, response.posterHeaders) {
                error { getImageFromDrawable(context, R.drawable.poster_placeholder) }
            }
            // Phase 8.33: prefer a resolved real title; when the
            // raw name is only a placeholder ("Id <number>"), ask
            // for a background resolution — the card swaps its
            // title in when it lands.
            val override = titleOverrides[response.url]
                ?: TitleResolver.cached(context, response.url)
            val shownTitle = override ?: displayTitle(response.name, response.url)
            // Phase 8.71: never print the raw placeholder ("Id 1260107139")
            // as a title — while resolution is pending (or failed) the
            // card shows its poster + meta only.
            binding.feedCardTitle.text =
                if (TitleResolver.isIdLike(shownTitle)) "" else shownTitle
            if (override == null && TitleResolver.isIdLike(response.name)) {
                onTitleNeeded(entry)
            }
            // Phase 8.56: meta line gains the rating (provider
            // score, else the universal TMDB/IMDb cache) and a
            // DUB/SUB label when the provider declares one.
            val ratingKey = MetadataResolver.keyFor(response)
            val rating = ratingOverrides[ratingKey]
                ?: MetadataResolver.cachedFor(context, response)
            if (rating == null && response.score == null &&
                !resolutionsPaused
            ) {
                MetadataResolver.resolve(context, response)
            }
            // Phase 8.88: metadata (genres / language / country)
            // for the Feed's categories — the fragment pre-kicks
            // the pool's head; cards deeper in the pool resolve
            // here on bind. Self-guarded (cached / missed /
            // in-flight items skip instantly). Phase 8.92: also
            // paused while the grid scrolls (see companion).
            if (!resolutionsPaused &&
                MetadataResolver.metaCachedFor(context, response) == null
            ) {
                MetadataResolver.resolveMeta(context, response)
            }
            // dubStatus is declared on AnimeSearchResponse only.
            val declaredDub = (response as? AnimeSearchResponse)?.dubStatus
            val dubSub = when {
                declaredDub.isNullOrEmpty() -> null
                declaredDub!!.contains(DubStatus.Dubbed) &&
                    declaredDub.contains(DubStatus.Subbed) -> "DUB+SUB"
                declaredDub.contains(DubStatus.Dubbed) -> "DUB"
                declaredDub.contains(DubStatus.Subbed) -> "SUB"
                else -> null
            }
            // Phase 8.71: prefer the type learned from the details page
            // (TitleResolver cache) over the listing's declared type, so
            // the card tag matches the watch screen's tag.
            val shownType = TitleResolver.cachedType(context, response.url)
                ?: response.type
            binding.feedCardMeta.text = goldStarText(buildString {
                append(entry.apiName)
                append(" • ")
                append(typeLabel(shownType))
                if (rating != null) append(" • ★ ").append(rating)
                if (dubSub != null) append(" • ").append(dubSub)
            })
            bindLike(entry)

            binding.root.setOnClickListener { onPlay(entry) }
            // Phase 8.22: long-press offers to hide the item for good.
            binding.root.setOnLongClickListener {
                binding.root.hapticTick()
                onHide(entry)
                true
            }
            binding.feedCardLike.setOnClickListener { view ->
                val nowLiked = !FeedLikes.isLiked(context, entry.response.url)
                FeedLikes.setLiked(context, entry, nowLiked)
                view.hapticTick()
                bindLike(entry)
                onLikeChanged(entry, nowLiked)
            }
        }

        private fun bindLike(entry: FeedEntry) {
            val context = binding.root.context
            val liked = FeedLikes.isLiked(context, entry.response.url)
            binding.feedCardLike.setImageResource(
                // Phase 8.73: a genuinely FILLED heart when liked —
                // the old "filled" drawable is a hollow outline in
                // this project, so the tap only reddened the rim.
                if (liked) R.drawable.ic_favorite_filled_24
                else R.drawable.ic_baseline_favorite_border_24
            )
            binding.feedCardLike.imageTintList = ContextCompat.getColorStateList(
                context,
                if (liked) R.color.colorPrimaryRed else R.color.white
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = FeedCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        // Every card answers a touch, like the home posters do (Phase 8.16).
        binding.root.addPressShrink()
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        /** Phase 8.92: while the Feed grid is scrolling, card
         *  binds stop kicking NEW background metadata/rating
         *  resolutions (cached values still render). The Feed
         *  fragment flips this from its scroll listener; the
         *  resolver storm used to compete with fling rendering
         *  on Android 8.1 and the page read as hung. */
        @Volatile
        var resolutionsPaused: Boolean = false

        /**
         * The title a card/header shows. Some providers hand back a
         * blank (or all-decoration) name — fall back to a readable
         * slug from the URL so the title slot is never empty.
         */
        fun displayTitle(name: String, url: String): String {
            val cleaned = cleanPosterTitle(name)
                // Phase 8.57: providers ship names ending in a
                // dangling separator ("Dear Boss ... -").
                .replace(Regex("[-\\u2013\\u2014|\\u2022\\u00B7:\\s]+$"), "")
            if (cleaned.isNotBlank()) return cleaned
            val slug = url.substringBefore('?').trimEnd('/')
                .substringAfterLast('/')
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
            return slug.replaceFirstChar { it.uppercase() }.ifBlank { "Untitled" }
        }

        fun typeLabel(type: TvType?): String = when (type) {
            TvType.Movie, TvType.AnimeMovie -> "Movie"
            TvType.TvSeries -> "Series"
            TvType.Anime, TvType.OVA -> "Anime"
            TvType.AsianDrama -> "Drama"
            TvType.Cartoon -> "Cartoon"
            TvType.Documentary -> "Documentary"
            TvType.Live -> "Live"
            else -> type?.name ?: ""
        }
    }
}
