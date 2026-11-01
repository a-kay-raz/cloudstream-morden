package com.lagradost.cloudstream3.ui.settings.stremio

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.stremio.AddonRepository
import com.lagradost.cloudstream3.stremio.StoredAddon
import com.lagradost.cloudstream3.stremio.StremioAddonProvider
import com.lagradost.cloudstream3.stremio.StremioClient
import com.lagradost.cloudstream3.stremio.StremioParse
import com.lagradost.cloudstream3.stremio.StremioProviders
import com.lagradost.cloudstream3.stremio.StremioTransport
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomPadding
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.launch

/**
 * Settings -> Stremio Addons: the user's own addon manager. Paste a
 * manifest link to install; each installed addon then behaves like a
 * provider in the app (Home rows, Search, details, streams).
 */
class StremioAddonsFragment : Fragment() {

    private lateinit var adapter: StremioAddonAdapter
    // 8.81: two tabs — Installed (page 0) and Discover (page 1,
    // selected by default, as the user praised in the reference).
    private var installedEmpty: TextView? = null
    private var quickInstallButton: MaterialButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_stremio_addons, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Status-bar inset (same fix as HomeCustomListsFragment 8.47):
        // plain fragments get no automatic inset handling, so the
        // header would slide under the status bar.
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
        view.findViewById<ImageButton>(R.id.stremio_back).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.stremio_add_button).setOnClickListener {
            showAddDialog()
        }
        adapter = StremioAddonAdapter(
            onToggle = { addon, enabled ->
                AddonRepository.setEnabled(requireContext(), addon.manifestUrl, enabled)
                StremioProviders.sync(requireContext())
                refresh()
            },
            onRemove = { addon -> confirmRemove(addon) },
        )
        val pager = view.findViewById<ViewPager2>(R.id.stremio_pager)
        val tabs = view.findViewById<TabLayout>(R.id.stremio_tabs)
        pager.offscreenPageLimit = 1
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

            override fun getItemViewType(position: Int): Int = position

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RecyclerView.ViewHolder {
                val layout = if (viewType == 0) {
                    R.layout.tab_page_list
                } else {
                    R.layout.fragment_stremio_discover
                }
                val page = LayoutInflater.from(parent.context)
                    .inflate(layout, parent, false)
                page.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (position == 0) {
                    val list = holder.itemView.findViewById<RecyclerView>(R.id.tab_recycler)
                    val empty = holder.itemView.findViewById<TextView>(R.id.tab_empty)
                    list.layoutManager = LinearLayoutManager(holder.itemView.context)
                    // Re-audit: fixed 92dp in tab_page_list was short
                    // by systemBottom on 3-button nav; use the runtime
                    // formula (see NuvioPluginsFragment for the note).
                    list.refreshFloatingNavBottomPadding()
                    list.adapter = adapter
                    empty.setText(R.string.stremio_addons_empty)
                    installedEmpty = empty
                } else {
                    holder.itemView.findViewById<MaterialButton>(
                        R.id.stremio_discover_add_button
                    ).setOnClickListener { showAddDialog() }
                    quickInstallButton = holder.itemView.findViewById(
                        R.id.stremio_quick_install_button
                    )
                }
                refresh()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(
                if (position == 0) R.string.stremio_tab_installed
                else R.string.stremio_tab_discover
            )
        }.attach()
        // The reference opens on Discover; the user praised that.
        pager.setCurrentItem(1, false)
        // Reconcile registrations on open (covers Family Mode toggles
        // made since the last sync).
        StremioProviders.sync(requireContext())
        refresh()
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val addons = AddonRepository.getAddons(requireContext())
        adapter.submit(addons)
        // 8.82: the empty text shows ONLY when the list is truly
        // empty. The 8.81 rule (visible while only the built-in row
        // exists) overlaid the text on top of the Cinemeta row —
        // the overlap the user reported on this page.
        installedEmpty?.visibility =
            if (addons.isEmpty()) View.VISIBLE else View.GONE
        updateQuickInstall(addons)
    }

    /** Discover tab: the Cinemeta quick-install card's state. */
    private fun updateQuickInstall(addons: List<StoredAddon>) {
        val button = quickInstallButton ?: return
        val cinemeta = addons.firstOrNull {
            it.manifestUrl == AddonRepository.CINEMETA_URL
        }
        when {
            cinemeta == null -> {
                button.setText(R.string.stremio_quick_install)
                button.isEnabled = true
                button.setOnClickListener {
                    installAddon(AddonRepository.CINEMETA_URL)
                }
            }
            !cinemeta.enabled -> {
                button.setText(R.string.stremio_quick_enable)
                button.isEnabled = true
                button.setOnClickListener {
                    AddonRepository.setEnabled(
                        requireContext(), AddonRepository.CINEMETA_URL, true
                    )
                    StremioProviders.sync(requireContext())
                    refresh()
                }
            }
            else -> {
                button.setText(R.string.stremio_quick_installed)
                button.isEnabled = false
                button.setOnClickListener(null)
            }
        }
    }

    private fun confirmRemove(addon: StoredAddon) {
        val name = addon.manifest()?.name
            ?: StremioAddonProvider.hostLabel(addon.manifestUrl)
        AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.stremio_addon_remove_title)
            .setMessage(getString(R.string.stremio_addon_remove_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                AddonRepository.remove(requireContext(), addon.manifestUrl)
                StremioProviders.sync(requireContext())
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.stremio_addon_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            // 8.82: readable on the dark dialog surface.
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(158, 158, 158))
        }
        val container = FrameLayout(context).apply {
            // 8.89: dialog content padding standard is 24dp sides.
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(R.string.stremio_add_addon)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isNotBlank()) installAddon(raw)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun installAddon(rawUrl: String) {
        val context = requireContext()
        Toast.makeText(context, R.string.stremio_addon_checking, Toast.LENGTH_SHORT)
            .show()
        viewLifecycleOwner.lifecycleScope.launch {
            var manifestUrl = StremioTransport.normalizeManifestUrl(rawUrl)
            var json = StremioClient.fetchJson(manifestUrl, ttlMs = 0)
            var manifest = json?.let {
                runCatching { StremioParse.parseManifest(it) }.getOrNull()
            }
            if (manifest == null || manifest.name.isBlank()) {
                // The pasted link may be a listing/info page (e.g. an
                // addons catalog site) that links to the real manifest
                // — dig the manifest URL out of the page and retry once.
                val found = StremioClient.findManifestUrlOnPage(manifestUrl)
                if (found != null && found != manifestUrl) {
                    val retryJson = StremioClient.fetchJson(found, ttlMs = 0)
                    val retryManifest = retryJson?.let {
                        runCatching { StremioParse.parseManifest(it) }.getOrNull()
                    }
                    if (retryJson != null && retryManifest != null &&
                        retryManifest.name.isNotBlank()
                    ) {
                        manifestUrl = found
                        json = retryJson
                        manifest = retryManifest
                    }
                }
            }
            if (json == null || manifest == null || manifest.name.isBlank()) {
                Toast.makeText(
                    context, R.string.stremio_addon_add_failed, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val added = AddonRepository.add(
                context, manifestUrl, manifest, json.toString()
            )
            StremioProviders.sync(context)
            refresh()
            Toast.makeText(
                context,
                if (added) R.string.stremio_addon_added
                else R.string.stremio_addon_already_added,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

class StremioAddonAdapter(
    private val onToggle: (StoredAddon, Boolean) -> Unit,
    private val onRemove: (StoredAddon) -> Unit,
) : RecyclerView.Adapter<StremioAddonAdapter.ViewHolder>() {

    private var items: List<StoredAddon> = emptyList()

    fun submit(list: List<StoredAddon>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stremio_addon, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.item_addon_logo)
        private val nameView: TextView = view.findViewById(R.id.item_addon_name)
        private val descView: TextView = view.findViewById(R.id.item_addon_desc)
        private val errorView: TextView = view.findViewById(R.id.item_addon_error)
        private val enabledSwitch: SwitchCompat =
            view.findViewById(R.id.item_addon_enabled)
        private val removeButton: ImageButton =
            view.findViewById(R.id.item_addon_remove)

        fun bind(addon: StoredAddon) {
            val manifest = addon.manifest()
            val context = itemView.context
            nameView.text = manifest?.name
                ?: if (addon.builtIn) "Cinemeta"
                else StremioAddonProvider.hostLabel(addon.manifestUrl)
            val desc = manifest?.description ?: addon.manifestUrl
            descView.text = if (addon.builtIn) {
                context.getString(R.string.stremio_addon_built_in) + " • " + desc
            } else {
                desc
            }
            val logoUrl = manifest?.logo
            if (logoUrl.isNullOrBlank()) {
                logo.setImageDrawable(null)
                logo.visibility = View.GONE
            } else {
                logo.visibility = View.VISIBLE
                logo.loadImage(logoUrl) {
                    transformations(CircleCropTransformation())
                    error(
                        com.lagradost.cloudstream3.utils.getImageFromDrawable(
                            context, R.drawable.ic_baseline_extension_24
                        )
                    )
                }
            }
            if (addon.lastError.isNullOrBlank()) {
                errorView.visibility = View.GONE
            } else {
                errorView.visibility = View.VISIBLE
                errorView.text = addon.lastError
            }
            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = addon.enabled
            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                onToggle(addon, checked)
            }
            removeButton.visibility = if (addon.builtIn) View.GONE else View.VISIBLE
            removeButton.setOnClickListener { onRemove(addon) }
        }
    }
}
