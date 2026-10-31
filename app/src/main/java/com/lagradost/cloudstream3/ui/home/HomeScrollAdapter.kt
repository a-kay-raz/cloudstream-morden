package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import coil3.request.crossfade
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.search.MetadataResolver
import com.lagradost.cloudstream3.databinding.HomeScrollViewBinding
import com.lagradost.cloudstream3.databinding.HomeScrollViewTvBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable

class HomeScrollAdapter(
    val callback: ((View, Int, LoadResponse) -> Unit)
) : NoStateAdapter<LoadResponse>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.uniqueUrl == b.uniqueUrl && a.name == b.name
})) {
    var hasMoreItems: Boolean = false

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (isLayout(TV or EMULATOR)) {
            HomeScrollViewTvBinding.inflate(inflater, parent, false)
        } else {
            HomeScrollViewBinding.inflate(inflater, parent, false)
        }

        return ViewHolderState(binding)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is HomeScrollViewBinding -> {
                clearImage(binding.homeScrollPreview)
            }

            is HomeScrollViewTvBinding -> {
                clearImage(binding.homeScrollPreview)
            }
        }
    }

    override fun onBindContent(
        holder: ViewHolderState<Any>,
        item: LoadResponse,
        position: Int,
    ) {
        val binding = holder.view

        val posterUrl = item.backgroundPosterUrl ?: item.posterUrl

        when (binding) {
            is HomeScrollViewBinding -> {
                // Phase 8.9: a failed/missing hero banner shows the custom
                // poster placeholder instead of staying blank
                binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders) {
                    // Phase 8.76: same no-crossfade rule as the grids.
                    crossfade(false)
                    error {
                        getImageFromDrawable(
                            binding.root.context,
                            R.drawable.poster_placeholder
                        )
                    }
                }
                // Phase 8.8: the hero photo must read CLEAR — no title,
                // meta, tags or logo overlaid on it. The views still exist
                // in home_scroll_view.xml (inside a visibility=gone block)
                // because the binding references them; here they are kept
                // gone on every bind so no code path can resurrect them.
                // The item's title/meta are still available via the
                // None/Play/Info row in fragment_home_head (header holder).
                // bindLogo is intentionally not called here: it force-toggles
                // title/logo visibility and would undo this state.
                binding.homeScrollPreviewTags.apply {
                    text = item.tags?.joinToString(" • ") ?: ""
                    isGone = true
                    maxLines = 2
                }
                binding.homeScrollPreviewTitle.text = item.name.html()
                binding.homeScrollPreviewTitle.isGone = true

                // Meta line: "year • ★ rating • type" — only the parts
                // that exist for this item. Score formatting mirrors the
                // result page (ResultViewModel2): 1 decimal, null when the
                // score is effectively zero/unset. Type text mirrors the
                // result page's TvType -> *_singular string mapping.
                val metaParts = mutableListOf<String>()
                item.year?.let { metaParts += it.toString() }
                // Phase 8.71: same universal-rating fallback as the
                // cards — this strip (Continue Watching / lists) only
                // ever showed the item's own score, so whole providers
                // stayed rating-less here even with a TMDB key set.
                val scrollCtx = binding.root.context
                val ownScore = item.score?.toStringNull(0.1, 10, 1, false, '.')
                val metaRating = ownScore
                    ?: MetadataResolver.cached(
                        scrollCtx, item.name, item.year,
                        MetadataResolver.isTv(item.type)
                    )
                if (metaRating != null) {
                    metaParts += "★ $metaRating"
                } else {
                    MetadataResolver.resolve(
                        scrollCtx, item.name, item.year,
                        item.type == TvType.TvSeries ||
                            item.type == TvType.Anime ||
                            item.type == TvType.OVA ||
                            item.type == TvType.AsianDrama
                    )
                }
                metaParts += binding.root.context.getString(
                    when (item.type) {
                        TvType.Movie -> R.string.movies_singular
                        TvType.AnimeMovie -> R.string.movies_singular
                        TvType.TvSeries -> R.string.tv_series_singular
                        TvType.Anime -> R.string.anime_singular
                        TvType.OVA -> R.string.ova_singular
                        TvType.Cartoon -> R.string.cartoons_singular
                        TvType.Documentary -> R.string.documentaries_singular
                        TvType.AsianDrama -> R.string.asian_drama_singular
                        TvType.Torrent -> R.string.torrent_singular
                        TvType.Live -> R.string.live_singular
                        TvType.NSFW -> R.string.nsfw_singular
                        TvType.Others -> R.string.other_singular
                        TvType.Music -> R.string.music_singular
                        TvType.AudioBook -> R.string.audio_book_singular
                        TvType.Audio -> R.string.audio_singular
                        TvType.Podcast -> R.string.podcast_singular
                        TvType.Video -> R.string.video_singular
                        TvType.CustomMedia -> R.string.custom_media_singular
                    }
                )
                binding.homeScrollPreviewMeta.apply {
                    text = metaParts.joinToString(" • ")
                    // Phase 8.8: always gone — see the title note above.
                    // The meta text is still computed so the value is
                    // there if the overlay ever returns.
                    isGone = true
                }

                binding.homePreviewLogo.isGone = true

                // Quality badge: LoadResponse has no quality field (quality
                // only exists on SearchResponse), so there is no value to
                // show and the badge stays hidden.
                binding.homeScrollQualityBadge.isGone = true
            }

            is HomeScrollViewTvBinding -> {
                binding.homeScrollPreview.isFocusable = false
                binding.homeScrollPreview.setOnClickListener { view ->
                    callback.invoke(view ?: return@setOnClickListener, position, item)
                }
                binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders)
            }
        }
    }
}
