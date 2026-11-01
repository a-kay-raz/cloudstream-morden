package com.lagradost.cloudstream3.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.TransactionTooLargeException
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.Toast.LENGTH_LONG
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.StyleRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.view.marginBottom
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.isRtl
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.disableBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.enableBackPressedCallback

object UIHelper {
    val Int.toPx: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()
    val Float.toPx: Float get() = (this * Resources.getSystem().displayMetrics.density)
    val Int.toDp: Int get() = (this / Resources.getSystem().displayMetrics.density).toInt()
    val Float.toDp: Float get() = (this / Resources.getSystem().displayMetrics.density)

    fun Context.checkWrite(): Boolean {
        return (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
                == PackageManager.PERMISSION_GRANTED
                // Since Android 13, we can't request external storage permission,
                // so don't check it.
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }

    fun populateChips(
        view: ChipGroup?,
        tags: List<String>,
        @StyleRes style: Int = R.style.ChipFilled,
        @AttrRes textColor: Int? = R.attr.white,
    ) {
        if (view == null) return
        view.removeAllViews()
        val context = view.context ?: return
        val maxTags = tags.take(10) // Limited because they are too much

        maxTags.forEach { tag ->
            val chip = Chip(context)
            val chipDrawable = ChipDrawable.createFromAttributes(
                context,
                null,
                0,
                style
            )
            chip.setChipDrawable(chipDrawable)
            // Phase 3: full-pill genre/tag chips (radius far beyond chip height)
            chip.chipCornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 100f, context.resources.displayMetrics
            )
            chip.text = tag
            chip.isChecked = false
            chip.isCheckable = false
            chip.isFocusable = false
            chip.isClickable = false
            textColor?.let {
                chip.setTextColor(context.colorFromAttribute(it))
            }
            view.addView(chip)
        }
    }

    fun Activity.requestRW() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE
            ),
            1337
        )
    }

    fun clipboardHelper(label: UiText, text: CharSequence) {
        val ctx = context ?: return
        try {
            ctx.let {
                val clip = ClipData.newPlainText(label.asString(ctx), text)
                val labelSuffix = txt(R.string.toast_copied).asString(ctx)
                ctx.getSystemService<ClipboardManager>()?.setPrimaryClip(clip)

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    showToast("${label.asString(ctx)} $labelSuffix")
                }
            }
        } catch (t: Throwable) {
            Log.e("ClipboardService", "$t")
            when (t) {
                is SecurityException -> {
                    showToast(R.string.clipboard_permission_error)
                }

                is TransactionTooLargeException -> {
                    showToast(R.string.clipboard_too_large)
                }

                else -> {
                    showToast(R.string.clipboard_unknown_error, LENGTH_LONG)
                }
            }
        }
    }

    /**
     * Sets ListView height dynamically based on the height of the items.
     *
     * @param listView to be resized
     * @return true if the listView is successfully resized, false otherwise
     */
    fun setListViewHeightBasedOnItems(listView: ListView?) {
        val listAdapter: ListAdapter = listView?.adapter ?: return
        val numberOfItems: Int = listAdapter.count

        // Get total height of all items.
        var totalItemsHeight = 0
        for (itemPos in 0 until numberOfItems) {
            val item: View = listAdapter.getView(itemPos, null, listView)
            item.measure(0, 0)
            totalItemsHeight += item.measuredHeight
        }

        // Get total height of all item dividers.
        val totalDividersHeight: Int = listView.dividerHeight *
                (numberOfItems - 1)

        // Set list height.
        val params: ViewGroup.LayoutParams = listView.layoutParams
        params.height = totalItemsHeight + totalDividersHeight
        listView.layoutParams = params
        listView.requestLayout()
    }

    fun Context.getSpanCount(isHorizontal:Boolean=false): Int {
//        val compactView = false
        val spanCountLandscape = if (isHorizontal) 3 else 6
        val spanCountPortrait = if (isHorizontal) 2 else 3
        val orientation = resources.configuration.orientation

        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            spanCountLandscape
        } else spanCountPortrait
    }

    /**
     * Batch 4: width-aware span count for the Feed grids (Feed,
     * FeedSearch, FeedCategory). Search already used [getSpanCount]
     * (3 portrait / 6 landscape); the Feed grids were a hardcoded 3
     * everywhere, so a ~320dp phone got ~95dp columns and landscape
     * stayed 3-across while Search went to 6. Below ~360dp wide the
     * grid drops to 2 columns; landscape goes to 6 like Search.
     */
    fun Context.getFeedSpanCount(): Int {
        val config = resources.configuration
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) return 6
        return if (config.screenWidthDp in 1..<360) 2 else 3
    }

    /**
     * Batch 4: the floating pill's footprint, as a runtime value.
     * The pill is 50dp tall + 6dp top/bottom padding = 62dp, lifted
     * 14dp above the screen edge, plus the system-bars bottom inset
     * on API 30+ (FloatingNavView grows its own bottom padding by
     * that inset) — so the pill top sits 76dp + systemBottom above
     * the screen bottom. Content clearing it needs 76dp +
     * systemBottom + a 12dp gap = 88dp + systemBottom. The old XML
     * values (76dp Feed, 92dp Home/Search) were hardcoded and short
     * by systemBottom (~16dp gesture nav, ~48dp 3-button nav).
     * Portrait only: in landscape the floating pill is hidden
     * (MainActivity shows the side rail instead), so the XML
     * padding is left untouched there. Insets are NOT consumed,
     * so a fragment root's own inset listener still runs.
     */
    private const val FLOATING_NAV_PILL_DP = 76f
    private const val FLOATING_NAV_GAP_DP = 12f

    fun View.applyFloatingNavBottomPadding() {
        val ctx = context ?: return
        if (ctx.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) return
        val density = ctx.resources.displayMetrics.density
        val basePx =
            ((FLOATING_NAV_PILL_DP + FLOATING_NAV_GAP_DP) * density).toInt()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updatePadding(bottom = basePx + ctx.getNavigationBarHeight())
            return
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBottom =
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.updatePadding(bottom = basePx + systemBottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /**
     * Re-audit fix: [applyFloatingNavBottomPadding] was only called
     * from onBindingCreated and early-returned in landscape, so a
     * portrait-created view kept 88dp + systemBottom after rotating
     * to landscape (where the pill is hidden and the fragment root
     * already adds its own bottom inset), and a landscape-created
     * view never got the runtime value in portrait. Call this from
     * fixLayout: portrait re-installs the listener, landscape
     * removes it and drops to a 12dp rest gap (the root inset
     * covers the system bar there).
     */
    fun View.refreshFloatingNavBottomPadding() {
        val ctx = context ?: return
        if (ctx.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) {
            ViewCompat.setOnApplyWindowInsetsListener(this, null)
            updatePadding(
                bottom = (FLOATING_NAV_GAP_DP * ctx.resources.displayMetrics.density).toInt()
            )
            return
        }
        applyFloatingNavBottomPadding()
    }

    /** Same runtime footprint as [applyFloatingNavBottomPadding],
     *  as a bottom margin — for views floating above the pill
     *  (Feed mini player, Home Random FAB). */
    fun View.applyFloatingNavBottomMargin() {
        val ctx = context ?: return
        if (ctx.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) return
        val density = ctx.resources.displayMetrics.density
        val basePx =
            ((FLOATING_NAV_PILL_DP + FLOATING_NAV_GAP_DP) * density).toInt()
        fun setMargin(extra: Int) {
            val params = layoutParams as? MarginLayoutParams ?: return
            params.bottomMargin = basePx + extra
            layoutParams = params
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setMargin(ctx.getNavigationBarHeight())
            return
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBottom =
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            setMargin(systemBottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /** Rotation-aware version of [applyFloatingNavBottomMargin]:
     * landscape removes the listener and drops to a 12dp rest gap,
     * portrait re-installs the runtime formula. See
     * [refreshFloatingNavBottomPadding] for why. */
    fun View.refreshFloatingNavBottomMargin() {
        val ctx = context ?: return
        if (ctx.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) {
            ViewCompat.setOnApplyWindowInsetsListener(this, null)
            val params = layoutParams as? MarginLayoutParams ?: return
            params.bottomMargin =
                (FLOATING_NAV_GAP_DP * ctx.resources.displayMetrics.density).toInt()
            layoutParams = params
            return
        }
        applyFloatingNavBottomMargin()
    }

    fun Fragment.hideKeyboard() {
        activity?.window?.decorView?.clearFocus()
        view?.let {
            hideKeyboard(it)
        }
    }

    fun View?.setAppBarNoScrollFlagsOnTV() {
        if (isLayout(TV or EMULATOR)) {
            this?.updateLayoutParams<AppBarLayout.LayoutParams> {
                scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
            }
        }
    }

    fun Activity.hideKeyboard() {
        window?.decorView?.clearFocus()
        this.findViewById<View>(android.R.id.content)?.rootView?.let {
            hideKeyboard(it)
        }
    }

    fun Activity?.navigate(
        navigationId: Int,
        args: Bundle? = null,
        navOptions: NavOptions? = null // To control nav graph & manage back stack
    ) {
        val tag = "NavComponent"
        if (this is FragmentActivity) {
            try {
                runOnUiThread {
                    // Navigate using navigation ID
                    val navHostFragment =
                        supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    Log.i(tag, "Navigating to fragment: $navigationId")
                    navHostFragment?.navController?.navigate(navigationId, args, navOptions)
                }
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    // Open activities from an activity outside the nav graph
    fun Context.openActivity(activity: Class<*>, args: Bundle? = null, baseIntent: Intent? = null) {
        val tag = "NavComponent"
        try {
            val intent = baseIntent ?: Intent()
            intent.setClass(this, activity)

            if (args != null) {
                intent.putExtras(args)
            }
            Log.i(tag, "Navigating to Activity: ${activity.simpleName}")
            startActivity(intent)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** If you want to call this from a BackPressedCallback, pass the name of the callback to temporarily disable it */
    fun FragmentActivity.popCurrentPage(fromBackPressedCallback: String? = null) {
        // Use the main looper handler to post actions on the main thread
        main {
            // Post the back press action to the main thread handler to ensure it executes
            // after any currently pending UI updates or fragment transactions.
            if (fromBackPressedCallback != null) {
                disableBackPressedCallback(fromBackPressedCallback)
            }
            if (!supportFragmentManager.isStateSaved) {
                // Get the top fragment from the back stack
                Log.d("popFragment", "Destroying Fragment")
                // If the state is not saved, it's safe to perform the back press action.
                onBackPressedDispatcher.onBackPressed()
            } else {
                // If the state is saved, retry the back press action after a slight delay.
                // This gives the FragmentManager time to complete any ongoing state-saving
                // operations or transactions, ensuring that we do not encounter an IllegalStateException.
                delay(100)
                if (!supportFragmentManager.isStateSaved) {
                    Log.d("popFragment", "Destroying after delay")
                    onBackPressedDispatcher.onBackPressed()
                }
            }
            if (fromBackPressedCallback != null) {
                enableBackPressedCallback(fromBackPressedCallback)
            }
        }
    }

    @ColorInt
    fun Context.getResourceColor(@AttrRes resource: Int, alphaFactor: Float = 1f): Int {
        val color = colorFromAttribute(resource)
        return if (alphaFactor < 1f) adjustAlpha(color, alphaFactor) else color
    }

    @ColorInt
    fun Context.colorFromAttribute(@AttrRes attribute: Int): Int {
        var color = 0
        withStyledAttributes(attrs = intArrayOf(attribute)) {
            color = getColor(0, 0)
        }
        return color
    }

    @ColorInt
    fun adjustAlpha(@ColorInt color: Int, factor: Float): Int {
        val alpha = (color.alpha * factor).roundToInt()
        return Color.argb(alpha, color.red, color.green, color.blue)
    }

    var createPaletteAsyncCache: HashMap<String, Palette> = hashMapOf()
    fun createPaletteAsync(url: String, bitmap: Bitmap, callback: (Palette) -> Unit) {
        createPaletteAsyncCache[url]?.let { palette ->
            callback.invoke(palette)
            return
        }
        Palette.from(bitmap).generate { paletteNull ->
            paletteNull?.let { palette ->
                createPaletteAsyncCache[url] = palette
                callback(palette)
            }
        }
    }

    fun Activity.hideSystemUI() {
        // Enables regular immersive mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            return
        }

        // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
        // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    fun Activity.enableEdgeToEdgeCompat() {
        // edge-to-edge is very buggy on earlier versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        WindowCompat.enableEdgeToEdge(window)
    }

    fun Activity.setNavigationBarColorCompat(@AttrRes resourceId: Int) {
        // edge-to-edge handles this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return

        @Suppress("DEPRECATION")
        window?.navigationBarColor = colorFromAttribute(resourceId)
    }

    fun Context.getStatusBarHeight(): Int {
        if (isLayout(TV or EMULATOR)) {
            return 0
        }

        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun fixPaddingStatusbarMargin(v: View?) {
        if (v == null) return
        val ctx = v.context ?: return

        v.layoutParams = v.layoutParams.apply {
            if (this is MarginLayoutParams) {
                setMargins(
                    v.marginLeft,
                    v.marginTop + ctx.getStatusBarHeight(),
                    v.marginRight,
                    v.marginBottom
                )
            }
        }
    }

    fun fixPaddingStatusbarView(v: View?) {
        if (v == null) return
        val ctx = v.context ?: return
        val params = v.layoutParams
        params.height = ctx.getStatusBarHeight()
        v.layoutParams = params
    }

    fun fixSystemBarsPadding(
        v: View,
        @DimenRes heightResId: Int? = null,
        @DimenRes widthResId: Int? = null,
        padTop: Boolean = true,
        padBottom: Boolean = true,
        padLeft: Boolean = true,
        padRight: Boolean = true,
        overlayCutout: Boolean = true,
        fixIme: Boolean = false
    ) {
        // edge-to-edge is very buggy on earlier versions so we just
        // handle the status bar here instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (padTop) {
                val ctx = v.context ?: return
                v.updatePadding(top = ctx.getStatusBarHeight())
            }
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(v) { view, windowInsets ->
            val leftCheck = if (view.isRtl()) padRight else padLeft
            val rightCheck = if (view.isRtl()) padLeft else padRight

            val insetTypes = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                if (fixIme) WindowInsetsCompat.Type.ime() else 0

            val insets = windowInsets.getInsets(insetTypes)

            view.updatePadding(
                left = if (leftCheck) insets.left else view.paddingLeft,
                right = if (rightCheck) insets.right else view.paddingRight,
                bottom = if (padBottom) insets.bottom else view.paddingBottom,
                top = if (padTop) insets.top else view.paddingTop
            )

            heightResId?.let {
                val heightPx = view.resources.getDimensionPixelSize(it)
                view.updateLayoutParams {
                    height = heightPx + insets.bottom
                }
            }

            widthResId?.let {
                val widthPx = view.resources.getDimensionPixelSize(it)
                view.updateLayoutParams {
                    val startInset = if (view.isRtl()) insets.right else insets.left
                    width = if (startInset > 0) widthPx + startInset else widthPx
                }
            }

            if (overlayCutout && isLayout(PHONE)) {
                // Draw a black overlay over the cutout. We do this so that
                // it doesn't use the fragment background. We want it to
                // appear as if the screen actually ends at cutout.
                val cutout = windowInsets.displayCutout
                if (cutout != null) {
                    val left = if (!leftCheck) 0 else cutout.safeInsetLeft
                    val right = if (!rightCheck) 0 else cutout.safeInsetRight
                    view.overlay.clear()
                    if (left > 0 || right > 0) {
                        view.overlay.add(
                            CutoutOverlayDrawable(
                                view,
                                leftCutout = left,
                                rightCutout = right
                            )
                        )
                    }
                }
            }

            WindowInsetsCompat.CONSUMED
        }
    }

    fun Context.getNavigationBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    fun Context?.isBottomLayout(): Boolean {
        if (this == null) return true
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        return settingsManager.getBoolean(getString(R.string.bottom_title_key), true)
    }

    fun Activity.changeStatusBarState(hide: Boolean) {
        try {
            if (hide) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    @Suppress("DEPRECATION")
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.show(WindowInsetsCompat.Type.statusBars())
                } else {
                    @Suppress("DEPRECATION")
                    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
            }
        } catch (t: Throwable) {
            logError(t)
        }
    }

    // Shows the system bars by removing all the flags
    // except for the ones that make the content appear under the system bars.
    fun Activity.showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLayout(EMULATOR)) {
                controller.show(WindowInsetsCompat.Type.navigationBars())
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
            return
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        changeStatusBarState(isLayout(EMULATOR))
    }

    fun hideKeyboard(view: View?) {
        if (view == null) return

        val inputMethodManager =
            view.context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager?
        inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun showInputMethod(view: View?) {
        if (view == null) return
        val inputMethodManager =
            view.context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager?
        inputMethodManager?.showSoftInput(view, 0)
    }

    fun Dialog?.dismissSafe(activity: Activity?) {
        if (this?.isShowing == true && activity?.isFinishing == false) {
            this.dismiss()
        }
    }

    fun Dialog?.dismissSafe() {
        if (this?.isShowing == true && activity?.isFinishing != true) {
            this.dismiss()
        }
    }

    /**
     * Source: https://stackoverflow.com/questions/70954321/circular-progress-indicator-inside-buttons-android-material-design
     *
     * Shows indeterminate progress bar on this button in place of where icon would be.
     * By default the tint of progress bar is the same as iconTint.
     *
     * @param tintColor (@ColorInt Int) Sets custom progress bar tint color.
     */
    fun MaterialButton.showProgress(@ColorInt tintColor: Int = this.iconTint.defaultColor) =
        // Use runOnMainThreadNative to allow process on io threads, to make the code a bit cleaner
        runOnMainThreadNative {
            // No need to set it again, as then it will reset the animation
            if(this.icon is IndeterminateDrawable<*>) {
                return@runOnMainThreadNative
            }
            val spec = CircularProgressIndicatorSpec(
                context, null, 0,
                com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall
            )

            spec.indicatorColors = intArrayOf(tintColor)

            val progressIndicatorDrawable =
                IndeterminateDrawable.createCircularDrawable(context, spec)

            this.icon = progressIndicatorDrawable
            if (this.getTag(R.id.text1) == null)
                this.setTag(R.id.text1, this.text)
            this.text = ""
        }

    fun MaterialButton.hideProgress() =
        runOnMainThreadNative {
            this.text = this.getTag(R.id.text1) as? String
            this.icon = null
        }

    /**id, stringRes */
    @SuppressLint("RestrictedApi")
    fun View.popupMenuNoIcons(
        items: List<Pair<Int, Int>>,
        onMenuItemClick: MenuItem.() -> Unit,
    ): PopupMenu {
        val ctw = ContextThemeWrapper(context, R.style.PopupMenu)
        val popup = PopupMenu(
            ctw,
            this,
            Gravity.NO_GRAVITY,
            androidx.appcompat.R.attr.actionOverflowMenuStyle,
            0
        )

        items.forEach { (id, stringRes) ->
            popup.menu.add(0, id, 0, stringRes)
        }

        (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

        popup.setOnMenuItemClickListener {
            it.onMenuItemClick()
            true
        }

        popup.show()
        return popup
    }

    /**id, string */
    @SuppressLint("RestrictedApi")
    fun View.popupMenuNoIconsAndNoStringRes(
        items: List<Pair<Int, String>>,
        onMenuItemClick: MenuItem.() -> Unit,
    ): PopupMenu {
        val ctw = ContextThemeWrapper(context, R.style.PopupMenu)
        val popup = PopupMenu(
            ctw,
            this,
            Gravity.NO_GRAVITY,
            androidx.appcompat.R.attr.actionOverflowMenuStyle,
            0
        )

        items.forEach { (id, string) ->
            popup.menu.add(0, id, 0, string)
        }

        (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

        popup.setOnMenuItemClickListener {
            it.onMenuItemClick()
            true
        }

        popup.show()
        return popup
    }
}

private class CutoutOverlayDrawable(
    private val view: View,
    private val leftCutout: Int,
    private val rightCutout: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        if (leftCutout > 0) canvas.drawRect(
            0f,
            0f,
            leftCutout.toFloat(),
            view.height.toFloat(),
            paint
        )
        if (rightCutout > 0) {
            canvas.drawRect(
                view.width - rightCutout.toFloat(),
                0f, view.width.toFloat(),
                view.height.toFloat(),
                paint
            )
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.OPAQUE
}