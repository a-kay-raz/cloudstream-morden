package com.lagradost.cloudstream3.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.lagradost.cloudstream3.R
import kotlin.math.abs

/**
 * Phase 8.99d — the floating bottom bar (PHONE layout only; TV keeps the
 * stock BottomNavigationView, untouched).
 *
 * Presentation: a dark pill floating above the content. The selected
 * destination expands — a tonal-grey organic blob grows behind its icon
 * and its label slides in — while the other destinations stay icon-only.
 * Highlight colour is the app's tonal grey family (@color/extButtonBackground);
 * red stays Play-only.
 *
 * State ownership: this view is presentation + touch only. The (now
 * INVISIBLE) BottomNavigationView remains the selection/state holder
 * wired to the NavController. Taps and scrubs are forwarded through
 * [onNavigateRequest]; MainActivity pushes them into the
 * BottomNavigationView and reads the true result back via
 * [syncSelection], so the existing debounced navigate listener and the
 * checked-state bookkeeping in updateNavBar run exactly as before.
 *
 * Phase 8.99e: the highlight is no longer a separate View chasing the
 * selected item from onLayout (the stretched vector ellipse lagged and
 * sat off-centre on device). The selected item now carries the blob as
 * its OWN background — an organic-radius GradientDrawable drawn by the
 * item itself, so it tracks the animated width exactly and the
 * icon+label group is centred inside it by construction.
 */
class FloatingNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private data class NavItem(
        val id: Int,
        val iconRes: Int,
        val labelRes: Int,
    )

    /** Same six destinations, same order as menu/bottom_nav_menu.xml. */
    private val navItems = listOf(
        NavItem(R.id.navigation_home, R.drawable.home_icon_outline_24, R.string.title_home),
        NavItem(R.id.navigation_search, R.drawable.search_icon, R.string.title_search),
        NavItem(
            R.id.navigation_feed,
            R.drawable.ic_baseline_ondemand_video_24,
            R.string.title_feed
        ),
        NavItem(R.id.navigation_library, R.drawable.library_icon, R.string.library),
        NavItem(R.id.navigation_downloads, R.drawable.netflix_download, R.string.title_downloads),
        NavItem(
            R.id.navigation_settings,
            R.drawable.settings_icon_outline,
            R.string.title_settings
        ),
    )

    // The two sub-destination groups MainActivity.updateNavBar maps onto a
    // tab (its `when` uses the same lists on destination.id).
    private val downloadGroup = listOf(
        R.id.navigation_downloads,
        R.id.navigation_download_child,
        R.id.navigation_download_queue,
    )
    private val settingsGroup = listOf(
        R.id.navigation_settings,
        R.id.navigation_subtitles,
        R.id.navigation_chrome_subtitles,
        R.id.navigation_settings_player,
        R.id.navigation_settings_updates,
        R.id.navigation_settings_ui,
        R.id.navigation_settings_account,
        R.id.navigation_settings_providers,
        R.id.navigation_settings_general,
        R.id.navigation_settings_extensions,
        R.id.navigation_settings_plugins,
        R.id.navigation_settings_sky,
        R.id.navigation_settings_nuvio,
        R.id.navigation_settings_stremio,
        R.id.navigation_test_providers,
    )

    /** A tap/scrub wants this destination. MainActivity routes it through
     *  the BottomNavigationView and calls [syncSelection] with the result. */
    var onNavigateRequest: ((Int) -> Unit)? = null

    /** The already-selected item was tapped again (Feed scroll-to-top). */
    var onReselected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()

    private val itemHeightPx = dp(50f)
    private val collapsedWidthPx = dp(50f)
    private val iconPx = dp(24f)
    private val itemPadPx = dp(13f)
    private val labelGapPx = dp(8f)
    private val baseBottomLiftPx = dp(14f)
    private val scrubThresholdPx = dp(8f).toFloat()

    private val inactiveTint = Color.rgb(168, 168, 168)

    private val pill: FrameLayout
    private val row: LinearLayout
    private val itemViews = mutableListOf<LinearLayout>()
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private val expandedWidths = mutableListOf<Int>()

    private var selectedIndex = 0
    private var widthAnimator: ValueAnimator? = null
    private var destinationListener: NavController.OnDestinationChangedListener? = null

    // Drag-to-scrub state (mirrors MainActivity.setupNavScrub semantics).
    private var scrubDownX = 0f
    private var scrubDownY = 0f
    private var scrubbing = false
    private var scrubChangedSelection = false

    val selectedItemId: Int
        get() = navItems[selectedIndex].id

    init {
        // Room around the pill for its elevation shadow; the bottom
        // padding is the bar's lift above the screen edge (plus the
        // system-bar inset on API 30+, mirroring how the flat bar's
        // height grew by the inset — see the inset audit in
        // PHASE899D_CHANGES.md).
        setPadding(dp(8f), dp(6f), dp(8f), baseBottomLiftPx)
        clipChildren = false
        clipToPadding = false

        pill = FrameLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setBackgroundResource(R.drawable.bg_floating_nav_pill)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            elevation = dp(8f).toFloat()
        }

        row = LinearLayout(context).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pill.addView(row)

        val labelPaint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
            )
        }

        navItems.forEachIndexed { index, item ->
            val labelText = context.getString(item.labelRes)
            val itemView = LinearLayout(context).apply {
                id = item.id
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(itemPadPx, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(collapsedWidthPx, itemHeightPx)
                contentDescription = labelText
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
                setImageResource(item.iconRes)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = labelGapPx }
                text = labelText
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                alpha = 0f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            itemView.addView(icon)
            itemView.addView(label)
            itemView.setOnClickListener {
                if (item.id == selectedItemId) {
                    onReselected?.invoke(item.id)
                } else {
                    onNavigateRequest?.invoke(item.id)
                }
            }
            val touch = OnTouchListener { _, event -> handleScrubTouch(event) }
            itemView.setOnTouchListener(touch)
            icon.setOnTouchListener(touch)
            label.setOnTouchListener(touch)
            row.addView(itemView)
            itemViews.add(itemView)
            iconViews.add(icon)
            labelViews.add(label)
            expandedWidths.add(
                itemPadPx + iconPx + labelGapPx +
                    labelPaint.measureText(labelText).toInt() + itemPadPx
            )
        }
        addView(pill)

        applySelection(animate = false)

        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBottom =
                    insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                view.updatePadding(bottom = baseBottomLiftPx + systemBottom)
            }
            insets
        }
    }

    override fun onDetachedFromWindow() {
        widthAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /** Read-back sync from the BottomNavigationView (the state owner). */
    fun syncSelection(itemId: Int, animate: Boolean) {
        val index = navItems.indexOfFirst { it.id == itemId }
        if (index < 0) return
        if (index == selectedIndex) {
            if (!animate) applySelection(animate = false)
            return
        }
        selectedIndex = index
        applySelection(animate)
    }

    /** Follow the NavController so back-stack pops and deep links move
     *  the highlight too (the BottomNavigationView's listener is only
     *  driven by taps). */
    fun setupWithNavController(navController: NavController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            tabForDestination(destination)?.let { syncSelection(it, animate = true) }
        }
        destinationListener = listener
        navController.addOnDestinationChangedListener(listener)
    }

    fun detachFromNavController(navController: NavController) {
        destinationListener?.let { navController.removeOnDestinationChangedListener(it) }
        destinationListener = null
    }

    private fun tabForDestination(destination: NavDestination): Int? {
        val hierarchyIds = destination.hierarchy.map { it.id }.toList()
        navItems.firstOrNull { it.id in hierarchyIds }?.let { return it.id }
        if (hierarchyIds.any { it in downloadGroup }) return R.id.navigation_downloads
        if (hierarchyIds.any { it in settingsGroup }) return R.id.navigation_settings
        return null
    }

    /** Library tab icon swap (sync provider icon), mirroring the
     *  BottomNavigationView menu update in MainActivity. */
    fun setItemIcon(itemId: Int, @DrawableRes iconRes: Int) {
        val index = navItems.indexOfFirst { it.id == itemId }
        if (index < 0) return
        iconViews[index].setImageResource(iconRes)
        iconViews[index].setColorFilter(
            if (index == selectedIndex) Color.WHITE else inactiveTint
        )
    }

    /** The item root view (long-press wiring lives in MainActivity). */
    fun getItemView(itemId: Int): View? {
        val index = navItems.indexOfFirst { it.id == itemId }
        return itemViews.getOrNull(index)
    }

    /** The organic blob from the approved preview (border-radius
     *  58% 42% 55% 45% / 52% 55% 45% 48% on the settled ~110x50dp item),
     *  expressed as fixed per-corner radii so the shape reads the same
     *  on every tab. Radii sums stay below the smallest expanded width
     *  and the item height, so nothing clamps at rest; mid-animation
     *  the platform scales them down, which is what morphs the shape
     *  from pill to blob as the item springs open. Fill is the settled
     *  grey (@color/extButtonBackground, #262626). A fresh instance per
     *  application — a Drawable can only live on one View at a time. */
    private fun makeBlobBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(context, R.color.extButtonBackground))
            cornerRadii = floatArrayOf(
                dp(56f).toFloat(), dp(24f).toFloat(), // top-left
                dp(44f).toFloat(), dp(25f).toFloat(), // top-right
                dp(54f).toFloat(), dp(21f).toFloat(), // bottom-right
                dp(46f).toFloat(), dp(22f).toFloat(), // bottom-left
            )
        }
    }

    private fun refreshItemBackgrounds() {
        itemViews.forEachIndexed { index, view ->
            val shouldHave = index == selectedIndex
            if (shouldHave && view.background == null) {
                view.background = makeBlobBackground()
            } else if (!shouldHave && view.background != null) {
                view.background = null
            }
        }
    }

    private fun applySelection(animate: Boolean) {
        val targets = IntArray(navItems.size) { index ->
            if (index == selectedIndex) expandedWidths[index] else collapsedWidthPx
        }
        widthAnimator?.cancel()
        refreshItemBackgrounds()
        iconViews[selectedIndex].setColorFilter(Color.WHITE)
        if (!animate) {
            itemViews.forEachIndexed { index, view ->
                (view.layoutParams as LinearLayout.LayoutParams).width = targets[index]
                view.requestLayout()
            }
            labelViews.forEachIndexed { index, label ->
                label.alpha = if (index == selectedIndex) 1f else 0f
                label.translationX = 0f
            }
            iconViews.forEachIndexed { index, icon ->
                icon.setColorFilter(if (index == selectedIndex) Color.WHITE else inactiveTint)
            }
            requestLayout()
            return
        }
        val starts = IntArray(navItems.size) { itemViews[it].width }
        val previousIndex = starts.indices.firstOrNull {
            it != selectedIndex && starts[it] > collapsedWidthPx
        }
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 420
        animator.interpolator = OvershootInterpolator(1.1f)
        animator.addUpdateListener { valueAnimator ->
            val fraction = valueAnimator.animatedValue as Float
            itemViews.forEachIndexed { index, view ->
                val width = starts[index] +
                    ((targets[index] - starts[index]) * fraction).toInt()
                val lp = view.layoutParams as LinearLayout.LayoutParams
                if (lp.width != width) {
                    lp.width = width
                    view.layoutParams = lp
                }
            }
            val incomingAlpha = ((fraction - 0.3f) / 0.7f).coerceIn(0f, 1f)
            labelViews[selectedIndex].alpha = incomingAlpha
            labelViews[selectedIndex].translationX =
                -dp(8f) * (1f - incomingAlpha)
            if (previousIndex != null) {
                labelViews[previousIndex].alpha =
                    (1f - fraction * 2.5f).coerceIn(0f, 1f)
                iconViews[previousIndex].setColorFilter(
                    if (fraction >= 1f) inactiveTint else Color.WHITE
                )
            }
            requestLayout()
        }
        widthAnimator = animator
        animator.start()
    }

    private fun itemIdAt(rawX: Float, rawY: Float): Int? {
        val location = IntArray(2)
        itemViews.forEachIndexed { index, view ->
            view.getLocationOnScreen(location)
            if (rawX >= location[0] && rawX < location[0] + view.width &&
                rawY >= location[1] && rawY < location[1] + view.height
            ) {
                return navItems[index].id
            }
        }
        return null
    }

    /** Drag horizontally across the bar to scrub between tabs — the same
     *  semantics as MainActivity.setupNavScrub on the stock bar: a move
     *  past the threshold starts scrubbing, crossing into another item
     *  selects it through the owner, and a scrub that changed the
     *  selection consumes the release so no click fires. */
    private fun handleScrubTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubDownX = event.rawX
                scrubDownY = event.rawY
                scrubbing = false
                scrubChangedSelection = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing &&
                    (abs(event.rawX - scrubDownX) > scrubThresholdPx ||
                        abs(event.rawY - scrubDownY) > scrubThresholdPx)
                ) {
                    scrubbing = true
                }
                if (scrubbing) {
                    val target = itemIdAt(event.rawX, event.rawY)
                    if (target != null && target != selectedItemId) {
                        onNavigateRequest?.invoke(target)
                        if (selectedItemId == target) scrubChangedSelection = true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = scrubbing && scrubChangedSelection
                scrubbing = false
                return consumed
            }
        }
        return false
    }
}
