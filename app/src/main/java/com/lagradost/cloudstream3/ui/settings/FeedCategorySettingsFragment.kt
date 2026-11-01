package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FeedCategoryPrefRowBinding
import com.lagradost.cloudstream3.databinding.FragmentFeedCategoriesBinding
import com.lagradost.cloudstream3.ui.feed.FeedCategories
import com.lagradost.cloudstream3.ui.feed.FeedCategoryPrefs
import com.lagradost.cloudstream3.ui.feed.FeedPool
import com.lagradost.cloudstream3.ui.feed.FeedSectionTags

/**
 * Phase 8.91 — Settings → Content → Feed Categories: every
 * canonical Feed tag with a show/hide switch and drag-handle
 * reorder, persisted to FeedCategoryPrefs (hidden set + order).
 * Tags the current pool actually shelves show their item count;
 * provider-section tags with no pool data are greyed. The
 * derived tags (Top 250, the must-watch / Top / New Release
 * rows, Emotional, the metadata smart categories and genres)
 * have no per-tag pool count to show — their rows are listed
 * plain and simply follow the Feed.
 */
class FeedCategorySettingsFragment : Fragment() {

    private var binding: FragmentFeedCategoriesBinding? = null
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentFeedCategoriesBinding.inflate(
            inflater, container, false
        )
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Status-bar inset, same fix as HomeCustomListsFragment.
        // Re-audit: this listener only added the top inset, and
        // this destination has no floating nav, so the bottom
        // (and side) insets were never applied: 3-button nav
        // covered ~32dp of the last category rows. Add all
        // system-bar insets to the existing 16dp root padding.
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )
            v.setPadding(
                baseLeft + bars.left, baseTop + bars.top,
                baseRight + bars.right, baseBottom + bars.bottom
            )
            insets
        }
        view.requestApplyInsets()
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        val adapter = TagsAdapter(
            rows = buildRows().toMutableList(),
            hidden = FeedCategoryPrefs.hidden(ctx).toMutableSet(),
            onHiddenChanged = { tags ->
                FeedCategoryPrefs.setHidden(ctx, tags)
            },
            onOrderChanged = { tags ->
                FeedCategoryPrefs.setOrder(ctx, tags)
            },
            onDragStart = { holder -> touchHelper.startDrag(holder) }
        )
        binding?.feedCategoriesRecycler?.layoutManager =
            LinearLayoutManager(ctx)
        binding?.feedCategoriesRecycler?.adapter = adapter
        touchHelper = ItemTouchHelper(TagsDragCallback(adapter))
        touchHelper.attachToRecyclerView(binding?.feedCategoriesRecycler)
    }

    /**
     * The full tag list in display order: the user's saved
     * order first, then the assembly order — derived tags, the
     * canonical vocabulary (in its curated order), passthrough
     * labels discovered in the pool (by count), the metadata
     * smart categories, the genres.
     */
    private fun buildRows(): List<TagRow> {
        val ctx = requireContext()
        val pool = FeedPool.entries
        val sectionCounts = mutableMapOf<String, Int>()
        pool.forEach { entry ->
            entry.sections
                .mapNotNull { FeedSectionTags.canonicalTag(it) }
                .toSet()
                .forEach { tag ->
                    sectionCounts[tag] =
                        (sectionCounts[tag] ?: 0) + 1
                }
        }
        val discovered = (sectionCounts.keys -
            FeedSectionTags.KNOWN_TAGS.toSet())
            .sortedByDescending { sectionCounts[it] ?: 0 }
        val base = (DERIVED_TAGS + FeedSectionTags.CANONICAL_ORDER +
            discovered + FeedCategories.SMART_CATEGORIES +
            FeedSectionTags.GENRES).distinct()
        val saved = FeedCategoryPrefs.order(ctx)
        val ordered = saved.filter { it in base } +
            base.filter { it !in saved }
        return ordered.map { tag ->
            val count = sectionCounts[tag]
            TagRow(
                tag = tag,
                count = count,
                hasData = if (count != null) {
                    count > 0
                } else {
                    pool.isNotEmpty()
                }
            )
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /** [count] is set only for provider-section tags. */
    data class TagRow(
        val tag: String,
        val count: Int?,
        val hasData: Boolean
    )

    private companion object {
        /** FeedFragment's derived/curated row tags (its
         *  constants are private; the names are the shared
         *  vocabulary the prefs are keyed by). */
        val DERIVED_TAGS: List<String> = listOf(
            "Top 250", "Must-watch Drama", "Must-watch Series",
            "Top Anime", "Top K-Drama", "New Release K-Drama",
            "Top C-Drama", "New Release C-Drama", "Emotional"
        )
    }

    private class TagsAdapter(
        private val rows: MutableList<TagRow>,
        private val hidden: MutableSet<String>,
        private val onHiddenChanged: (Set<String>) -> Unit,
        private val onOrderChanged: (List<String>) -> Unit,
        private val onDragStart: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<TagsAdapter.Holder>() {

        class Holder(val b: FeedCategoryPrefRowBinding) :
            RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): Holder = Holder(
            FeedCategoryPrefRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun getItemCount(): Int = rows.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val b = holder.b
            val ctx = b.root.context
            b.feedCategoryName.text = row.tag
            val dim = if (row.hasData) 1f else 0.45f
            b.feedCategoryName.alpha = dim
            b.feedCategoryMeta.alpha = dim
            val count = row.count
            if (count != null) {
                b.feedCategoryMeta.visibility = View.VISIBLE
                b.feedCategoryMeta.text = if (count > 0) {
                    ctx.getString(
                        R.string.feed_categories_in_feed, count
                    )
                } else {
                    ctx.getString(R.string.feed_categories_not_in_feed)
                }
            } else {
                b.feedCategoryMeta.visibility = View.GONE
            }
            b.feedCategorySwitch.setOnCheckedChangeListener(null)
            b.feedCategorySwitch.isChecked = row.tag !in hidden
            b.feedCategorySwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    hidden.remove(row.tag)
                } else {
                    hidden.add(row.tag)
                }
                onHiddenChanged(hidden.toSet())
            }
            b.feedCategoryDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragStart(holder)
                }
                false
            }
        }

        fun onItemMove(from: Int, to: Int) {
            val moved = rows.removeAt(from)
            rows.add(to, moved)
            notifyItemMoved(from, to)
        }

        fun persistOrder() {
            onOrderChanged(rows.map { it.tag })
        }
    }

    private class TagsDragCallback(
        private val adapter: TagsAdapter
    ) : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int = makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        )

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            adapter.onItemMove(
                viewHolder.absoluteAdapterPosition,
                target.absoluteAdapterPosition
            )
            return true
        }

        override fun onSwiped(
            viewHolder: RecyclerView.ViewHolder, direction: Int
        ) = Unit

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ) {
            super.clearView(recyclerView, viewHolder)
            adapter.persistOrder()
        }
    }
}
