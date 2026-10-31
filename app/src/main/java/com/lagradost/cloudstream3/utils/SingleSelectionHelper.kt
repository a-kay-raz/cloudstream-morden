package com.lagradost.cloudstream3.utils

import android.content.Context
import android.app.Activity
import android.app.Dialog
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginTop
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.BottomInputDialogBinding
import com.lagradost.cloudstream3.databinding.BottomSelectionDialogBinding
import com.lagradost.cloudstream3.databinding.BottomTextDialogBinding
import com.lagradost.cloudstream3.databinding.OptionsPopupTvBinding
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SheetChrome
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.UIHelper.popupMenuNoIconsAndNoStringRes

object SingleSelectionHelper {
    fun Activity?.showOptionSelectStringRes(
        view: View?,
        poster: String?,
        options: List<Int>,
        tvOptions: List<Int> = listOf(),
        callback: (Pair<Boolean, Int>) -> Unit
    ) {
        if (this == null) return

        this.showOptionSelect(
            view,
            poster,
            options.map { this.getString(it) },
            tvOptions.map { this.getString(it) },
            callback
        )
    }

    private fun Activity?.showOptionSelect(
        view: View?,
        poster: String?,
        options: List<String>,
        tvOptions: List<String>,
        callback: (Pair<Boolean, Int>) -> Unit
    ) {
        if (this == null) return

        // This was temporarily removed until better UI is made
        /*if (isLayout(TV or EMULATOR)) {
            val binding = OptionsPopupTvBinding.inflate(layoutInflater)
            val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setView(binding.root)
                .create()

            dialog.show()

            binding.listview1.let { listView ->
                listView.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                listView.adapter =
                    ArrayAdapter<String>(this, R.layout.sort_bottom_single_choice_color).apply {
                        addAll(tvOptions)
                    }

                listView.setOnItemClickListener { _, _, i, _ ->
                    callback.invoke(Pair(true, i))
                    dialog.dismissSafe(this)
                }
            }

            binding.imageView.apply {
                isGone = poster.isNullOrEmpty()
                loadImage(poster)
            }
        } else {*/
        view?.popupMenuNoIconsAndNoStringRes(options.mapIndexed { index, s ->
            Pair(
                index,
                s
            )
        }) {
            callback(Pair(false, this.itemId))
        }
        //}
    }

    /**
     * Phase 8.13 — responsive popup sizing, applied from the shared dialog
     * funnels below so every selection/input popup in the app inherits it.
     * Bottom sheets sized to their content through a match_parent root, so
     * a long list (e.g. 22 accent colours) filled nearly the whole screen;
     * sheets are now capped at 80% of the screen height (shrunk only, never
     * stretched — shorter sheets keep their natural height, the inner list
     * scrolls). Centred dialogs get a consistent width of 92% of the
     * screen, capped at 440dp, instead of AppCompat's min-width defaults
     * that read as wall-to-wall on small phones. Phone layout only; TV
     * dialogs keep their stock sizing.
     */
    private fun applyResponsiveDialogSize(dialog: Dialog) {
        if (!isLayout(PHONE or EMULATOR)) return
        val metrics = dialog.context.resources.displayMetrics
        if (dialog is BottomSheetDialog) {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return
            val maxHeight = (metrics.heightPixels * 0.80f).toInt()
            sheet.post {
                if (sheet.height > maxHeight) {
                    sheet.layoutParams = sheet.layoutParams.apply {
                        height = maxHeight
                    }
                }
            }
        } else {
            val maxWidth = (440 * metrics.density).toInt()
            val width = minOf((metrics.widthPixels * 0.92f).toInt(), maxWidth)
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun Activity?.showDialog(
        binding: BottomSelectionDialogBinding,
        dialog: Dialog,
        items: List<String>,
        selectedIndex: List<Int>,
        name: String,
        showApply: Boolean,
        isMultiSelect: Boolean,
        callback: (List<Int>) -> Unit,
        dismissCallback: () -> Unit,
        itemLayout: Int = R.layout.sort_bottom_single_choice,
        icons: List<Int?>? = null
    ) {
        if (this == null) return

        val realShowApply = showApply || isMultiSelect
        val listView = binding.listview1
        val textView = binding.text1
        val applyButton = binding.applyBtt
        val cancelButton = binding.cancelBtt
        val applyHolder = binding.applyBttHolder

        if (isLayout(PHONE or EMULATOR) && dialog is BottomSheetDialog) {
            binding.dragHandle.isVisible = true
            // Phase 8.89: the standard sheet close button joins the
            // drag handle — real bottom sheets only; the centred
            // AlertDialog funnels sharing this layout keep it hidden.
            binding.closeBtt.isVisible = true
            listView.isNestedScrollingEnabled = true
        }
        binding.closeBtt.setOnClickListener {
            dialog.dismissSafe(this)
        }

        applyHolder.isVisible = realShowApply
        applyResponsiveDialogSize(dialog)
        if (!realShowApply) {
            val params = listView.layoutParams as LinearLayout.LayoutParams
            params.setMargins(listView.marginLeft, listView.marginTop, listView.marginRight, 0)
            listView.layoutParams = params
        }

        textView.text = name
        textView.isGone = name.isBlank()

        val arrayAdapter: ArrayAdapter<String> = if (icons == null) {
            ArrayAdapter<String>(this, itemLayout).apply { addAll(items) }
        } else {
            IconArrayAdapter(this, itemLayout, items, icons)
        }

        listView.adapter = arrayAdapter
        if (isMultiSelect) {
            listView.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        } else {
            listView.choiceMode = AbsListView.CHOICE_MODE_SINGLE
        }

        for (select in selectedIndex) {
            listView.setItemChecked(select, true)
        }

        selectedIndex.minOrNull()?.let {
            listView.setSelection(it)
        }

        //  var lastSelectedIndex = if(selectedIndex.isNotEmpty()) selectedIndex.first() else -1

        dialog.setOnDismissListener {
            dismissCallback.invoke()
        }

        listView.setOnItemClickListener { _, _, which, _ ->
            //  lastSelectedIndex = which
            if (realShowApply) {
                if (!isMultiSelect) {
                    listView.setItemChecked(which, true)
                }
            } else {
                callback.invoke(listOf(which))
                dialog.dismissSafe(this)
            }
        }
        if (realShowApply) {
            applyButton.setOnClickListener {
                val list = ArrayList<Int>()
                for (index in 0 until listView.count) {
                    if (listView.checkedItemPositions[index])
                        list.add(index)
                }
                callback.invoke(list)
                dialog.dismissSafe(this)
            }
            cancelButton.setOnClickListener {
                dialog.dismissSafe(this)
            }
        }
    }

    private fun Activity?.showInputDialog(
        binding: BottomInputDialogBinding,
        dialog: Dialog,
        value: String,
        name: String,
        textInputType: Int?,
        callback: (String) -> Unit,
        dismissCallback: () -> Unit
    ) {
        if (this == null) return

        val inputView = binding.nginxTextInput
        val textView = binding.text1
        val applyButton = binding.applyBtt
        val cancelButton = binding.cancelBtt
        val applyHolder = binding.applyBttHolder

        // Phase 8.89: standard sheet close button.
        binding.closeBtt.setOnClickListener {
            dialog.dismissSafe(this)
        }

        applyHolder.isVisible = true
        textView.text = name

        applyResponsiveDialogSize(dialog)

        if (textInputType != null) {
            inputView.inputType = textInputType // 16 for website url input type
        }
        inputView.setText(value, TextView.BufferType.EDITABLE)


        applyButton.setOnClickListener {
            callback.invoke(inputView.text.toString())  // try to save the setting, using callback
            dialog.dismissSafe(this)
        }

        cancelButton.setOnClickListener {  // just dismiss
            dialog.dismissSafe(this)
        }

        dialog.setOnDismissListener {
            dismissCallback.invoke()
        }

    }

    fun Activity?.showMultiDialog(
        items: List<String>,
        selectedIndex: List<Int>,
        name: String,
        dismissCallback: () -> Unit,
        callback: (List<Int>) -> Unit,
    ) {
        if (this == null) return

        val binding: BottomSelectionDialogBinding = BottomSelectionDialogBinding.inflate(
            LayoutInflater.from(this)
        )
        val builder =
            AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setView(binding.root)

        val dialog = builder.create()
        dialog.show()
        showDialog(
            binding,
            dialog,
            items,
            selectedIndex,
            name,
            showApply = true,
            isMultiSelect = true,
            callback,
            dismissCallback
        )
    }

    fun Activity?.showDialog(
        items: List<String>,
        selectedIndex: Int,
        name: String,
        showApply: Boolean,
        dismissCallback: () -> Unit,
        callback: (Int) -> Unit,
    ) {
        if (this == null) return

        val binding: BottomSelectionDialogBinding = BottomSelectionDialogBinding.inflate(
            LayoutInflater.from(this)
        )
        val builder =
            AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setView(binding.root)

        val dialog = builder.create()
        dialog.show()


        showDialog(
            binding,
            dialog,
            items,
            listOf(selectedIndex),
            name,
            showApply,
            false,
            { if (it.isNotEmpty()) callback.invoke(it.first()) },
            dismissCallback
        )
    }

    /** Only for a low amount of items */
    fun Activity?.showBottomDialog(
        items: List<String>,
        selectedIndex: Int,
        name: String,
        showApply: Boolean,
        dismissCallback: () -> Unit,
        callback: (Int) -> Unit,
    ) {
        if (this == null) return

        val binding: BottomSelectionDialogBinding = BottomSelectionDialogBinding.inflate(
            LayoutInflater.from(this)
        )

        val builder =
            BottomSheetDialog(this)
        builder.setContentView(binding.root)

        builder.show()
        showDialog(
            binding,
            builder,
            items,
            listOf(selectedIndex),
            name,
            showApply,
            false,
            { if (it.isNotEmpty()) callback.invoke(it.first()) },
            dismissCallback
        )
    }

    fun Activity.showBottomDialogInstant(
        items: List<String>,
        name: String,
        dismissCallback: () -> Unit,
        callback: (Int) -> Unit,
        icons: List<Int?>? = null,
    ): BottomSheetDialog {
        val builder =
            BottomSheetDialog(this)

        val binding: BottomSelectionDialogBinding = BottomSelectionDialogBinding.inflate(
            LayoutInflater.from(this)
        )

        //builder.setContentView(R.layout.bottom_selection_dialog_direct)
        builder.setContentView(binding.root)
        builder.show()
        showDialog(
            binding,
            builder,
            items,
            emptyList(),
            name,
            showApply = false,
            isMultiSelect = false,
            callback = { if (it.isNotEmpty()) callback.invoke(it.first()) },
            dismissCallback = dismissCallback,
            itemLayout = R.layout.sort_bottom_single_choice_no_checkmark,
            icons = icons
        )
        return builder
    }

    fun Activity.showNginxTextInputDialog(
        name: String,
        value: String,
        textInputType: Int?,
        dismissCallback: () -> Unit,
        callback: (String) -> Unit,
    ) {
        val builder = BottomSheetDialog(this)
        // Batch 4: no soft-input handling before, so the
        // Apply row could sit behind the keyboard.
        SheetChrome.applyImeResize(builder)

        val binding: BottomInputDialogBinding = BottomInputDialogBinding.inflate(
            LayoutInflater.from(this)
        )

        builder.setContentView(binding.root)

        builder.show()
        showInputDialog(
            binding,
            builder,
            value,
            name,
            textInputType,  // type is a uri
            callback,
            dismissCallback
        )
    }

    fun Activity.showBottomDialogText(
        title: String,
        text: Spanned,
        dismissCallback: () -> Unit
    ) {
        val binding = BottomTextDialogBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)

        dialog.setContentView(binding.root)

        binding.dialogTitle.text = title
        binding.dialogText.text = text
        // Phase 8.89: standard sheet close button (dismiss runs the
        // same dismissCallback as an outside tap / swipe).
        binding.closeBtt.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            dismissCallback.invoke()
        }

        dialog.show()
        applyResponsiveDialogSize(dialog)
    }
}

/**
 * ArrayAdapter that additionally draws a per-row start icon on the row's
 * text view (the row style already reserves drawablePadding for it).
 * Icons are tinted with the theme's iconColor; a null entry clears the icon
 * so recycled rows never show a stale glyph.
 */
private class IconArrayAdapter(
    context: Context,
    resource: Int,
    items: List<String>,
    private val icons: List<Int?>,
) : ArrayAdapter<String>(context, resource, items) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val textView = view.findViewById<TextView>(android.R.id.text1) ?: return view
        val iconRes = icons.getOrNull(position)
        if (iconRes == null) {
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            return view
        }
        val drawable = ContextCompat.getDrawable(context, iconRes)?.mutate() ?: return view
        DrawableCompat.setTint(drawable, context.getResourceColor(R.attr.iconColor))
        textView.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
        return view
    }
}
