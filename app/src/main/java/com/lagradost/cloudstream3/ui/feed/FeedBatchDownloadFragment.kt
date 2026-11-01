package com.lagradost.cloudstream3.ui.feed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.isMovie
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentFeedDownloadBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.player.LOADTYPE_ALL
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Phase 8.37: batch download page for series, opened from the
 * feed watch screen's Download pill. Resolution + audio-language
 * chips on top, a per-episode multi-select below. On confirm,
 * each selected episode's links are extracted (bounded parallel)
 * and the chosen link is handed to the download queue directly —
 * the same mechanism the single download uses — so the picked
 * quality/language is what actually downloads. Episodes whose
 * extraction finds nothing are queued without links and the
 * download service extracts them itself, as before.
 */
@UnstableApi
class FeedBatchDownloadFragment : BaseFragment<FragmentFeedDownloadBinding>(
    BindingCreator.Inflate(FragmentFeedDownloadBinding::inflate)
) {
    private val qualityOptions = listOf("Any", "1080p", "720p", "SD")
    private var qualityIndex = 0
    private var languages: List<String> = emptyList()
    private var language: String? = null
    private val checked = mutableSetOf<Int>()
    private var extracting = false
    private lateinit var episodeAdapter: EpisodeAdapter

    override fun fixLayout(view: View) {
        // Re-audit: this page has no floating nav either, and its
        // 52dp Download button sits in normal flow at the bottom
        // with only 16dp layout padding — 3-button nav covered
        // ~32dp of it. Always inset bottom and left.
        fixSystemBarsPadding(view, padBottom = true, padLeft = true)
    }

    override fun onBindingCreated(binding: FragmentFeedDownloadBinding) {
        val page = FeedPlayer.currentPage
        val episodes = FeedPlayer.episodes
        binding.feedDownloadTitle.text =
            FeedPlayer.currentName ?: page?.name ?: ""
        binding.feedDownloadBack.setOnClickListener {
            findNavController().navigateUp()
        }

        checked.addAll(episodes.indices)
        episodeAdapter = EpisodeAdapter(episodes) { refreshStart(binding) }
        binding.feedDownloadEpisodes.layoutManager =
            LinearLayoutManager(requireContext())
        binding.feedDownloadEpisodes.adapter = episodeAdapter

        binding.feedDownloadSelectAll.isChecked = true
        binding.feedDownloadSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checked.addAll(episodes.indices) else checked.clear()
            episodeAdapter.notifyDataSetChanged()
            refreshStart(binding)
        }

        buildChips(
            binding.feedDownloadQualityChips, qualityOptions, qualityIndex
        ) { index ->
            qualityIndex = index
        }

        languages = FeedPlayer.links
            .mapNotNull { linkLanguage(it) }
            .distinct()
        if (languages.size > 1) {
            val options = listOf("Any") + languages
            buildChips(binding.feedDownloadAudioChips, options, 0) { index ->
                language = if (index == 0) null else options[index]
            }
        } else {
            binding.feedDownloadAudioBox.isVisible = false
        }

        binding.feedDownloadStart.setOnClickListener { startDownloads(binding) }
        refreshStart(binding)
    }

    private fun buildChips(
        container: ViewGroup,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit
    ) {
        container.removeAllViews()
        options.forEachIndexed { index, label ->
            val chip = TextView(container.context).apply {
                text = label
                textSize = 13f
                setPadding(30, 14, 30, 14)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 20 }
            }
            styleChip(chip, index == selected)
            chip.setOnClickListener {
                for (i in 0 until container.childCount) {
                    styleChip(container.getChildAt(i) as TextView, i == index)
                }
                onSelect(index)
            }
            container.addView(chip)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setBackgroundResource(R.drawable.feed_chip_selected)
            chip.setTextColor(
                ContextCompat.getColor(chip.context, android.R.color.black)
            )
        } else {
            chip.setBackgroundResource(R.drawable.feed_search_bg)
            chip.setTextColor(
                ContextCompat.getColor(chip.context, android.R.color.white)
            )
        }
    }

    private fun refreshStart(binding: FragmentFeedDownloadBinding) {
        binding.feedDownloadStart.text =
            getString(R.string.feed_download_n, checked.size)
        binding.feedDownloadStart.isEnabled = checked.isNotEmpty() && !extracting
        val all = FeedPlayer.episodes.size
        val box = binding.feedDownloadSelectAll
        if (box.isChecked != (all > 0 && checked.size == all)) {
            box.setOnCheckedChangeListener(null)
            box.isChecked = all > 0 && checked.size == all
            box.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    checked.addAll(FeedPlayer.episodes.indices)
                } else {
                    checked.clear()
                }
                episodeAdapter.notifyDataSetChanged()
                refreshStart(binding)
            }
        }
    }

    private fun linkLanguage(link: ExtractorLink): String? =
        Regex("""\(([^)]+)\)""").find(link.name)?.groupValues?.get(1)

    private fun startDownloads(binding: FragmentFeedDownloadBinding) {
        val page = FeedPlayer.currentPage ?: return
        val episodes = FeedPlayer.episodes
        val selected = episodes.filterIndexed { index, _ -> checked.contains(index) }
        if (selected.isEmpty() || extracting) return
        extracting = true
        refreshStart(binding)
        binding.feedDownloadProgressBox.isVisible = true
        binding.feedDownloadProgressText.text =
            getString(R.string.feed_preparing_downloads, 0, selected.size)
        val cap = when (qualityIndex) {
            1 -> 1080
            2 -> 720
            3 -> 480
            else -> Int.MAX_VALUE
        }
        val lang = language
        viewLifecycleOwner.lifecycleScope.launch {
            val semaphore = Semaphore(3)
            var done = 0
            val lock = Any()
            val jobs = selected.map { episode ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        extractAndQueue(page, episode, cap, lang)
                        val now = synchronized(lock) { ++done }
                        withContext(Dispatchers.Main) {
                            binding.feedDownloadProgressText.text = getString(
                                R.string.feed_preparing_downloads, now, selected.size
                            )
                        }
                    }
                }
            }
            jobs.awaitAll()
            extracting = false
            Toast.makeText(
                requireContext(),
                getString(R.string.feed_batch_queued, selected.size),
                Toast.LENGTH_SHORT
            ).show()
            findNavController().navigateUp()
        }
    }

    private suspend fun extractAndQueue(
        page: LoadResponse,
        episode: ResultEpisode,
        qualityCap: Int,
        language: String?
    ) {
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleData>()
        try {
            RepoLinkGenerator(listOf(episode)).generateLinks(
                clearCache = false,
                sourceTypes = LOADTYPE_ALL,
                callback = { pair ->
                    pair.first?.let { synchronized(links) { links.add(it) } }
                },
                subtitleCallback = { sub ->
                    synchronized(subs) { subs.add(sub) }
                },
                offset = 0,
                isCasting = false
            )
        } catch (e: Exception) {
            // Fall through: queueing without links lets the
            // download service extract the episode itself.
        }
        val playable = synchronized(links) {
            links.filter {
                it.type == ExtractorLinkType.VIDEO ||
                    it.type == ExtractorLinkType.M3U8 ||
                    it.type == ExtractorLinkType.DASH
            }
        }
        val chosen = chooseLink(playable, qualityCap, language)
        DownloadQueueManager.addToQueue(
            DownloadObjects.DownloadQueueItem(
                episode,
                page.isMovie(),
                page.name,
                page.type,
                page.posterUrl,
                page.apiName,
                page.getId(),
                page.url,
                links = playable.toMutableList().apply {
                    if (chosen != null) {
                        remove(chosen)
                        add(0, chosen)
                    }
                }.ifEmpty { null },
                subs = synchronized(subs) { ArrayList(subs) }.ifEmpty { null }
            ).toWrapper()
        )
    }

    private fun chooseLink(
        playable: List<ExtractorLink>,
        qualityCap: Int,
        language: String?
    ): ExtractorLink? {
        if (playable.isEmpty()) return null
        val byLanguage = if (language != null) {
            playable.filter { linkLanguage(it) == language }
                .ifEmpty { playable }
        } else {
            playable
        }
        val underCap = byLanguage.filter { it.quality in 1..qualityCap }
        return if (underCap.isNotEmpty()) {
            underCap.maxByOrNull { it.quality }
        } else {
            byLanguage.minByOrNull { it.quality }
        }
    }

    private inner class EpisodeAdapter(
        private val items: List<ResultEpisode>,
        private val onToggle: () -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val check: CheckBox = view.findViewById(R.id.feed_dl_check)
            val title: TextView = view.findViewById(R.id.feed_dl_title)
            val sub: TextView = view.findViewById(R.id.feed_dl_sub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.feed_download_episode_item, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ep = items[position]
            val season = ep.season ?: 1
            holder.title.text = if (ep.episode > 0) {
                "S$season E${ep.episode}"
            } else {
                ep.name ?: "Episode ${position + 1}"
            }
            holder.sub.text = ep.name ?: ""
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = checked.contains(position)
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(position) else checked.remove(position)
                onToggle()
            }
            holder.itemView.setOnClickListener {
                holder.check.isChecked = !holder.check.isChecked
            }
        }
    }
}
