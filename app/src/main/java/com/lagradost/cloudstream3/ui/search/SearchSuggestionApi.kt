package com.lagradost.cloudstream3.ui.search

import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.NiceResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API for fetching search suggestions from external sources.
 * Uses TheMovieDB API to provide movie/show/anime related suggestions.
 */
object SearchSuggestionApi {
    private const val TMDB_API_URL = "https://api.themoviedb.org/3/search/multi"
    
    @Serializable
    data class TmdbSearchResult(
        @JsonProperty("results") @SerialName("results") val results: List<TmdbSearchItem>?,
    )
    
    @Serializable
    data class TmdbSearchItem(
        @JsonProperty("media_type") @SerialName("media_type") val mediaType: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("name") @SerialName("name") val name: String?,
        @JsonProperty("original_title") @SerialName("original_title") val originalTitle: String?,
        @JsonProperty("original_name") @SerialName("original_name") val originalName: String?,
    )
    
    /**
     * Fetches search suggestions from TheMovieDB multi search API.
     * Returns suggestions for movies, TV series, and anime.
     *
     * Batch 1: the previously hardcoded TMDB key shipped in this
     * file is removed. Suggestions now use the user's own key from
     * Settings → Account (the same key MetadataResolver uses); with
     * no user key set, suggestions are empty rather than calling
     * TMDB with a key baked into the app. A TMDB Read Access Token
     * (eyJ…) is sent as a Bearer header, a v3 key as a query param
     * — the same split MetadataResolver uses.
     *
     * @param query The search query to get suggestions for
     * @return List of suggestion strings, empty list on failure
     */
    suspend fun getSuggestions(query: String): List<String> {
        if (query.isBlank() || query.length < 2) return emptyList()
        val userKey = CloudStreamApp.context?.let { ctx ->
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(MetadataResolver.TMDB_KEY_PREF, null)?.trim()
        }.orEmpty()
        if (userKey.isEmpty()) return emptyList()

        return try {
            val response = if (userKey.startsWith("eyJ")) {
                app.get(
                    TMDB_API_URL,
                    params = mapOf(
                        "query" to query,
                        "language" to "en-US"
                    ),
                    headers = mapOf("Authorization" to "Bearer $userKey"),
                    cacheTime = 60 * 24  // Cache for 1 day (cacheUnit default is Minutes)
                )
            } else {
                app.get(
                    TMDB_API_URL,
                    params = mapOf(
                        "api_key" to userKey,
                        "query" to query,
                        "language" to "en-US"
                    ),
                    cacheTime = 60 * 24  // Cache for 1 day (cacheUnit default is Minutes)
                )
            }

            parseSuggestions(response)
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
    
    /**
     * Parses the TMDB search response and extracts movie/TV show titles.
     * Filters to only include movies, TV shows, and anime.
     */
    private suspend fun parseSuggestions(response: NiceResponse): List<String> {
        return try {
            val parsed = response.parsed<TmdbSearchResult>()
            parsed.results
                ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                ?.mapNotNull { it.title ?: it.name }
                ?.distinct()
                ?.take(10)
                ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
}
