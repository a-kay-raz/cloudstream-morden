package com.lagradost.cloudstream3.sky

import org.json.JSONArray
import org.json.JSONObject

/**
 * SkyStream plugin contracts (bridge 8.78), mirrored from the
 * reference implementation in akashdh11/skystream
 * (lib/core/extensions) and verified against the live sky-universe
 * repository chain:
 *
 *   mega-repo {repos: [repo.json URL, ...]}
 *     -> repo.json {pluginLists: [plugins.json URL, ...]}
 *       -> plugins.json [ {packageName, name, version (int), baseUrl,
 *                          categories, languages, authors, url}, ... ]
 *         -> <plugin>.sky  a ZIP holding plugin.js + plugin.json
 *
 * All parsers are tolerant: unknown fields are ignored, aliases the
 * reference accepts (types/tvTypes for categories, language/lang for
 * languages, bool/switch/dropdown/... for setting types) are honoured,
 * and a plugin entry is only rejected when its packageName is missing.
 */

data class SkySubProvider(
    val id: String,
    val name: String,
    val baseUrl: String?,
)

data class SkySettingOption(
    val label: String,
    val value: String,
)

data class SkySettingDef(
    val key: String,
    val title: String,
    val description: String?,
    /** toggle | select | toggleGroup | text | url */
    val type: String,
    /** Raw JSON value (String/Boolean/Number/JSONObject/JSONArray) or null. */
    val defaultRaw: Any?,
    val options: List<SkySettingOption>,
    val isBaseUrl: Boolean,
)

data class SkyPluginManifest(
    val packageName: String,
    val name: String,
    /** Integer version; updates are a plain integer compare. */
    val version: Int,
    val baseUrl: String,
    val description: String,
    val iconUrl: String?,
    val authors: List<String>,
    val languages: List<String>,
    val categories: List<String>,
    /** The .sky download URL (listing entries only; plugin.json has none). */
    val downloadUrl: String,
    /**
     * Batch 1: expected sha256 (hex) of plugin.js as listed by the
     * repository, when the listing supplies one ("sha256" or "hash").
     * Null means the listing carries no hash: installs of such
     * plugins are NOT hash-verified (see installPlugin) — that
     * remaining gap is documented there, not silently papered over.
     */
    val sha256: String?,
    /** 0 down / 1 ok / 2 slow / 3 beta, as listed. */
    val status: Int,
    val subProviders: List<SkySubProvider>,
    val settings: List<SkySettingDef>,
    /** true when the listing hands out a settings schema at all. */
    val hasSettingsSchema: Boolean,
    val rawJson: String,
) {
    /**
     * Family Mode signal: the manifest marks itself adult via a
     * category or an explicit flag. Mirrors the Stremio guard, which
     * keeps adult-flagged addons unregistered while Family Mode is on.
     */
    val isAdult: Boolean
        get() {
            val cats = categories.map { it.lowercase() }
            if (cats.any {
                    it == "adult" || it == "nsfw" || it == "18+" ||
                        it.contains("adult") || it.contains("nsfw")
                }
            ) return true
            val raw = runCatching { JSONObject(rawJson) }.getOrNull()
            return raw?.optBoolean("isAdult", false) == true ||
                raw?.optBoolean("adult", false) == true
        }
}

/** One repository document (either a mega-repo or a plain repo). */
data class SkyRepoDoc(
    val name: String,
    val packageName: String?,
    val description: String,
    /** Mega-repo: child repo.json URLs. */
    val repos: List<String>,
    /** Plain repo: plugins.json URLs. */
    val pluginLists: List<String>,
    val rawJson: String,
) {
    val isMega: Boolean get() = repos.isNotEmpty()
}

/** A concrete repo expanded to its plugin listings. */
data class SkyRepoListing(
    /** The concrete repo.json URL that owns these plugins. */
    val repoUrl: String,
    val repoName: String,
    val plugins: List<SkyPluginManifest>,
    val error: String? = null,
)

// ---- runtime data shapes returned by plugin JS ----

data class SkyCast(
    val name: String,
    val role: String?,
    val image: String?,
)

data class SkyEpisode(
    val name: String?,
    val season: Int?,
    val episode: Int?,
    /** Opaque to the host — plugins may store a JSON array here. */
    val url: String,
    val posterUrl: String?,
    val description: String?,
    val airDate: String?,
)

data class SkyItem(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val bannerUrl: String?,
    val logoUrl: String?,
    val description: String?,
    /** Raw plugin type string ("movie", "series", "TvSeries", ...). */
    val type: String?,
    val year: Int?,
    /** Raw score value (number or numeric string), out of 10. */
    val score: Double?,
    val durationText: String?,
    val tags: List<String>,
    val isAdult: Boolean,
    val cast: List<SkyCast>,
    val trailerUrls: List<String>,
    val episodes: List<SkyEpisode>,
    val recommendations: List<SkyItem>,
    val syncData: Map<String, String>,
    val contentRating: String?,
)

data class SkyStreamResult(
    val url: String,
    val source: String?,
    val name: String?,
    /** Raw quality value: int height or a string like "1080p"/"4K". */
    val qualityRaw: Any?,
    val headers: Map<String, String>,
    val isM3U8: Boolean,
    val type: String?,
    /** (label, url) pairs. */
    val subtitles: List<Pair<String, String>>,
    /** DRM fields have no equivalent in our ExtractorLink model (v1: drop). */
    val hasDrm: Boolean,
)

object SkyParse {

    fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optString(i).takeIf { it.isNotBlank() }
        }
    }

    private fun stringListField(obj: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            val arr = obj.optJSONArray(key)
            if (arr != null) return arr.toStringList()
            val single = obj.optStringOrNull(key)
            if (single != null) return listOf(single)
        }
        return emptyList()
    }

    // ---- repository chain ----

    fun parseRepoDoc(json: JSONObject): SkyRepoDoc? {
        val repos = json.optJSONArray("repos").toStringList()
        val pluginLists = json.optJSONArray("pluginLists").toStringList()
        if (repos.isEmpty() && pluginLists.isEmpty()) return null
        return SkyRepoDoc(
            name = json.optStringOrNull("name") ?: "SkyStream repository",
            packageName = json.optStringOrNull("packageName")
                ?: json.optStringOrNull("id"),
            description = json.optString("description"),
            repos = repos,
            pluginLists = pluginLists,
            rawJson = json.toString(),
        )
    }

    /** plugins.json root is a bare array; tolerate a wrapping object too. */
    fun parsePluginList(root: Any?): List<SkyPluginManifest> {
        val arr = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("plugins")
                ?: root.optJSONArray("items")
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<SkyPluginManifest>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            parsePluginManifest(entry)?.let { out += it }
        }
        return out
    }

    // ---- plugin manifest (listing entry or plugin.json) ----

    fun parsePluginManifest(json: JSONObject): SkyPluginManifest? {
        val packageName = json.optStringOrNull("packageName")
            ?: json.optStringOrNull("id") ?: return null
        val categories = stringListField(json, "categories", "types", "tvTypes")
        val languages = stringListField(json, "languages", "language", "lang")
        val subs = mutableListOf<SkySubProvider>()
        val subArr = json.optJSONArray("providers")
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val s = subArr.optJSONObject(i) ?: continue
                val id = s.optStringOrNull("id") ?: continue
                subs += SkySubProvider(
                    id = id,
                    name = s.optStringOrNull("name") ?: id,
                    baseUrl = s.optStringOrNull("baseUrl"),
                )
            }
        }
        val settings = parseSettingsSchema(json.optJSONArray("settings"))
        return SkyPluginManifest(
            packageName = packageName,
            name = json.optStringOrNull("name") ?: packageName,
            version = json.optInt("version", 0),
            baseUrl = json.optString("baseUrl"),
            description = json.optString("description"),
            iconUrl = json.optStringOrNull("iconUrl")
                ?: json.optStringOrNull("icon"),
            authors = json.optJSONArray("authors").toStringList(),
            languages = languages,
            categories = categories,
            downloadUrl = json.optString("url"),
            sha256 = (json.optStringOrNull("sha256")
                ?: json.optStringOrNull("hash"))
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
            status = json.optInt("status", 1),
            subProviders = subs,
            settings = settings,
            hasSettingsSchema = settings.isNotEmpty(),
            rawJson = json.toString(),
        )
    }

    fun parseSettingsSchema(arr: JSONArray?): List<SkySettingDef> {
        if (arr == null) return emptyList()
        val out = mutableListOf<SkySettingDef>()
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val key = f.optStringOrNull("key") ?: f.optStringOrNull("id")
                ?: continue
            val type = when (f.optString("type", "text").lowercase()) {
                "bool", "boolean", "switch", "toggle" -> "toggle"
                "select", "dropdown", "list" -> "select"
                "toggle_group", "togglegroup", "multi_toggle",
                "multitoggle", "switch_group" -> "toggleGroup"
                "url", "domain" -> "url"
                else -> "text"
            }
            val options = mutableListOf<SkySettingOption>()
            val rawOptions = f.optJSONArray("options")
            if (rawOptions != null) {
                for (j in 0 until rawOptions.length()) {
                    when (val entry = rawOptions.opt(j)) {
                        is JSONObject -> {
                            val value = entry.optString("value")
                            if (value.isNotEmpty()) {
                                options += SkySettingOption(
                                    label = entry.optStringOrNull("label") ?: value,
                                    value = value,
                                )
                            }
                        }
                        is String -> options += SkySettingOption(entry, entry)
                    }
                }
            }
            val defaultRaw: Any? = when {
                f.has("defaultValue") && !f.isNull("defaultValue") ->
                    f.opt("defaultValue")
                f.has("default") && !f.isNull("default") -> f.opt("default")
                f.has("value") && !f.isNull("value") -> f.opt("value")
                else -> null
            }
            val baseFlag = if (f.has("isBaseUrl")) {
                f.opt("isBaseUrl")
            } else {
                f.opt("baseUrl")
            }
            out += SkySettingDef(
                key = key,
                title = f.optStringOrNull("title")
                    ?: f.optStringOrNull("name") ?: key,
                description = f.optStringOrNull("description"),
                type = type,
                defaultRaw = defaultRaw,
                options = options,
                isBaseUrl = baseFlag == true || key == "base_url" || key == "baseUrl",
            )
        }
        return out
    }

    // ---- plugin JS return shapes ----

    fun parseItem(json: JSONObject): SkyItem? {
        val title = json.optStringOrNull("title")
            ?: json.optStringOrNull("name") ?: return null
        val url = json.optString("url")
        if (url.isBlank()) return null
        val cast = mutableListOf<SkyCast>()
        val castArr = json.optJSONArray("cast")
        if (castArr != null) {
            for (i in 0 until castArr.length()) {
                val c = castArr.optJSONObject(i) ?: continue
                val name = c.optStringOrNull("name") ?: continue
                cast += SkyCast(
                    name = name,
                    role = c.optStringOrNull("role")
                        ?: c.optStringOrNull("character"),
                    image = c.optStringOrNull("image")
                        ?: c.optStringOrNull("imageUrl"),
                )
            }
        }
        val episodes = mutableListOf<SkyEpisode>()
        val epArr = json.optJSONArray("episodes")
        if (epArr != null) {
            for (i in 0 until epArr.length()) {
                val e = epArr.optJSONObject(i) ?: continue
                val epUrl = e.optString("url")
                if (epUrl.isBlank()) continue
                episodes += SkyEpisode(
                    name = e.optStringOrNull("name")
                        ?: e.optStringOrNull("title"),
                    season = if (e.has("season") && !e.isNull("season")) {
                        e.optInt("season")
                    } else {
                        null
                    },
                    episode = if (e.has("episode") && !e.isNull("episode")) {
                        e.optInt("episode")
                    } else {
                        null
                    },
                    url = epUrl,
                    posterUrl = e.optStringOrNull("posterUrl")
                        ?: e.optStringOrNull("thumbnail"),
                    description = e.optStringOrNull("description")
                        ?: e.optStringOrNull("overview"),
                    airDate = e.optStringOrNull("airDate")
                        ?: e.optStringOrNull("releaseDate"),
                )
            }
        }
        val trailers = mutableListOf<String>()
        val trArr = json.optJSONArray("trailers")
        if (trArr != null) {
            for (i in 0 until trArr.length()) {
                when (val t = trArr.opt(i)) {
                    is String -> if (t.isNotBlank()) trailers += t
                    is JSONObject -> t.optStringOrNull("url")?.let { trailers += it }
                }
            }
        }
        val recommendations = mutableListOf<SkyItem>()
        val recArr = json.optJSONArray("recommendations")
        if (recArr != null) {
            for (i in 0 until recArr.length()) {
                recArr.optJSONObject(i)?.let { rec ->
                    parseItemShallow(rec)?.let { recommendations += it }
                }
            }
        }
        val sync = mutableMapOf<String, String>()
        val syncObj = json.optJSONObject("syncData")
        if (syncObj != null) {
            val keys = syncObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                syncObj.optStringOrNull(k)?.let { sync[k.lowercase()] = it }
            }
        }
        val scoreRaw = json.opt("score") ?: json.opt("rating")
        val score = when (scoreRaw) {
            is Number -> scoreRaw.toDouble()
            is String -> scoreRaw.toDoubleOrNull()
            else -> null
        }
        val durationRaw = json.opt("duration")
        return SkyItem(
            title = title,
            url = url,
            posterUrl = json.optStringOrNull("posterUrl")
                ?: json.optStringOrNull("poster"),
            bannerUrl = json.optStringOrNull("bannerUrl")
                ?: json.optStringOrNull("backdropUrl")
                ?: json.optStringOrNull("background"),
            logoUrl = json.optStringOrNull("logoUrl"),
            description = json.optStringOrNull("description")
                ?: json.optStringOrNull("overview")
                ?: json.optStringOrNull("plot"),
            type = json.optStringOrNull("type"),
            year = when (val y = json.opt("year")) {
                is Number -> y.toInt()
                is String -> y.filter { it.isDigit() }.take(4).toIntOrNull()
                else -> null
            },
            score = score,
            durationText = when (durationRaw) {
                is Number -> durationRaw.toInt().toString()
                is String -> durationRaw
                else -> null
            },
            tags = json.optJSONArray("tags").toStringList()
                .ifEmpty { json.optJSONArray("genres").toStringList() },
            isAdult = json.optBoolean("isAdult", false) ||
                json.optBoolean("adult", false),
            cast = cast,
            trailerUrls = trailers,
            episodes = episodes,
            recommendations = recommendations,
            syncData = sync,
            contentRating = json.optStringOrNull("contentRating"),
        )
    }

    /** Recommendations nested inside an item: no recursion into their own. */
    private fun parseItemShallow(json: JSONObject): SkyItem? {
        val stripped = JSONObject(json.toString())
        stripped.remove("recommendations")
        stripped.remove("episodes")
        return parseItem(stripped)
    }

    fun parseStream(json: JSONObject): SkyStreamResult? {
        val url = json.optStringOrNull("url") ?: return null
        val headers = mutableMapOf<String, String>()
        val headerObj = json.optJSONObject("headers")
        if (headerObj != null) {
            val keys = headerObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headerObj.optStringOrNull(k)?.let { headers[k] = it }
            }
        }
        val subs = mutableListOf<Pair<String, String>>()
        val subArr = json.optJSONArray("subtitles")
        if (subArr != null) {
            for (i in 0 until subArr.length()) {
                val s = subArr.optJSONObject(i) ?: continue
                val subUrl = s.optStringOrNull("url") ?: continue
                val label = s.optStringOrNull("label")
                    ?: s.optStringOrNull("lang") ?: "Unknown"
                subs += label to subUrl
            }
        }
        val hasDrm = !json.optStringOrNull("drmKid").isNullOrBlank() ||
            !json.optStringOrNull("drmKey").isNullOrBlank() ||
            !json.optStringOrNull("licenseUrl").isNullOrBlank() ||
            !json.optStringOrNull("drmLicenseUrl").isNullOrBlank()
        return SkyStreamResult(
            url = url,
            source = json.optStringOrNull("source"),
            name = json.optStringOrNull("name"),
            qualityRaw = if (json.has("quality") && !json.isNull("quality")) {
                json.opt("quality")
            } else {
                null
            },
            headers = headers,
            isM3U8 = json.optBoolean("isM3U8", false),
            type = json.optStringOrNull("type"),
            subtitles = subs,
            hasDrm = hasDrm,
        )
    }
}
