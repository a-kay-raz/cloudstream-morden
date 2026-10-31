package com.lagradost.cloudstream3.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R

/**
 * Phase 8.89 — the app's single bottom-sheet chrome, defined once.
 *
 * The reference is the Feed Filters sheet (Phase 8.85), whose look
 * the user praised and ordered applied everywhere ("এই sheet টা সব
 * খানেই দিও"): a dark rounded surface, a small centred drag handle,
 * a bold title row with a circular ✕ close button, 20dp side
 * padding, dark-pill secondary actions and a full-width red-pill
 * primary action. XML sheets reproduce the same values inline (see
 * PHASE889_CHANGES.md); code-built sheets use these factories so
 * the numbers live in exactly one place.
 *
 * The surface itself is NOT built here: every BottomSheetDialog in
 * the app already draws @drawable/rounded_dialog through
 * AppBottomSheetDialogTheme / AppModalStyle (or the sheet root
 * carries it, with the dialog frame forced transparent via
 * [transparentSheetFrame]).
 */
object SheetChrome {
    const val SIDE_PADDING_DP = 20
    const val HANDLE_WIDTH_DP = 36
    const val HANDLE_HEIGHT_DP = 4
    const val HANDLE_TOP_DP = 8
    const val HANDLE_BOTTOM_DP = 14
    const val TITLE_SIZE_SP = 18f
    // Batch 4: 48dp touch target, visible icon kept at 18dp
    // (was 36dp with 9dp padding) — same change as the XML
    // sheet close buttons.
    const val CLOSE_BUTTON_DP = 48
    const val CLOSE_BUTTON_PADDING_DP = 15
    const val PRIMARY_BUTTON_HEIGHT_DP = 52
    const val PRIMARY_BUTTON_RADIUS_DP = 26
    const val ROW_BUTTON_RADIUS_DP = 20
    const val PRIMARY_BUTTON_TOP_MARGIN_DP = 16

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    /**
     * The 36×4 centred drag handle (bg_feed_drag_handle). Carries
     * the 14dp gap to the title row; the top inset comes from the
     * sheet root's padding (8dp), exactly like the reference sheet.
     */
    fun dragHandle(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundResource(R.drawable.bg_feed_drag_handle)
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, HANDLE_WIDTH_DP), dp(ctx, HANDLE_HEIGHT_DP)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(ctx, HANDLE_BOTTOM_DP)
            }
        }
    }

    /** Sheet title text: 18sp, sans-serif-medium + bold, white. */
    fun titleText(ctx: Context, text: CharSequence?): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = TITLE_SIZE_SP
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }

    /**
     * The circular ✕ close button (bg_feed_circle_button). The
     * caller wires the click (dismiss), because code-built sheets
     * create their dialog after the sheet root.
     */
    fun closeButton(ctx: Context): ImageButton {
        return ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_baseline_close_24)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.white)
            setBackgroundResource(R.drawable.bg_feed_circle_button)
            val pad = dp(ctx, CLOSE_BUTTON_PADDING_DP)
            setPadding(pad, pad, pad, pad)
            contentDescription = ctx.getString(R.string.switcher_close)
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, CLOSE_BUTTON_DP), dp(ctx, CLOSE_BUTTON_DP)
            )
        }
    }

    /**
     * Title row: title (weight 1) + close button, centred
     * vertically. Returns the row and the close button so the
     * caller can wire dismissal once its dialog exists.
     */
    fun titleRow(
        ctx: Context,
        title: CharSequence?,
    ): Pair<LinearLayout, ImageButton> {
        val close = closeButton(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(
                titleText(ctx, title).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
            )
            addView(close)
        }
        return row to close
    }

    /** Vertical sheet root carrying the rounded_dialog surface. */
    fun sheetRoot(ctx: Context): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_dialog)
            setPadding(
                dp(ctx, SIDE_PADDING_DP), dp(ctx, HANDLE_TOP_DP),
                dp(ctx, SIDE_PADDING_DP), dp(ctx, SIDE_PADDING_DP)
            )
        }
    }

    /** Secondary action in a button row: dark pill (#262626), white text. */
    fun secondaryButton(ctx: Context, textRes: Int): MaterialButton {
        return MaterialButton(ctx).apply {
            setText(textRes)
            backgroundTintList =
                ContextCompat.getColorStateList(ctx, R.color.extButtonBackground)
            setTextColor(Color.WHITE)
            cornerRadius = dp(ctx, ROW_BUTTON_RADIUS_DP)
        }
    }

    /** Primary action: red pill (the Filters sheet's DONE). */
    fun primaryButton(ctx: Context, textRes: Int): MaterialButton {
        return MaterialButton(ctx).apply {
            setText(textRes)
            backgroundTintList =
                ContextCompat.getColorStateList(ctx, R.color.colorPrimaryRed)
            setTextColor(Color.WHITE)
            cornerRadius = dp(ctx, PRIMARY_BUTTON_RADIUS_DP)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, PRIMARY_BUTTON_HEIGHT_DP)
            ).apply { topMargin = dp(ctx, PRIMARY_BUTTON_TOP_MARGIN_DP) }
        }
    }

    /**
     * The 8.82/8.85 recipe: when the sheet root carries the
     * rounded_dialog surface itself, the dialog's own sheet frame
     * must stay transparent so no second surface shows through.
     * Call after show().
     */
    fun transparentSheetFrame(dialog: BottomSheetDialog) {
        dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(Color.TRANSPARENT)
    }

    /**
     * Batch 4: cap a bottom sheet at 80% of screen height, the
     * same clamp SingleSelectionHelper.applyResponsiveDialogSize
     * applies to BottomSheetDialogs. Plugin settings sheets
     * previously had no cap, so a tall form pushed Save/Cancel
     * below the sheet edge.
     */
    fun capSheetHeight(dialog: BottomSheetDialog) {
        val sheet = dialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val maxHeight =
            (dialog.context.resources.displayMetrics.heightPixels * 0.80f).toInt()
        sheet.post {
            if (sheet.height > maxHeight) {
                sheet.layoutParams = sheet.layoutParams.apply {
                    height = maxHeight
                }
            }
        }
    }

    /**
     * Batch 4: keep a sheet's button row above the keyboard.
     * No dialog/sheet in scope previously set a soft-input mode
     * or IME inset, so Apply/Save could sit behind the keyboard.
     */
    fun applyImeResize(dialog: Dialog) {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }
}
