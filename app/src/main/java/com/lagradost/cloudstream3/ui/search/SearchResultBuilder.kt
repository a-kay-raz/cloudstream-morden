package com.lagradost.cloudstream3.ui.search

import android.annotation.SuppressLint
import coil3.request.crossfade
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.getShortSeasonText
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.getImageFromDrawable

object SearchResultBuilder {
    private val showCache: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * Phase 8.17: poster labels show the clean title only — providers pack
     * the raw name with the year, quality tags and episode ranges
     * ("DeSu7 (2026) [HDTS Best Quality]"), which crowds out the actual
     * title. Everything bracketed goes, the year goes, quality words go;
     * a season marker survives, normalised ("S5" -> "S05") and moved to
     * the end: "Bachelor Point (2026) S05 [Ep 121-128]" becomes
     * "Bachelor Point S05". Display only: the response itself is untouched,
     * so search matching and the details page keep the full name. If
     * cleaning would leave nothing, the raw name is used as-is.
     */
    private val TITLE_LANGUAGES = listOf(
        "Hindi", "Bengali", "Bangla", "Tamil", "Telugu", "Malayalam",
        "Kannada", "Punjabi", "English", "Korean", "Japanese",
        "Chinese", "Arabic", "Urdu", "Marathi", "Gujarati", "Spanish",
        "French", "German", "Russian", "Turkish", "Thai",
        "Vietnamese", "Indonesian"
    )

    /**
     * Phase 8.51: some providers trail the real title with a
     * release-spec block ("Alaap Bengali Hoichoi – 480P | 720P |
     * 1080P – x264 – 250MB | 1.8GB ESub – Download"). Everything
     * from the first quality/size/codec token on is junk; cut it
     * there and let the normal cleanup finish. A token at the
     * very start is part of the name and is kept.
     */
    private val RELEASE_JUNK = Regex(
        """(?i)\b(240p|360p|480p|720p|1080p|1440p|2160p|4320p|x264|x265|h\.?264|h\.?265|hevc|10bit|8bit|\d+(?:\.\d+)?\s?(?:mb|gb))\b"""
    )

    private fun truncateReleaseJunk(raw: String): String {
        val match = RELEASE_JUNK.find(raw) ?: return raw
        if (match.range.first <= 0) return raw
        return raw.substring(0, match.range.first)
    }

    fun cleanPosterTitle(raw: String): String {
        val season = Regex("""(?i)\bS(\d{1,2})\b""").find(raw)?.groupValues?.get(1)
            ?: Regex("""(?i)\bSeason\s*(\d{1,2})\b""").find(raw)?.groupValues?.get(1)

        var title = truncateReleaseJunk(raw)
            .replace(Regex("""\[[^\]]*\]"""), " ")
            .replace(Regex("""\(\s*(19|20)\d{2}\s*\)"""), " ")
            .replace(Regex("""(?i)\bS\d{1,2}\b"""), " ")
            .replace(Regex("""(?i)\bSeason\s*\d{1,2}\b"""), " ")
            .replace(
                Regex("""(?i)\b(full\s?hd|hdts|hdtc|hdcam|hdrip|web-?dl|webrip|bluray|blu-ray|camrip|telesync|telecine|4k|uhd)\b"""),
                " "
            )
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ':', '•', '.', ' ')
            .trim()

        if (title.isBlank()) return raw.trim()
        if (season != null) title += " S" + season.padStart(2, '0')
        return title
    }

    fun updateCache(context: Context?) {
        if (context == null) return
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)

        for (k in context.resources.getStringArray(R.array.poster_ui_options_values)) {
            showCache[k] = settingsManager.getBoolean(k, showCache[k] ?: true)
        }
    }

    /**
     * Phase 8.10-A: resolve one poster-UI flag with the same semantics the
     * settings dialog uses — the persisted preference, defaulting to ON.
     *
     * Previously bind() read only [showCache] and defaulted every flag to
     * OFF on a cache miss (`?: false`), while the dialog reads/writes the
     * preferences with default ON. Any bind that ran without [updateCache]
     * having populated the exact key (or with a stale cache) therefore hid
     * all six poster elements — quality/dub/sub/rating/title/episode text —
     * even though every toggle in the dialog was checked. Now a cache miss
     * falls back to the preference itself, so an element is hidden only
     * when the user has explicitly turned it off.
     */
    private fun showFlag(context: Context?, keyRes: Int): Boolean {
        if (context == null) return true
        val key = context.getString(keyRes)
        showCache[key]?.let { return it }
        val value = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(key, true)
        showCache[key] = value
        return value
    }

    @SuppressLint("StringFormatInvalid")
    fun bind(
        clickCallback: (SearchClickCallback) -> Unit,
        card: SearchResponse,
        position: Int,
        itemView: View,
        nextFocusUp: Int? = null,
        nextFocusDown: Int? = null,
        colorCallback: ((Palette) -> Unit)? = null
    ) {
        val cardView: ImageView = itemView.findViewById(R.id.imageView)
        val cardText: TextView? = itemView.findViewById(R.id.imageText)

        val textIsDub: TextView? = itemView.findViewById(R.id.text_is_dub)
        val textIsSub: TextView? = itemView.findViewById(R.id.text_is_sub)
        val textFlag: TextView? = itemView.findViewById(R.id.text_flag)
        val rating: TextView? = itemView.findViewById(R.id.text_rating)

        val textQuality: TextView? = itemView.findViewById(R.id.text_quality)
        val shadow: View? = itemView.findViewById(R.id.title_shadow)

        val bg: CardView = itemView.findViewById(R.id.background_card)

        val bar: ProgressBar? = itemView.findViewById(R.id.watchProgress)
        val playImg: ImageView? = itemView.findViewById(R.id.search_item_download_play)
        val episodeText: TextView? = itemView.findViewById(R.id.episode_text)

        // Do logic

        bar?.isVisible = false
        playImg?.isVisible = false
        textIsDub?.isVisible = false
        textIsSub?.isVisible = false
        textIsDub?.text = null
        textIsSub?.text = null
        textFlag?.isVisible = false
        rating?.isVisible = false
        episodeText?.isVisible = false

        val showSub = showFlag(textIsDub?.context, R.string.show_sub_key)
        val showDub = showFlag(textIsDub?.context, R.string.show_dub_key)
        val showTitle = showFlag(cardText?.context, R.string.show_title_key)
        val showEpisodeText = showFlag(cardText?.context, R.string.show_episode_text_key)
        val showHd = showFlag(textQuality?.context, R.string.show_hd_key)
        val showRatingView = showFlag(textQuality?.context, R.string.show_rating_key)
        if (card is SyncAPI.LibraryItem) {
            val ratingText = card.personalRating?.toStringNull(0.1, 10, 1)
            val showRating = !ratingText.isNullOrBlank()
            rating?.isVisible = showRating
            if (showRating) {
                rating?.text = ratingText
            }
        } else if (showRatingView) {
            // Phase 8.56: universal rating — a provider without a
            // score falls back to the TMDB/IMDb cache; a miss kicks
            // off background resolution (needs a key in Settings →
            // Account) and the rebind listener fills it in later.
            val ratingText = card.score?.toStringNull(0.1, 10, 1)
                ?: rating?.context?.let { ctx ->
                    MetadataResolver.cached(
                        ctx, card.name, MetadataResolver.yearOf(card),
                        MetadataResolver.isTv(card)
                    ).also { hit ->
                        if (hit == null) MetadataResolver.resolve(ctx, card)
                    }
                }
            val showRating = !ratingText.isNullOrBlank()
            rating?.isVisible = showRating
            if (showRating) {
                rating?.text = ratingText
            }
        }

        shadow?.isVisible = showTitle

        when (card.quality) {
            SearchQuality.BlueRay -> R.string.quality_blueray
            SearchQuality.Cam -> R.string.quality_cam
            SearchQuality.CamRip -> R.string.quality_cam_rip
            SearchQuality.DVD -> R.string.quality_dvd
            SearchQuality.HD -> R.string.quality_hd
            SearchQuality.HQ -> R.string.quality_hq
            SearchQuality.HdCam -> R.string.quality_cam_hd
            SearchQuality.Telecine -> R.string.quality_tc
            SearchQuality.Telesync -> R.string.quality_ts
            SearchQuality.WorkPrint -> R.string.quality_workprint
            SearchQuality.SD -> R.string.quality_sd
            SearchQuality.FourK -> R.string.quality_4k
            SearchQuality.UHD -> R.string.quality_uhd
            SearchQuality.SDR -> R.string.quality_sdr
            SearchQuality.HDR -> R.string.quality_hdr
            SearchQuality.WebRip -> R.string.quality_webrip
            null -> null
        }?.let { textRes ->
            textQuality?.setText(textRes)
            textQuality?.isVisible = showHd
        } ?: run {
            textQuality?.isVisible = false
        }

        // Phase 8.33: a resolved real title (TitleResolver) beats
        // the raw name, which for some provider families is blank
        // or an "Id <number>" placeholder.
        val resolvedTitle = TitleResolver.cached(cardText?.context, card.url)
        cardText?.text = resolvedTitle ?: cleanPosterTitle(card.name)
        cardText?.isVisible = showTitle
        if (resolvedTitle == null && TitleResolver.isIdLike(card.name)) {
            cardText?.context?.let { ctx ->
                TitleResolver.resolve(ctx, card.apiName, card.url, card.name)
            }
        }
        cardView.isVisible = true
        if (!card.posterUrl.isNullOrEmpty()) {
            // Phase 8.73: loadImage() disposes + crossfades on EVERY
            // bind, and Home rebinds constantly (resolved titles /
            // ratings landing, row rotation) — every rebind blinked
            // every visible poster, which he reads as the screen /
            // categories shaking. Skip the reload when this view
            // already shows this poster.
            if (cardView.tag != card.posterUrl) {
                cardView.tag = card.posterUrl
                cardView.loadImage(card.posterUrl, card.posterHeaders) {
                    // Phase 8.76: no crossfade on grid posters. The
                    // loader's default 150ms fade turned every rebind
                    // wave (titles/ratings landing during scroll)
                    // into a visible shimmer across the whole grid —
                    // the rest of his কাপাকাপি. Posters now cut in
                    // instantly; the URL guard above still skips
                    // reloads of posters already on screen.
                    crossfade(false)
                    error { getImageFromDrawable(itemView.context, R.drawable.poster_placeholder) }
                }
            }
        } else {
            cardView.tag = null
            cardView.loadImage(R.drawable.poster_placeholder)
        }

        fun click(view: View?) {
            clickCallback.invoke(
                SearchClickCallback(
                    if (card is DataStoreHelper.ResumeWatchingResult) SEARCH_ACTION_PLAY_FILE else SEARCH_ACTION_LOAD,
                    view ?: return,
                    position,
                    card
                )
            )
        }

        fun longClick(view: View?) {
            clickCallback.invoke(
                SearchClickCallback(
                    SEARCH_ACTION_SHOW_METADATA,
                    view ?: return,
                    position,
                    card
                )
            )
        }

        fun focus(view: View?, focus: Boolean) {
            if (focus) {
                clickCallback.invoke(
                    SearchClickCallback(
                        SEARCH_ACTION_FOCUSED,
                        view ?: return,
                        position,
                        card
                    )
                )
            }
        }

        bg.isFocusable = false
        bg.isFocusableInTouchMode = false
        if (!isLayout(TV)) {
            bg.setOnClickListener {
                click(it)
            }
            bg.setOnLongClickListener {
                longClick(it)
                return@setOnLongClickListener true
            }
        }
        //
        //
        //

        itemView.setOnClickListener {
            click(it)
        }
        if (nextFocusUp != null) {
            itemView.nextFocusUpId = nextFocusUp
        }

        if (nextFocusDown != null) {
            itemView.nextFocusDownId = nextFocusDown
        }

        /*when (nextFocusBehavior) {
            true -> itemView.nextFocusLeftId = bg.id
            false -> itemView.nextFocusRightId = bg.id
            null -> {
                bg.nextFocusRightId = -1
                bg.nextFocusLeftId = -1
            }
        }*/

        /*if (nextFocusUp != null) {
            bg.nextFocusUpId = nextFocusUp
        }

        if (nextFocusDown != null) {
            bg.nextFocusDownId = nextFocusDown
        }

        */

        if (isLayout(TV)) {
            // bg.isFocusable = true
            // bg.isFocusableInTouchMode = true
            // bg.touchscreenBlocksFocus = false
            itemView.isFocusableInTouchMode = true
            itemView.isFocusable = true
        }

        /**/

        itemView.setOnLongClickListener {
            longClick(it)
            return@setOnLongClickListener true
        }

        /*bg.setOnFocusChangeListener { view, b ->
            focus(view, b)
        }*/

        itemView.setOnFocusChangeListener { view, b ->
            focus(view, b)
        }

        when (card) {
            is LiveSearchResponse -> {
                SubtitleHelper.getFlagFromIso(card.lang)?.let { flagEmoji ->
                    textFlag?.apply {
                        isVisible = true
                        text = flagEmoji
                    }
                }
            }

            is DataStoreHelper.ResumeWatchingResult -> {
                val pos = card.watchPos?.fixVisual()
                if (pos != null) {
                    bar?.max = (pos.duration / 1000).toInt()
                    bar?.progress = (pos.position / 1000).toInt()
                    bar?.visibility = View.VISIBLE
                }
                // Phase 8.99e (his rule): the centre badge is a state
                // icon — PAUSE on the card the player most recently
                // had loaded, PLAY on every other card. Set in both
                // directions because holders recycle.
                playImg?.setImageResource(
                    if (card.id != null && card.id == DataStoreHelper.lastPlayedContentId) {
                        R.drawable.ic_baseline_pause_24
                    } else {
                        R.drawable.play_button_transparent
                    }
                )
                playImg?.visibility = View.VISIBLE
                if (card.type?.isMovieType() == false && showEpisodeText) {
                    episodeText?.context?.getShortSeasonText(card.episode, card.season)?.let {text->
                        episodeText.text = text
                        episodeText.isVisible = true
                    }
                }
            }

            is AnimeSearchResponse -> {
                val dubStatus = card.dubStatus
                if (!dubStatus.isNullOrEmpty()) {
                    if (dubStatus.contains(DubStatus.Dubbed)) {
                        textIsDub?.isVisible = showDub
                    }
                    if (dubStatus.contains(DubStatus.Subbed)) {
                        textIsSub?.isVisible = showSub
                    }
                }

                val dubEpisodes = card.episodes[DubStatus.Dubbed]
                val subEpisodes = card.episodes[DubStatus.Subbed]

                textIsDub?.apply {
                    val dubText = context.getString(R.string.app_dubbed_text)
                    text = if (dubEpisodes != null && dubEpisodes > 0) {
                        context.getString(R.string.app_dub_sub_episode_text_format)
                            .format(dubText, dubEpisodes)
                    } else {
                        dubText
                    }
                }

                textIsSub?.apply {
                    val subText = context.getString(R.string.app_subbed_text)
                    text = if (subEpisodes != null && subEpisodes > 0) {
                        context.getString(R.string.app_dub_sub_episode_text_format)
                            .format(subText, subEpisodes)
                    } else {
                        subText
                    }
                }
            }
        }

        // Phase 8.33: dub/sub badges used to be anime-only. Other
        // providers carry the language in the title's brackets
        // ("[Hindi Dub]", "(Hindi)") — surface it as the badge
        // instead of letting the title cleaner strip it silently.
        if (card !is AnimeSearchResponse && textIsDub?.text.isNullOrBlank()) {
            val langLabel = Regex("""[\[\(]([^\]\)]{3,24})[\)\]]""")
                .findAll(card.name)
                .map { it.groupValues[1].trim() }
                .firstOrNull { g ->
                    g.contains("dub", true) || g.contains("sub", true) ||
                        TITLE_LANGUAGES.any { g.equals(it, true) }
                }
            if (langLabel != null) {
                textIsDub?.text = langLabel
                textIsDub?.isVisible = showDub
            }
        }

        // This is the logic for making the rounded corners more round on the top and bottom element
        // a bit dirty to do memory allocation, but it makes it more extensible and is easier to reason about
        // then a large if statement

        // Requires that the ordering here is the same as in the xml
        val boxes = arrayListOf<TextView>()
        for (view in arrayOf(textIsDub, textIsSub, rating)) {
            if (view?.isVisible == true) {
                boxes.add(view)
            }
        }
        if (boxes.size == 1) {
            boxes[0].setBackgroundResource(R.drawable.bg_color_both)
        } else if (boxes.size > 1) {
            boxes[0].setBackgroundResource(R.drawable.bg_color_top)
            for (i in 1 until boxes.size) {
                boxes[i].setBackgroundResource(R.drawable.bg_color_center)
            }
            boxes[boxes.size - 1].setBackgroundResource(R.drawable.bg_color_bottom)
        }
        textIsDub?.apply {
            backgroundTintList = ColorStateList.valueOf(context.colorFromAttribute(R.attr.textColor))
        }
    }
}
