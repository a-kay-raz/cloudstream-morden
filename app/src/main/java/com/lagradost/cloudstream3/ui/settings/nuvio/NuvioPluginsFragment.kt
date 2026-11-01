package com.lagradost.cloudstream3.ui.settings.nuvio

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
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.utils.SheetChrome
import com.lagradost.cloudstream3.utils.UIHelper.refreshFloatingNavBottomPadding
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.nuvio.NuvioEngine
import com.lagradost.cloudstream3.nuvio.NuvioDiagnostics
import com.lagradost.cloudstream3.nuvio.NuvioValidity
import com.lagradost.cloudstream3.nuvio.NuvioParse
import com.lagradost.cloudstream3.nuvio.NuvioProviders
import com.lagradost.cloudstream3.nuvio.NuvioRepository
import com.lagradost.cloudstream3.nuvio.NuvioScraperInfo
import com.lagradost.cloudstream3.nuvio.NuvioSettingsField
import com.lagradost.cloudstream3.nuvio.NuvioTmdb
import com.lagradost.cloudstream3.nuvio.StoredNuvioRepo
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Settings -> Nuvio Plugins: install JS scraper repositories. Each
 * enabled scraper adds streams to Stremio addon titles (bridge
 * step 2), resolved through the TMDB id of the title.
 */
class NuvioPluginsFragment : Fragment() {

    // 8.81: two tabs — Installed (page 0: master card + scraper
    // groups) and Repositories (page 1, selected by default).
    private lateinit var adapter: NuvioRepoAdapter
    private lateinit var repoListAdapter: NuvioRepoListAdapter
    private var installedEmpty: TextView? = null
    private var reposEmpty: TextView? = null
    private lateinit var diagView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.fragment_nuvio_plugins, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Status-bar inset (same fix as HomeCustomListsFragment 8.47).
        val baseTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        view.requestApplyInsets()
        view.findViewById<ImageButton>(R.id.nuvio_back).setOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<MaterialButton>(R.id.nuvio_add_button).setOnClickListener {
            showAddDialog()
        }
        diagView = view.findViewById(R.id.nuvio_diagnostics)
        adapter = NuvioRepoAdapter(
            onToggle = { repo, scraper, enabled ->
                NuvioRepository.setScraperEnabled(
                    requireContext(), repo.manifestUrl, scraper, enabled
                )
                refresh()
            },
            onRefreshRepo = { repo -> fetchAndStoreRepo(repo.manifestUrl) },
            onRemoveRepo = { repo -> confirmRemove(repo) },
            onSettings = { repo, scraper -> openScraperSettings(repo, scraper) },
            onCheckRepo = { repo -> checkRepo(repo) },
            onToggleCollapse = { repo ->
                val ctx = requireContext()
                val collapsed = NuvioRepository.collapsedRepos(ctx)
                    .contains(repo.manifestUrl)
                NuvioRepository.setRepoCollapsed(ctx, repo.manifestUrl, !collapsed)
                // Phase 8.77: animated single-group toggle; the
                // full refresh() stays for data-changing actions.
                adapter.toggleRepo(
                    repo,
                    NuvioRepository.getRepos(ctx),
                    NuvioRepository.collapsedRepos(ctx),
                )
            },
        )
        repoListAdapter = NuvioRepoListAdapter(
            onRefreshRepo = { repo -> fetchAndStoreRepo(repo.manifestUrl) },
            onRemoveRepo = { repo -> confirmRemove(repo) },
            onCheckRepo = { repo -> checkRepo(repo) },
        )
        val pager = view.findViewById<ViewPager2>(R.id.nuvio_pager)
        val tabs = view.findViewById<TabLayout>(R.id.nuvio_tabs)
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
                // Re-audit: fixed 92dp in tab_page_list was short
                // by systemBottom on 3-button nav; use the runtime
                // formula (refresh = correct at creation, and it
                // re-evaluates if the page is rebound).
                list.refreshFloatingNavBottomPadding()
                if (position == 0) {
                    installedEmpty = empty
                    list.adapter = adapter
                    empty.setText(R.string.nuvio_plugins_empty)
                    // Phase 8.77: SkyStream's AnimatedSize runs 300ms;
                    // the RecyclerView insert/remove animations
                    // approximate it (Installed tab groups only).
                    (list.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)
                        ?.apply {
                            addDuration = 260
                            removeDuration = 200
                            moveDuration = 260
                            changeDuration = 200
                        }
                } else {
                    reposEmpty = empty
                    list.adapter = repoListAdapter
                    empty.setText(R.string.nuvio_repos_empty)
                }
                refresh()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(
                if (position == 0) R.string.nuvio_tab_installed
                else R.string.nuvio_tab_repositories
            )
        }.attach()
        // Reference behaviour: the page opens on the browse tab.
        pager.setCurrentItem(1, false)
        refresh()
    }

    /**
     * Validity check for one repository (8.68): downloads each scraper's
     * code and load-tests it in the engine (no stream fetch — just "does
     * this code load and export getStreams"). Results land in NuvioValidity
     * and show on the scraper rows, so broken scrapers (syntax errors,
     * missing exports, dead code URLs) are visible before pressing Play.
     */
    private fun checkRepo(repo: StoredNuvioRepo) {
        val manifest = repo.manifest() ?: return
        val ctx = requireContext()
        val progress = TextView(ctx).apply {
            setPadding(48, 24, 48, 8)
            text = "…"
        }
        var cancelled = false
        val dialog = AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.nuvio_check_title)
            .setView(progress)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .create()
        dialog.show()
        lifecycleScope.launch {
            var ok = 0
            var bad = 0
            val scrapers = manifest.scrapers
            for ((index, scraper) in scrapers.withIndex()) {
                if (cancelled) break
                progress.text = "${index + 1} / ${scrapers.size} · ${scraper.name}"
                val code = NuvioRepository.fetchCode(ctx, repo, scraper)
                if (code == null) {
                    NuvioValidity.setProbe(
                        ctx, repo.manifestUrl, scraper.id, false, "code download failed"
                    )
                    bad++
                    continue
                }
                val result = NuvioEngine.probe(
                    ctx, repo.manifestUrl, scraper, code,
                    NuvioRepository.scraperSettings(ctx, repo.manifestUrl, scraper.id),
                    NuvioTmdb.apiKey(ctx),
                )
                when {
                    result.has("error") -> {
                        NuvioValidity.setProbe(
                            ctx, repo.manifestUrl, scraper.id, false,
                            result.optString("error").take(120)
                        )
                        bad++
                    }
                    !result.optBoolean("gs") -> {
                        NuvioValidity.setProbe(
                            ctx, repo.manifestUrl, scraper.id, false, "no getStreams export"
                        )
                        bad++
                    }
                    else -> {
                        NuvioValidity.setProbe(
                            ctx, repo.manifestUrl, scraper.id, true,
                            if (result.optBoolean("st")) "loads · has settings" else "loads"
                        )
                        ok++
                    }
                }
            }
            if (dialog.isShowing) dialog.dismiss()
            if (!cancelled && isAdded) {
                Toast.makeText(
                    ctx,
                    getString(R.string.nuvio_check_done, ok, bad),
                    Toast.LENGTH_LONG
                ).show()
            }
            if (isAdded) refresh()
        }
    }

    private fun refresh() {
        val diag = NuvioDiagnostics.lastSummary
        diagView.visibility = if (diag.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!diag.isNullOrBlank()) {
            diagView.text = "Last stream check · $diag"
        }
        if (!::adapter.isInitialized) return
        val ctx = requireContext()
        val repos = NuvioRepository.getRepos(ctx)
        var totalScrapers = 0
        var enabledScrapers = 0
        for (repo in repos) {
            val scrapers = repo.manifest()?.scrapers.orEmpty()
            totalScrapers += scrapers.size
            enabledScrapers += scrapers.count { repo.isScraperEnabled(it) }
        }
        adapter.submit(
            repos, NuvioRepository.collapsedRepos(ctx),
            enabledScrapers, totalScrapers,
        )
        repoListAdapter.submit(repos)
        installedEmpty?.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
        reposEmpty?.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
        // Keep the browsable Nuvio provider registered exactly while
        // it can work (TMDB key set + at least one scraper enabled).
        NuvioProviders.sync(ctx)
    }

    private fun toast(message: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // ---- add / refresh repository ----

    private fun showAddDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.nuvio_repo_url_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            // 8.82: readable on the dark dialog surface.
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(158, 158, 158))
        }
        val container = FrameLayout(ctx).apply {
            // 8.89: dialog content padding standard is 24dp sides.
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.nuvio_add_repo)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val url = when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.isNotBlank() -> "https://$raw"
                    else -> ""
                }
                if (url.isNotBlank()) fetchAndStoreRepo(url)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchAndStoreRepo(url: String) {
        val ctx = requireContext()
        val existed = NuvioRepository.getRepos(ctx).any { it.manifestUrl == url }
        toast(R.string.nuvio_repo_checking)
        lifecycleScope.launch {
            val fetched = runCatching {
                val response = app.get(url)
                if (response.code !in 200..299) return@runCatching null
                val raw = response.text
                val manifest = NuvioParse.parseRepoManifest(JSONObject(raw))
                if (manifest.scrapers.isEmpty()) null else raw
            }.getOrNull()
            if (!isAdded) return@launch
            if (fetched == null) {
                toast(R.string.nuvio_repo_add_failed)
                return@launch
            }
            NuvioRepository.addRepo(ctx, url, fetched)
            toast(
                if (existed) R.string.nuvio_repo_refreshed else R.string.nuvio_repo_added_off
            )
            refresh()
            // Prefetch scraper code so the first play is not slowed by it.
            val repo = NuvioRepository.getRepos(ctx)
                .firstOrNull { it.manifestUrl == url } ?: return@launch
            repo.manifest()?.scrapers?.forEach { scraper ->
                runCatching { NuvioRepository.fetchCode(ctx, repo, scraper) }
            }
        }
    }

    private fun confirmRemove(repo: StoredNuvioRepo) {
        val ctx = requireContext()
        val name = repo.manifest()?.name ?: repo.manifestUrl
        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle(R.string.nuvio_repo_remove_title)
            .setMessage(getString(R.string.nuvio_repo_remove_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                NuvioRepository.removeRepo(ctx, repo.manifestUrl)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- scraper settings ----

    private fun openScraperSettings(repo: StoredNuvioRepo, scraper: NuvioScraperInfo) {
        val ctx = requireContext()
        val loading = AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setMessage(R.string.nuvio_settings_loading)
            .setCancelable(false)
            .create()
        loading.show()
        lifecycleScope.launch {
            val code = runCatching {
                NuvioRepository.fetchCode(ctx, repo, scraper)
            }.getOrNull()
            val saved = NuvioRepository.scraperSettings(ctx, repo.manifestUrl, scraper.id)
            val fields = if (code == null) {
                null
            } else {
                runCatching {
                    NuvioEngine.fetchSettingsLayout(
                        ctx, repo.manifestUrl, scraper, code, saved,
                        NuvioTmdb.apiKey(ctx)
                    )
                }.getOrNull()
            }
            loading.dismiss()
            if (!isAdded) return@launch
            when {
                fields == null -> toast(R.string.nuvio_settings_load_failed)
                fields.none { it.key.isNotBlank() } ->
                    toast(R.string.nuvio_settings_no_fields)
                else -> showSettingsForm(repo, scraper, fields, saved)
            }
        }
    }

    private fun showSettingsForm(
        repo: StoredNuvioRepo,
        scraper: NuvioScraperInfo,
        fields: List<NuvioSettingsField>,
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

        fun currentRaw(field: NuvioSettingsField): Any? {
            if (saved.has(field.key) && !saved.isNull(field.key)) {
                return saved.opt(field.key)
            }
            return field.defaultRaw
        }

        for (field in fields) {
            when (field.type) {
                "header" -> {
                    column.addView(TextView(ctx).apply {
                        text = field.label
                        setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                        textSize = 16f
                        setPadding(0, dp(12), 0, dp(4))
                    })
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                }
                "info" -> {
                    val text = listOf(field.label, field.description)
                        .filter { !it.isNullOrBlank() }
                        .joinToString("\n")
                    if (text.isNotBlank()) {
                        column.addView(TextView(ctx).apply {
                            this.text = text
                            setPadding(0, dp(6), 0, dp(6))
                        })
                    }
                }
                "toggle" -> {
                    if (field.key.isBlank()) continue
                    val raw = currentRaw(field)
                    // Phase 8.72: MaterialSwitch — Google's M3 switch
                    // with the thumb-grow/check animation he asked for
                    // (was a plain SwitchCompat).
                    val switch = MaterialSwitch(ctx).apply {
                        text = field.label.ifBlank { field.key }
                        isChecked = when (raw) {
                            is Boolean -> raw
                            is String -> raw.equals("true", ignoreCase = true)
                            else -> false
                        }
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
                    if (field.key.isBlank() || field.options.isEmpty()) continue
                    column.addView(TextView(ctx).apply {
                        text = field.label.ifBlank { field.key }
                        setPadding(0, dp(10), 0, 0)
                    })
                    val raw = currentRaw(field)?.toString()
                    val spinner = Spinner(ctx).apply {
                        adapter = ArrayAdapter(
                            ctx,
                            android.R.layout.simple_spinner_dropdown_item,
                            field.options.map { it.first }
                        )
                        val index = field.options.indexOfFirst { it.second == raw }
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
                            values.put(key, options[index].second)
                        }
                    }
                }
                else -> { // text / number
                    if (field.key.isBlank()) continue
                    column.addView(TextView(ctx).apply {
                        text = field.label.ifBlank { field.key }
                        setPadding(0, dp(10), 0, 0)
                    })
                    val raw = currentRaw(field)
                    val edit = EditText(ctx).apply {
                        setSingleLine(true)
                        hint = field.placeholder
                        inputType = when {
                            field.isPassword ->
                                InputType.TYPE_CLASS_TEXT or
                                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                            field.type == "number" ->
                                InputType.TYPE_CLASS_NUMBER or
                                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                                    InputType.TYPE_NUMBER_FLAG_SIGNED
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        setText(
                            when (raw) {
                                null, JSONObject.NULL -> ""
                                is String -> raw
                                else -> raw.toString()
                            }
                        )
                    }
                    column.addView(edit)
                    field.description?.let { desc ->
                        column.addView(TextView(ctx).apply { text = desc })
                    }
                    val key = field.key
                    val isNumber = field.type == "number"
                    getters += {
                        val text = edit.text?.toString()?.trim().orEmpty()
                        if (isNumber && text.isNotEmpty()) {
                            val number = text.toLongOrNull()
                                ?: text.toDoubleOrNull()
                            if (number != null) {
                                values.put(key, number)
                            } else {
                                values.put(key, text)
                            }
                        } else {
                            values.put(key, text)
                        }
                    }
                }
            }
        }

        // 8.82: readable white form text on the dark dialog surface,
        // independent of the theme variant in use.
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

        // Phase 8.89: this form now opens as the standard bottom
        // sheet (SheetChrome) instead of an AlertDialog — the same
        // conversion the Sky settings form got in 8.81, so both
        // bridge settings forms share one surface: drag handle,
        // title row with ✕, dark Cancel pill, red Save pill. The
        // save path is byte-for-byte the old positive button's.
        // The sheet root carries the 20dp side padding, so the
        // column drops its own (it was sized for the dialog).
        column.setPadding(0, dp(4), 0, 0)
        val scroll = ScrollView(ctx).apply { addView(column) }
        // Batch 4: weight 1 so the form scrolls and the
        // button row below it stays on-screen (see the Sky
        // sheet for the unweighted-ScrollView failure mode).
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        val root = SheetChrome.sheetRoot(ctx)
        root.addView(SheetChrome.dragHandle(ctx))
        val (titleRow, closeButton) = SheetChrome.titleRow(ctx, scraper.name)
        root.addView(titleRow)
        root.addView(scroll)
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val cancelButton = SheetChrome.secondaryButton(
            ctx, android.R.string.cancel
        )
        val saveButton = MaterialButton(ctx).apply {
            setText(R.string.nuvio_settings_save)
            backgroundTintList = ContextCompat.getColorStateList(
                ctx, R.color.colorPrimaryRed
            )
            setTextColor(Color.WHITE)
            cornerRadius = dp(20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        buttonRow.addView(cancelButton)
        buttonRow.addView(saveButton)
        root.addView(buttonRow)

        val dialog = BottomSheetDialog(ctx, R.style.AppBottomSheetDialogTheme)
        dialog.setContentView(root)
        closeButton.setOnClickListener { dialog.dismiss() }
        cancelButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            getters.forEach { it() }
            NuvioRepository.saveScraperSettings(
                ctx, repo.manifestUrl, scraper.id, values
            )
            toast(R.string.nuvio_settings_saved)
            dialog.dismiss()
        }
        dialog.show()
        // Batch 4: cap the sheet at 80% of screen height
        // and keep Save/Cancel above the keyboard.
        SheetChrome.applyImeResize(dialog)
        SheetChrome.capSheetHeight(dialog)
        SheetChrome.transparentSheetFrame(dialog)
    }
}

// ---- list (repo headers + scraper rows in one RecyclerView) ----

private sealed interface NuvioRow {
    /** 8.81: master summary card, first row of the Installed tab. */
    data class Master(
        val enabled: Int,
        val total: Int,
    ) : NuvioRow
    data class RepoHeader(
        val repo: StoredNuvioRepo,
        val name: String,
        val collapsed: Boolean,
    ) : NuvioRow
    data class ScraperRow(
        val repo: StoredNuvioRepo,
        val scraper: NuvioScraperInfo,
        val enabled: Boolean,
        // Phase 8.77: last row of an expanded group gets the
        // rounded-bottom group background.
        val groupLast: Boolean = false,
    ) : NuvioRow
}

private class NuvioRepoAdapter(
    private val onToggle: (StoredNuvioRepo, NuvioScraperInfo, Boolean) -> Unit,
    private val onRefreshRepo: (StoredNuvioRepo) -> Unit,
    private val onRemoveRepo: (StoredNuvioRepo) -> Unit,
    private val onSettings: (StoredNuvioRepo, NuvioScraperInfo) -> Unit,
    private val onToggleCollapse: (StoredNuvioRepo) -> Unit,
    private val onCheckRepo: (StoredNuvioRepo) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<NuvioRow> = emptyList()
    private var masterEnabled = 0
    private var masterTotal = 0

    fun submit(
        repos: List<StoredNuvioRepo>,
        collapsed: Set<String> = emptySet(),
        enabledScrapers: Int = masterEnabled,
        totalScrapers: Int = masterTotal,
    ) {
        masterEnabled = enabledScrapers
        masterTotal = totalScrapers
        rows = buildRows(repos, collapsed)
        notifyDataSetChanged()
    }

    private fun buildRows(
        repos: List<StoredNuvioRepo>,
        collapsed: Set<String>,
    ): List<NuvioRow> {
        val out = mutableListOf<NuvioRow>()
        // 8.81: the master card leads the Installed tab; with no
        // repos the tab shows its empty text instead.
        if (repos.isNotEmpty()) {
            out += NuvioRow.Master(masterEnabled, masterTotal)
        }
        for (repo in repos) {
            val manifest = repo.manifest()
            val isCollapsed = collapsed.contains(repo.manifestUrl)
            out += NuvioRow.RepoHeader(
                repo, manifest?.name ?: repo.manifestUrl, isCollapsed
            )
            if (!isCollapsed) {
                val scrapers = manifest?.scrapers.orEmpty()
                scrapers.forEachIndexed { index, scraper ->
                    out += NuvioRow.ScraperRow(
                        repo, scraper, repo.isScraperEnabled(scraper),
                        groupLast = index == scrapers.lastIndex,
                    )
                }
            }
        }
        return out
    }

    /**
     * Phase 8.77: expand/collapse ONE repo group with the list's
     * item animator (insert/remove) instead of a full rebuild —
     * the SkyStream-style smooth dropdown the GitHub feedback
     * asked for. Falls back to a full submit when the header is
     * not in the current rows.
     */
    fun toggleRepo(
        repo: StoredNuvioRepo,
        repos: List<StoredNuvioRepo>,
        collapsed: Set<String>,
    ) {
        val headerPos = rows.indexOfFirst {
            it is NuvioRow.RepoHeader && it.repo.manifestUrl == repo.manifestUrl
        }
        if (headerPos < 0) {
            submit(repos, collapsed)
            return
        }
        var oldCount = 0
        var i = headerPos + 1
        while (i < rows.size && rows[i] is NuvioRow.ScraperRow) {
            oldCount++
            i++
        }
        rows = buildRows(repos, collapsed)
        var newCount = 0
        var j = headerPos + 1
        while (j < rows.size && rows[j] is NuvioRow.ScraperRow) {
            newCount++
            j++
        }
        notifyItemChanged(headerPos)
        if (newCount > oldCount) {
            notifyItemRangeInserted(headerPos + 1, newCount - oldCount)
        } else if (oldCount > newCount) {
            notifyItemRangeRemoved(headerPos + 1, oldCount - newCount)
        }
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is NuvioRow.Master -> TYPE_MASTER
        is NuvioRow.RepoHeader -> TYPE_REPO
        is NuvioRow.ScraperRow -> TYPE_SCRAPER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MASTER -> MasterHolder(
                inflater.inflate(R.layout.item_nuvio_master, parent, false)
            )
            TYPE_REPO -> RepoHolder(
                inflater.inflate(R.layout.item_nuvio_repo, parent, false)
            )
            else -> ScraperHolder(
                inflater.inflate(R.layout.item_nuvio_scraper, parent, false)
            )
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NuvioRow.Master -> {
                val h = holder as MasterHolder
                h.summary.text = h.itemView.context.getString(
                    R.string.nuvio_master_summary, row.enabled, row.total
                )
            }
            is NuvioRow.RepoHeader -> {
                val h = holder as RepoHolder
                h.name.text = row.name
                h.url.text = row.repo.manifestUrl
                // Phase 8.77: one group, one card — while expanded
                // the header takes the top-segment background and
                // loses its bottom margin so the scraper rows join
                // it; the chevron animates instead of snapping.
                h.itemView.setBackgroundResource(
                    if (row.collapsed) R.drawable.bg_repository_card
                    else R.drawable.bg_repository_card_top
                )
                (h.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.bottomMargin = if (row.collapsed) dp(h.itemView, 2) else 0
                    h.itemView.layoutParams = lp
                }
                val targetRotation = if (row.collapsed) -90f else 0f
                if (h.expand.rotation != targetRotation) {
                    h.expand.animate().rotation(targetRotation)
                        .setDuration(250).start()
                }
                h.itemView.setOnClickListener { onToggleCollapse(row.repo) }
                h.check.setOnClickListener { onCheckRepo(row.repo) }
                h.refresh.setOnClickListener { onRefreshRepo(row.repo) }
                h.remove.setOnClickListener { onRemoveRepo(row.repo) }
            }
            is NuvioRow.ScraperRow -> {
                val h = holder as ScraperHolder
                val scraper = row.scraper
                // Phase 8.77: grouped-card segments — square middle
                // rows, rounded bottom on the group's last row, no
                // gaps inside the group (the repo header below the
                // group carries the spacing via its top margin).
                h.itemView.setBackgroundResource(
                    if (row.groupLast) R.drawable.bg_group_bottom
                    else R.drawable.bg_group_mid
                )
                (h.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                    lp.topMargin = 0
                    lp.bottomMargin = if (row.groupLast) dp(h.itemView, 10) else 0
                    h.itemView.layoutParams = lp
                }
                h.name.text = scraper.name
                val version = h.itemView.context.getString(
                    R.string.nuvio_version_format, scraper.version
                )
                h.desc.text = listOf(scraper.description, version)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                // Circle-masked logos, same treatment as the main
                // Extensions list (PluginAdapter, phase 8.9).
                val logo = scraper.logo
                if (!logo.isNullOrBlank()) {
                    h.logo.visibility = View.VISIBLE
                    h.logo.loadImage(logo) {
                        transformations(CircleCropTransformation())
                        error(
                            getImageFromDrawable(
                                h.itemView.context,
                                R.drawable.ic_baseline_extension_24,
                            )
                        )
                    }
                } else {
                    h.logo.visibility = View.GONE
                }
                h.settings.visibility = if (scraper.hasSettings) View.VISIBLE else View.GONE
                h.settings.setOnClickListener { onSettings(row.repo, scraper) }
                // Validity line (8.68): last load-test result and/or last
                // real fetch outcome for this scraper, when known.
                val validity = NuvioValidity.get(
                    h.itemView.context, row.repo.manifestUrl, scraper.id
                )
                val statusParts = mutableListOf<String>()
                validity?.probe?.let { probe ->
                    statusParts += if (validity.probeOk) "✓ $probe" else "✗ $probe"
                }
                validity?.last?.let { statusParts += "last fetch: $it" }
                if (statusParts.isEmpty()) {
                    h.status.visibility = View.GONE
                } else {
                    h.status.visibility = View.VISIBLE
                    h.status.text = statusParts.joinToString(" · ")
                }
                h.enabled.setOnCheckedChangeListener(null)
                h.enabled.isChecked = row.enabled
                h.enabled.setOnCheckedChangeListener { _, checked ->
                    onToggle(row.repo, scraper, checked)
                }
            }
        }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    private class MasterHolder(view: View) : RecyclerView.ViewHolder(view) {
        val summary: TextView = view.findViewById(R.id.item_master_summary)
    }

    private class RepoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val expand: ImageView = view.findViewById(R.id.item_repo_expand)
        val name: TextView = view.findViewById(R.id.item_repo_name)
        val url: TextView = view.findViewById(R.id.item_repo_url)
        val check: ImageButton = view.findViewById(R.id.item_repo_check)
        val refresh: ImageButton = view.findViewById(R.id.item_repo_refresh)
        val remove: ImageButton = view.findViewById(R.id.item_repo_remove)
    }

    private class ScraperHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.item_scraper_logo)
        val name: TextView = view.findViewById(R.id.item_scraper_name)
        val desc: TextView = view.findViewById(R.id.item_scraper_desc)
        val status: TextView = view.findViewById(R.id.item_scraper_status)
        val settings: ImageButton = view.findViewById(R.id.item_scraper_settings)
        val enabled: SwitchCompat = view.findViewById(R.id.item_scraper_enabled)
    }

    companion object {
        private const val TYPE_REPO = 0
        private const val TYPE_SCRAPER = 1
        private const val TYPE_MASTER = 2
    }
}

/**
 * 8.81: Repositories tab — the simple browse-side repo list (name,
 * URL, check / refresh / remove). Scraper management lives on the
 * Installed tab in [NuvioRepoAdapter]; the callbacks are the same.
 */
private class NuvioRepoListAdapter(
    private val onRefreshRepo: (StoredNuvioRepo) -> Unit,
    private val onRemoveRepo: (StoredNuvioRepo) -> Unit,
    private val onCheckRepo: (StoredNuvioRepo) -> Unit,
) : RecyclerView.Adapter<NuvioRepoListAdapter.Holder>() {

    private var repos: List<StoredNuvioRepo> = emptyList()

    fun submit(repos: List<StoredNuvioRepo>) {
        this.repos = repos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nuvio_repo, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = repos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val repo = repos[position]
        holder.name.text = repo.manifest()?.name ?: repo.manifestUrl
        holder.url.text = repo.manifestUrl
        // No group to expand on this tab.
        holder.expand.visibility = View.GONE
        holder.check.setOnClickListener { onCheckRepo(repo) }
        holder.refresh.setOnClickListener { onRefreshRepo(repo) }
        holder.remove.setOnClickListener { onRemoveRepo(repo) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val expand: ImageView = view.findViewById(R.id.item_repo_expand)
        val name: TextView = view.findViewById(R.id.item_repo_name)
        val url: TextView = view.findViewById(R.id.item_repo_url)
        val check: ImageButton = view.findViewById(R.id.item_repo_check)
        val refresh: ImageButton = view.findViewById(R.id.item_repo_refresh)
        val remove: ImageButton = view.findViewById(R.id.item_repo_remove)
    }
}
