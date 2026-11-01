package com.lagradost.cloudstream3.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * Liquid-glass chrome for the View-system shell (Phase 1, realistic route).
 *
 * Honest limit, read before "improving" this: this app has no Compose
 * tree, so Kyant0 Backdrop's layerBackdrop/drawBackdrop cannot capture
 * the Fragment content below a bar, and a View RenderEffect would blur
 * a view's OWN children (icons, labels), not what is behind it. So
 * glass here is what the View system can actually do: a translucent
 * fill that lets the content/video underneath show through (the nav
 * pill floats over the NavHost, the player bars float over a
 * texture_view, the mini player floats over the feed), plus a top
 * highlight and a thin light border, which is what reads as glass.
 * No RenderEffect is applied to any bar, on purpose.
 *
 * Functionality is untouched: this file only returns backgrounds.
 * Navigation state, taps, scrub, player gestures and insets are
 * owned by the callers, exactly as before.
 */
object GlassChrome {

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun baseShape(
        context: Context,
        fillColor: Int,
        radiusDp: Float,
        cornerRadii: FloatArray? = null,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            if (cornerRadii != null) {
                this.cornerRadii = cornerRadii
            } else {
                cornerRadius = dp(context, radiusDp).toFloat()
            }
            // Thin light border: the glass edge.
            setStroke(dp(context, 1f), Color.argb(0x40, 0xFF, 0xFF, 0xFF))
        }
    }

    private fun highlightShape(
        context: Context,
        radiusDp: Float,
        cornerRadii: FloatArray? = null,
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(0x33, 0xFF, 0xFF, 0xFF),
                Color.argb(0x00, 0xFF, 0xFF, 0xFF),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            if (cornerRadii != null) {
                this.cornerRadii = cornerRadii
            } else {
                cornerRadius = dp(context, radiusDp).toFloat()
            }
        }
    }

    private fun layered(
        context: Context,
        base: GradientDrawable,
        highlight: GradientDrawable,
        highlightBottomInsetDp: Float,
    ): Drawable {
        return LayerDrawable(arrayOf(base, highlight)).apply {
            // Highlight only on the top half, so the bar reads lit
            // from above instead of washed out all over.
            setLayerInset(
                1,
                0,
                0,
                0,
                dp(context, highlightBottomInsetDp),
            )
        }
    }

    /** Floating nav pill: near-black glass, 40dp fully-rounded ends. */
    fun pillBackground(context: Context): Drawable {
        return layered(
            context,
            baseShape(context, Color.argb(0xCC, 0x14, 0x14, 0x14), 40f),
            highlightShape(context, 40f),
            highlightBottomInsetDp = 31f,
        )
    }

    /**
     * Selected nav item blob: same organic per-corner radii as
     * FloatingNavView used to draw (58% 42% 55% 45% / 52% 55% 45% 48%
     * on the settled item), now as translucent tonal grey glass
     * instead of an opaque #262626 fill, so the pill behind it
     * still shows through at the edges.
     */
    fun blobBackground(context: Context): Drawable {
        val radii = floatArrayOf(
            dp(context, 56f).toFloat(), dp(context, 24f).toFloat(),
            dp(context, 44f).toFloat(), dp(context, 25f).toFloat(),
            dp(context, 54f).toFloat(), dp(context, 21f).toFloat(),
            dp(context, 46f).toFloat(), dp(context, 22f).toFloat(),
        )
        return layered(
            context,
            baseShape(context, Color.argb(0xB3, 0x26, 0x26, 0x26), 0f, radii),
            highlightShape(context, 0f, radii),
            highlightBottomInsetDp = 25f,
        )
    }

    /** Player top/bottom bars and other floating bars: 20dp glass. */
    fun barBackground(context: Context, radiusDp: Float = 20f): Drawable {
        return layered(
            context,
            baseShape(context, Color.argb(0x99, 0x14, 0x14, 0x14), radiusDp),
            highlightShape(context, radiusDp),
            highlightBottomInsetDp = 20f,
        )
    }

    fun applyBarBackground(view: View?) {
        if (view == null) return
        view.background = barBackground(view.context)
    }
}
