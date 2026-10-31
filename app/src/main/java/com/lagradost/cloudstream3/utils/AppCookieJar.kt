package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal persistent cookie store (8.83 part 2): one RFC 6265
 * cookie jar the APP owns, so hosts whose plugins/scrapers/providers
 * never think about cookies still get correct session behaviour —
 * and the cookies survive process death and bridge-session eviction.
 *
 * Parsing and URL matching are delegated to OkHttp: it parses every
 * Set-Cookie header (via [Cookie.parse]) before handing the cookies
 * to [saveFromResponse], and [Cookie.matches] implements the RFC
 * matching rules used by [loadForRequest]:
 * host-only cookies (no Domain attribute) only ever return to the
 * exact host that set them, Domain cookies match subdomains, paths
 * prefix-match, expired cookies are dropped and Secure cookies only
 * travel over https. OkHttp's own bridge interceptor supplies the
 * second half of the contract: it calls [loadForRequest] only when
 * the request does not already carry a Cookie header, so a cookie a
 * plugin/provider set explicitly always wins over the jar.
 *
 * Instances are namespaced ("sky:<packageName>", "nuvio:<id>",
 * "native", ...): namespaces are fully isolated and persist to one
 * SharedPreferences string each. Batch 1: only PERSISTENT cookies
 * (ones with a real expiry) are persisted. Session cookies stay
 * in-memory for the life of the process and are never written to
 * disk or resurrected with an open-ended expiry — previously a
 * session cookie was restored with expiresAt=Long.MAX_VALUE, i.e.
 * silently promoted to a permanent credential. A plugin whose
 * verify/handshake cookie must survive a restart will now have to
 * redo its handshake; that is the intended trade-off.
 *
 * Thread safety: every store access is synchronized on one lock.
 * The store is capped per namespace; on overflow the oldest entries
 * go first (expired ones are evicted before any live cookie).
 */
class AppCookieJar(
    private val contextProvider: () -> Context?,
    private val namespace: String,
) : CookieJar {

    constructor(context: Context, namespace: String) : this({ context }, namespace)

    private val lock = Any()

    /** Insertion-ordered store, key "domain|path|name". */
    private val store = LinkedHashMap<String, Cookie>()

    @Volatile
    private var loaded = false

    private val prefsKey =
        PREFS_PREFIX + namespace.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            ensureLoadedLocked()
            var changed = false
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                val key = keyOf(cookie)
                if (cookie.expiresAt <= now) {
                    // An expired Set-Cookie is a deletion instruction.
                    if (store.remove(key) != null) changed = true
                } else {
                    // Re-insert so replacement also refreshes the
                    // insertion order used by the cap eviction.
                    store.remove(key)
                    store[key] = cookie
                    changed = true
                }
            }
            if (changed) {
                evictLocked(now)
                persistLocked()
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ensureLoadedLocked()
            if (evictLocked(now)) persistLocked()
            return store.values.filter { it.matches(url) }
        }
    }

    private fun keyOf(cookie: Cookie): String =
        cookie.domain + "|" + cookie.path + "|" + cookie.name

    /**
     * Lazily hydrates the store from prefs, once a Context exists.
     * Until then the jar silently works in-memory (a Context is
     * available by the time any real request runs). Must be called
     * with [lock] held.
     */
    private fun ensureLoadedLocked() {
        if (loaded) return
        val ctx = contextProvider() ?: return
        loaded = true
        val raw = runCatching {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(prefsKey, null)
        }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                val domain = obj.optString("domain")
                if (name.isEmpty() || domain.isEmpty()) continue
                val persistent = obj.optBoolean("persistent", false)
                // Batch 1: legacy persisted session entries are
                // skipped, never promoted to a permanent expiry.
                if (!persistent) continue
                val expiresAt = obj.optLong("expiresAt", -1L)
                if (expiresAt <= now) continue
                val builder = Cookie.Builder()
                    .name(name)
                    .value(obj.optString("value"))
                    .path(obj.optString("path").ifEmpty { "/" })
                    .expiresAt(expiresAt)
                if (obj.optBoolean("hostOnly", false)) {
                    builder.hostOnlyDomain(domain)
                } else {
                    builder.domain(domain)
                }
                if (obj.optBoolean("secure", false)) builder.secure()
                if (obj.optBoolean("httpOnly", false)) builder.httpOnly()
                val cookie = runCatching { builder.build() }.getOrNull()
                    ?: continue
                store[keyOf(cookie)] = cookie
            }
        }
    }

    /**
     * Drops expired cookies, then enforces the per-namespace cap by
     * dropping the oldest-inserted entries. Must be called with
     * [lock] held. @return true when anything was removed.
     */
    private fun evictLocked(now: Long): Boolean {
        var changed = false
        val iter = store.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.expiresAt <= now) {
                iter.remove()
                changed = true
            }
        }
        while (store.size > MAX_COOKIES) {
            val eldest = store.keys.firstOrNull() ?: break
            store.remove(eldest)
            changed = true
        }
        return changed
    }

    /**
     * Serializes the store as one JSON array string. Batch 1:
     * session (non-persistent) cookies are NOT written — they live
     * in memory only (see the class doc).
     */
    private fun persistLocked() {
        val ctx = contextProvider() ?: return
        val arr = JSONArray()
        for (cookie in store.values.filter { it.persistent }) {
            arr.put(
                JSONObject()
                    .put("name", cookie.name)
                    .put("value", cookie.value)
                    .put("domain", cookie.domain)
                    .put("path", cookie.path)
                    .put("expiresAt", cookie.expiresAt)
                    .put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly)
                    .put("hostOnly", cookie.hostOnly)
                    .put("persistent", cookie.persistent)
            )
        }
        runCatching {
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString(prefsKey, arr.toString()).apply()
        }
    }

    companion object {
        private const val PREFS_PREFIX = "app_cookies_v1_"
        private const val MAX_COOKIES = 300
    }
}
