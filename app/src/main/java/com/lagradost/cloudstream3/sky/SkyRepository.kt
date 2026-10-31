package com.lagradost.cloudstream3.sky

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.AppCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Installed SkyStream repositories and plugins, their settings and
 * the plugins' own preference storage.
 *
 * Storage mirrors the reference implementation: plugin code lives on
 * disk under filesDir/sky_code/<repoKey>/<packageName>/ (plugin.js +
 * plugin.json), and every install record keeps the source repository
 * URL, the version and the sha256 of plugin.js. Repositories are a
 * three-level chain (mega-repo -> repo -> pluginLists), resolved and
 * fetched live; only the repo document itself is cached in prefs.
 */
data class StoredSkyRepo(
    /** Canonical URL of the repo document, after shortcode resolution. */
    val url: String,
    /** What the user typed (URL, github spelling or bare code). */
    val input: String,
    val addedAt: Long,
    val docJson: String?,
    val lastError: String? = null,
) {
    fun doc(): SkyRepoDoc? {
        val raw = docJson ?: return null
        return runCatching { SkyParse.parseRepoDoc(JSONObject(raw)) }.getOrNull()
    }

    fun displayName(): String = doc()?.name ?: url
}

data class StoredSkyPlugin(
    val packageName: String,
    /** Directory key under sky_code (derived from the source repo). */
    val repoKey: String,
    /** The concrete repo.json URL that listed this plugin. */
    val sourceRepoUrl: String,
    val name: String,
    val version: Int,
    val enabled: Boolean,
    /** sha256 (hex) of plugin.js as installed. */
    val sha256: String,
    /** plugin.json content, kept for instant manifest access. */
    val manifestJson: String,
    val addedAt: Long,
) {
    fun manifest(): SkyPluginManifest? {
        return runCatching {
            SkyParse.parsePluginManifest(JSONObject(manifestJson))
        }.getOrNull()
    }
}

/** Plain OkHttp transport for the repository chain (JSON + .sky ZIPs). */
object SkyHttp {

    data class Response(val code: Int, val body: String, val finalUrl: String)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // 8.83: repository-chain fetches (repo docs, shortcode
        // redirects, .sky downloads) share the universal persistent
        // cookie store under their own namespace. The context is
        // resolved lazily — this object can be touched before the
        // Application's context is published.
        .cookieJar(AppCookieJar({ CloudStreamApp.context }, "sky:repo"))
        .build()

    /**
     * Batch 1 caps: no response body is ever loaded unbounded.
     * A Content-Length above the cap is rejected before any body
     * bytes are read, and a body that grows past the cap while
     * streaming aborts the download (null result, nothing handed
     * to a parser or written to disk).
     */
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    const val MAX_SKY_BYTES = 16 * 1024 * 1024

    private fun readCapped(stream: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            total += read
            if (total > cap) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    /** GET text; finalUrl is the URL after redirects (shortcodes!). */
    fun get(url: String): Response? = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", SKY_USER_AGENT)
                .build()
        ).execute().use { resp ->
            val body = resp.body ?: return@use Response(
                code = resp.code,
                body = "",
                finalUrl = resp.request.url.toString(),
            )
            if (body.contentLength() > MAX_TEXT_BYTES) return@use null
            val bytes = body.byteStream().use { readCapped(it, MAX_TEXT_BYTES) }
                ?: return@use null
            Response(
                code = resp.code,
                body = String(bytes, Charsets.UTF_8),
                finalUrl = resp.request.url.toString(),
            )
        }
    }.getOrNull()

    fun getBytes(url: String): ByteArray? = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", SKY_USER_AGENT)
                .build()
        ).execute().use { resp ->
            if (resp.code !in 200..299) return@use null
            val body = resp.body ?: return@use null
            if (body.contentLength() > MAX_SKY_BYTES) return@use null
            body.byteStream().use { readCapped(it, MAX_SKY_BYTES) }
        }
    }.getOrNull()

    const val SKY_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
}

object SkyRepository {

    private const val KEY_REPOS = "sky_repos_v1"
    private const val KEY_INSTALLED = "sky_installed_v1"
    private const val KEY_SETTINGS = "sky_settings_v1"
    private const val KEY_STORAGE = "sky_storage_v1"
    private const val KEY_COLLAPSED = "sky_collapsed_v1"
    private const val KEY_SECTIONS = "sky_sections_v1"

    /** Batch 1: bounded .sky ZIP expansion (see installPlugin). */
    private const val MAX_ZIP_ENTRIES = 100
    private const val MAX_ZIP_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 16 * 1024 * 1024

    /**
     * Reserved key inside a plugin's settings object (8.82): a JSON
     * array of sub-provider ids the user switched off in the
     * settings sheet. SkyProviders skips those instances.
     */
    const val DISABLED_PROVIDERS_KEY = "disabled_providers"

    private fun prefs(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    // ---- repositories ----

    fun getRepos(ctx: Context): List<StoredSkyRepo> = runCatching {
        val raw = prefs(ctx).getString(KEY_REPOS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url")
            if (url.isBlank()) return@mapNotNull null
            StoredSkyRepo(
                url = url,
                input = obj.optString("input").ifBlank { url },
                addedAt = obj.optLong("addedAt", 0L),
                docJson = obj.optString("doc").takeIf { it.isNotBlank() },
                lastError = obj.optString("error").takeIf { it.isNotBlank() },
            )
        }
    }.getOrElse { emptyList() }

    private fun saveRepos(ctx: Context, repos: List<StoredSkyRepo>) {
        val arr = JSONArray()
        repos.forEach { repo ->
            val obj = JSONObject()
            obj.put("url", repo.url)
            obj.put("input", repo.input)
            obj.put("addedAt", repo.addedAt)
            repo.docJson?.let { obj.put("doc", it) }
            repo.lastError?.let { obj.put("error", it) }
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    /** @return true when the repo is new, false when it was refreshed. */
    fun addRepo(ctx: Context, url: String, input: String, doc: SkyRepoDoc): Boolean {
        val current = getRepos(ctx).toMutableList()
        val index = current.indexOfFirst { it.url == url }
        if (index >= 0) {
            current[index] = current[index].copy(docJson = doc.rawJson, lastError = null)
            saveRepos(ctx, current)
            return false
        }
        current += StoredSkyRepo(
            url = url,
            input = input,
            addedAt = System.currentTimeMillis(),
            docJson = doc.rawJson,
        )
        saveRepos(ctx, current)
        return true
    }

    fun removeRepo(ctx: Context, url: String) {
        // Installed plugins keep working (their install records carry
        // their own source URL); only the listing source goes away.
        saveRepos(ctx, getRepos(ctx).filterNot { it.url == url })
    }

    /**
     * Stores a freshly resolved repository (8.82). A mega-repo is
     * NOT stored itself: each sub-repo becomes its own entry (its
     * own repo.json URL as the key, name/description from its own
     * repo document), exactly how the reference treats Stars, ZORO,
     * MiraiExt and TJ-Plugins. A plain repo stores exactly like
     * [addRepo]. A sub-repo whose document cannot be fetched right
     * now is still stored (doc-less); its document loads live on the
     * next chain fetch.
     *
     * @return how many entries were newly added (0 = all refreshed).
     */
    suspend fun addRepoResolved(
        ctx: Context,
        url: String,
        input: String,
        doc: SkyRepoDoc,
    ): Int = withContext(Dispatchers.IO) {
        if (!doc.isMega) {
            return@withContext if (addRepo(ctx, url, input, doc)) 1 else 0
        }
        // The mega document itself is never a stored entry.
        removeRepo(ctx, url)
        val current = getRepos(ctx).toMutableList()
        var added = 0
        for (childUrl in doc.repos) {
            val child = tryFetchRepoDoc(childUrl)
            val childKey = child?.first ?: childUrl
            val index = current.indexOfFirst { it.url == childKey }
            if (index >= 0) {
                current[index] = current[index].copy(
                    docJson = child?.second?.rawJson ?: current[index].docJson,
                    lastError = null,
                )
            } else {
                current += StoredSkyRepo(
                    url = childKey,
                    input = input,
                    addedAt = System.currentTimeMillis(),
                    docJson = child?.second?.rawJson,
                )
                added++
            }
        }
        saveRepos(ctx, current)
        added
    }

    /**
     * One-time migration (8.82): installs from 8.81 and earlier
     * stored a mega-repo as ONE entry whose card expanded into flat
     * section labels. Replace every stored mega entry by its
     * sub-repo entries — the same shape [addRepoResolved] stores —
     * and persist. Runs on repository load from the settings page;
     * a no-op once no stored document is a mega-repo.
     *
     * @return true when the stored list was rewritten.
     */
    suspend fun migrateMegaRepos(ctx: Context): Boolean =
        withContext(Dispatchers.IO) {
            val repos = getRepos(ctx)
            if (repos.none { it.doc()?.isMega == true }) {
                return@withContext false
            }
            val out = mutableListOf<StoredSkyRepo>()
            for (entry in repos) {
                val doc = entry.doc()
                if (doc == null || !doc.isMega) {
                    out += entry
                    continue
                }
                for (childUrl in doc.repos) {
                    val child = tryFetchRepoDoc(childUrl)
                    val childKey = child?.first ?: childUrl
                    if (out.any { it.url == childKey }) continue
                    out += StoredSkyRepo(
                        url = childKey,
                        input = entry.input,
                        addedAt = entry.addedAt,
                        docJson = child?.second?.rawJson,
                    )
                }
            }
            saveRepos(ctx, out)
            true
        }

    fun setRepoError(ctx: Context, url: String, error: String?) {
        val current = getRepos(ctx).map {
            if (it.url == url) it.copy(lastError = error) else it
        }
        saveRepos(ctx, current)
    }

    fun repoKeyFor(repoUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(repoUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest.take(16)
    }

    // ---- input resolution (URL / github spelling / shortcode) ----

    private fun tryFetchRepoDoc(url: String): Pair<String, SkyRepoDoc>? {
        val resp = SkyHttp.get(url) ?: return null
        if (resp.code !in 200..299) return null
        val doc = runCatching {
            SkyParse.parseRepoDoc(JSONObject(resp.body))
        }.getOrNull() ?: return null
        return resp.finalUrl to doc
    }

    /**
     * Resolves what the user typed in the add dialog to a stored repo:
     * a full URL (used as-is, then repo.json / mega_repo.json siblings),
     * a github.com/user/repo spelling (raw repo.json, then mega_repo.json
     * on main, then master), or a bare shortcode — resolved exactly like
     * SkyStream's repository_service: cutt.ly/sky-<code> first, then
     * cutt.ly/<code>, following redirects to the JSON.
     *
     * @return (canonicalUrl, doc) or null when nothing resolves.
     */
    suspend fun resolveRepoInput(input: String): Pair<String, SkyRepoDoc>? =
        withContext(Dispatchers.IO) {
            val raw = input.trim()
            if (raw.isBlank()) return@withContext null
            val candidates = mutableListOf<String>()
            val githubMatch = Regex(
                "(?:https?://)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$"
            ).find(raw)
            when {
                githubMatch != null -> {
                    val user = githubMatch.groupValues[1]
                    val repo = githubMatch.groupValues[2]
                    for (branch in listOf("main", "master")) {
                        candidates +=
                            "https://raw.githubusercontent.com/$user/$repo/$branch/repo.json"
                        candidates +=
                            "https://raw.githubusercontent.com/$user/$repo/$branch/mega_repo.json"
                    }
                }
                raw.startsWith("http://") || raw.startsWith("https://") -> {
                    candidates += raw
                    if (!raw.endsWith(".json")) {
                        val base = raw.trimEnd('/')
                        candidates += base + "/repo.json"
                        candidates += base + "/mega_repo.json"
                    }
                }
                else -> {
                    // Bare shortcode, SkyStream's resolution order.
                    candidates += "https://cutt.ly/sky-$raw"
                    candidates += "https://cutt.ly/$raw"
                }
            }
            for (candidate in candidates) {
                tryFetchRepoDoc(candidate)?.let { return@withContext it }
            }
            null
        }

    /** Fetches a repo document directly (refresh path). */
    suspend fun fetchRepoDoc(url: String): SkyRepoDoc? =
        withContext(Dispatchers.IO) {
            tryFetchRepoDoc(url)?.second
        }

    // ---- chain expansion: repo -> plugin listings ----

    private fun fetchPluginList(url: String): List<SkyPluginManifest>? {
        val resp = SkyHttp.get(url) ?: return null
        if (resp.code !in 200..299) return null
        val body = resp.body.trim()
        val root: Any = runCatching {
            if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
        }.getOrNull() ?: return null
        return SkyParse.parsePluginList(root)
    }

    private fun expandRepoDoc(
        doc: SkyRepoDoc,
        repoUrl: String,
    ): SkyRepoListing {
        val plugins = mutableListOf<SkyPluginManifest>()
        var error: String? = null
        for (listUrl in doc.pluginLists) {
            val entries = fetchPluginList(listUrl)
            if (entries == null) {
                error = "Could not load $listUrl"
            } else {
                plugins += entries
            }
        }
        return SkyRepoListing(
            repoUrl = repoUrl,
            repoName = doc.name,
            plugins = plugins,
            error = error,
        )
    }

    /**
     * Expands one stored repo into its plugin listings: a mega-repo
     * expands to each child repo (one listing each), a plain repo to
     * its own pluginLists.
     */
    suspend fun fetchRepoChain(repo: StoredSkyRepo): List<SkyRepoListing> =
        withContext(Dispatchers.IO) {
            val doc = repo.doc()
                ?: fetchRepoDoc(repo.url)
                ?: return@withContext listOf(
                    SkyRepoListing(
                        repoUrl = repo.url,
                        repoName = repo.displayName(),
                        plugins = emptyList(),
                        error = "Could not load the repository document",
                    )
                )
            if (!doc.isMega) {
                return@withContext listOf(expandRepoDoc(doc, repo.url))
            }
            val out = mutableListOf<SkyRepoListing>()
            for (childUrl in doc.repos) {
                val child = tryFetchRepoDoc(childUrl)
                if (child == null) {
                    out += SkyRepoListing(
                        repoUrl = childUrl,
                        repoName = childUrl,
                        plugins = emptyList(),
                        error = "Could not load this repository",
                    )
                } else {
                    out += expandRepoDoc(child.second, child.first)
                }
            }
            out
        }

    // ---- installed plugins ----

    fun getInstalled(ctx: Context): List<StoredSkyPlugin> = runCatching {
        val raw = prefs(ctx).getString(KEY_INSTALLED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val pkg = obj.optString("pkg")
            if (pkg.isBlank()) return@mapNotNull null
            StoredSkyPlugin(
                packageName = pkg,
                repoKey = obj.optString("repoKey"),
                sourceRepoUrl = obj.optString("sourceRepo"),
                name = obj.optString("name"),
                version = obj.optInt("version", 0),
                enabled = obj.optBoolean("enabled", true),
                sha256 = obj.optString("sha256"),
                manifestJson = obj.optString("manifest"),
                addedAt = obj.optLong("addedAt", 0L),
            )
        }
    }.getOrElse { emptyList() }

    private fun saveInstalled(ctx: Context, plugins: List<StoredSkyPlugin>) {
        val arr = JSONArray()
        plugins.forEach { plugin ->
            val obj = JSONObject()
            obj.put("pkg", plugin.packageName)
            obj.put("repoKey", plugin.repoKey)
            obj.put("sourceRepo", plugin.sourceRepoUrl)
            obj.put("name", plugin.name)
            obj.put("version", plugin.version)
            obj.put("enabled", plugin.enabled)
            obj.put("sha256", plugin.sha256)
            obj.put("manifest", plugin.manifestJson)
            obj.put("addedAt", plugin.addedAt)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_INSTALLED, arr.toString()).apply()
    }

    fun installedByPackage(ctx: Context): Map<String, StoredSkyPlugin> =
        getInstalled(ctx).associateBy { it.packageName }

    /** Integer version compare, the reference's update rule. */
    fun updateAvailable(installed: StoredSkyPlugin, listing: SkyPluginManifest): Boolean =
        listing.version > installed.version

    fun setPluginEnabled(ctx: Context, packageName: String, enabled: Boolean) {
        val current = getInstalled(ctx).map {
            if (it.packageName == packageName) it.copy(enabled = enabled) else it
        }
        saveInstalled(ctx, current)
    }

    // ---- plugin code store ----

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun pluginDir(ctx: Context, repoKey: String, packageName: String): File =
        File(ctx.filesDir, "sky_code/$repoKey/${safeSegment(packageName)}")

    /**
     * Batch 1: plugin.js is only returned when it matches the hash
     * recorded at install time. On a mismatch the code is refused
     * (null, so the engine session fails to start and the code is
     * never evaluated); the file is left on disk for the user to
     * remove/reinstall rather than silently deleted. Records with
     * no stored hash (pre-fix installs) cannot be verified — that
     * gap is documented at [installPlugin], not hidden.
     */
    fun readCode(ctx: Context, plugin: StoredSkyPlugin): String? {
        val file = File(pluginDir(ctx, plugin.repoKey, plugin.packageName), "plugin.js")
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (plugin.sha256.isNotBlank() &&
            sha256Hex(bytes) != plugin.sha256.lowercase()
        ) {
            return null
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Downloads a .sky (ZIP of plugin.js + plugin.json), validates it
     * (both files present, packageName matches the listing, no path
     * escapes) and stores it. @return the install record, or null.
     *
     * Batch 1: ZIP entries are read streaming with per-entry, total
     * and entry-count caps — nothing is written or evaluated when a
     * cap is hit. When the listing supplies a sha256, plugin.js must
     * match it before anything is written; on mismatch the install
     * fails and no partial output is left behind. Listings without
     * a hash are still installable (rejecting them would break
     * existing hash-less repositories); their installs are then
     * only protected by the install-time hash stored in the record
     * and checked by [readCode] on load, not by the listing.
     */
    suspend fun installPlugin(
        ctx: Context,
        entry: SkyPluginManifest,
        sourceRepoUrl: String,
    ): StoredSkyPlugin? = withContext(Dispatchers.IO) {
        if (entry.downloadUrl.isBlank()) return@withContext null
        val bytes = SkyHttp.getBytes(entry.downloadUrl)
            ?: return@withContext null
        var pluginJs: ByteArray? = null
        var pluginJson: String? = null
        val ok = runCatching {
            var entryCount = 0
            var totalUncompressed = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw IllegalStateException(".sky entry count over cap")
                    }
                    val name = zipEntry.name.replace('\\', '/')
                    // Path-escape guard: reject the whole archive if
                    // any entry tries to leave the plugin directory.
                    if (name.startsWith("/") ||
                        name.split('/').any { it == ".." }
                    ) {
                        throw IllegalStateException("path escape in .sky")
                    }
                    if (!zipEntry.isDirectory) {
                        when (name.substringAfterLast('/')) {
                            "plugin.js", "plugin.json" -> {
                                val out =
                                    java.io.ByteArrayOutputStream()
                                val buf = ByteArray(8192)
                                var entryBytes = 0
                                while (true) {
                                    val read = zip.read(buf)
                                    if (read < 0) break
                                    entryBytes += read
                                    totalUncompressed += read
                                    if (entryBytes > MAX_ZIP_ENTRY_BYTES ||
                                        totalUncompressed > MAX_ZIP_TOTAL_BYTES
                                    ) {
                                        throw IllegalStateException(
                                            ".sky entry over cap"
                                        )
                                    }
                                    out.write(buf, 0, read)
                                }
                                if (name.substringAfterLast('/') == "plugin.js") {
                                    pluginJs = out.toByteArray()
                                } else {
                                    pluginJson = out.toString(
                                        Charsets.UTF_8.name()
                                    )
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            true
        }.getOrElse { false }
        if (!ok) return@withContext null
        val js = pluginJs ?: return@withContext null
        val jsonText = pluginJson ?: return@withContext null
        val manifest = runCatching {
            SkyParse.parsePluginManifest(JSONObject(jsonText))
        }.getOrNull() ?: return@withContext null
        if (manifest.packageName != entry.packageName) return@withContext null
        // Verify against the listing hash BEFORE writing anything.
        val computedSha = sha256Hex(js)
        if (!entry.sha256.isNullOrBlank() &&
            entry.sha256.lowercase() != computedSha
        ) {
            return@withContext null
        }

        val repoKey = repoKeyFor(sourceRepoUrl)
        val dir = pluginDir(ctx, repoKey, entry.packageName)
        dir.mkdirs()
        File(dir, "plugin.js").writeBytes(js)
        File(dir, "plugin.json").writeText(jsonText)

        val previous = getInstalled(ctx)
            .firstOrNull { it.packageName == entry.packageName }
        val record = StoredSkyPlugin(
            packageName = entry.packageName,
            repoKey = repoKey,
            sourceRepoUrl = sourceRepoUrl,
            name = manifest.name,
            version = manifest.version,
            // A fresh install is a deliberate act: start enabled.
            // A re-install (update) keeps the user's previous choice.
            enabled = previous?.enabled ?: true,
            sha256 = computedSha,
            manifestJson = jsonText,
            addedAt = previous?.addedAt ?: System.currentTimeMillis(),
        )
        val current = getInstalled(ctx)
            .filterNot { it.packageName == entry.packageName } + record
        saveInstalled(ctx, current)
        record
    }

    fun removePlugin(ctx: Context, packageName: String) {
        val plugin = getInstalled(ctx).firstOrNull { it.packageName == packageName }
        saveInstalled(ctx, getInstalled(ctx).filterNot { it.packageName == packageName })
        if (plugin != null) {
            pluginDir(ctx, plugin.repoKey, packageName).deleteRecursively()
        }
        // Engine sessions for this plugin are dropped by the caller
        // (SkyEngine.evictPackage) to avoid a repository->engine cycle.
    }

    // ---- per-plugin settings (schema values) ----

    private fun settingsRoot(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString(KEY_SETTINGS, null) ?: "{}")
    }.getOrElse { JSONObject() }

    fun pluginSettings(ctx: Context, packageName: String): JSONObject =
        settingsRoot(ctx).optJSONObject(packageName) ?: JSONObject()

    /**
     * Batch 1: MERGE, don't replace. The settings sheet only renders
     * a subset of keys (schema fields present at open time, plus the
     * base-URL and sub-provider keys), so replacing the whole object
     * silently deleted every other saved key — including keys saved
     * when the manifest was different or absent. Here the existing
     * object is the base and only keys present in [values] are
     * overwritten; a JSON null value in [values] removes that key,
     * which is how the sheet clears a rendered field (e.g. a blank
     * base URL) without touching unrendered keys.
     */
    fun savePluginSettings(ctx: Context, packageName: String, values: JSONObject) {
        val root = settingsRoot(ctx)
        val merged = JSONObject(pluginSettings(ctx, packageName).toString())
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (values.isNull(key)) merged.remove(key) else merged.put(key, values.opt(key))
        }
        root.put(packageName, merged)
        prefs(ctx).edit().putString(KEY_SETTINGS, root.toString()).apply()
    }

    /** Sub-provider ids switched off in the settings sheet (8.82). */
    fun disabledSubProviders(ctx: Context, packageName: String): Set<String> {
        val arr = pluginSettings(ctx, packageName)
            .optJSONArray(DISABLED_PROVIDERS_KEY) ?: return emptySet()
        return (0 until arr.length()).mapNotNull {
            arr.optString(it).takeIf { id -> id.isNotBlank() }
        }.toSet()
    }

    /**
     * The base URL the plugin actually runs against: a user value for
     * the schema's base-url field wins, then a sub-provider's static
     * baseUrl, then the manifest's own baseUrl.
     */
    fun effectiveBaseUrl(
        ctx: Context,
        manifest: SkyPluginManifest,
        sub: SkySubProvider? = null,
    ): String {
        val values = pluginSettings(ctx, manifest.packageName)
        val baseDef = manifest.settings.firstOrNull { it.isBaseUrl }
        val fromSettings = when {
            baseDef != null ->
                values.optString(baseDef.key).takeIf { it.isNotBlank() }
            else -> values.optString("base_url").takeIf { it.isNotBlank() }
                ?: values.optString("baseUrl").takeIf { it.isNotBlank() }
        }
        return fromSettings
            ?: sub?.baseUrl?.takeIf { it.isNotBlank() }
            ?: manifest.baseUrl
    }

    // ---- per-plugin JS preference store (getPreference/setPreference) ----

    private fun storageRoot(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString(KEY_STORAGE, null) ?: "{}")
    }.getOrElse { JSONObject() }

    private fun instanceKey(packageName: String, subId: String?): String =
        packageName + "|" + (subId ?: "")

    /**
     * Read path for the JS getPreference bridge. Resolution order
     * mirrors SkyStream, where settings values live in the same store
     * plugins read: the plugin's own writes first, then the user's
     * settings-schema values, then the schema default. Values are
     * returned in the JSON-encoded form the polyfill expects (it runs
     * JSON.parse with a raw-string fallback).
     */
    fun prefGet(
        ctx: Context,
        packageName: String,
        subId: String?,
        key: String,
    ): String? {
        val store = storageRoot(ctx).optJSONObject(instanceKey(packageName, subId))
        if (store != null && store.has(key) && !store.isNull(key)) {
            return store.optString(key)
        }
        val values = pluginSettings(ctx, packageName)
        if (values.has(key) && !values.isNull(key)) {
            return encodePrefValue(values.opt(key))
        }
        val manifest = getInstalled(ctx)
            .firstOrNull { it.packageName == packageName }
            ?.manifest()
        val def = manifest?.settings?.firstOrNull { it.key == key }
        if (def?.defaultRaw != null) {
            return encodePrefValue(def.defaultRaw)
        }
        return null
    }

    private fun encodePrefValue(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        else -> value.toString()
    }

    fun prefSet(
        ctx: Context,
        packageName: String,
        subId: String?,
        key: String,
        value: String?,
    ) {
        synchronized(this) {
            val root = storageRoot(ctx)
            val iKey = instanceKey(packageName, subId)
            val store = root.optJSONObject(iKey) ?: JSONObject()
            if (value == null) store.remove(key) else store.put(key, value)
            root.put(iKey, store)
            prefs(ctx).edit().putString(KEY_STORAGE, root.toString()).apply()
        }
    }

    // ---- home section names (learned from getHome, read by mainPage) ----

    private fun sectionsRoot(ctx: Context): JSONObject = runCatching {
        JSONObject(prefs(ctx).getString(KEY_SECTIONS, null) ?: "{}")
    }.getOrElse { JSONObject() }

    fun getSections(ctx: Context, key: String): List<String> {
        val arr = sectionsRoot(ctx).optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }

    fun setSections(ctx: Context, key: String, names: List<String>) {
        synchronized(this) {
            val root = sectionsRoot(ctx)
            val arr = JSONArray()
            names.forEach { arr.put(it) }
            root.put(key, arr)
            prefs(ctx).edit().putString(KEY_SECTIONS, root.toString()).apply()
        }
    }

    // ---- collapsed repo groups on the SkyStream Plugins page ----

    fun collapsedRepos(ctx: Context): Set<String> = runCatching {
        val raw = prefs(ctx).getString(KEY_COLLAPSED, null) ?: return emptySet()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrElse { emptySet() }

    fun setRepoCollapsed(ctx: Context, repoUrl: String, collapsed: Boolean) {
        val current = collapsedRepos(ctx).toMutableSet()
        if (collapsed) current += repoUrl else current -= repoUrl
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY_COLLAPSED, arr.toString()).apply()
    }
}
