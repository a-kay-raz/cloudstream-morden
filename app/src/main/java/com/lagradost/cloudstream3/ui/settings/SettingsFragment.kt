package com.lagradost.cloudstream3.ui.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.RotateDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.MainSettingsBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.errorProfilePic
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomPadding
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SettingsFragment : BaseFragment<MainSettingsBinding>(
    BaseFragment.BindingCreator.Inflate(MainSettingsBinding::inflate)
) {
    companion object {
        /** Phase 8.6: expand/collapse animation length for accordion sections. */
        private const val ACCORDION_ANIM_MS = 250L

        fun PreferenceFragmentCompat?.getPref(id: Int): Preference? {
            if (this == null) return null
            return try {
                findPreference(getString(id))
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        /**
         * Hide many Preferences on selected layouts.
         **/
        fun PreferenceFragmentCompat?.hidePrefs(ids: List<Int>, layoutFlags: Int) {
            if (this == null) return

            try {
                ids.forEach {
                    getPref(it)?.isVisible = !isLayout(layoutFlags)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        /**
         * Hide the [Preference] on selected layouts.
         * @return [Preference] if visible otherwise null.
         *
         * [hideOn] is usually followed by some actions on the preference which are mostly
         * unnecessary when the preference is disabled for the said layout thus returning null.
         **/
        fun Preference?.hideOn(layoutFlags: Int): Preference? {
            if (this == null) return null
            this.isVisible = !isLayout(layoutFlags)
            return if(this.isVisible) this else null
        }

        /**
         * On TV you cannot properly scroll to the bottom of settings, this fixes that.
         * */
        fun PreferenceFragmentCompat.setPaddingBottom() {
            if (isLayout(TV or EMULATOR)) {
                listView?.setPadding(0, 0, 0, 100.toPx)
            }
        }

        fun PreferenceFragmentCompat.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)

                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = this?.view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)

                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setUpToolbar(title: String) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return

            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    setNavigationOnClickListener {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
            }
        }

        fun Fragment?.setUpToolbar(@StringRes title: Int) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return

            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    children.firstOrNull { it is ImageView }?.tag = getString(R.string.tv_no_focus_tag)
                    setNavigationOnClickListener {
                        safe { activity?.onBackPressedDispatcher?.onBackPressed() }
                    }
                }
            }
        }

        fun Fragment.setSystemBarsPadding() {
            view?.let {
                fixSystemBarsPadding(
                    it,
                    padLeft = isLayout(TV or EMULATOR),
                    padBottom = isLandscape()
                )
            }
        }

        fun getFolderSize(dir: File): Long {
            var size: Long = 0
            dir.listFiles()?.let {
                for (file in it) {
                    size += if (file.isFile) {
                        // System.out.println(file.getName() + " " + file.length());
                        file.length()
                    } else getFolderSize(file)
                }
            }

            return size
        }
    }

    /**
     * Phase 8.6 — one accordion section of this page: a header row (the
     * entry that used to navigate to a settings sub-page) plus the container
     * directly below it that hosts that sub-page as an embedded child
     * fragment. Sections toggle independently and all start collapsed.
     */
    private class AccordionGroup(
        val header: TextView,
        val container: FrameLayout,
        /** The header's XML-declared nextFocusDown target. Restored while
         * collapsed; cleared while expanded so TV focus can flow from the
         * header into the section's own list instead of skipping past it. */
        val collapsedNextFocusDownId: Int,
        val createFragment: () -> Fragment,
    ) {
        var expanded: Boolean = false
        var chevron: RotateDrawable? = null
        var heightAnimator: ValueAnimator? = null
        var chevronAnimator: ValueAnimator? = null
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

        /** Phase 8.8: the section's full content height, measured with an
         * explicit UNSPECIFIED(0) height spec (see
         * [measureAccordionFullHeight]). While the section is expanded its
         * container is pinned to this height, because the natural
         * wrap_content chain under-measures it at about one screenful. */
        var fullHeight: Int = 0

        /** Phase 8.8: keeps [fullHeight] current when the embedded page's
         * rows change while the section is open. Registered on the page's
         * preference adapter (a recreated view brings a new adapter, so
         * the registered instance is tracked and replaced). */
        var contentObserver: RecyclerView.AdapterDataObserver? = null
        var observedAdapter: RecyclerView.Adapter<*>? = null
    }

    private var accordionGroups: List<AccordionGroup> = emptyList()

    /** Turns the header's end arrow (right = collapsed) into a rotating
     * chevron by wrapping it in a [RotateDrawable] (down = expanded). */
    private fun setupAccordionChevron(group: AccordionGroup) {
        val drawables = group.header.compoundDrawablesRelative
        val end = drawables.getOrNull(2) ?: return
        val rotate = RotateDrawable().apply {
            drawable = end.mutate()
            fromDegrees = 0f
            toDegrees = 90f
            level = 0
        }
        group.chevron = rotate
        group.header.setCompoundDrawablesRelativeWithIntrinsicBounds(
            drawables.getOrNull(0), drawables.getOrNull(1), rotate, drawables.getOrNull(3)
        )
    }

    private fun animateAccordionChevron(group: AccordionGroup, expand: Boolean) {
        val rotate = group.chevron ?: return
        val target = if (expand) 10000 else 0
        if (rotate.level == target) return
        group.chevronAnimator?.cancel()
        group.chevronAnimator = ValueAnimator.ofInt(rotate.level, target).apply {
            duration = ACCORDION_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { rotate.level = it.animatedValue as Int }
            start()
        }
    }

    private fun toggleAccordionGroup(group: AccordionGroup) {
        val expand = !group.expanded
        group.expanded = expand
        animateAccordionChevron(group, expand)
        if (expand) {
            ensureAccordionFragment(group)
            group.header.nextFocusDownId = View.NO_ID
            expandAccordionContainer(group)
        } else {
            group.header.nextFocusDownId = group.collapsedNextFocusDownId
            collapseAccordionContainer(group)
        }
    }

    /** Creates the section's sub-page fragment on first expand. After that
     * the instance is kept alive — toggling only animates the container —
     * and after a view recreation the FragmentManager-restored instance is
     * found here and reused instead of duplicated. */
    private fun ensureAccordionFragment(group: AccordionGroup) {
        if (childFragmentManager.findFragmentById(group.container.id) == null) {
            childFragmentManager.beginTransaction()
                .add(group.container.id, group.createFragment())
                .commitNow()
        }
    }

    /**
     * Phase 8.8 — the section's TRUE full content height, measured
     * directly instead of trusting what the wrap_content chain reports.
     *
     * Why the chain under-measures: the page's ScrollView measures its
     * content with an UNSPECIFIED height spec that still carries the
     * viewport's size, and every ancestor between it and the embedded
     * list forwards that size along with the mode — the main
     * LinearLayout (which also subtracts the height of the rows above the
     * panel from the size), the panel, the sub-page's CoordinatorLayout
     * and the list container. The embedded list is a RecyclerView with a
     * LinearLayoutManager, and LinearLayoutManager only treats an
     * UNSPECIFIED spec as unbounded when its size is also 0
     * (`resolveIsInfinite()`: mode == UNSPECIFIED && getEnd() == 0,
     * where getEnd() is the stored spec size). With a leftover size of
     * roughly one screenful it lays out only that much content and
     * reports exactly that as its measured height — which is why an
     * expanded General section stopped right after "Concurrent
     * connections": nothing was removed by the grouping, the remaining
     * rows (Battery optimization, the ISP and Links categories) were
     * simply never laid out.
     *
     * Measuring the container ourselves with UNSPECIFIED size 0 makes
     * the list measure every row, and the true height propagates back up
     * through the same ancestors. (The Phase 8.7 behaviour detachment is
     * still required for this: the app-bar scrolling behaviour clamps the
     * list container during any measure, including this one.)
     */
    private fun measureAccordionFullHeight(group: AccordionGroup): Int {
        val container = group.container
        val width = container.width
        if (width <= 0) return 0
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(widthSpec, heightSpec)
        return container.measuredHeight
    }

    /** Phase 8.8: re-measures an expanded section's full content height
     * and re-pins its container to it. Called when the embedded page's
     * adapter reports changes (rows appearing or disappearing, summaries
     * changing) and after configuration changes. No-ops while the
     * section is collapsed or mid-animation — the expand flow measures
     * for itself. The re-measure is posted so it runs after the change
     * that triggered it has been laid out. */
    private fun refreshAccordionHeight(group: AccordionGroup): Unit {
        if (!group.expanded || group.heightAnimator != null) return
        val container = group.container
        if (container.visibility != View.VISIBLE) return
        container.post {
            if (!group.expanded || group.heightAnimator != null) return@post
            val newHeight = measureAccordionFullHeight(group)
            if (newHeight > 0 && newHeight != group.fullHeight) {
                group.fullHeight = newHeight
                container.layoutParams.height = newHeight
                container.requestLayout()
            }
        }
    }

    /** Phase 8.8: observes the embedded page's preference adapter so a
     * section whose content grows or shrinks while it is open (dependent
     * rows appearing, visibility toggles) re-pins to its new full height.
     * Registered once per adapter instance; a recreated view brings a new
     * adapter, which the identity check picks up on the next expand. */
    private fun watchAccordionContent(group: AccordionGroup) {
        val fragment = childFragmentManager.findFragmentById(group.container.id)
            as? PreferenceFragmentCompat ?: return
        val adapter = runCatching { fragment.listView.adapter }.getOrNull() ?: return
        if (group.observedAdapter === adapter) return
        group.observedAdapter = adapter
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refreshAccordionHeight(group)

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
                refreshAccordionHeight(group)

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                refreshAccordionHeight(group)

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
                refreshAccordionHeight(group)

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                refreshAccordionHeight(group)
        }
        group.contentObserver = observer
        adapter.registerAdapterDataObserver(observer)
    }

    /** Stops any in-flight expand/collapse work for the group. A cancelled
     * animator still delivers its end callback, so references are cleared
     * first and callbacks verify they are still current before acting. */
    private fun cancelAccordionAnimation(group: AccordionGroup) {
        group.preDrawListener?.let {
            group.container.viewTreeObserver.removeOnPreDrawListener(it)
            group.preDrawListener = null
        }
        group.heightAnimator?.let {
            group.heightAnimator = null
            it.cancel()
        }
    }

    private fun expandAccordionContainer(group: AccordionGroup) {
        val container = group.container
        cancelAccordionAnimation(group)
        watchAccordionContent(group)
        // A cancelled collapse may have left a fixed mid-animation height.
        container.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        container.visibility = View.VISIBLE
        container.alpha = 0f
        // The section's full height is only known after its first layout.
        // Shrink it back during pre-draw — before anything of it is
        // painted — then animate open from zero.
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                container.viewTreeObserver.removeOnPreDrawListener(this)
                if (group.preDrawListener === this) group.preDrawListener = null
                if (!group.expanded) return true
                // Phase 8.8: container.height here is whatever the
                // wrap_content chain settled at, which under-measures
                // the section at about one screenful (see
                // measureAccordionFullHeight). Measure the true full
                // height explicitly; fall back to the laid-out height
                // only if the container has no width yet.
                val target = measureAccordionFullHeight(group)
                    .takeIf { it > 0 } ?: container.height
                if (target <= 0) {
                    container.alpha = 1f
                    return true
                }
                group.fullHeight = target
                startAccordionHeightAnimation(group, 0, target, expanding = true)
                return true
            }
        }
        group.preDrawListener = listener
        container.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun collapseAccordionContainer(group: AccordionGroup) {
        val container = group.container
        cancelAccordionAnimation(group)
        container.alpha = 1f
        if (container.visibility != View.VISIBLE) return
        val from = container.height
        if (from <= 0) {
            container.visibility = View.GONE
            return
        }
        startAccordionHeightAnimation(group, from, 0, expanding = false)
    }

    private fun startAccordionHeightAnimation(
        group: AccordionGroup,
        from: Int,
        to: Int,
        expanding: Boolean,
    ) {
        val container = group.container
        container.layoutParams.height = from
        container.requestLayout()
        val animator = ValueAnimator.ofInt(from, to).apply {
            duration = ACCORDION_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                container.layoutParams.height = it.animatedValue as Int
                container.requestLayout()
                container.alpha =
                    if (expanding) it.animatedFraction else 1f - it.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // A superseded (cancelled) animator must not touch the view.
                    if (group.heightAnimator !== animation) return
                    group.heightAnimator = null
                    // Phase 8.8: an expanded section stays PINNED to its
                    // explicitly measured full height — returning it to
                    // WRAP_CONTENT would let the under-measuring parent
                    // chain shrink it back to about one screenful on the
                    // next layout pass.
                    container.layoutParams.height =
                        if (expanding && group.fullHeight > 0) group.fullHeight
                        else ViewGroup.LayoutParams.WRAP_CONTENT
                    container.alpha = 1f
                    if (!expanding) container.visibility = View.GONE
                    container.requestLayout()
                }
            })
        }
        group.heightAnimator = animator
        animator.start()
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
        // Re-audit: main settings ScrollView's fixed 92dp was
        // short by systemBottom-16dp on 3-button nav.
        view.findViewById<View>(R.id.settings_main_scroll)
            ?.refreshFloatingNavBottomPadding()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Phase 8.8: a pinned section height was measured for the old
        // width; re-measure any expanded sections once they have been
        // laid out at the new one. (If the activity is recreated
        // instead, the sections simply start collapsed again and there
        // is no live state to repair.)
        accordionGroups.forEach { group ->
            if (group.expanded) {
                group.container.doOnLayout { refreshAccordionHeight(group) }
            }
        }
    }

    override fun onBindingCreated(binding: MainSettingsBinding) {
        fun navigate(id: Int) {
            activity?.navigate(id, Bundle())
        }

        /** used to debug leaks
        showToast(activity,"${VideoDownloadManager.downloadStatusEvent.size} :
        ${VideoDownloadManager.downloadProgressEvent.size}") **/

        fun hasProfilePictureFromAccountManagers(accountManagers: Array<AuthRepo>): Boolean {
            for (syncApi in accountManagers) {
                val login = syncApi.authUser()
                val pic = login?.profilePicture ?: continue

                binding.settingsProfilePic.let { imageView ->
                    imageView.loadImage(pic) {
                        // Fallback to random error drawable
                        error { getImageFromDrawable(context ?: return@error null, errorProfilePic) }
                    }
                }
                binding.settingsProfileText.text = login.name
                return true // sync profile exists
            }
            return false // not syncing
        }

        // display local account information if not syncing
        if (!hasProfilePictureFromAccountManagers(AccountManager.allApis)) {
            val activity = activity ?: return
            val currentAccount = try {
                DataStoreHelper.accounts.firstOrNull {
                    it.keyIndex == DataStoreHelper.selectedKeyIndex
                } ?: activity.let { DataStoreHelper.getDefaultAccount(activity) }

            } catch (t: IllegalStateException) {
                Log.e("AccountManager", "Activity not found", t)
                null
            }

            binding.settingsProfilePic.loadImage(currentAccount?.image)
            binding.settingsProfileText.text = currentAccount?.name
        }

        binding.apply {
            // Phase 8.6: the six sub-page entries no longer navigate. Each is
            // an accordion header; tapping it expands that sub-page (embedded
            // as a child fragment) directly below the header on this page,
            // tapping again collapses it. The standalone sub-page
            // destinations stay in the nav graph for deep links.
            accordionGroups = listOf(
                AccordionGroup(
                    settingsGeneral, settingsGeneralContainer, R.id.settings_player
                ) { SettingsGeneral() },
                AccordionGroup(
                    settingsPlayer, settingsPlayerContainer, R.id.settings_ui
                ) { SettingsPlayer() },
                AccordionGroup(
                    settingsUi, settingsUiContainer, R.id.settings_providers
                ) { SettingsUI() },
                AccordionGroup(
                    settingsProviders, settingsProvidersContainer, R.id.settings_extensions
                ) { SettingsProviders() },
                AccordionGroup(
                    settingsUpdates, settingsUpdatesContainer, R.id.settings_credits
                ) { SettingsUpdates() },
                AccordionGroup(
                    settingsCredits, settingsAccountContainer, View.NO_ID
                ) { SettingsAccount() },
            )
            accordionGroups.forEach { group ->
                setupAccordionChevron(group)
                // Clip the expanded panel to the same 20dp rounded corners
                // as the group cards above it.
                group.container.clipToOutline = true
                group.container.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 20.toPx.toFloat())
                    }
                }
                group.header.setOnClickListener { toggleAccordionGroup(group) }
            }

            // Extensions has no PreferenceFragment sub-page (it is a custom
            // plugin-list screen), so its row keeps navigating as before.
            settingsExtensions.setOnClickListener {
                navigate(R.id.action_navigation_global_to_navigation_settings_extensions)
            }

            // Stremio Addons is likewise a custom screen (addon list),
            // so its row navigates to its own page.
            settingsStremioAddons.setOnClickListener {
                navigate(R.id.action_navigation_global_to_navigation_settings_stremio)
            }

            // Nuvio Plugins (bridge step 2) — JS scraper repositories.
            settingsNuvioPlugins.setOnClickListener {
                navigate(R.id.action_navigation_global_to_navigation_settings_nuvio)
            }

            // SkyStream Plugins (bridge 8.78) — JS plugin repositories,
            // full providers on a persistent QuickJS context.
            settingsSkyPlugins.setOnClickListener {
                navigate(R.id.action_navigation_global_to_navigation_settings_sky)
            }

            // Phase 8.45: Categories lives in the Content group
            // (his ask) and opens the custom home list manager.
            settingsCategories.setOnClickListener {
                navigate(R.id.global_to_navigation_home_custom_lists)
            }

            // Phase 8.91: Feed Categories (Content group, under
            // Categories) — show/hide + reorder the Feed's
            // category rows and chips.
            settingsFeedCategories.setOnClickListener {
                navigate(R.id.global_to_navigation_feed_categories)
            }

            // Phase 8.50: Family Mode switch (Content group) —
            // hides adult providers and NSFW content app-wide.
            val familyPrefs = PreferenceManager
                .getDefaultSharedPreferences(requireContext())
            settingsFamilyModeSwitch.isChecked =
                familyPrefs.getBoolean(FamilyMode.KEY, false)
            settingsFamilyMode.setOnClickListener {
                val enabled = !settingsFamilyModeSwitch.isChecked
                settingsFamilyModeSwitch.isChecked = enabled
                familyPrefs.edit()
                    .putBoolean(FamilyMode.KEY, enabled).apply()
                Toast.makeText(
                    requireContext(),
                    if (enabled) R.string.family_mode_enabled
                    else R.string.family_mode_disabled,
                    Toast.LENGTH_SHORT
                ).show()
            }

            if (isLayout(TV)) {
                (accordionGroups.map { it.header } + settingsExtensions).forEach { view ->
                    view.isFocusable = true
                    view.isFocusableInTouchMode = true
                }

                // Default focus on TV
                settingsGeneral.requestFocus()
            }
        }

        val appVersion = BuildConfig.VERSION_NAME
        val commitHash = activity?.currentCommitHash() ?: ""
        val buildTimestamp = SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM,
            Locale.getDefault()
        ).apply { timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")

        binding.appVersion.text = appVersion
        binding.buildDate.text = buildTimestamp
        binding.commitHash.text = commitHash
        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(txt(R.string.extension_version), "$appVersion $commitHash $buildTimestamp")
            true
        }
    }
}
