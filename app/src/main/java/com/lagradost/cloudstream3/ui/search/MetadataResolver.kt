package com.lagradost.cloudstream3.ui.search

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8.74: render a meta string with every ★ glyph in gold (his
 * request — the rating star beside titles should be yellow). Returns
 * the text unchanged when it contains no star.
 */
fun goldStarText(text: String): CharSequence {
    if (!text.contains('★')) return text
    val spannable = android.text.SpannableString(text)
    var index = text.indexOf('★')
    while (index >= 0) {
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(
                android.graphics.Color.parseColor("#FFC107")
            ),
            index, index + 1,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        index = text.indexOf('★', index + 1)
    }
    return spannable
}

/**
 * Phase 8.56 — universal ratings (his ask): many providers ship no
 * score, so cards and the details page show no rating at all. When
 * the user has entered a TMDB and/or IMDb (OMDb) API key under
 * Settings → Account, this resolves a rating by cleaned title +
 * year in the background and caches it persistently, TitleResolver
 * style. Matching is deliberately conservative — a wrong rating is
 * worse than none: the title must match (normalised) and, when
 * both years are known, agree within a year. No key, no lookup.
 */
object MetadataResolver {
    // Batch 3: v2 invalidates every v1 entry. v1 ratings were
    // cached under substring title matching and kind-less keys,
    // so wrong ratings (remake/sequel, movie-vs-TV collisions)
    // would otherwise be served forever. The v1 file is simply
    // orphaned; items re-resolve on demand under the new keys.
    private const val PREFS = "universal_ratings_v2"
    const val TMDB_KEY_PREF = "tmdb_api_key"
    const val OMDB_KEY_PREF = "omdb_api_key"

    // Batch 3: even a correctly matched rating goes stale
    // (re-ratings, corrected TMDB data). Ratings older than
    // this are dropped on read and re-resolved.
    private const val RATING_TTL_MS = 90L * 24 * 60 * 60 * 1000
    private fun ratingTimestampKey(key: String) = "ts:$key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(3)
    private val inFlight = mutableSetOf<String>()
    private val misses = mutableSetOf<String>()
    private val memory = ConcurrentHashMap<String, String>()
    private val listeners = mutableListOf<(String, String) -> Unit>()

    fun addListener(listener: (String, String) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (String, String) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ---------- Phase 8.88: item metadata (genres / original
    // language / origin country) for real Feed categories. This
    // extends the SAME pipeline — same scope, same semaphore,
    // same title cleaning, same prefs file. Records live under
    // "meta:" + the rating cache key in the same prefs, so
    // caches written by older builds simply lack them until an
    // item re-resolves; the rating value itself is untouched.
    // The TMDB/OMDb lookups below harvest the fields from the
    // very search responses they already fetch (genre_ids,
    // original_language, origin_country / Genre, Language,
    // Country) — no extra round-trip on the rating path. Items
    // the rating path skips (provider score present, rating
    // already cached, or no key at all) are covered by
    // [resolveMeta], which adds the keyless sources TVmaze
    // (TV) and AniList (anime) behind the same limiter. ----------

    data class Meta(
        val genres: List<String>,
        val language: String?,
        val country: String?,
        val anime: Boolean = false
    )

    private val metaMemory = ConcurrentHashMap<String, Meta>()
    private val metaInFlight = mutableSetOf<String>()
    private val metaMisses = mutableSetOf<String>()
    private val metaListeners = mutableListOf<(String) -> Unit>()

    fun addMetaListener(listener: (String) -> Unit) {
        synchronized(metaListeners) { metaListeners.add(listener) }
    }

    fun removeMetaListener(listener: (String) -> Unit) {
        synchronized(metaListeners) { metaListeners.remove(listener) }
    }

    private fun metaToJson(meta: Meta): String {
        val json = JSONObject()
        json.put("g", meta.genres.joinToString("|"))
        meta.language?.let { json.put("l", it) }
        meta.country?.let { json.put("c", it) }
        if (meta.anime) json.put("a", 1)
        return json.toString()
    }

    private fun metaFromJson(raw: String): Meta? = try {
        val json = JSONObject(raw)
        Meta(
            genres = json.optString("g").split("|")
                .filter { it.isNotBlank() },
            language = json.optString("l").ifBlank { null },
            country = json.optString("c").ifBlank { null },
            anime = json.optInt("a", 0) == 1
        )
    } catch (e: Exception) {
        null
    }

    fun metaCached(context: Context?, rawTitle: String, year: Int?, tv: Boolean): Meta? {
        val key = cacheKey(SearchResultBuilder.cleanPosterTitle(rawTitle), year, tv)
        metaMemory[key]?.let { return it }
        if (context == null) return null
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("meta:$key", null)
        val meta = stored?.let { metaFromJson(it) }
        if (meta != null) metaMemory[key] = meta
        return meta
    }

    /** The cached metadata for a search response, if resolved. */
    fun metaCachedFor(context: Context?, response: SearchResponse): Meta? =
        metaCached(context, response.name, yearOf(response), isTv(response))

    private suspend fun storeMeta(context: Context, key: String, meta: Meta) {
        if (meta.genres.isEmpty() && meta.language == null &&
            meta.country == null
        ) {
            return
        }
        if (metaMemory.putIfAbsent(key, meta) != null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("meta:$key", metaToJson(meta)).apply()
        val snapshot = synchronized(metaListeners) { metaListeners.toList() }
        if (snapshot.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                snapshot.forEach { it(key) }
            }
        }
    }

    fun hasAnyKey(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return !prefs.getString(TMDB_KEY_PREF, null).isNullOrBlank() ||
            !prefs.getString(OMDB_KEY_PREF, null).isNullOrBlank()
    }

    // Batch 3: the kind is part of the key — a movie and a TV
    // series with the same normalised title and year must not
    // share one cached rating.
    private fun cacheKey(title: String, year: Int?, tv: Boolean): String =
        normalize(title) + "|" + (year?.toString() ?: "") + "|" +
            if (tv) "tv" else "movie"

    /** The listener/cache key for a raw provider title + year. */
    fun keyFor(rawTitle: String, year: Int?, tv: Boolean): String =
        cacheKey(SearchResultBuilder.cleanPosterTitle(rawTitle), year, tv)

    /** The listener/cache key for a search response. */
    fun keyFor(response: SearchResponse): String =
        cacheKey(
            SearchResultBuilder.cleanPosterTitle(response.name),
            yearOf(response), isTv(response)
        )

    private fun normalize(title: String): String =
        title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** The year a search response carries, where the class has one. */
    fun yearOf(response: SearchResponse): Int? = when (response) {
        is MovieSearchResponse -> response.year
        is TvSeriesSearchResponse -> response.year
        is AnimeSearchResponse -> response.year
        else -> null
    }

    fun isTv(response: SearchResponse): Boolean = isTv(response.type)

    fun isTv(type: TvType?): Boolean = when (type) {
        TvType.TvSeries, TvType.AsianDrama, TvType.Anime, TvType.OVA -> true
        else -> false
    }

    /** Batch 3: true when the stored rating for [key] is past
     * its TTL (or, equivalently, its timestamp is missing only
     * for entries this build never wrote — those are treated as
     * fresh so a partial store path cannot evict on every read).
     * Expired entries are evicted, never served. */
    private fun isRatingExpired(context: Context, key: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(ratingTimestampKey(key), 0L)
        if (stored <= 0L) return false
        return System.currentTimeMillis() - stored > RATING_TTL_MS
    }

    private fun evictRating(context: Context, key: String) {
        memory.remove(key)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).remove(ratingTimestampKey(key)).apply()
    }

    fun cached(context: Context?, rawTitle: String, year: Int?, tv: Boolean): String? {
        val key = cacheKey(SearchResultBuilder.cleanPosterTitle(rawTitle), year, tv)
        if (context != null && isRatingExpired(context, key)) {
            evictRating(context, key)
            return null
        }
        memory[key]?.let { return it }
        if (context == null) return null
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
        if (!stored.isNullOrBlank()) memory[key] = stored
        return stored
    }

    /** Convenience for card binds: provider score wins, else cache. */
    fun cachedFor(context: Context?, response: SearchResponse): String? {
        response.score?.toStringNull(0.1, 10, 1)?.let { return it }
        return cached(context, response.name, yearOf(response), isTv(response))
    }

    fun resolve(context: Context, response: SearchResponse) {
        if (response.score != null) return
        resolve(context, response.name, yearOf(response), isTv(response))
    }

    fun resolve(context: Context, rawTitle: String, year: Int?, tv: Boolean) {
        val title = SearchResultBuilder.cleanPosterTitle(rawTitle)
        if (title.isBlank() || TitleResolver.isIdLike(title)) return
        if (!hasAnyKey(context)) return
        val key = cacheKey(title, year, tv)
        // Batch 3: the early return must not serve an expired
        // rating — evict it and fall through to re-resolve.
        if (isRatingExpired(context, key)) {
            evictRating(context, key)
        } else if (memory.containsKey(key)) return
        synchronized(misses) { if (key in misses) return }
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        val appContext = context.applicationContext
        scope.launch {
            try {
                semaphore.withPermit {
                    val (rating, meta) = lookup(appContext, title, year, tv)
                    if (meta != null) {
                        storeMeta(appContext, key, meta)
                    }
                    if (rating != null) {
                        memory[key] = rating
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(key, rating)
                            .putLong(
                                ratingTimestampKey(key),
                                System.currentTimeMillis()
                            ).apply()
                        val snapshot = synchronized(listeners) { listeners.toList() }
                        withContext(Dispatchers.Main) {
                            snapshot.forEach { it(key, rating) }
                        }
                    } else {
                        synchronized(misses) { misses.add(key) }
                    }
                }
            } catch (e: Exception) {
                synchronized(misses) { misses.add(key) }
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    private suspend fun lookup(
        context: Context, title: String, year: Int?, tv: Boolean
    ): Pair<String?, Meta?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var meta: Meta? = null
        val tmdbKey = prefs.getString(TMDB_KEY_PREF, null)?.trim().orEmpty()
        if (tmdbKey.isNotEmpty()) {
            // Phase 8.71: providers regularly mislabel the kind (an
            // anime-tagged film, a series listed as a movie) — if the
            // requested kind misses, try the other one before giving
            // up. His report: key set, yet many providers showed no
            // rating at all.
            val first = tmdb(tmdbKey, title, year, tv)
            meta = first.second
            if (first.first != null) return first.first to meta
            val second = tmdb(tmdbKey, title, year, !tv)
            if (meta == null) meta = second.second
            if (second.first != null) return second.first to meta
        }
        val omdbKey = prefs.getString(OMDB_KEY_PREF, null)?.trim().orEmpty()
        if (omdbKey.isNotEmpty()) {
            val omdbResult = omdb(omdbKey, title, year)
            if (meta == null) meta = omdbResult.second
            if (omdbResult.first != null) return omdbResult.first to meta
        }
        return null to meta
    }

    /**
     * Phase 8.57: fetches go through the app's own HTTP client
     * (the same stack the providers use successfully) instead of
     * a raw HttpURLConnection — his report: a valid key fetched
     * nothing at all, and the raw connection path is the one
     * thing that differs from every working request in the app.
     * A TMDB "Read Access Token" (starts with eyJ) is sent as a
     * Bearer header; a v3 API key goes in the query string.
     */
    private suspend fun getJson(url: String, bearer: String?): JSONObject? {
        return try {
            val response = app.get(
                url,
                headers = if (bearer != null) {
                    mapOf("Authorization" to "Bearer $bearer")
                } else {
                    emptyMap()
                }
            )
            if (response.code !in 200..299) null else JSONObject(response.text)
        } catch (e: Exception) {
            null
        }
    }

    private fun tmdbAuth(key: String): Pair<String, String?> =
        if (key.startsWith("eyJ")) "" to key else key to null

    /** Settings key test: 200 = works, 401/403 = rejected,
     *  anything else / -1 = could not verify. */
    suspend fun testTmdbKey(key: String): Int {
        val (apiKey, bearer) = tmdbAuth(key.trim())
        val url = if (bearer != null) {
            "https://api.themoviedb.org/3/configuration"
        } else {
            "https://api.themoviedb.org/3/configuration?api_key=$apiKey"
        }
        return try {
            val response = app.get(
                url,
                headers = if (bearer != null) {
                    mapOf("Authorization" to "Bearer $bearer")
                } else {
                    emptyMap()
                }
            )
            response.code
        } catch (e: Exception) {
            -1
        }
    }

    /** Settings key test for OMDb: true = the key answers. */
    suspend fun testOmdbKey(key: String): Boolean {
        val json = getJson(
            "https://www.omdbapi.com/?apikey=${key.trim()}&t=Inception",
            null
        ) ?: return false
        return json.optString("Response") == "True"
    }

    // Batch 3: exact normalised equality only. The old
    // substring rule let "Batman" match "The Batman" and a
    // title match a sequel/remake, and the first such hit was
    // then cached persistently.
    private fun titlesMatch(candidate: String, wanted: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b
    }

    private suspend fun tmdb(
        apiKey: String, title: String, year: Int?, tv: Boolean
    ): Pair<String?, Meta?> {
        // Strict pass first: year-filtered search, year must agree.
        val strict = tmdbOnce(apiKey, title, year, tv, enforceYear = true)
        if (strict.first != null) return strict
        // Phase 8.71: a wrong provider year makes TMDB's own year
        // filter return zero results, so retry with an unfiltered
        // query — but Batch 3 keeps the ±1-year gate on the
        // candidate's own date, via yearGate. The loose pass must
        // never accept a different-year remake/sequel on title
        // alone (now exact-matched, year-gated).
        if (year != null) {
            val loose = tmdbOnce(
                apiKey, title, null, tv,
                enforceYear = true, yearGate = year
            )
            return loose.first to (loose.second ?: strict.second)
        }
        return strict
    }

    private suspend fun tmdbOnce(
        apiKey: String,
        title: String,
        year: Int?,
        tv: Boolean,
        enforceYear: Boolean,
        // Batch 3: the year the candidate's own date is gated
        // against. Normally the query year; the loose pass
        // queries unfiltered (year = null) but still gates
        // against the wanted year, so it cannot accept a
        // different-year remake/sequel.
        yearGate: Int? = null
    ): Pair<String?, Meta?> {
        val kind = if (tv) "tv" else "movie"
        val query = URLEncoder.encode(title, "UTF-8")
        val yearParam = when {
            year == null -> ""
            tv -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val (keyParam, bearer) = tmdbAuth(apiKey)
        val authParam = if (bearer != null) "" else "api_key=$keyParam&"
        val json = getJson(
            "https://api.themoviedb.org/3/search/$kind" +
                "?$authParam" + "query=$query$yearParam",
            bearer
        ) ?: return null to null
        val results = json.optJSONArray("results") ?: return null to null
        // Phase 8.88: the metadata of the first candidate that
        // passes the title + year gates is harvested from this
        // same response — the vote gate below only judges the
        // rating, genre/language/country need no votes.
        var harvested: Meta? = null
        for (i in 0 until minOf(results.length(), 5)) {
            val item = results.optJSONObject(i) ?: continue
            val candidate = item.optString(
                if (tv) "name" else "title",
                item.optString(if (tv) "original_name" else "original_title")
            )
            if (!titlesMatch(candidate, title)) continue
            val dateText = item.optString(
                if (tv) "first_air_date" else "release_date"
            )
            val candidateYear = dateText.take(4).toIntOrNull()
            val gateYear = yearGate ?: year
            if (enforceYear && gateYear != null && candidateYear != null &&
                kotlin.math.abs(candidateYear - gateYear) > 1
            ) continue
            if (harvested == null) harvested = metaFromTmdbItem(item, tv)
            val votes = item.optInt("vote_count", 0)
            val average = item.optDouble("vote_average", 0.0)
            if (votes < 10 || average <= 0.0) continue
            return String.format(Locale.US, "%.1f", average) to harvested
        }
        return null to harvested
    }

    /** Phase 8.88: genres / language / country off one TMDB
     *  search result. genre_ids map through the standard TMDB
     *  genre tables for the kind. */
    private fun metaFromTmdbItem(item: JSONObject, tv: Boolean): Meta {
        val table = if (tv) TMDB_TV_GENRES else TMDB_MOVIE_GENRES
        val ids = item.optJSONArray("genre_ids")
        val genres = mutableListOf<String>()
        if (ids != null) {
            for (i in 0 until ids.length()) {
                table[ids.optInt(i)]?.let { genres += it }
            }
        }
        val countries = item.optJSONArray("origin_country")
        val country = if (countries != null && countries.length() > 0) {
            countries.optString(0).ifBlank { null }
        } else {
            null
        }
        return Meta(
            genres = genres,
            language = item.optString("original_language")
                .ifBlank { null }?.lowercase(Locale.US),
            country = country?.uppercase(Locale.US)
        )
    }

    private suspend fun omdb(
        apiKey: String, title: String, year: Int?
    ): Pair<String?, Meta?> {
        val first = omdbOnce(apiKey, title, year)
        if (first.first != null) return first
        // Phase 8.71: same wrong-year trap as TMDB - retry yearless,
        // but Batch 3 still gates the returned Year within ±1, as
        // with the TMDB loose pass.
        if (year != null) {
            val loose = omdbOnce(apiKey, title, null, yearGate = year)
            return loose.first to (loose.second ?: first.second)
        }
        return first
    }

    private suspend fun omdbOnce(
        apiKey: String, title: String, year: Int?,
        yearGate: Int? = null
    ): Pair<String?, Meta?> {
        val query = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (year != null) "&y=$year" else ""
        val json = getJson(
            "https://www.omdbapi.com/?apikey=$apiKey&t=$query$yearParam",
            null
        ) ?: return null to null
        if (json.optString("Response") != "True") return null to null
        val candidate = json.optString("Title")
        if (!titlesMatch(candidate, title)) return null to null
        // Batch 3: OMDb returns a single best match for an exact
        // title, which may still be a different-year remake —
        // gate its Year within ±1 of the wanted year (the query
        // year itself, when present, already filters server-side).
        val gateYear = yearGate ?: year
        val returnedYear = json.optString("Year").take(4).toIntOrNull()
        if (gateYear != null && returnedYear != null &&
            kotlin.math.abs(returnedYear - gateYear) > 1
        ) return null to null
        // Phase 8.88: the same payload carries Genre / Language /
        // Country — harvest them alongside the rating.
        val meta = metaFromOmdb(json)
        val rating = json.optString("imdbRating")
        if (rating.isBlank() || rating == "N/A") return null to meta
        return rating to meta
    }

    /** Phase 8.88: OMDb speaks in full names ("Korean",
     *  "South Korea", "Action, Drama") — map to the ISO codes
     *  and genre list the category layer works with. */
    private fun metaFromOmdb(json: JSONObject): Meta {
        fun first(csv: String): String? =
            csv.split(",").firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() && it != "N/A" }
        val genres = json.optString("Genre").split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "N/A" }
        return Meta(
            genres = genres,
            language = first(json.optString("Language"))
                ?.let { languageIso(it) },
            country = first(json.optString("Country"))
                ?.let { countryIso(it) }
        )
    }

    // ---------- Phase 8.88: metadata-only resolution ----------

    /**
     * Resolve genres / language / country for an item the rating
     * path may never touch (provider score present, rating
     * already cached, or no API key configured — TVmaze and
     * AniList need none). Same guards, same semaphore, same
     * persistent cache as ratings; misses are session-only, like
     * rating misses. The feed calls this in the background and
     * re-renders its categories when [addMetaListener] fires.
     */
    fun resolveMeta(context: Context, response: SearchResponse) {
        val title = SearchResultBuilder.cleanPosterTitle(response.name)
        if (title.isBlank() || TitleResolver.isIdLike(title)) return
        val year = yearOf(response)
        if (metaCached(context, response.name, year, isTv(response)) != null) return
        val key = cacheKey(title, year, isTv(response))
        synchronized(metaMisses) { if (key in metaMisses) return }
        synchronized(metaInFlight) { if (!metaInFlight.add(key)) return }
        val appContext = context.applicationContext
        val tv = isTv(response)
        val type = response.type ?: TvType.Others
        scope.launch {
            try {
                semaphore.withPermit {
                    val meta = lookupMeta(appContext, title, year, tv, type)
                    if (meta != null) {
                        storeMeta(appContext, key, meta)
                    } else {
                        synchronized(metaMisses) { metaMisses.add(key) }
                    }
                }
            } catch (e: Exception) {
                synchronized(metaMisses) { metaMisses.add(key) }
            } finally {
                synchronized(metaInFlight) { metaInFlight.remove(key) }
            }
        }
    }

    /** Source order: TMDB (key) → TVmaze (TV, keyless) →
     *  AniList (anime, keyless) → OMDb (existing key path only).
     *  The TMDB step reuses the rating pipeline's own search —
     *  [tmdb] harvests the metadata from its results — so an
     *  item costs the same search call either way. */
    private suspend fun lookupMeta(
        context: Context, title: String, year: Int?, tv: Boolean, type: TvType
    ): Meta? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val tmdbKey = prefs.getString(TMDB_KEY_PREF, null)?.trim().orEmpty()
        if (tmdbKey.isNotEmpty()) {
            tmdb(tmdbKey, title, year, tv).second?.let { return it }
            tmdb(tmdbKey, title, year, !tv).second?.let { return it }
        }
        if (tv) {
            tvmaze(title)?.let { return it }
        }
        if (type == TvType.Anime || type == TvType.OVA) {
            anilist(title)?.let { return it }
        }
        val omdbKey = prefs.getString(OMDB_KEY_PREF, null)?.trim().orEmpty()
        if (omdbKey.isNotEmpty()) {
            omdb(omdbKey, title, year).second?.let { return it }
        }
        return null
    }

    /** TVmaze (no key): genres are plain names, language is a
     *  full name, the country is already an ISO code on the
     *  network / web channel. First title match wins, exactly
     *  the discipline the TMDB pass uses; no match, no data. */
    private suspend fun tvmaze(title: String): Meta? {
        val query = URLEncoder.encode(title, "UTF-8")
        val response = try {
            app.get("https://api.tvmaze.com/search/shows?q=$query")
        } catch (e: Exception) {
            return null
        }
        if (response.code !in 200..299) return null
        val results = try {
            JSONArray(response.text)
        } catch (e: Exception) {
            return null
        }
        for (i in 0 until minOf(results.length(), 5)) {
            val show = results.optJSONObject(i)?.optJSONObject("show")
                ?: continue
            if (!titlesMatch(show.optString("name"), title)) continue
            val genreArray = show.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genreArray != null) {
                for (j in 0 until genreArray.length()) {
                    genreArray.optString(j)
                        .takeIf { it.isNotBlank() }?.let { genres += it }
                }
            }
            val country = show.optJSONObject("network")
                ?.optJSONObject("country")?.optString("code")
                ?.ifBlank { null }
                ?: show.optJSONObject("webChannel")
                    ?.optJSONObject("country")?.optString("code")
                    ?.ifBlank { null }
            return Meta(
                genres = genres,
                language = languageIso(show.optString("language")),
                country = country?.uppercase(Locale.US),
                anime = genres.any { it.equals("anime", true) } ||
                    show.optString("type").equals("Animation", true)
            )
        }
        return null
    }

    /** AniList (no key): GraphQL over the same form-post shape
     *  the app's own AniList sync provider uses. Gives genres +
     *  country of origin; the language is derived from the
     *  country (JP → ja, CN → zh, …). */
    private suspend fun anilist(title: String): Meta? {
        val query = "query (\$search: String) { Page(perPage: 5) { " +
            "media(search: \$search, type: ANIME, sort: SEARCH_MATCH) { " +
            "title { romaji english native } genres countryOfOrigin } } }"
        val variables = JSONObject().put("search", title).toString()
        val response = try {
            app.post(
                "https://graphql.anilist.co/",
                data = mapOf("query" to query, "variables" to variables)
            )
        } catch (e: Exception) {
            return null
        }
        if (response.code !in 200..299) return null
        val media = try {
            JSONObject(response.text).optJSONObject("data")
                ?.optJSONObject("Page")?.optJSONArray("media")
        } catch (e: Exception) {
            null
        } ?: return null
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            val titles = item.optJSONObject("title")
            val candidates = listOf(
                titles?.optString("english").orEmpty(),
                titles?.optString("romaji").orEmpty(),
                titles?.optString("native").orEmpty()
            )
            if (candidates.none {
                    it.isNotBlank() && titlesMatch(it, title)
                }
            ) {
                continue
            }
            val genreArray = item.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genreArray != null) {
                for (j in 0 until genreArray.length()) {
                    genreArray.optString(j)
                        .takeIf { it.isNotBlank() }?.let { genres += it }
                }
            }
            val country = item.optString("countryOfOrigin")
                .ifBlank { null }?.uppercase(Locale.US)
            return Meta(
                genres = genres,
                language = languageForCountry(country),
                country = country,
                anime = true
            )
        }
        return null
    }

    private fun languageIso(name: String): String? =
        LANGUAGE_NAME_TO_ISO[name.trim().lowercase(Locale.US)]

    private fun countryIso(name: String): String? =
        COUNTRY_NAME_TO_ISO[name.trim().lowercase(Locale.US)]

    private fun languageForCountry(country: String?): String? =
        when (country) {
            "JP" -> "ja"
            "CN", "TW", "HK" -> "zh"
            "KR" -> "ko"
            else -> null
        }

    private val TMDB_MOVIE_GENRES: Map<Int, String> = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation",
        35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy",
        36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance",
        878 to "Science Fiction", 10770 to "TV Movie",
        53 to "Thriller", 10752 to "War", 37 to "Western"
    )

    private val TMDB_TV_GENRES: Map<Int, String> = mapOf(
        10759 to "Action & Adventure", 16 to "Animation",
        35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 10762 to "Kids",
        9648 to "Mystery", 10763 to "News", 10764 to "Reality",
        10765 to "Sci-Fi & Fantasy", 10766 to "Soap",
        10767 to "Talk", 10768 to "War & Politics", 37 to "Western"
    )

    /** Full-name → ISO 639-1, for TVmaze / OMDb language fields. */
    private val LANGUAGE_NAME_TO_ISO: Map<String, String> = mapOf(
        "english" to "en", "korean" to "ko", "japanese" to "ja",
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh",
        "hindi" to "hi", "thai" to "th", "turkish" to "tr",
        "spanish" to "es", "castilian" to "es", "french" to "fr",
        "german" to "de", "italian" to "it", "portuguese" to "pt",
        "russian" to "ru", "arabic" to "ar", "bengali" to "bn",
        "bangla" to "bn", "tamil" to "ta", "telugu" to "te",
        "malayalam" to "ml", "kannada" to "kn", "punjabi" to "pa",
        "marathi" to "mr", "urdu" to "ur", "indonesian" to "id",
        "malay" to "ms", "vietnamese" to "vi", "filipino" to "tl",
        "tagalog" to "tl", "dutch" to "nl", "swedish" to "sv",
        "danish" to "da", "norwegian" to "no", "finnish" to "fi",
        "polish" to "pl", "ukrainian" to "uk", "greek" to "el",
        "hebrew" to "he", "persian" to "fa", "hungarian" to "hu",
        "czech" to "cs", "romanian" to "ro"
    )

    /** Full-name → ISO 3166-1, for OMDb country fields. */
    private val COUNTRY_NAME_TO_ISO: Map<String, String> = mapOf(
        "united states" to "US", "united states of america" to "US",
        "usa" to "US", "united kingdom" to "GB", "uk" to "GB",
        "south korea" to "KR", "korea" to "KR", "japan" to "JP",
        "china" to "CN", "india" to "IN", "thailand" to "TH",
        "turkey" to "TR", "türkiye" to "TR", "taiwan" to "TW",
        "hong kong" to "HK", "france" to "FR", "germany" to "DE",
        "spain" to "ES", "italy" to "IT", "canada" to "CA",
        "australia" to "AU", "philippines" to "PH",
        "indonesia" to "ID", "vietnam" to "VN", "russia" to "RU",
        "brazil" to "BR", "mexico" to "MX", "netherlands" to "NL",
        "sweden" to "SE", "denmark" to "DK", "norway" to "NO",
        "finland" to "FI", "poland" to "PL", "ukraine" to "UA",
        "pakistan" to "PK", "bangladesh" to "BD", "malaysia" to "MY",
        "singapore" to "SG", "new zealand" to "NZ",
        "ireland" to "IE", "egypt" to "EG", "nigeria" to "NG",
        "south africa" to "ZA", "argentina" to "AR", "chile" to "CL",
        "colombia" to "CO", "peru" to "PE", "greece" to "GR",
        "portugal" to "PT", "belgium" to "BE", "austria" to "AT",
        "switzerland" to "CH", "israel" to "IL", "iran" to "IR"
    )
}
