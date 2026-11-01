package com.lagradost.cloudstream3.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.txt

/**
 * A base Fragment class that simplifies ViewBinding usage and handles view inflation safely.
 *
 * This class allows two modes of creating ViewBinding:
 * 1. Inflate: Using the standard `inflate()` method provided by generated ViewBinding classes.
 * 2. Bind: Using `bind()` on an existing root view.
 *
 * It also provides hooks for:
 * - Safe initialization of the binding (`onBindingCreated`)
 * - Automatic padding adjustment for system bars (`fixPadding`)
 * - Optional layout resource selection via `pickLayout()`
 *
 * @param T The type of ViewBinding for this Fragment.
 * @param bindingCreator The strategy used to create the binding instance.
 */
private interface BaseFragmentHelper<T : ViewBinding> {
    val bindingCreator: BaseFragment.BindingCreator<T>

    var _binding: T?
    val binding: T? get() = _binding

    fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = pickLayout()
        val root: View? = layoutId?.let { inflater.inflate(it, container, false) }
        _binding = try {
            when (val creator = bindingCreator) {
                is BaseFragment.BindingCreator.Inflate -> creator.fn(inflater, container, false)
                is BaseFragment.BindingCreator.Bind -> {
                    if (root != null) creator.fn(root)
                    else throw IllegalStateException("Root view is null for bind()")
                }
            }
        } catch (t: Throwable) {
            showToast(
                txt(R.string.unable_to_inflate, t.message ?: ""),
                Toast.LENGTH_LONG
            )
            logError(t)
            null
        }

        return _binding?.root ?: root
    }

    /**
     * Called after the fragment's view has been created.
     *
     * This method is `final` to ensure that the binding is properly initialized and
     * system bar padding adjustments are applied before any subclass logic runs.
     * Subclasses should use [onBindingCreated] instead of overriding this method directly.
     */
    fun onViewReady(view: View, savedInstanceState: Bundle?) {
        fixLayout(view)
        binding?.let { onBindingCreated(it, savedInstanceState) }
    }

    /**
     * Called when the binding is safely created and view is ready.
     * Can be overridden to provide fragment-specific initialization.
     *
     * @param binding The safely created ViewBinding.
     * @param savedInstanceState Saved state bundle or null.
     */
    fun onBindingCreated(binding: T, savedInstanceState: Bundle?) {
        onBindingCreated(binding)
    }

    /**
     * Called when the binding is safely created and view is ready.
     * Overload without savedInstanceState for convenience.
     *
     * @param binding The safely created ViewBinding.
     */
    fun onBindingCreated(binding: T) {}

    /**
     * Pick a layout resource ID for the fragment.
     *
     * Return `null` by default. Override to provide a layout resource when using
     * `BindingCreator.Bind`. Not needed if using `BindingCreator.Inflate`.
     *
     * @return Layout resource ID or null.
     */
    @LayoutRes
    fun pickLayout(): Int? = null

    /**
     * Ensures the layout of the root view is correctly adjusted for the current configuration.
     *
     * This may include applying padding for system bars, adjusting insets, or performing other
     * layout updates. `fixLayout` should remain idempotent, as it can be called multiple
     * times on the same view, such as during configuration changes (e.g. device rotation) or when
     * the view is recreated.
     *
     * @param view The root view to adjust.
     */
    fun fixLayout(view: View)
}

abstract class BaseFragment<T : ViewBinding>(
    override val bindingCreator: BindingCreator<T>
) : Fragment(), BaseFragmentHelper<T> {
    override var _binding: T? = null

    /** Safer activity?.onBackPressedDispatcher?.onBackPressed() with fallback behavior instead of app crash */
    fun dispatchBackPressed() {
        try {
            activity?.onBackPressedDispatcher?.onBackPressed()
        } catch (_: IllegalStateException) {
            // FragmentManager is already executing transactions, so try again
            delayedDispatchBackPressed(5)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** Recursive back press when available */
    private fun delayedDispatchBackPressed(remaining: Int) {
        if (remaining <= 0) return
        binding?.root?.postDelayed({
            try {
                activity?.onBackPressedDispatcher?.onBackPressed()
            } catch (_: IllegalStateException) {
                // FragmentManager is already executing transactions, so try again
                delayedDispatchBackPressed(remaining - 1)
            } catch (t: Throwable) {
                logError(t)
            }
        }, 200)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = createBinding(inflater, container, savedInstanceState)

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onViewReady(view, savedInstanceState)
    }

    /**
     * Called when the device configuration changes (e.g., orientation).
     * Re-applies system bar padding fixes to the root view to ensure it
     * readjusts for orientation changes.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.let { fixLayout(it) }
    }

    /** Cleans up the binding reference when the view is destroyed to avoid memory leaks. */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Sealed class representing the two strategies for creating a ViewBinding instance.
     */
    sealed class BindingCreator<T : ViewBinding> {

        /**
         * Use the standard inflate() method for creating the binding.
         *
         * @param fn Lambda that inflates the binding.
         */
        class Inflate<T : ViewBinding>(
            val fn: (LayoutInflater, ViewGroup?, Boolean) -> T
        ) : BindingCreator<T>()

        /**
         * Use bind() on an existing root view to create the binding. This should
         * be used if you are differing per device layouts, such as different
         * layouts for TV and Phone.
         *
         * @param fn Lambda that binds the root view.
         */
        class Bind<T : ViewBinding>(
            val fn: (View) -> T
        ) : BindingCreator<T>()
    }
}

abstract class BaseDialogFragment<T : ViewBinding>(
    override val bindingCreator: BaseFragment.BindingCreator<T>
) : DialogFragment(), BaseFragmentHelper<T> {
    override var _binding: T? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = createBinding(inflater, container, savedInstanceState)

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onViewReady(view, savedInstanceState)
    }

    /** @see [BaseFragment.onConfigurationChanged] for documentation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.let { fixLayout(it) }
    }

    /** Cleans up the binding reference when the view is destroyed to avoid memory leaks. */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

abstract class BaseBottomSheetDialogFragment<T : ViewBinding>(
    override val bindingCreator: BaseFragment.BindingCreator<T>
) : BottomSheetDialogFragment(), BaseFragmentHelper<T> {
    override var _binding: T? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = createBinding(inflater, container, savedInstanceState)

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onViewReady(view, savedInstanceState)
    }

    /** @see [BaseFragment.onConfigurationChanged] for documentation. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view?.let { fixLayout(it) }
    }

    /** Cleans up the binding reference when the view is destroyed to avoid memory leaks. */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

abstract class BasePreferenceFragmentCompat() : PreferenceFragmentCompat() {
    /** The preference list's own bottom padding, captured before the
     * Phase 7.5 nav clearance is applied, so it can be restored when the
     * floating pill is not in play (TV / landscape rail). */
    private var defaultListBottomPadding: Int? = null

    /**
     * Phase 8.6 — true when this page is embedded as a section of the main
     * settings accordion (a child fragment of [SettingsFragment]) instead of
     * being a standalone nav destination. The host page then owns the
     * toolbar, the system-bar padding and the floating-nav clearance, and
     * this page's list must give up its own scrolling so the whole settings
     * page scrolls as one. Standalone destinations are hosted by the
     * NavHostFragment, never by SettingsFragment, so this cannot misfire.
     */
    val isAccordionSection: Boolean
        get() = parentFragment is SettingsFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isAccordionSection) {
            // The accordion header already shows this page's icon + title, so
            // the page's own toolbar (from the settings_title_top layout)
            // would only duplicate it — and its back arrow would pop the
            // whole settings page instead of collapsing the section.
            view.findViewById<View>(R.id.settings_toolbar)?.visibility = View.GONE
            // Phase 8.7 (clipping fix): the page root (settings_title_top)
            // is a CoordinatorLayout whose list container carries the app-bar
            // scrolling behaviour. That behaviour never lets the list measure
            // to its content height — it clamps the container to the
            // CoordinatorLayout's own height (in this wrap_content host:
            // whatever height the previous layout pass left behind), which
            // turned the embedded list into a short inner viewport whose
            // lower rows were unreachable. Detach the behaviour and let the
            // container wrap the list's full content height instead.
            // Standalone pages keep the behaviour — their collapsing toolbar
            // needs it.
            view.findViewById<View>(android.R.id.list_container)?.let { listContainer ->
                (listContainer.layoutParams as? CoordinatorLayout.LayoutParams)
                    ?.let { params ->
                        params.behavior = null
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        listContainer.layoutParams = params
                    }
            }
            // Phase 8.7: the accordion header already names the group, so
            // the in-list category titles ("Downloads", …) are hidden in
            // embedded mode. Standalone pages keep them.
            preferenceScreen?.let { hideAccordionCategoryTitles(it) }
            // The outer page scrolls; this list takes its full content
            // height instead of scrolling inside the section.
            listView.isNestedScrollingEnabled = false
            // Subclass onViewCreated() calls (setPaddingBottom on TV) run
            // after super(), so restore the list's own bottom padding once
            // they are done — a section ends inside the page and must not
            // keep a standalone page's bottom gap.
            val ownBottomPadding = listView.paddingBottom
            view.post {
                if (isAccordionSection) {
                    // Re-apply the category-title hiding: this runs after the
                    // subclass's own onViewCreated, so anything it set up
                    // late is covered too.
                    preferenceScreen?.let { hideAccordionCategoryTitles(it) }
                    listView.setPadding(
                        listView.paddingLeft,
                        listView.paddingTop,
                        listView.paddingRight,
                        ownBottomPadding
                    )
                }
            }
        } else {
            setSystemBarsPadding()
        }
        // Phase 7: gentle staggered fade+rise entrance for the settings list,
        // shared by every settings sub-page via this base class.
        listView.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            requireContext(), R.anim.settings_list_layout_animation
        )
        // Phase 8.5D: group each category's rows into a rounded card,
        // mirroring the main settings page (Phase 5A). Pure decoration —
        // the preference tree, clicks and dependencies are untouched.
        listView.addItemDecoration(SettingsGroupCardDecoration(requireContext()))
        applyNavClearancePadding()
    }

    /**
     * Phase 8.7 — embedded (accordion) sections hide their
     * [PreferenceCategory] title rows: the accordion header on the main
     * settings page already names the group, so the in-list category text
     * would only repeat it. Each category's row layout is swapped for a
     * zero-height one; the category and its children stay fully functional.
     * [SettingsGroupCardDecoration] is unaffected — it anchors each card to
     * the group's item rows, never to the header row, so a zero-height
     * header just removes the text and the gap between cards stays the
     * decoration's own 16dp bottom offset. Only ever called when
     * [isAccordionSection]; standalone pages keep their category titles.
     */
    private fun hideAccordionCategoryTitles(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceCategory &&
                pref.layoutResource != R.layout.preference_category_hidden
            ) {
                pref.layoutResource = R.layout.preference_category_hidden
            }
            if (pref is PreferenceGroup) hideAccordionCategoryTitles(pref)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Inside the accordion the host page re-applies its own system-bar
        // padding; a section must not pad itself a second time.
        if (!isAccordionSection) setSystemBarsPadding()
        // The pill only overlays in portrait; re-evaluate on rotation, the
        // same reason setSystemBarsPadding() is re-applied here.
        if (view != null) runCatching { applyNavClearancePadding() }
    }

    /** Phase 7.5 (fix 5): the phone bottom nav is a true overlay (70dp bar
     * + 18dp bottom margin = 88dp zone), so the settings sub-pages — which
     * show the nav but are not main tabs — need the same 100dp rest
     * clearance the main tab lists got, or their last rows rest hidden
     * behind the pill. Only the bottom padding changes; the list's other
     * padding is preserved. Guard mirrors MainActivity: the pill shows on
     * phone layouts (PHONE or EMULATOR, the settings screens' own phone
     * check) in portrait; landscape uses the nav rail and TV never shows
     * the pill, so both keep their original padding. */
    private fun applyNavClearancePadding() {
        // Phase 8.6: an accordion section ends inside the settings page,
        // whose own scroll area already clears the floating pill — adding
        // the 100dp clearance here would leave a hole at the section's end.
        if (isAccordionSection) return
        val list = listView
        if (defaultListBottomPadding == null) {
            defaultListBottomPadding = list.paddingBottom
        }
        list.clipToPadding = false
        if (isLayout(PHONE or EMULATOR) && !isLandscape()) {
            // Re-audit: fixed 100dp was short by systemBottom-16dp
            // on 3-button nav; use the runtime formula, which is
            // re-evaluated by onConfigurationChanged.
            list.refreshFloatingNavBottomPadding()
            return
        }
        // Landscape/TV: remove any runtime listener and restore
        // the original padding (refresh would set a 12dp gap).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(list, null)
        list.setPadding(
            list.paddingLeft,
            list.paddingTop,
            list.paddingRight,
            defaultListBottomPadding ?: list.paddingBottom
        )
    }
}

/**
 * Phase 8.5D — settings sub-page group cards.
 *
 * Draws each preference group on a settings sub-page as one rounded card,
 * mirroring the main settings page (Phase 5A): 16dp side margins, 20dp
 * corners, `?attr/primaryGrayBackground` fill (the same values as
 * `@drawable/bg_settings_group`, resolved from the theme so every variant
 * flips the same way the drawable does).
 *
 * A "group" is the rows of one [PreferenceCategory]; preferences declared
 * directly under the PreferenceScreen (General's first rows, the whole
 * Providers page) form a card of their own. Category headers stay OUTSIDE
 * their card, above it, exactly like the main page's group headers.
 *
 * This is purely an [RecyclerView.ItemDecoration]: group membership is read
 * live from the [PreferenceGroupAdapter] on every layout/draw pass, so
 * visibility changes (hidden prefs, dynamically toggled rows) re-group
 * automatically, and preference clicks/dependencies are never touched.
 */
@SuppressLint("RestrictedApi") // PreferenceGroupAdapter is LIBRARY_GROUP-restricted in androidx.preference; same suppression the project already uses in CommonActivity/AppContextUtils
private class SettingsGroupCardDecoration(context: Context) :
    RecyclerView.ItemDecoration() {

    private val sideInset = 16.toPx
    private val cardGap = 16.toPx
    private val topGap = 16.toPx
    private val cornerRadius = 20.toPx.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getResourceColor(R.attr.primaryGrayBackground)
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    /**
     * The card a preference belongs to: its parent group (a category, or the
     * screen itself for root-level preferences). Null = no card — the row is
     * a category header, which sits above its card like on the main page.
     */
    private fun cardGroupOf(pref: Preference?): PreferenceGroup? {
        if (pref == null || pref is PreferenceCategory) return null
        return pref.parent
    }

    private fun isFirstInGroup(adapter: PreferenceGroupAdapter, position: Int): Boolean {
        val group = cardGroupOf(adapter.getItem(position)) ?: return false
        return position == 0 || cardGroupOf(adapter.getItem(position - 1)) != group
    }

    private fun isLastInGroup(adapter: PreferenceGroupAdapter, position: Int): Boolean {
        val group = cardGroupOf(adapter.getItem(position)) ?: return false
        return position == adapter.itemCount - 1 ||
            cardGroupOf(adapter.getItem(position + 1)) != group
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        // Headers keep stock spacing; card rows are inset to the card's edges.
        if (cardGroupOf(adapter.getItem(position)) == null) return
        outRect.left = sideInset
        outRect.right = sideInset
        // Breathing room above a card that starts the list (root-level group
        // with no header above it); cards under a header hug it directly.
        outRect.top = if (isFirstInGroup(adapter, position) && position == 0) topGap else 0
        outRect.bottom = if (isLastInGroup(adapter, position)) cardGap else 0
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        val left = (parent.paddingLeft + sideInset).toFloat()
        val right = (parent.width - parent.paddingRight - sideInset).toFloat()

        var runStart: View? = null
        var runEnd: View? = null
        var runGroup: PreferenceGroup? = null
        var runFirstPos = -1
        var runLastPos = -1

        fun flush() {
            val start = runStart
            val end = runEnd
            if (start != null && end != null && runGroup != null) {
                var top = start.top.toFloat()
                var bottom = end.bottom.toFloat()
                // If the group continues off-screen, push that edge past the
                // visible area so no rounded corner shows mid-group.
                if (!isFirstInGroup(adapter, runFirstPos)) top -= cornerRadius
                if (!isLastInGroup(adapter, runLastPos)) bottom += cornerRadius
                rect.set(left, top, right, bottom)
                c.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            }
            runStart = null
            runEnd = null
            runGroup = null
        }

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) {
                flush()
                continue
            }
            val group = cardGroupOf(adapter.getItem(position))
            if (group == null) {
                flush()
                continue
            }
            if (group != runGroup || position != runLastPos + 1) {
                flush()
                runStart = child
                runFirstPos = position
            }
            runGroup = group
            runEnd = child
            runLastPos = position
        }
        flush()
    }
}
