package com.lagradost.cloudstream3.ui.feed

import android.animation.ValueAnimator
import com.lagradost.cloudstream3.TvType
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedWatchBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.ui.search.TitleResolver
import com.lagradost.cloudstream3.ui.search.goldStarText
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.mvvm.logError
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.hapticTick
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase 8.18–8.20 — the Feed watch page (YouTube-style, portrait).
 * Playback itself lives in [FeedPlayer] (shared with the mini player);
 * this fragment is the full-screen UI over it: custom controls,
 * double-tap skip, quality/subtitle/audio pickers, next episode,
 * drag-down to minimise into the Feed, and the landscape/fullscreen
 * handoff to the main player.
 */
@UnstableApi
class FeedWatchFragment : BaseFragment<FragmentFeedWatchBinding>(
    BindingCreator.Inflate(FragmentFeedWatchBinding::inflate)
) {
    private var handedOff = false
    private var seeking = false
    private var tickCount = 0
    private var lastShownUrl: String? = null

    /** Phase 8.92: the last brightness the swipe gesture set —
     *  remembered so a trip to the batch page and back resumes
     *  adjusting from the user's level instead of the 0.5
     *  default. Phase 8.99b: the 8.99 video-only experiment
     *  (scrim + media3 Brightness effect) is REVERTED by his
     *  order — the level again drives the window's
     *  screenBrightness attribute exactly as in 8.92/8.97.
     *  Phase 8.99c: the window model had NO effect on his
     *  device at all — the gesture now drives the SYSTEM
     *  brightness exactly like the main player (see the
     *  brightness section below); this field remains the
     *  page's own record of the gesture level (HUD continuity
     *  and the window fallback's resume value). */
    private var savedFeedBrightness: Float? = null

    /** Phase 8.99c (job B): true while the gesture drives the
     *  SYSTEM brightness (Settings.System) — the main player's
     *  primary path. Flips false for the rest of this page
     *  instance's life the first time a system call throws
     *  (the modify-system-settings permission is missing) and
     *  the window attribute takes over: PlayerGestureHelper's
     *  exception-driven fail-over, mirrored. */
    private var useFeedSystemBrightness = true

    /** Phase 8.99c (job B): the system level read when this
     *  page was entered. Batch 2: it IS restored now (level
     *  + mode) by restoreSystemBrightness() on pause and on
     *  destroy, and the gesture level is re-applied on resume
     *  while this page is visible. */
    private var entrySystemBrightness: Float? = null

    /** Batch 2: the entry level/mode as raw system ints (no
     *  /255 rounding loss), restored on pause/destroy. */
    private var entrySystemBrightnessRaw: Int? = null
    private var entrySystemBrightnessMode: Int? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            // Phase 8.56: the buffering spinner and the controls
            // auto-hide are re-evaluated on every tick — deciding
            // them only on state-change callbacks is what left
            // videos spinning without an animation and controls
            // stuck on screen (his reports).
            updateBuffering()
            tickControlsHide()
            handler.postDelayed(this, 500L)
        }
    }
    private val hideControlsRunnable = Runnable { binding?.feedWatchControls?.isVisible = false }
    // 8.70: seek-wedge state (ported from the main player, 8.62) —
    // a burst counter so repeated double-taps show 10/20/30 seconds.
    private var seekFxToken = 0
    private var seekBurstMs = 0L
    private var seekBurstAt = 0L

    // Phase 8.38: navigating to the batch download page must not
    // kill the video being watched; onDestroyView checks this.
    private var goingToBatch = false

    private var titleFullHeight = 0
    private var titleShown = true
    private var titleAnimator: ValueAnimator? = null
    private var titleScrollAccum = 0

    private var orientationListener: OrientationEventListener? = null
    private var landscapeSince = 0L

    private val upNextAdapter = FeedAdapter(
        onPlay = { entry ->
            context?.let {
                FeedPlayer.play(
                    it, entry.response.url, entry.apiName,
                    entry.response.name, entry.response.posterUrl
                )
            }
            binding?.feedWatchUpnext?.scrollToPosition(0)
        },
        onHide = { entry -> confirmHide(entry) },
        // Phase 8.99b (job C): this adapter never kicked title
        // resolution — unlike the Feed grid's — so an id-titled
        // Up-next card could only ever show a title the grid
        // had already resolved. It kicks like every other
        // surface now.
        onTitleNeeded = { entry ->
            context?.let {
                TitleResolver.resolve(
                    it, entry.apiName, entry.response.url, entry.response.name
                )
            }
        }
    )

    /** Phase 8.22: long-press a card → offer to hide it for good. */
    private fun confirmHide(entry: FeedEntry) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(FeedAdapter.displayTitle(entry.response.name, entry.response.url))
            .setMessage(R.string.feed_hide_message)
            .setPositiveButton(R.string.feed_hide) { _, _ ->
                FeedHidden.hide(context, entry.response.url)
                FeedPool.hide(entry.response.url)
                FeedPlayer.currentUrl?.let { refreshUpNext(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val stateListener: () -> Unit = { updatePlaybackUi() }

    // Phase 8.74: when a raw "Id …" title resolves in the background,
    // refresh the header so the real title replaces the blank.
    // Phase 8.99b (job C): a landed title also reaches the Up-next
    // cards showing that item — previously only the CURRENT item's
    // header refreshed, so a title landing while this screen was
    // up never appeared on its cards here.
    private val watchTitleListener: (String, String) -> Unit = { url, title ->
        upNextAdapter.setTitleOverride(url, title)
        if (url == FeedPlayer.currentUrl) updateHeader()
    }

    /**
     * Phase 8.23 — the title bar gets out of the way while the viewer
     * scrolls Up next (YouTube-style) and comes back on scroll up.
     */
    private fun setupTitleAutoHide(binding: FragmentFeedWatchBinding) {
        binding.feedWatchTitleBox.post {
            titleFullHeight = binding.feedWatchTitleBox.height
        }
        binding.feedWatchUpnext.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Phase 8.41: firmer thresholds + never restart the
                // height animation mid-fling -- repeated re-layout
                // during scroll read as shaking on the watch page.
                if (titleAnimator?.isRunning == true) return
                titleScrollAccum += dy
                if (titleScrollAccum > 110 && titleShown) {
                    animateTitleBox(binding, show = false)
                    titleScrollAccum = 0
                } else if (titleScrollAccum < -60 && !titleShown) {
                    animateTitleBox(binding, show = true)
                    titleScrollAccum = 0
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (!recyclerView.canScrollVertically(-1) && !titleShown) {
                    animateTitleBox(binding, show = true)
                    titleScrollAccum = 0
                }
            }
        })
    }

    private var titleHeightDirty = false

    private fun animateTitleBox(binding: FragmentFeedWatchBinding, show: Boolean) {
        if (titleHeightDirty) {
            titleHeightDirty = false
            val box = binding.feedWatchTitleBox
            val widthSpec = if (box.width > 0) {
                android.view.View.MeasureSpec.makeMeasureSpec(
                    box.width, android.view.View.MeasureSpec.EXACTLY
                )
            } else {
                android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
            }
            box.measure(
                widthSpec,
                android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
            )
            if (box.measuredHeight > 0) {
                titleFullHeight = box.measuredHeight
            }
        }
        if (show == titleShown || titleFullHeight == 0) return
        titleShown = show
        val box = binding.feedWatchTitleBox
        val from = box.height
        val to = if (show) titleFullHeight else 0
        titleAnimator?.cancel()
        titleAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 200L
            addUpdateListener { animator ->
                val params = box.layoutParams
                params.height = animator.animatedValue as Int
                box.layoutParams = params
                box.alpha = if (to == 0) {
                    1f - animator.animatedFraction
                } else {
                    animator.animatedFraction
                }
            }
            start()
        }
    }

    // Phase 8.99e (his order: "ratio area lock"): the collapsing
    // player is GONE. Three shrink mechanisms (8.98 layout height,
    // 8.99 pinned PlayerView + clip box, 8.99b TextureView stage,
    // 8.99c uniform scale + follower translation) all still
    // deformed the picture on his device, and the scale version
    // left a black gap under the player. The player area is now
    // locked at its natural XML 16:9 forever: nothing in this
    // fragment writes the player box's size, scale or translation,
    // and the title / Up-next region below scrolls in its own
    // layout region exactly as the layout separates them.

    // Phase 8.98: the watch page's own trailer banner (the
    // Info sheet's slot, shared via FeedInfoSheet) — bound
    // from FeedPlayer.currentPage exactly like the sheet
    // binds it, torn down whenever the item changes.
    private var trailerViews: FeedInfoSheet.TrailerSlotViews? = null
    private var trailerPlayer: ExoPlayer? = null
    private var trailerBoundUrl: String? = null

    private var lastPlayerStatus: FeedPlayer.Status = FeedPlayer.Status.IDLE

    private val iconListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val binding = binding ?: return
            binding.feedWatchPlayer.keepScreenOn = isPlaying
            binding.feedWatchPlayPause.setImageResource(
                if (isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
            )
            if (isPlaying) {
                scheduleHideControls()
            } else if (FeedPlayer.exo?.playWhenReady == false) {
                // A real pause keeps the controls up. A transient
                // not-playing moment (seeking, buffering) must NOT
                // flash them — Phase 8.34: double-tap seeks used to
                // pop every icon on screen.
                showControls()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            updateBuffering()
        }

        // Phase 8.99b: the 8.99 onPlayerError hook (dropping the
        // brightness boost effect on error) is gone with the
        // rest of the video-effects machinery — playback errors
        // are FeedPlayer's own business (it fails over), as
        // they were before 8.99.
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: FragmentFeedWatchBinding) {
        goingToBatch = false
        FeedPlayer.warmUp(requireContext())
        binding.feedWatchPlayer.player = FeedPlayer.exo
        FeedPlayer.exo?.addListener(iconListener)
        FeedPlayer.addListener(stateListener)
        TitleResolver.addListener(watchTitleListener)

        // Phase 8.99c (job B): record the system brightness
        // this page was entered at (a plain read — reads need
        // no permission and must not flip the system-path
        // flag). Batch 2 restores this entry level + mode on
        // pause/destroy (see restoreSystemBrightness).
        if (entrySystemBrightness == null) {
            try {
                entrySystemBrightnessRaw = Settings.System.getInt(
                    requireContext().contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                )
                entrySystemBrightnessMode = Settings.System.getInt(
                    requireContext().contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE
                )
                entrySystemBrightness = entrySystemBrightnessRaw!! / 255f
            } catch (_: Exception) {
                entrySystemBrightness = null
                entrySystemBrightnessRaw = null
                entrySystemBrightnessMode = null
            }
        }

        // Phase 8.90: Up next is a 3-column grid like the Feed
        // itself (his order) — the feed card was already sized
        // for three-across in 8.85, so titles/meta stay readable.
        binding.feedWatchUpnext.layoutManager = GridLayoutManager(context, 3)
        binding.feedWatchUpnext.adapter = upNextAdapter
        setupTitleAutoHide(binding)

        // Phase 8.98: trailer banner (Job 4) — the Info
        // sheet's 16:9 slot, placed in the title box between
        // the action pills and the "For You" header (the title
        // box's last child). It stays gone until the loaded
        // page ships a trailer (see syncTrailer).
        val trailerSlotViews = FeedInfoSheet.buildTrailerSlot(requireContext())
        trailerViews = trailerSlotViews
        binding.feedWatchTitleBox.let { titleBox ->
            trailerSlotViews.slot.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
            titleBox.addView(
                trailerSlotViews.slot,
                (titleBox.childCount - 1).coerceAtLeast(0)
            )
        }

        val url = arguments?.getString("url") ?: return
        val apiName = arguments?.getString("apiName") ?: return
        val name = arguments?.getString("name") ?: url
        val poster = arguments?.getString("posterUrl")
        refreshUpNext(url)
        FeedPlayer.play(requireContext(), url, apiName, name, poster)

        // Phase 8.39: pull-to-refresh on Up next rotates in
        // not-yet-shown suggestions; a floating button appears
        // after scrolling a screenful, back to the top (video).
        binding.feedWatchUpnextRefresh.setOnRefreshListener {
            refreshUpNext(
                FeedPlayer.currentUrl ?: url,
                rotate = true
            )
            binding.feedWatchUpnextRefresh.isRefreshing = false
        }
        binding.feedWatchUpnext.addOnScrollListener(object :
            RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val first = (recyclerView.layoutManager as? GridLayoutManager)
                    ?.findFirstVisibleItemPosition() ?: 0
                binding.feedWatchToTop.isVisible = first >= 8
            }
        })
        // Phase 8.93: the Info link opens the rich details sheet
        // (FeedInfoSheet: description, cast, trailer) — it no
        // longer expands the plot inline under the title.
        // Phase 8.97: it is a text link now ("Info ›"), not a
        // pill button (his order — see fragment_feed_watch.xml).
        binding.feedWatchMore.isVisible = true
        binding.feedWatchMore.setOnClickListener { view ->
            view.hapticTick()
            val act = activity ?: return@setOnClickListener
            val watchUrl = FeedPlayer.currentUrl
                ?: arguments?.getString("url") ?: return@setOnClickListener
            val watchApi = FeedPlayer.currentApiName
                ?: arguments?.getString("apiName") ?: return@setOnClickListener
            val watchName = FeedPlayer.currentName
                ?: arguments?.getString("name") ?: watchUrl
            val watchPoster = FeedPlayer.currentPoster
                ?: arguments?.getString("posterUrl")
            FeedInfoSheet.show(
                act, watchUrl, watchApi, watchName, watchPoster,
                onPlay = {
                    if (FeedPlayer.currentUrl == watchUrl) {
                        // Already this item: just resume it.
                        FeedPlayer.exo?.playWhenReady = true
                    } else {
                        context?.let {
                            FeedPlayer.play(
                                it, watchUrl, watchApi, watchName, watchPoster
                            )
                        }
                    }
                },
                onOpenDetails = {
                    FeedPool.entries.firstOrNull { it.response.url == watchUrl }
                        ?.let { entry -> act.loadSearchResult(entry.response) }
                }
            )
        }
        binding.feedWatchToTop.setOnClickListener { view ->
            view.hapticTick()
            val first = (binding.feedWatchUpnext.layoutManager as? GridLayoutManager)
                ?.findFirstVisibleItemPosition() ?: 0
            if (first > 30) binding.feedWatchUpnext.scrollToPosition(30)
            binding.feedWatchUpnext.smoothScrollToPosition(0)
        }

        // Single tap toggles controls; double tap skips ∓10 s; a long
        // downward drag minimises into the Feed's mini player.
        // Phase 8.92: vertical swipes adjust brightness / volume,
        // main-player style — see the gesture helpers below.
        // Phase 8.92b (his safe-area order): the swipe zones are
        // set by the touch-DOWN x — left 30% = brightness, right
        // 30% = volume, and the middle 40% is a SAFE area: no
        // brightness/volume there, and the pull-down minimise
        // now starts from that middle zone at any height (the
        // 8.92 top-strip rule is gone). Side zones never
        // minimise.
        val gestureDetector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val view = binding.feedWatchPlayer
                    if (e.x < view.width / 2) {
                        skipBy(-10_000L, back = true)
                    } else {
                        skipBy(10_000L, back = false)
                    }
                    return true
                }
            }
        )
        var downX = 0f
        var downY = 0f
        var dragHandled = false
        var adjustClaimed = false
        var adjustBrightness = false
        // Phase 8.92b: the gesture zone, fixed at touch-down:
        // 0 = left 30% (brightness), 1 = middle 40% (safe),
        // 2 = right 30% (volume).
        var downZone = 1
        var startBrightness = 0.5f
        var startVolume = 0f
        binding.feedWatchPlayer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragHandled = false
                    adjustClaimed = false
                    downZone = when {
                        view.width <= 0 -> 1
                        downX < view.width * 0.30f -> 0
                        downX >= view.width * 0.70f -> 2
                        else -> 1
                    }
                    startBrightness = currentFeedBrightness()
                    startVolume = currentVolumeFraction()
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dyDown = event.y - downY
                    if (!dragHandled && !adjustClaimed &&
                        dyDown > 160f && downZone == 1 && FeedPlayer.isPlaying
                    ) {
                        dragHandled = true
                        minimizeToFeed()
                    } else if (!dragHandled) {
                        // The main player's claim rule
                        // (PlayerGestureHelper): a mostly-vertical
                        // move past 2% of the height claims the
                        // gesture — but only in the side zones;
                        // the safe middle never adjusts.
                        if (!adjustClaimed && downZone != 1 &&
                            abs(dyDown) > view.height * 0.02f &&
                            abs(dyDown) > abs(dx)
                        ) {
                            adjustClaimed = true
                            adjustBrightness = downZone == 0
                        }
                        if (adjustClaimed && view.height > 0) {
                            // Main-player multiplier (2.0): the
                            // full 0–100% range is half the view
                            // height of travel, applied from the
                            // value snapshotted at touch-down.
                            val start = if (adjustBrightness) {
                                startBrightness
                            } else {
                                startVolume
                            }
                            val next = (start +
                                (-dyDown) * 2.0f / view.height)
                                .coerceIn(0f, 1f)
                            if (adjustBrightness) {
                                applyFeedBrightness(next)
                            } else {
                                applyFeedVolume(next)
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    adjustClaimed = false
                }
            }
            gestureDetector.onTouchEvent(event)
            true
        }

        // Phase 8.34: quick actions beside the title — source/dub,
        // episodes, download — the Premium Details pill row idea.
        // Phase 8.62: TV cast option in the feed - the same
        // MediaRouteButton wiring as the details page. The button
        // starts gone and only appears when the Cast API is
        // available and the CastContext initialises.
        binding.feedWatchCast.let { castBtn ->
            activity?.let { act ->
                if (act.isCastApiAvailable()) {
                    try {
                        CastButtonFactory.setUpMediaRouteButton(act, castBtn)
                        CastContext.getSharedInstance(act.applicationContext) {
                            it.run()
                        }.addOnCompleteListener {
                            castBtn.isGone = !it.isSuccessful
                        }
                    } catch (e: Exception) {
                        logError(e)
                        castBtn.isGone = true
                    }
                }
            }
        }
        binding.feedWatchSourceBtn.setOnClickListener { view ->
            view.hapticTick()
            showQuality()
        }
        binding.feedWatchEpisodesBtn.setOnClickListener { view ->
            view.hapticTick()
            showEpisodePicker()
        }
        // Phase 8.57: Add to list — his placement (feed player
        // only, removed from details). Same watch-status dialog
        // and storage as the details bookmark FAB.
        binding.feedWatchListBtn.setOnClickListener { view ->
            view.hapticTick()
            showAddToListDialog()
        }
        binding.feedWatchDownloadBtn.setOnClickListener { view ->
            view.hapticTick()
            if (FeedPlayer.episodes.size > 1) {
                // Series: the batch page (resolution + audio
                // chips, per-episode multi-select).
                goingToBatch = true
                findNavController().navigate(R.id.navigation_feed_download)
            } else if (
                FeedPlayer.links.isEmpty() &&
                FeedPlayer.currentEpisode != null &&
                FeedPlayer.status == FeedPlayer.Status.LOADING
            ) {
                // Phase 8.43: the video is still loading — wait
                // for the playback extraction already in flight
                // and download with THOSE links, instead of
                // queueing a blind second extraction that may
                // never produce a file.
                Toast.makeText(
                    requireContext(), R.string.feed_download_preparing,
                    Toast.LENGTH_SHORT
                ).show()
                updateDownloadPill()
                viewLifecycleOwner.lifecycleScope.launch {
                    var waited = 0
                    while (FeedPlayer.links.isEmpty() && waited < 60) {
                        if (FeedPlayer.status == FeedPlayer.Status.ERROR) break
                        delay(500)
                        waited++
                    }
                    if (FeedPlayer.downloadCurrentEpisode()) {
                        context?.let {
                            Toast.makeText(
                                it, R.string.download_started,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    updateDownloadPill()
                }
            } else if (FeedPlayer.downloadCurrentEpisode()) {
                Toast.makeText(requireContext(), R.string.download_started, Toast.LENGTH_SHORT)
                    .show()
                updateDownloadPill()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadQueueManager.queue.collect { updateDownloadPill() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            VideoDownloadManager.currentDownloads.collect { updateDownloadPill() }
        }

        binding.feedWatchPlayPause.setOnClickListener { FeedPlayer.togglePlay() }
        binding.feedWatchFullscreen.setOnClickListener { view ->
            view.hapticTick()
            openInMainPlayer()
        }
        binding.feedWatchNext.setOnClickListener { view ->
            view.hapticTick()
            FeedPlayer.nextEpisode()
        }
        binding.feedWatchSubtitles.setOnClickListener { showSubtitlePicker() }
        binding.feedWatchAudio.setOnClickListener { showAudioPicker() }
        binding.feedWatchSeek.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val exo = FeedPlayer.exo ?: return
                    val duration = exo.duration.takeIf { it > 0 } ?: return
                    binding.feedWatchTimeCurrent.text = formatTime(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seeking = false
                val exo = FeedPlayer.exo ?: return
                val duration = exo.duration.takeIf { it > 0 } ?: return
                exo.seekTo(duration * (seekBar?.progress ?: 0) / 1000)
            }
        })
        binding.feedWatchRetry.setOnClickListener {
            context?.let { FeedPlayer.replayCurrent(it) }
        }
        binding.feedWatchPlayMain.setOnClickListener { openInMainPlayer() }
        binding.feedWatchOpenDetails.setOnClickListener {
            val current = FeedPlayer.currentUrl ?: return@setOnClickListener
            FeedPool.entries.firstOrNull { it.response.url == current }?.let { entry ->
                activity.loadSearchResult(entry.response)
            }
        }
        setupOrientationHandoff()
        updatePlaybackUi()
    }

    override fun onResume() {
        super.onResume()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        handler.post(progressRunnable)
        orientationListener?.enable()
        // Phase 8.97: onPause handed the brightness back
        // (see below) — take the gesture's level again now
        // that this screen is the resumed, visible one.
        // Batch 2: that round trip now covers system mode
        // too (onPause restores the entry level + mode, and
        // the else branch below re-applies the gesture level).
        if (!useFeedSystemBrightness) {
            savedFeedBrightness?.let { setWindowBrightness(it) }
        } else {
            // Batch 2: onPause handed the entry system level
            // back — take the gesture's level again now that
            // this screen is the resumed, visible one.
            savedFeedBrightness?.let { setFeedBrightness(it) }
        }
        if (handedOff) {
            handedOff = false
            context?.let { FeedPlayer.replayCurrent(it) }
        }
    }

    override fun onPause() {
        FeedPlayer.savePosition()
        handler.removeCallbacks(progressRunnable)
        orientationListener?.disable()
        // Phase 8.97: the brightness override must not
        // outlive this screen's visibility. Until now the
        // ONLY restore point was onDestroyView — but this
        // fragment shares MainActivity's window with the
        // whole app, and any pause that leaves the fragment
        // alive (the activity covered/backgrounded, e.g. a
        // call or a system activity) left the gesture's
        // level sitting on that shared window, so the whole
        // display — not just this screen — ran at the
        // video's brightness (his 12:12 report). The level
        // itself is kept in savedFeedBrightness and onResume
        // re-applies it, so a pause/resume round trip is
        // invisible to the viewer.
        // Batch 2: hand back BOTH the window override and
        // the entry system level + mode, so a pause that
        // leaves this fragment alive (a call, a system
        // activity) no longer leaves the video's brightness
        // sitting on the whole display. The gesture level is
        // kept in savedFeedBrightness and onResume re-applies
        // it, so a pause/resume round trip is invisible to
        // the viewer.
        restoreWindowBrightness()
        restoreSystemBrightness()
        super.onPause()
    }

    override fun onDestroyView() {
        // Phase 8.98: the trailer's inline player dies with
        // the view (the sheet releases its own on dismiss).
        trailerPlayer?.release()
        trailerPlayer = null
        trailerViews = null
        trailerBoundUrl = null
        FeedPlayer.removeListener(stateListener)
        TitleResolver.removeListener(watchTitleListener)
        FeedPlayer.exo?.removeListener(iconListener)
        handler.removeCallbacksAndMessages(null)
        orientationListener?.disable()
        orientationListener = null
        binding?.feedWatchPlayer?.player = null
        // Leaving for good (not minimised, not handed off) stops playback.
        // Phase 8.38: the batch download page is a child of this
        // screen — keep the video (paused) so Back returns to the
        // same video at the same position.
        if (goingToBatch) {
            FeedPlayer.exo?.pause()
        } else if (!handedOff && !FeedPlayer.minimized) {
            FeedPlayer.stop()
        }
        // Phase 8.92: hand the window's brightness back — the
        // swipe gesture's override belongs to this screen only
        // and must not leak into the rest of the app.
        // Phase 8.97: onPause already restored it (the override
        // now lives only while this screen is resumed); this
        // stays as the final backstop for teardown paths.
        // Batch 2: final backstop for teardown paths — the
        // entry system level + mode are handed back here too,
        // not just the window override.
        restoreWindowBrightness()
        restoreSystemBrightness()
        // After a handoff the main player owns the orientation; only
        // restore it when this page is really being left behind.
        if (!handedOff) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        }
        super.onDestroyView()
    }

    // ---------- UI state ----------

    private fun updatePlaybackUi() {
        val binding = binding ?: return
        val exo = FeedPlayer.exo
        when (FeedPlayer.status) {
            FeedPlayer.Status.LOADING -> {
                binding.feedWatchErrorBox.isVisible = false
                binding.feedWatchControls.isVisible = false
                // The poster carries the loading state; the (empty) video
                // surface stays hidden until the first frame is ready.
                binding.feedWatchPlayer.isVisible = false
                binding.feedWatchPoster.isVisible = FeedPlayer.currentPoster != null
                FeedPlayer.currentPoster?.let {
                    binding.feedWatchPoster.loadImage(it)
                }
                binding.feedWatchLoadingBox.isVisible = true
                // Phase 8.35: the "Finding sources" counter is
                // hidden by request — just the spinner.
                binding.feedWatchSourceCount.isVisible = false
            }

            FeedPlayer.Status.READY -> {
                binding.feedWatchLoadingBox.isVisible = false
                binding.feedWatchPoster.isVisible = false
                binding.feedWatchPlayer.isVisible = true
                binding.feedWatchErrorBox.isVisible = false
                // Phase 8.35: show the controls only when playback
                // (re)starts — this state callback also fires on
                // every seek/buffer churn, and re-showing the
                // controls there is what kept popping every icon
                // over the video on double-tap seeks.
                if (lastPlayerStatus != FeedPlayer.Status.READY) {
                    showControls()
                }
            }

            FeedPlayer.Status.ERROR -> {
                binding.feedWatchLoadingBox.isVisible = false
                binding.feedWatchPoster.isVisible = false
                binding.feedWatchPlayer.isVisible = false
                binding.feedWatchControls.isVisible = false
                binding.feedWatchErrorBox.isVisible = true
                binding.feedWatchPlayMain.isVisible = FeedPlayer.generator != null
            }

            FeedPlayer.Status.IDLE -> Unit
        }
        lastPlayerStatus = FeedPlayer.status
        updateDownloadPill()
        if (exo != null) {
            binding.feedWatchPlayPause.setImageResource(
                if (exo.isPlaying) R.drawable.netflix_pause else R.drawable.netflix_play
            )
        }
        binding.feedWatchNext.isVisible = FeedPlayer.hasNextEpisode
        binding.feedWatchEpisodesBtn.isVisible = FeedPlayer.episodes.size > 1
        updateHeader()
        val current = FeedPlayer.currentUrl
        if (current != null && current != lastShownUrl) {
            lastShownUrl = current
            refreshUpNext(current)
        }
        // Phase 8.56: buffering gets the final word — the READY
        // branch above hides the spinner on every state callback,
        // including the churn that precedes a rebuffer.
        updateBuffering()
        syncTrailer()
    }

    /**
     * Phase 8.98: bind the trailer banner from the player's
     * loaded page — the same data path the Info sheet uses
     * (FeedPlayer.currentPage for the current url). The banner
     * appears only when the page ships a trailer; tapping it
     * swaps in the inline player in the same slot via the
     * sheet's own playTrailer (which pauses the feed video
     * first). A new item tears the old trailer down.
     */
    private fun syncTrailer() {
        val views = trailerViews ?: return
        val url = FeedPlayer.currentUrl
        val page = FeedPlayer.currentPage?.takeIf { it.url == url }
        if (page == null) {
            // Page not loaded (yet) for this item; if the item
            // changed under a bound trailer, tear it down now.
            if (trailerBoundUrl != null && trailerBoundUrl != url) {
                resetTrailer()
            }
            return
        }
        if (trailerBoundUrl == url) return
        resetTrailer()
        trailerBoundUrl = url
        val trailer = page.trailers.firstOrNull() ?: return
        val bannerArt = page.backgroundPosterUrl
            ?: page.posterUrl ?: FeedPlayer.currentPoster
        if (!bannerArt.isNullOrBlank()) {
            views.image.loadImage(bannerArt, page.posterHeaders)
        }
        views.slot.isVisible = true
        views.banner.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            FeedInfoSheet.playTrailer(
                ctx, views.slot, views.banner, trailer,
                viewLifecycleOwner.lifecycleScope
            ) { player ->
                trailerPlayer = player
            }
        }
    }

    /** Release the trailer player (if any) and restore the
     *  slot to its banner-only, hidden state. */
    private fun resetTrailer() {
        trailerPlayer?.release()
        trailerPlayer = null
        trailerBoundUrl = null
        val views = trailerViews ?: return
        for (i in views.slot.childCount - 1 downTo 0) {
            val child = views.slot.getChildAt(i)
            if (child !== views.banner) views.slot.removeViewAt(i)
        }
        views.banner.isVisible = true
        views.slot.isGone = true
    }

    private fun updateBuffering() {
        val binding = binding ?: return
        val exo = FeedPlayer.exo ?: return
        if (FeedPlayer.status == FeedPlayer.Status.READY &&
            exo.playbackState == Player.STATE_BUFFERING
        ) {
            binding.feedWatchLoadingBox.isVisible = true
        } else if (FeedPlayer.status == FeedPlayer.Status.READY) {
            binding.feedWatchLoadingBox.isVisible = false
        }
    }

    private fun updateHeader() {
        val binding = binding ?: return
        val name = FeedPlayer.currentName ?: return
        // Phase 8.74: never print the raw "Id 1260…" title while the
        // video loads — show the resolved title when one is cached,
        // kick off resolution and keep the title blank until the
        // resolver listener refreshes this header.
        val currentUrl0 = FeedPlayer.currentUrl ?: ""
        val titleOverride = TitleResolver.cached(context, currentUrl0)
        binding.feedWatchTitle.text = when {
            titleOverride != null ->
                FeedAdapter.displayTitle(titleOverride, currentUrl0)
            TitleResolver.isIdLike(name) -> {
                context?.let { ctx ->
                    TitleResolver.resolve(
                        ctx, FeedPlayer.currentApiName ?: "",
                        currentUrl0, name
                    )
                }
                ""
            }
            else -> FeedAdapter.displayTitle(name, currentUrl0)
        }
        val ep = FeedPlayer.currentEpisode
        val apiName = FeedPlayer.currentApiName ?: ""
        val typeText = ep?.let { FeedAdapter.typeLabel(it.tvType) } ?: ""
        // Phase 8.57: a movie's single "episode" is the film
        // itself — never label it S1 E1.
        val isMovieLike = ep?.tvType == TvType.Movie ||
            ep?.tvType == TvType.AnimeMovie ||
            FeedPlayer.currentPage?.type == TvType.Movie ||
            FeedPlayer.currentPage?.type == TvType.AnimeMovie
        val epText = if (ep != null && ep.episode > 0 && !isMovieLike) {
            " • S${ep.season ?: 1} E${ep.episode}"
        } else {
            ""
        }
        // Phase 8.71: rating + year next to the title (his request).
        // Sources, best first: the loaded page's own score, then the
        // universal TMDB/OMDb cache for the title+year.
        val page0 = FeedPlayer.currentPage
        val year = page0?.year
        val headerRating = page0?.score?.toStringNull(0.5, 10)
            ?: com.lagradost.cloudstream3.ui.search.MetadataResolver
                .cached(
                    context, name, year,
                    com.lagradost.cloudstream3.ui.search.MetadataResolver
                        .isTv(ep?.tvType ?: page0?.type)
                )
        // Phase 8.71: teach the feed cards this item's real type, so
        // a card tagged "Anime" by its provider agrees with this
        // screen's "Movie" after the first open.
        val currentUrl = FeedPlayer.currentUrl
        val loadedType = ep?.tvType ?: page0?.type
        val appCtx = context
        if (currentUrl != null && loadedType != null && appCtx != null) {
            com.lagradost.cloudstream3.ui.search.TitleResolver
                .storeType(appCtx, currentUrl, loadedType)
        }
        binding.feedWatchMeta.text = goldStarText(
            listOf(FeedPlayer.currentApiName ?: apiName, typeText)
                .filter { it.isNotBlank() }.joinToString(" • ") + epText +
                (if (headerRating != null) " • ★ $headerRating" else "") +
                (if (year != null) " • $year" else "")
        )
        // Phase 8.57: Add-to-list label mirrors the details
        // bookmark FAB — the watch status once one is set.
        val page = FeedPlayer.currentPage
        if (page != null) {
            val watchType = DataStoreHelper.getResultWatchState(page.getId())
            binding.feedWatchListLabel.setText(
                if (watchType == WatchType.NONE) {
                    R.string.add_to_list
                } else {
                    watchType.stringRes
                }
            )
        }
        // Phase 8.93: the Info link stays available whenever the
        // watch screen is up — the sheet loads whatever the
        // player has not loaded yet, so it no longer waits for a
        // plot to exist.
        binding.feedWatchMore.isVisible = true
        binding.feedWatchMore.setText(R.string.feed_info)
        // Phase 8.42: the collapse animation's full height was
        // measured once per view -- a new item whose title/meta
        // needs a different height kept the old (smaller) one
        // and the meta line drew OVER the title. Re-measure on
        // the next collapse animation instead.
        titleHeightDirty = true
    }

    // Phase 8.39: urls already presented at the top of Up next;
    // a pull-to-refresh brings pool entries that were NOT in
    // that window first, so the visible content actually changes.
    private var upNextShownUrls: Set<String> = emptySet()

    private fun refreshUpNext(exceptUrl: String, rotate: Boolean = false) {
        val all = FeedPool.entries.filter { it.response.url != exceptUrl }
        if (!rotate) {
            upNextShownUrls = all.take(30).map { it.response.url }.toSet()
            upNextAdapter.submitList(all)
            return
        }
        val fresh = all.filter { it.response.url !in upNextShownUrls }.shuffled()
        val rest = all.filter { it.response.url in upNextShownUrls }.shuffled()
        val merged = fresh + rest
        upNextShownUrls = merged.take(30).map { it.response.url }.toSet()
        // Phase 8.43: scroll AFTER the diff commits — scrolling
        // first let the layout manager re-anchor to the old
        // first item's new position, flinging the list down.
        upNextAdapter.submitList(merged) {
            binding?.feedWatchUpnext?.scrollToPosition(0)
        }
    }

    // ---------- controls ----------

    private fun updateProgress() {
        val exo = FeedPlayer.exo ?: return
        val binding = binding ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        if (!seeking && duration > 0) {
            binding.feedWatchSeek.progress =
                (exo.currentPosition * 1000 / duration).toInt()
        }
        binding.feedWatchTimeCurrent.text = formatTime(exo.currentPosition)
        binding.feedWatchTimeTotal.text = formatTime(duration)
        if (++tickCount % 10 == 0) FeedPlayer.savePosition()
    }

    private fun toggleControls() {
        val binding = binding ?: return
        if (FeedPlayer.status != FeedPlayer.Status.READY) return
        if (binding.feedWatchControls.isVisible) {
            binding.feedWatchControls.isVisible = false
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            showControls()
        }
    }

    private fun showControls() {
        val binding = binding ?: return
        if (FeedPlayer.status != FeedPlayer.Status.READY) return
        binding.feedWatchControls.isVisible = true
        controlsShownAt = System.currentTimeMillis()
        scheduleHideControls()
    }

    /** Phase 8.56: when the controls were (re)shown. */
    private var controlsShownAt = 0L

    /**
     * Phase 8.56: the scheduled hide only posts while the player
     * reports playing at schedule time — show the controls while
     * paused, press play, and the timer was never set, so the
     * bar stayed up forever (his "title bar auto-hide হয় না"
     * report). The tick is the backstop: playing + visible for
     * 3s = hide, no matter how we got here.
     */
    private fun tickControlsHide() {
        val binding = binding ?: return
        if (!binding.feedWatchControls.isVisible) return
        if (!FeedPlayer.isPlaying) return
        if (System.currentTimeMillis() - controlsShownAt >= 3_000L) {
            binding.feedWatchControls.isVisible = false
            handler.removeCallbacks(hideControlsRunnable)
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (FeedPlayer.isPlaying) {
            handler.postDelayed(hideControlsRunnable, 3_000L)
        }
    }

    private fun skipBy(deltaMs: Long, back: Boolean) {
        val exo = FeedPlayer.exo ?: return
        val binding = binding ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, duration))
        binding.feedWatchPlayer.hapticTick()
        // 8.70: show the main player's seek wedge; repeated taps inside
        // the burst window accumulate the displayed seconds.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - seekBurstAt > 900L) seekBurstMs = 0L
        seekBurstAt = now
        seekBurstMs += 10_000L
        showFeedSeekFx(back, seekBurstMs / 1000L)
    }

    /** The main player's wedge animation (PlayerView.showSeekFx, 8.62). */
    private fun showFeedSeekFx(back: Boolean, seconds: Long) {
        val binding = binding ?: return
        val fx = if (back) binding.feedSeekFxLeft else binding.feedSeekFxRight
        val label = if (back) binding.feedSeekFxLeftText else binding.feedSeekFxRightText
        label.text = getString(R.string.seek_fx_seconds_format, seconds)
        val token = ++seekFxToken
        fx.animate().cancel()
        fx.visibility = android.view.View.VISIBLE
        fx.alpha = 0f
        fx.animate().alpha(1f).setDuration(130).start()
        fx.postDelayed({
            if (token == seekFxToken) {
                fx.animate().alpha(0f).setDuration(240)
                    .withEndAction {
                        if (token == seekFxToken) fx.visibility = android.view.View.GONE
                    }
                    .start()
            }
        }, 620)
    }

    private fun minimizeToFeed() {
        FeedPlayer.minimized = true
        FeedPlayer.savePosition()
        binding?.feedWatchPlayer?.hapticTick()
        findNavController().popBackStack()
    }

    // ---------- Phase 8.92: brightness / volume swipe gestures ----------

    // Phase 8.99c (job B — his order: full SCREEN-LEVEL
    // brightness): the gesture now drives the brightness the
    // way the MAIN PLAYER does, because the main player's
    // brightness works on his phone while the 8.92–8.99b
    // window-only model (restored in 8.99b on his "আগের
    // সিস্টেম টাই আনো" order) had NO effect on his device at
    // all. The mechanism is mirrored from PlayerGestureHelper
    // (read first-hand for this change; the helper itself is
    // not reused — it is bound to the main player's
    // PlayerView, its lock / extra-brightness machinery and
    // its rails):
    //  - read: Settings.System.SCREEN_BRIGHTNESS / 255;
    //  - write: SCREEN_BRIGHTNESS_MODE_MANUAL first, then
    //    SCREEN_BRIGHTNESS on the same 0..255 scale;
    //  - permission: there is no canWrite /
    //    ACTION_MANAGE_WRITE_SETTINGS flow anywhere in this
    //    app (grep-verified) — the helper's handling IS the
    //    exception-driven fail-over: the first system call
    //    that throws (permission missing) retires the system
    //    path for this page instance and the window attribute
    //    takes over, with the helper's 0.004 floor;
    //  - exit contract (Batch 2): the entry system level +
    //    mode ARE handed back on pause/destroy (see
    //    restoreSystemBrightness) and the gesture level is
    //    re-applied on resume, so the level stays set only
    //    while this page is the visible one. The WINDOW
    //    fallback keeps the 8.97 discipline (override handed
    //    back in onPause/onDestroyView, re-applied in
    //    onResume) because that override lives on the app's
    //    shared window.
    //  Batch 2 fixed the helper's write to the full 0..255
    //  scale (it was min(1, fraction x 255), capped at 1 of
    //  255, contradicting its own read of x / 255), so both
    //  paths now write the value the read side expects; the
    //  path, the manual-mode handling, the fallback order and
    //  the floors are otherwise the helper's own.

    /** The gesture's starting brightness, mirroring
     *  PlayerGestureHelper.getBrightness: the SYSTEM level
     *  while the system path is live; a failed read retires
     *  the path and re-reads on the fallback — the window
     *  attribute when one is set, else the level this page
     *  last used, else mid. */
    private fun currentFeedBrightness(): Float {
        if (useFeedSystemBrightness) {
            try {
                return Settings.System.getInt(
                    activity?.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            } catch (_: Exception) {
                // The helper's lazy permission check: because
                // true system brightness requires permission,
                // the throw IS the check.
                useFeedSystemBrightness = false
                return currentFeedBrightness()
            }
        }
        val attr = activity?.window?.attributes?.screenBrightness ?: -1f
        return when {
            attr >= 0f -> attr
            savedFeedBrightness != null -> savedFeedBrightness!!
            else -> 0.5f
        }
    }

    /** Write a brightness level, mirroring
     *  PlayerGestureHelper.setBrightness: manual mode first,
     *  then the system value; any failure retires the system
     *  path and the same value is written to the window
     *  fallback instead — the gesture never fails silently. */
    private fun setFeedBrightness(fraction: Float) {
        if (useFeedSystemBrightness) {
            try {
                val resolver = activity?.contentResolver ?: return
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    (fraction.coerceIn(0f, 1f) * 255).toInt()
                )
                return
            } catch (_: Exception) {
                useFeedSystemBrightness = false
                // fall through to the window write below
            }
        }
        setWindowBrightness(fraction)
    }

    /** The window-attribute write — since 8.99c only the
     *  fallback for a missing system permission (the main
     *  player's fallback, its 0.004 floor included: a value
     *  too close to 0 makes some devices substitute their
     *  own brightness, per the helper's own note). While
     *  this override is in play its lifetime is this screen's
     *  RESUMED lifetime (the 8.97 discipline): set by the
     *  gesture, re-applied by onResume, handed back in
     *  onPause and again in onDestroyView. */
    private fun setWindowBrightness(fraction: Float) {
        val act = activity ?: return
        val lp = act.window.attributes
        lp.screenBrightness = fraction.coerceIn(0.004f, 1f)
        act.window.attributes = lp
    }

    /** Hand the window's brightness back to the system/app
     *  default (BRIGHTNESS_OVERRIDE_NONE). No-op when no
     *  override is in place. */
    private fun restoreWindowBrightness() {
        activity?.window?.let { win ->
            val lp = win.attributes
            val none = android.view.WindowManager.LayoutParams
                .BRIGHTNESS_OVERRIDE_NONE
            if (lp.screenBrightness != none) {
                lp.screenBrightness = none
                win.attributes = lp
            }
        }
    }

    /** Batch 2: hand the entry system level + mode back.
     *  No-op in window-fallback mode (no system level was
     *  ever written) or when no entry snapshot exists. The
     *  snapshot is kept for the page's life: onPause restores
     *  it, onResume re-applies the gesture level, and
     *  onDestroyView restores it finally. */
    private fun restoreSystemBrightness() {
        if (!useFeedSystemBrightness) return
        val level = entrySystemBrightnessRaw ?: return
        val mode = entrySystemBrightnessMode ?: return
        try {
            val resolver = activity?.contentResolver ?: return
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode
            )
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS, level
            )
        } catch (_: Exception) {
            useFeedSystemBrightness = false
        }
    }

    private fun applyFeedBrightness(fraction: Float) {
        setFeedBrightness(fraction)
        savedFeedBrightness = fraction
        showGestureIndicator(brightness = true, fraction = fraction)
    }

    private fun audioManager(): AudioManager? =
        activity?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun currentVolumeFraction(): Float {
        val am = audioManager() ?: return 0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
            .toFloat() / max
    }

    private fun applyFeedVolume(fraction: Float) {
        val am = audioManager() ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = (fraction * max).roundToInt().coerceIn(0, max)
        // Flag 0: no system volume UI — the gesture indicator
        // below is the feedback (same as the main player).
        am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        showGestureIndicator(brightness = false, fraction = fraction)
    }

    private val gestureHideRunnable = Runnable {
        val binding = binding ?: return@Runnable
        // Phase 8.97: the main player's rails fade out (300 ms)
        // rather than hard-cutting — mirror that.
        for (holder in listOf(
            binding.feedGestureLeft, binding.feedGestureRight
        )) {
            if (holder.isVisible) {
                holder.animate().cancel()
                holder.animate().alpha(0f).setDuration(300)
                    .withEndAction {
                        holder.isVisible = false
                        holder.alpha = 1f
                    }
                    .start()
            }
        }
    }

    /** Phase 8.97: the main player's brightness icons, by
     *  level (PlayerGestureHelper.brightnessIcons) — the feed
     *  used to hard-code the middle sun for every level. */
    private val brightnessIcons = listOf(
        R.drawable.sun_1, R.drawable.sun_2, R.drawable.sun_3,
        R.drawable.sun_4, R.drawable.sun_5, R.drawable.sun_6,
        R.drawable.sun_7
    )

    /**
     * The on-video gesture feedback: the MAIN PLAYER's slim
     * side rail (his order — the 8.92 centred dark box with
     * its thick bar is gone). Phase 8.98: the rail stands on
     * the side OPPOSITE the gesture zone (brightness is
     * adjusted from the left zone, its rail renders on the
     * right edge — the gesturing hand used to cover it):
     * icon + percentage over a 5dp vertical level bar drawn
     * with the main player's own progress_drawable_vertical
     * at its 100_000 max (progress clamped to the same
     * 2_000 floor PlayerGestureHelper uses). The other rail
     * hides immediately; the shown rail fades out 1.5 s
     * after the gesture settles, like the main player's.
     * (The holder ids keep their 8.97 names — feedGestureLeft
     * is the brightness holder; only its screen side moved,
     * in fragment_feed_watch.xml.)
     */
    private fun showGestureIndicator(brightness: Boolean, fraction: Float) {
        val binding = binding ?: return
        val holder = if (brightness) {
            binding.feedGestureLeft
        } else {
            binding.feedGestureRight
        }
        val other = if (brightness) {
            binding.feedGestureRight
        } else {
            binding.feedGestureLeft
        }
        val icon = if (brightness) {
            binding.feedGestureLeftIcon
        } else {
            binding.feedGestureRightIcon
        }
        val text = if (brightness) {
            binding.feedGestureLeftText
        } else {
            binding.feedGestureRightText
        }
        val bar = if (brightness) {
            binding.feedGestureLeftBar
        } else {
            binding.feedGestureRightBar
        }
        icon.setImageResource(
            if (brightness) {
                val idx = (fraction * brightnessIcons.lastIndex)
                    .roundToInt()
                    .coerceIn(0, brightnessIcons.lastIndex)
                brightnessIcons[idx]
            } else when {
                fraction <= 0.01f -> R.drawable.ic_baseline_volume_mute_24
                fraction < 0.5f -> R.drawable.ic_baseline_volume_down_24
                else -> R.drawable.ic_baseline_volume_up_24
            }
        )
        val percent = (fraction * 100).roundToInt()
        text.text = "$percent%"
        bar.progress = (fraction * 100_000).toInt()
            .coerceIn(2_000, 100_000)
        other.animate().cancel()
        other.isVisible = false
        other.alpha = 1f
        holder.animate().cancel()
        holder.alpha = 1f
        holder.isVisible = true
        handler.removeCallbacks(gestureHideRunnable)
        handler.postDelayed(gestureHideRunnable, 1_500L)
    }

    // ---------- pickers ----------

    /**
     * Phase 8.24 — the source list, like the main player's: one row
     * per link, but labelled compactly (resolution + audio language
     * or format) instead of the full release name the owner rejected
     * in 8.21. Languages on multi-audio sources are separate links,
     * so this list is also where the audio language is chosen.
     */
    private fun showQuality() {
        val context = context ?: return
        val exo = FeedPlayer.exo ?: return
        val links = FeedPlayer.links
        val current = links.getOrNull(FeedPlayer.linkIndex) ?: return
        // Phase 8.41: an adaptive link (DASH/HLS) carries several
        // renditions inside one stream -- the main player exposes
        // them as tracks, the source list only showed the file.
        // Offer both: sources switch files, renditions switch the
        // in-stream quality of the current file.
        val adaptive = current.type ==
            com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH ||
            current.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
        val renditions = if (adaptive) {
            trackOptions(TRACK_TYPE_VIDEO).filter {
                !it.label.startsWith("Track")
            }
        } else {
            emptyList()
        }
        if (links.size < 2 && renditions.size < 2) return
        val linkLabels = links.map { linkLabel(it) }
        val renditionLabels = renditions.map { "${it.label} \u2022 Stream" }
        val labels = (linkLabels + renditionLabels).toTypedArray()
        val selectedRendition = renditions.indexOfFirst { it.selected }
        val checked = if (selectedRendition >= 0) {
            links.size + selectedRendition
        } else {
            FeedPlayer.linkIndex
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_sources)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which < links.size) {
                    FeedPlayer.selectLink(which)
                } else {
                    val option = renditions[which - links.size]
                    exo.trackSelectionParameters =
                        exo.trackSelectionParameters.buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(
                                    option.group.mediaTrackGroup,
                                    option.index
                                )
                            )
                            .build()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun linkLabel(link: com.lagradost.cloudstream3.utils.ExtractorLink): String {
        val res = if (link.quality > 0) "${link.quality}p" else "Auto"
        val audio = Regex("\\(([^)]+)\\)").find(link.name)?.groupValues?.get(1)
        val kind = when (link.type) {
            com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH -> "DASH"
            com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 -> "HLS"
            else -> "MP4"
        }
        return if (audio != null) "$res • $audio • $kind" else "$res • $kind"
    }

    private data class TrackOption(
        val label: String,
        val group: Tracks.Group,
        val index: Int,
        val selected: Boolean
    )

    private fun trackOptions(type: Int): List<TrackOption> {
        val exo = FeedPlayer.exo ?: return emptyList()
        val options = mutableListOf<TrackOption>()
        for (group in exo.currentTracks.groups.filter { it.type == type }) {
            for (i in 0 until group.length) {
                val format = group.mediaTrackGroup.getFormat(i)
                val base = when {
                    type == TRACK_TYPE_VIDEO && format.height > 0 -> "${format.height}p"
                    else -> format.label ?: format.language ?: "Track ${i + 1}"
                }
                val label = if (type == TRACK_TYPE_AUDIO && format.channelCount > 2) {
                    "$base • ${format.channelCount}ch"
                } else {
                    base
                }
                options.add(TrackOption(label, group, i, group.isTrackSelected(i)))
            }
        }
        return options
    }

    /** Phase 8.34: episode list for the title-row Episodes button. */
    /**
     * Phase 8.37: YouTube-style download affordance on the pill —
     * a spinning ring while the current episode is queued or
     * downloading, a check once it is downloaded.
     */
    private fun updateDownloadPill() {
        val binding = binding ?: return
        val epId = FeedPlayer.currentEpisode?.id
        val done = epId != null &&
            VideoDownloadManager.downloadStatus[epId] ==
            VideoDownloadManager.DownloadType.IsDone
        val active = epId != null && (
            VideoDownloadManager.currentDownloads.value.contains(epId) ||
                DownloadQueueManager.queue.value.any {
                    it.downloadItem?.episode?.id == epId
                }
            )
        binding.feedWatchDownloadProgress.isVisible = active && !done
        binding.feedWatchDownloadIcon.isVisible = !active || done
        binding.feedWatchDownloadIcon.setImageResource(
            if (done) R.drawable.ic_baseline_check_24
            else R.drawable.ic_feed_download
        )
    }

    /** Phase 8.57: the watch-status picker, same dialog and
     *  DataStore writes as the details page bookmark FAB. */
    private fun showAddToListDialog() {
        val page = FeedPlayer.currentPage ?: return
        val act = activity ?: return
        val current = DataStoreHelper.getResultWatchState(page.getId())
        act.showBottomDialog(
            WatchType.entries.map { getString(it.stringRes) },
            current.ordinal,
            getString(R.string.action_add_to_bookmarks),
            showApply = false,
            {}
        ) { index ->
            applyWatchStatus(page, WatchType.entries[index])
        }
    }

    private fun applyWatchStatus(
        page: com.lagradost.cloudstream3.LoadResponse,
        status: WatchType
    ) {
        val id = page.getId()
        DataStoreHelper.setResultWatchState(id, status.internalId)
        if (status != WatchType.NONE) {
            val existing = DataStoreHelper.getBookmarkedData(id)
            val now = System.currentTimeMillis()
            DataStoreHelper.setBookmarkedData(
                id,
                DataStoreHelper.BookmarkedData(
                    existing?.bookmarkedTime ?: now,
                    id,
                    now,
                    page.name,
                    page.url,
                    page.apiName,
                    page.type,
                    page.posterUrl,
                    page.year,
                    page.syncData,
                    plot = page.plot,
                    tags = page.tags,
                    score = page.score
                )
            )
        }
        updateHeader()
    }

    private fun showEpisodePicker() {
        val context = context ?: return
        val episodes = FeedPlayer.episodes
        if (episodes.size < 2) return
        val labels = episodes.map { ep ->
            val num = if (ep.episode > 0) {
                "S${ep.season ?: 1} E${ep.episode}"
            } else {
                "Episode"
            }
            if (ep.name.isNullOrBlank()) num else "$num • ${ep.name}"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.episodes)
            .setSingleChoiceItems(labels, FeedPlayer.episodeIndex) { dialog, which ->
                FeedPlayer.playEpisode(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun showSubtitlePicker() {
        val context = context ?: return
        val exo = FeedPlayer.exo ?: return
        val options = trackOptions(TRACK_TYPE_TEXT)
        if (options.isEmpty()) {
            Toast.makeText(context, R.string.feed_no_subtitles, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = arrayOf(
            getString(R.string.feed_off),
            *options.map { it.label }.toTypedArray()
        )
        val selectedIndex = options.indexOfFirst { it.selected }
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_subtitles)
            .setSingleChoiceItems(labels, if (selectedIndex >= 0) selectedIndex + 1 else 0) { dialog, which ->
                val params = exo.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    params.setTrackTypeDisabled(TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(TRACK_TYPE_TEXT)
                } else {
                    val option = options[which - 1]
                    params.setTrackTypeDisabled(TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(option.group.mediaTrackGroup, option.index)
                        )
                }
                exo.trackSelectionParameters = params.build()
                dialog.dismiss()
            }
            .show()
    }

    private fun linkLanguage(
        link: com.lagradost.cloudstream3.utils.ExtractorLink
    ): String? =
        Regex("""\(([^)]+)\)""").find(link.name)?.groupValues?.get(1)

    private fun showAudioPicker() {
        val context = context ?: return
        val exo = FeedPlayer.exo ?: return
        val options = trackOptions(TRACK_TYPE_AUDIO)
        if (options.size <= 1) {
            // Phase 8.37: the audio button is for LANGUAGE only —
            // it no longer opens the (quality) source list. On
            // multi-language sources the language is the link, so
            // offer the languages and switch to the best link of
            // the chosen one; quality stays under Sources.
            val languages = FeedPlayer.links
                .mapNotNull { linkLanguage(it) }
                .distinct()
            if (languages.size <= 1) {
                Toast.makeText(context, R.string.feed_single_audio, Toast.LENGTH_SHORT).show()
                return
            }
            val current = FeedPlayer.links
                .getOrNull(FeedPlayer.linkIndex)
                ?.let { linkLanguage(it) }
            AlertDialog.Builder(context)
                .setTitle(R.string.feed_audio)
                .setSingleChoiceItems(
                    languages.toTypedArray(),
                    languages.indexOf(current).coerceAtLeast(0)
                ) { dialog, which ->
                    val target = FeedPlayer.links.indexOfFirst {
                        linkLanguage(it) == languages[which]
                    }
                    if (target >= 0) FeedPlayer.selectLink(target)
                    dialog.dismiss()
                }
                .show()
            return
        }
        val labels = options.map { it.label }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle(R.string.feed_audio)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val option = options[which]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(option.group.mediaTrackGroup, option.index)
                    )
                    .build()
                dialog.dismiss()
            }
            .show()
    }

    // ---------- handoff to the main player ----------

    private fun setupOrientationHandoff() {
        val context = context ?: return
        if (orientationListener != null) return
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val isLandscape = orientation in 70..110 || orientation in 250..290
                if (isLandscape && FeedPlayer.isPlaying) {
                    if (landscapeSince == 0L) landscapeSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - landscapeSince > 700) {
                        landscapeSince = 0L
                        openInMainPlayer()
                    }
                } else {
                    landscapeSince = 0L
                }
            }
        }
        orientationListener?.enable()
    }

    private fun openInMainPlayer() {
        val gen = FeedPlayer.generator ?: return
        if (handedOff) return
        handedOff = true
        FeedPlayer.pause()
        // Hand over a generator whose episodes carry the LIVE saved
        // positions, exactly as GeneratorPlayer refreshes them itself —
        // the feed's copies were stamped when playback started.
        val page = FeedPlayer.currentPage
        val liveGen = if (page != null) {
            val refreshed = FeedPlayer.episodes.map { ep ->
                DataStoreHelper.getViewPos(ep.id)
                    ?.let { pd -> ep.copy(position = pd.position, duration = pd.duration) }
                    ?: ep
            }
            RepoLinkGenerator(refreshed, page = page)
        } else {
            gen
        }
        // Unlock into landscape BEFORE navigating, so the main player
        // never flashes its portrait layout on the way over.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(liveGen, FeedPlayer.episodeIndex)
        )
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    companion object {
        fun newInstance(url: String, apiName: String, name: String): Bundle =
            Bundle().apply {
                putString("url", url)
                putString("apiName", apiName)
                putString("name", name)
            }
    }
}
