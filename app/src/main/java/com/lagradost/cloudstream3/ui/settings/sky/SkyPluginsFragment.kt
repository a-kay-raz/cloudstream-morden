package com.lagradost.cloudstream3.ui.settings.sky

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.utils.SheetChrome
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.sky.SkyEngine
import com.lagradost.cloudstream3.sky.SkyPluginManifest
import com.lagradost.cloudstream3.sky.SkyProviders
import com.lagradost.cloudstream3.sky.SkyRepository
import com.lagradost.cloudstream3.sky.SkyRepoListing
import com.lagradost.cloudstream3.sky.SkySettingDef
import com.lagradost.cloudstream3.sky.StoredSkyPlugin
import com.lagradost.cloudstream3.sky.StoredSkyRepo
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings -> SkyStream Plugins: install SkyStream JS plugin
 * repositories (mega-repo -> repo -> plugin lists). Each installed
 * plugin becomes a full provider in the app — home sections, search,
 * details and streams — running on the same QuickJS engine as the
 * Nuvio bridge, with a persistent per-plugin context.
 */
class SkyPluginsFragment : Fragment() {

    // 8.81: two tabs — Installed (page 0) and Repositories (page 1,
    // selected by default). Page views are created by the pager.
    private lateinit var installedAdapter: SkyRepoAdapter
    private lateinit var reposAdapter: SkyRepoAdapter
    private var installedRecycler: RecyclerView? = null
    private var reposRecycler: RecyclerView? = null
    private var installedEmpty: TextView? = null
    private var reposEmpty: TextView? = null

    /** Chain expansions per stored repo URL, filled in asynchronously. */
    private val listings = mutableMapOf<String, List<SkyRepoListing>>()
    private val loadingRepos = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_sky_plugins, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Status-bar inset (same fix as the other bridge pages).
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
        view.findViewById<ImageButton>(R.id.sky_back).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.sky_add_button).setOnClickListener {
            showAddDialog()
        }
        installedAdapter = makeAdapter()
        reposAdapter = makeAdapter()
        val pager = view.findViewById<ViewPager2>(R.id.sky_pager)
        val tabs = view.findViewById<TabLayout>(R.id.sky_tabs)
        pager.offscreenPageLimit = 1
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount(): Int = 2

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RecyclerView.ViewHolder {
                val page = LayoutInflater.from(parent.context)
                    .inflate(R.layout.tab_page_list, parent, false)
                page.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                return object : RecyclerView.ViewHolder(page) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val list = holder.itemView.findViewById<RecyclerView>(R.id.tab_recycler)
                val empty = holder.itemView.findViewById<TextView>(R.id.tab_empty)
                list.layoutManager = LinearLayoutManager(holder.itemView.context)
                if (position == 0) {
                    installedRecycler = list
                    installedEmpty = empty
                    list.adapter = installedAdapter
                    empty.setText(R.string.sky_installed_empty)
                } else {
                    reposRecycler = list
                    reposEmpty = empty
                    list.adapter = reposAdapter
                    empty.setText(R.string.sky_plugins_empty)
                }
                refresh()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(
                if (position == 0) R.string.sky_tab_installed
                else R.string.sky_tab_repositories
            )
        }.attach()
        // Reference behaviour: the page opens on the browse tab.
        pager.setCurrentItem(1, false)
        SkyProviders.sync(requireContext())
        refresh()
        // 8.82: one-time migration — a mega-repo stored by 8.81 and
        // earlier as ONE entry becomes one entry per sub-repo; the
        // chains then load for the flattened list.
        lifecycleScope.launch {
            runCatching { SkyRepository.migrateMegaRepos(requireContext()) }
            if (!isAdded) return@launch
            refresh()
            fetchAllChains()
        }
    }

    private fun toast(message: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun makeAdapter(): SkyRepoAdapter = SkyRepoAdapter(
        onToggleCollapse = { repo ->
            val ctx = requireContext()
            val collapsed = SkyRepository.collapsedRepos(ctx).contains(repo.url)
            SkyRepository.setRepoCollapsed(ctx, repo.url, !collapsed)
            refresh()
        },
        onRefreshRepo = { repo -> refreshRepo(repo) },
        onRemoveRepo = { repo -> confirmRemoveRepo(repo) },
        onPluginAction = { entry, listing, state ->
            when (state) {
                SkyRow.PluginState.INSTALLED -> {
                    val installed = SkyRepository.installedByPackage(requireContext())
                    installed[entry.packageName]?.let { confirmRemovePlugin(it) }
                }
                else -> installPlugin(entry, listing.repoUrl)
            }
        },
        onToggleInstalled = { plugin, enabled ->
            val ctx = requireContext()
            SkyRepository.setPluginEnabled(ctx, plugin.packageName, enabled)
            if (!enabled) SkyEngine.evictPackage(plugin.packageName)
            SkyProviders.sync(ctx)
        },
        onRemoveInstalled = { plugin -> confirmRemovePlugin(plugin) },
        onInstalledSettings = { plugin -> openPluginSettings(plugin) },
    )

    private fun refresh() {
        if (!::installedAdapter.isInitialized) return
        val ctx = requireContext()
        val repos = SkyRepository.getRepos(ctx)
        val installed = SkyRepository.getInstalled(ctx)
        installedAdapter.submitInstalled(installed)
        reposAdapter.submitRepos(
            repos = repos,
            listings = listings,
            loading = loadingRepos,
            installed = installed,
            collapsed = SkyRepository.collapsedRepos(ctx),
        )
        installedEmpty?.visibility =
            if (installed.isEmpty()) View.VISIBLE else View.GONE
        reposEmpty?.visibility =
            if (repos.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- repository chain loading ----

    private fun fetchAllChains() {
        val ctx = requireContext()
        for (repo in SkyRepository.getRepos(ctx)) {
            fetchChain(repo)
        }
    }

    private fun fetchChain(repo: StoredSkyRepo) {
        val ctx = requireContext()
        if (!loadingRepos.add(repo.url)) return
        refresh()
        lifecycleScope.launch {
            val chain = runCatching { SkyRepository.fetchRepoChain(repo) }
                .getOrElse {
                    listOf(
                        SkyRepoListing(
                            repoUrl = repo.url,
                            repoName = repo.displayName(),
                            plugins = emptyList(),
                            error = "Could not load this repository",
                        )
                    )
                }
            loadingRepos.remove(repo.url)
            if (!isAdded) return@launch
            listings[repo.url] = chain
            val allFailed = chain.isNotEmpty() &&
                chain.all { it.plugins.isEmpty() && it.error != null }
            SkyRepository.setRepoError(
                ctx, repo.url, if (allFailed) chain.first().error else null
            )
            refresh()
        }
    }

    private fun refreshRepo(repo: StoredSkyRepo) {
        val ctx = requireContext()
        toast(R.string.sky_repo_checking)
        lifecycleScope.launch {
            val doc = SkyRepository.fetchRepoDoc(repo.url)
            if (!isAdded) return@launch
            var flattened = false
            if (doc != null) {
                if (doc.isMega) {
                    // A repo whose document turned into a mega-repo
                    // flattens into per-sub-repo entries (8.82).
                    SkyRepository.addRepoResolved(ctx, repo.url, repo.input, doc)
                    flattened = true
                } else {
                    SkyRepository.addRepo(ctx, repo.url, repo.input, doc)
                }
            }
            listings.remove(repo.url)
            refresh()
            if (flattened) {
                fetchAllChains()
            } else {
                fetchChain(
                    SkyRepository.getRepos(ctx)
                        .firstOrNull { it.url == repo.url } ?: repo
                )
            }
        }
    }

    // ---- add / remove repository ----

    private fun showAddDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.sky_repo_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            // 8.82: readable on the dark dialog surface.
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(158, 158, 158))
        }
        val container = FrameLayout(ctx).apply {
            // 8.89: dialog content padding standard is 24dp sides.
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.sky_add_repo)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isNotBlank()) resolveAndAdd(raw)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resolveAndAdd(raw: String) {
        val ctx = requireContext()
        toast(R.string.sky_repo_checking)
        lifecycleScope.launch {
            val resolved = SkyRepository.resolveRepoInput(raw)
            if (!isAdded) return@launch
            if (resolved == null) {
                toast(R.string.sky_repo_add_failed)
                return@launch
            }
            val (url, doc) = resolved
            // 8.82: a mega-repo flattens into one stored entry per
            // sub-repo; the mega URL itself is never stored.
            val addedCount = SkyRepository.addRepoResolved(ctx, url, raw, doc)
            toast(
                if (addedCount > 0) R.string.sky_repo_added
                else R.string.sky_repo_refreshed
            )
            listings.clear()
            refresh()
            fetchAllChains()
        }
    }

    private fun confirmRemoveRepo(repo: StoredSkyRepo) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.sky_repo_remove_title)
            .setMessage(getString(R.string.sky_repo_remove_message, repo.displayName()))
            .setPositiveButton(R.string.delete) { _, _ ->
                SkyRepository.removeRepo(ctx, repo.url)
                listings.remove(repo.url)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- install / remove plugin ----

    private fun installPlugin(entry: SkyPluginManifest, sourceRepoUrl: String) {
        val ctx = requireContext()
        val wasInstalled = SkyRepository.installedByPackage(ctx)
            .containsKey(entry.packageName)
        toast(R.string.sky_plugin_installing)
        lifecycleScope.launch {
            val record = runCatching {
                SkyRepository.installPlugin(ctx, entry, sourceRepoUrl)
            }.getOrNull()
            if (!isAdded) return@launch
            if (record == null) {
                toast(R.string.sky_plugin_install_failed)
                return@launch
            }
            // New code (and possibly a new base URL): drop any live
            // context so the next call rebuilds from the new files.
            SkyEngine.evictPackage(entry.packageName)
            SkyProviders.sync(ctx)
            toast(
                if (wasInstalled) R.string.sky_plugin_updated
                else R.string.sky_plugin_installed
            )
            refresh()
        }
    }

    private fun confirmRemovePlugin(plugin: StoredSkyPlugin) {
        val ctx = requireContext()
        val name = plugin.manifest()?.name ?: plugin.name
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.sky_plugin_remove_title)
            .setMessage(getString(R.string.sky_plugin_remove_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SkyRepository.removePlugin(ctx, plugin.packageName)
                SkyEngine.evictPackage(plugin.packageName)
                SkyProviders.sync(ctx)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- plugin settings form ----

    private fun openPluginSettings(plugin: StoredSkyPlugin) {
        val ctx = requireContext()
        val manifest = plugin.manifest()
        val fields = manifest?.settings.orEmpty()
            .filter { it.key.isNotBlank() }
        val saved = SkyRepository.pluginSettings(ctx, plugin.packageName)
        showSettingsForm(plugin, manifest, fields, saved)
    }

    private fun showSettingsForm(
        plugin: StoredSkyPlugin,
        manifest: SkyPluginManifest?,
        fields: List<SkySettingDef>,
        saved: JSONObject,
    ) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val getters = mutableListOf<() -> Unit>()
        val values = JSONObject()

        fun currentRaw(field: SkySettingDef): Any? {
            if (saved.has(field.key) && !saved.isNull(field.key)) {
                return saved.opt(field.key)
            }
            return field.defaultRaw
        }

        fun rawBool(raw: Any?): Boolean = when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> false
        }

        fun rawString(raw: Any?): String = when (raw) {
            null, JSONObject.NULL -> ""
            is String -> raw
            else -> raw.toString()
        }

        fun rawStringSet(raw: Any?): Set<String> = when (raw) {
            is JSONArray -> (0 until raw.length())
                .mapNotNull { raw.optString(it).takeIf { s -> s.isNotBlank() } }
                .toSet()
            is String -> raw.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            else -> emptySet()
        }

        // 8.82: the sheet opens with the Base URL field (the order
        // the reference uses), whether or not the schema declares
        // one. It writes through the schema's base-url key when
        // there is one, else the "base_url" override key — exactly
        // what SkyRepository.effectiveBaseUrl reads. Batch 1: a
        // blank value, or the manifest default re-entered, writes
        // JSON null, which savePluginSettings interprets as "remove
        // this key" — so clearing the field still drops an old
        // override, while keys the sheet never rendered survive
        // the save (merge, not replace).
        if (manifest != null) {
            val baseKey = fields.firstOrNull { it.isBaseUrl }?.key ?: "base_url"
            column.addView(TextView(ctx).apply {
                text = getString(R.string.sky_base_url_label)
                setPadding(0, dp(10), 0, 0)
            })
            val baseEdit = EditText(ctx).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI
                setText(SkyRepository.effectiveBaseUrl(ctx, manifest))
                hint = getString(R.string.sky_base_url_hint, manifest.baseUrl)
            }
            column.addView(baseEdit)
            getters += {
                val value = baseEdit.text?.toString()?.trim().orEmpty()
                if (value.isNotBlank() && value != manifest.baseUrl) {
                    values.put(baseKey, value)
                } else {
                    values.put(baseKey, JSONObject.NULL)
                }
            }
        }

        for (field in fields) {
            // The base-url field is the dedicated section above.
            if (field.isBaseUrl) continue
            val label = field.title.ifBlank { field.key }
            when (field.type) {
                "toggle" -> {
                    val switch = MaterialSwitch(ctx).apply {
                        text = label
                        isChecked = rawBool(currentRaw(field))
                        setPadding(0, dp(8), 0, dp(8))
                    }
                    column.addView(switch)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    getters += { values.put(key, switch.isChecked) }
                }
                "select" -> {
                    if (field.options.isEmpty()) continue
                    column.addView(TextView(ctx).apply {
                        text = label
                        setPadding(0, dp(10), 0, 0)
                    })
                    val raw = currentRaw(field)?.toString()
                    val spinner = Spinner(ctx).apply {
                        adapter = ArrayAdapter(
                            ctx,
                            android.R.layout.simple_spinner_dropdown_item,
                            field.options.map { it.label }
                        )
                        val index = field.options.indexOfFirst { it.value == raw }
                        if (index >= 0) setSelection(index)
                    }
                    column.addView(spinner)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    val options = field.options
                    getters += {
                        val index = spinner.selectedItemPosition
                        if (index in options.indices) {
                            values.put(key, options[index].value)
                        }
                    }
                }
                "toggleGroup" -> {
                    column.addView(TextView(ctx).apply {
                        text = label
                        setPadding(0, dp(10), 0, 0)
                    })
                    val selected = rawStringSet(currentRaw(field)).toMutableSet()
                    val switches = mutableListOf<Pair<String, MaterialSwitch>>()
                    for (option in field.options) {
                        val switch = MaterialSwitch(ctx).apply {
                            text = option.label.ifBlank { option.value }
                            isChecked = selected.contains(option.value)
                            setPadding(dp(12), dp(4), 0, dp(4))
                        }
                        column.addView(switch)
                        switches += option.value to switch
                    }
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    getters += {
                        val arr = JSONArray()
                        switches.forEach { (value, switch) ->
                            if (switch.isChecked) arr.put(value)
                        }
                        values.put(key, arr)
                    }
                }
                else -> { // text / url
                    column.addView(TextView(ctx).apply {
                        text = label
                        setPadding(0, dp(10), 0, 0)
                    })
                    val edit = EditText(ctx).apply {
                        setSingleLine(true)
                        inputType = if (field.type == "url") {
                            InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_URI
                        } else {
                            InputType.TYPE_CLASS_TEXT
                        }
                        setText(rawString(currentRaw(field)))
                    }
                    column.addView(edit)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    getters += {
                        values.put(key, edit.text?.toString()?.trim().orEmpty())
                    }
                }
            }
        }

        // 8.82: sub-provider switches (SkyStream parity) — plugins
        // like Netmirror declare providers[] but no settings schema,
        // and the reference still gives each sub-provider a switch
        // in this sheet. Off ids persist under the reserved settings
        // key; SkyProviders skips them at registration, and saving
        // re-syncs (see the save handler below).
        val subProviders = manifest?.subProviders.orEmpty()
        if (subProviders.isNotEmpty()) {
            val disabledSubs = SkyRepository.disabledSubProviders(
                ctx, plugin.packageName
            )
            column.addView(TextView(ctx).apply {
                text = getString(R.string.sky_settings_providers)
                textSize = 16f
                setPadding(0, dp(16), 0, 0)
            })
            val subSwitches = mutableListOf<Pair<String, MaterialSwitch>>()
            for (sub in subProviders) {
                val switch = MaterialSwitch(ctx).apply {
                    text = sub.name.ifBlank { sub.id }
                    isChecked = sub.id !in disabledSubs
                    setPadding(0, dp(8), 0, dp(8))
                }
                column.addView(switch)
                subSwitches += sub.id to switch
            }
            getters += {
                val arr = JSONArray()
                subSwitches.forEach { (id, switch) ->
                    if (!switch.isChecked) arr.put(id)
                }
                values.put(SkyRepository.DISABLED_PROVIDERS_KEY, arr)
            }
        }
        if (column.childCount == 0) {
            column.addView(TextView(ctx).apply {
                text = getString(R.string.nuvio_settings_no_fields)
                setPadding(0, dp(10), 0, 0)
            })
        }

        // 8.82: the sheet is a dark surface with readable text —
        // force the colours on the whole form (labels, descriptions,
        // switches, inputs) instead of inheriting whatever the
        // active theme variant gives programmatic views.
        fun styleFormText(view: View) {
            when (view) {
                is EditText -> {
                    view.setTextColor(Color.WHITE)
                    view.setHintTextColor(Color.rgb(158, 158, 158))
                }
                is TextView -> view.setTextColor(Color.WHITE)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    styleFormText(view.getChildAt(i))
                }
            }
        }
        styleFormText(column)

        // 8.81: settings open as a bottom sheet (SkyStream parity)
        // instead of an AlertDialog. 8.82: the app's own bottom-sheet
        // theme plus the app modal surface (rounded_dialog) on the
        // sheet root, so no light frame can show through.
        // 8.89: standard sheet chrome (SheetChrome) — drag handle +
        // title row with the circular close button; the form column
        // keeps carrying the 20dp side padding.
        val scroll = ScrollView(ctx).apply { addView(column) }
        val (skyTitleRow, skyCloseButton) = SheetChrome.titleRow(
            ctx, plugin.manifest()?.name ?: plugin.name
        ).apply {
            first.setPadding(dp(20), 0, dp(20), 0)
        }
        val sheetRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.rounded_dialog)
            setPadding(0, dp(8), 0, 0)
            addView(SheetChrome.dragHandle(ctx))
            addView(skyTitleRow)
            addView(scroll)
        }
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val chipTint = ColorStateList.valueOf(Color.rgb(0x26, 0x26, 0x26))
        val cancelButton = MaterialButton(ctx).apply {
            setText(android.R.string.cancel)
            backgroundTintList = chipTint
            setTextColor(Color.WHITE)
            cornerRadius = dp(20)
        }
        // 8.89: Save is the primary action — standard red pill.
        // Batch 1: with no manifest there is no schema to save
        // against, so Save is disabled rather than writing (and,
        // before the merge fix, wiping) settings blind.
        val saveButton = MaterialButton(ctx).apply {
            isEnabled = manifest != null
            setText(R.string.nuvio_settings_save)
            backgroundTintList = ContextCompat.getColorStateList(
                ctx, R.color.colorPrimaryRed
            )
            setTextColor(Color.WHITE)
            cornerRadius = dp(20)
        }
        buttonRow.addView(cancelButton)
        buttonRow.addView(saveButton)
        sheetRoot.addView(buttonRow)
        val dialog = BottomSheetDialog(ctx, R.style.AppBottomSheetDialogTheme)
        dialog.setContentView(sheetRoot)
        skyCloseButton.setOnClickListener { dialog.dismiss() }
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            getters.forEach { it() }
            SkyRepository.savePluginSettings(ctx, plugin.packageName, values)
            // Settings feed manifest.baseUrl and getPreference:
            // rebuild the plugin's context so they take effect, and
            // re-sync registrations so sub-provider switches (8.82)
            // apply immediately.
            SkyEngine.evictPackage(plugin.packageName)
            SkyProviders.sync(ctx)
            toast(R.string.sky_settings_saved)
            dialog.dismiss()
        }
        dialog.show()
        // The dialog's own sheet frame stays transparent — the
        // sheet root's rounded_dialog background is the only surface.
        SheetChrome.transparentSheetFrame(dialog)
    }
}

// ---- list (sections + repo headers + plugin rows + installed rows) ----

private sealed interface SkyRow {
    enum class PluginState { NOT_INSTALLED, INSTALLED, UPDATE }

    data class Section(val titleRes: Int) : SkyRow
    data class Info(val text: String) : SkyRow
    data class RepoHeader(
        val repo: StoredSkyRepo,
        val name: String,
        val collapsed: Boolean,
        val error: String?,
    ) : SkyRow
    data class ListingHeader(val name: String) : SkyRow
    data class PluginRow(
        val entry: SkyPluginManifest,
        val listing: SkyRepoListing,
        val state: PluginState,
    ) : SkyRow
    data class InstalledRow(val plugin: StoredSkyPlugin) : SkyRow
}

private class SkyRepoAdapter(
    private val onToggleCollapse: (StoredSkyRepo) -> Unit,
    private val onRefreshRepo: (StoredSkyRepo) -> Unit,
    private val onRemoveRepo: (StoredSkyRepo) -> Unit,
    private val onPluginAction: (SkyPluginManifest, SkyRepoListing, SkyRow.PluginState) -> Unit,
    private val onToggleInstalled: (StoredSkyPlugin, Boolean) -> Unit,
    private val onRemoveInstalled: (StoredSkyPlugin) -> Unit,
    private val onInstalledSettings: (StoredSkyPlugin) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<SkyRow> = emptyList()

    /** Installed tab: installed plugins only (8.81). */
    fun submitInstalled(installed: List<StoredSkyPlugin>) {
        rows = installed.sortedBy { it.name.lowercase() }
            .map { SkyRow.InstalledRow(it) }
        notifyDataSetChanged()
    }

    /** Repositories tab: repos + their browsable plugin lists (8.81). */
    fun submitRepos(
        repos: List<StoredSkyRepo>,
        listings: Map<String, List<SkyRepoListing>>,
        loading: Set<String>,
        installed: List<StoredSkyPlugin>,
        collapsed: Set<String>,
    ) {
        val out = mutableListOf<SkyRow>()
        val installedByPackage = installed.associateBy { it.packageName }
        if (repos.isNotEmpty()) {
            for (repo in repos) {
                val chain = listings[repo.url]
                val isCollapsed = collapsed.contains(repo.url)
                val chainError = chain
                    ?.firstOrNull { it.error != null && it.plugins.isEmpty() }
                    ?.error
                out += SkyRow.RepoHeader(
                    repo = repo,
                    name = repo.doc()?.name
                        ?: chain?.firstOrNull()?.repoName
                        ?: repo.url,
                    collapsed = isCollapsed,
                    error = repo.lastError ?: chainError,
                )
                if (isCollapsed) continue
                when {
                    chain == null -> {
                        out += SkyRow.Info(
                            if (repo.url in loading) "…" else ""
                        )
                    }
                    chain.size == 1 -> {
                        val listing = chain[0]
                        listing.error?.let { out += SkyRow.Info(it) }
                        listing.plugins.forEach { entry ->
                            out += pluginRow(entry, listing, installedByPackage)
                        }
                    }
                    else -> {
                        for (listing in chain) {
                            out += SkyRow.ListingHeader(listing.repoName)
                            listing.error?.let { out += SkyRow.Info(it) }
                            listing.plugins.forEach { entry ->
                                out += pluginRow(entry, listing, installedByPackage)
                            }
                        }
                    }
                }
            }
        }
        rows = out
        notifyDataSetChanged()
    }

    /**
     * 8.81: plugin icon with the SkyStream-style puzzle placeholder —
     * the manifest/listing icon when there is one, the placeholder
     * vector otherwise (the row tile is bg_sky_icon in the layouts).
     */
    private fun bindSkyIcon(view: ImageView, iconUrl: String?, ctx: android.content.Context) {
        if (!iconUrl.isNullOrBlank()) {
            view.loadImage(iconUrl) {
                error(
                    getImageFromDrawable(
                        ctx, R.drawable.ic_sky_plugin_placeholder
                    )
                )
            }
        } else {
            view.setImageResource(R.drawable.ic_sky_plugin_placeholder)
        }
    }

    private fun pluginRow(
        entry: SkyPluginManifest,
        listing: SkyRepoListing,
        installedByPackage: Map<String, StoredSkyPlugin>,
    ): SkyRow.PluginRow {
        val installed = installedByPackage[entry.packageName]
        val state = when {
            installed == null -> SkyRow.PluginState.NOT_INSTALLED
            SkyRepository.updateAvailable(installed, entry) ->
                SkyRow.PluginState.UPDATE
            else -> SkyRow.PluginState.INSTALLED
        }
        return SkyRow.PluginRow(entry, listing, state)
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is SkyRow.Section, is SkyRow.Info, is SkyRow.ListingHeader -> TYPE_TEXT
        is SkyRow.RepoHeader -> TYPE_REPO
        is SkyRow.PluginRow -> TYPE_PLUGIN
        is SkyRow.InstalledRow -> TYPE_INSTALLED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_REPO -> RepoHolder(
                inflater.inflate(R.layout.item_sky_repo, parent, false)
            )
            TYPE_PLUGIN -> PluginHolder(
                inflater.inflate(R.layout.item_sky_plugin, parent, false)
            )
            TYPE_INSTALLED -> InstalledHolder(
                inflater.inflate(R.layout.item_sky_installed, parent, false)
            )
            else -> TextHolder(
                inflater.inflate(R.layout.item_sky_section, parent, false)
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SkyRow.Section -> {
                (holder as TextHolder).text.setText(row.titleRes)
            }
            is SkyRow.Info -> {
                (holder as TextHolder).text.text = row.text
            }
            is SkyRow.ListingHeader -> {
                (holder as TextHolder).text.text = row.name
            }
            is SkyRow.RepoHeader -> {
                val h = holder as RepoHolder
                h.name.text = row.name
                h.url.text = row.repo.url
                if (row.error.isNullOrBlank()) {
                    h.error.visibility = View.GONE
                } else {
                    h.error.visibility = View.VISIBLE
                    h.error.text = row.error
                }
                val targetRotation = if (row.collapsed) -90f else 0f
                if (h.expand.rotation != targetRotation) {
                    h.expand.animate().rotation(targetRotation)
                        .setDuration(250).start()
                }
                h.itemView.setOnClickListener { onToggleCollapse(row.repo) }
                h.refresh.setOnClickListener { onRefreshRepo(row.repo) }
                h.remove.setOnClickListener { onRemoveRepo(row.repo) }
            }
            is SkyRow.PluginRow -> {
                val h = holder as PluginHolder
                val entry = row.entry
                val ctx = h.itemView.context
                h.name.text = entry.name
                bindSkyIcon(h.icon, entry.iconUrl, ctx)
                val version = ctx.getString(R.string.sky_version_format, entry.version)
                h.desc.text = listOf(entry.description, version)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ") +
                    if (entry.categories.isNotEmpty()) {
                        "\n" + entry.categories.joinToString(", ")
                    } else {
                        ""
                    }
                // 8.82: icon action (SkyStream-style) — download to
                // install, update glyph when a newer version is
                // listed, a subtle disabled check when the plugin is
                // already installed (removal lives in the Installed
                // tab). Install/update taps behave exactly as the
                // old text button did.
                when (row.state) {
                    SkyRow.PluginState.NOT_INSTALLED -> {
                        h.action.setImageResource(R.drawable.ic_sheet_download)
                        h.action.contentDescription =
                            ctx.getString(R.string.sky_plugin_install)
                        h.action.isEnabled = true
                        h.action.alpha = 1f
                        h.action.setOnClickListener {
                            onPluginAction(row.entry, row.listing, row.state)
                        }
                    }
                    SkyRow.PluginState.UPDATE -> {
                        h.action.setImageResource(
                            R.drawable.ic_baseline_system_update_24
                        )
                        h.action.contentDescription =
                            ctx.getString(R.string.sky_plugin_update)
                        h.action.isEnabled = true
                        h.action.alpha = 1f
                        h.action.setOnClickListener {
                            onPluginAction(row.entry, row.listing, row.state)
                        }
                    }
                    SkyRow.PluginState.INSTALLED -> {
                        h.action.setImageResource(R.drawable.ic_baseline_check_24)
                        h.action.contentDescription =
                            ctx.getString(R.string.sky_plugin_state_installed)
                        h.action.isEnabled = false
                        h.action.alpha = 0.55f
                        h.action.setOnClickListener(null)
                    }
                }
            }
            is SkyRow.InstalledRow -> {
                val h = holder as InstalledHolder
                val plugin = row.plugin
                val ctx = h.itemView.context
                val manifest = plugin.manifest()
                h.name.text = manifest?.name ?: plugin.name
                h.desc.text = manifest?.description ?: plugin.sourceRepoUrl
                // 8.81 meta line: "v9 • By IGRIS • 3 providers".
                val metaParts = mutableListOf(
                    ctx.getString(R.string.sky_version_format, plugin.version)
                )
                val authors = manifest?.authors.orEmpty()
                    .filter { it.isNotBlank() }
                if (authors.isNotEmpty()) {
                    metaParts += ctx.getString(
                        R.string.sky_installed_by_format,
                        authors.joinToString(", "),
                    )
                }
                val providerCount = manifest?.subProviders?.size ?: 0
                if (providerCount > 0) {
                    metaParts += ctx.getString(
                        R.string.sky_installed_providers_format, providerCount
                    )
                }
                h.meta.text = metaParts.joinToString(" • ")
                bindSkyIcon(h.icon, manifest?.iconUrl, ctx)
                // 8.81: language chips under the meta line.
                val languages = manifest?.languages.orEmpty()
                    .filter { it.isNotBlank() }
                h.langs.removeAllViews()
                if (languages.isEmpty()) {
                    h.langs.visibility = View.GONE
                } else {
                    h.langs.visibility = View.VISIBLE
                    val density = ctx.resources.displayMetrics.density
                    fun chipDp(value: Int) = (value * density).toInt()
                    for (language in languages) {
                        val chip = TextView(ctx).apply {
                            text = language.uppercase()
                            textSize = 10f
                            setBackgroundResource(R.drawable.bg_sky_chip)
                            setPadding(chipDp(8), chipDp(3), chipDp(8), chipDp(3))
                        }
                        val chipParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        chipParams.marginEnd = chipDp(6)
                        h.langs.addView(chip, chipParams)
                    }
                }
                // 8.81: the settings sheet always opens (it carries
                // the Base URL field even without a settings schema).
                h.settings.visibility = View.VISIBLE
                h.settings.setOnClickListener { onInstalledSettings(plugin) }
                h.enabled.setOnCheckedChangeListener(null)
                h.enabled.isChecked = plugin.enabled
                h.enabled.setOnCheckedChangeListener { _, checked ->
                    onToggleInstalled(plugin, checked)
                }
                h.remove.setOnClickListener { onRemoveInstalled(plugin) }
            }
        }
    }

    private class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.item_sky_section_title)
    }

    private class RepoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val expand: ImageView = view.findViewById(R.id.item_sky_repo_expand)
        val name: TextView = view.findViewById(R.id.item_sky_repo_name)
        val url: TextView = view.findViewById(R.id.item_sky_repo_url)
        val error: TextView = view.findViewById(R.id.item_sky_repo_error)
        val refresh: ImageButton = view.findViewById(R.id.item_sky_repo_refresh)
        val remove: ImageButton = view.findViewById(R.id.item_sky_repo_remove)
    }

    private class PluginHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.item_sky_plugin_icon)
        val name: TextView = view.findViewById(R.id.item_sky_plugin_name)
        val desc: TextView = view.findViewById(R.id.item_sky_plugin_desc)
        val action: ImageButton = view.findViewById(R.id.item_sky_plugin_action)
    }

    private class InstalledHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.item_sky_inst_icon)
        val name: TextView = view.findViewById(R.id.item_sky_inst_name)
        val desc: TextView = view.findViewById(R.id.item_sky_inst_desc)
        val meta: TextView = view.findViewById(R.id.item_sky_inst_meta)
        val langs: LinearLayout = view.findViewById(R.id.item_sky_inst_langs)
        val settings: ImageButton = view.findViewById(R.id.item_sky_inst_settings)
        val enabled: MaterialSwitch = view.findViewById(R.id.item_sky_inst_enabled)
        val remove: ImageButton = view.findViewById(R.id.item_sky_inst_remove)
    }

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_REPO = 1
        private const val TYPE_PLUGIN = 2
        private const val TYPE_INSTALLED = 3
    }
}
