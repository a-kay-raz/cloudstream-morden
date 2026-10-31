package com.lagradost.cloudstream3.nuvio

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.quickJs
import com.lagradost.cloudstream3.utils.AppCookieJar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Nuvio host: runs one scraper in dokar3's QuickJS (quickjs-kt)
 * with the polyfill installed and three host bindings —
 * `nuvioCall` (sync JSON bridge: result, crypto, storage, log),
 * `nuvioFetchAsync` (async JSON bridge: HTTP with a persistent
 * per-scraper cookie jar) and `nuvioSleepAsync` (backs the
 * polyfill's timers).
 *
 * Engine choice notes: app.cash.quickjs 0.9.2 and the other Android
 * QuickJS bindings never execute QuickJS's pending-job queue, so
 * async/await scrapers would stall forever; Rhino has no async/class
 * syntax at all; V8 via Javet ships ~100MB of native code per ABI.
 * quickjs-kt pumps jobs through coroutines and offers real async
 * host bindings, which is what this design needs.
 */
class NuvioHostBridge(
    private val appContext: Context,
    private val repoUrl: String,
    private val scraperId: String,
) {
    @Volatile
    var resultJson: String? = null
        private set

    val resultDeferred = CompletableDeferred<String>()

    /**
     * 8.83: cookies moved from the old per-run in-memory map to the
     * universal persistent [AppCookieJar], namespaced per scraper —
     * a scraper's session now survives across runs and app restarts
     * (the bridge knows its scraper id, so per-scraper isolation is
     * free). OkHttp sends/stores automatically per redirect hop and
     * still yields to a Cookie header the scraper set itself.
     */
    private val httpClient: OkHttpClient by lazy {
        HTTP.newBuilder()
            .cookieJar(AppCookieJar(appContext, "nuvio:$scraperId"))
            .build()
    }

    // ---- sync bridge (nuvioCall) ----

    fun callSync(requestJson: String): String? {
        return try {
            val request = JSONObject(requestJson)
            val payload = request.optJSONObject("payload") ?: JSONObject()
            when (request.optString("op")) {
                "result" -> {
                    val data = payload.optString("data")
                    resultJson = data
                    if (!resultDeferred.isCompleted) resultDeferred.complete(data)
                    "{}"
                }
                "log" -> {
                    Log.d("NuvioJS", "[$scraperId] ${payload.optString("message")}")
                    "{}"
                }
                "crypto" -> doCrypto(payload).toString()
                "storageGet" -> JSONObject()
                    .put(
                        "value",
                        NuvioRepository.storageGet(
                            appContext, repoUrl, scraperId, payload.optString("key")
                        ) ?: JSONObject.NULL
                    )
                    .toString()
                "storageSet" -> {
                    val value = if (payload.has("value") && !payload.isNull("value")) {
                        payload.optString("value")
                    } else {
                        null
                    }
                    NuvioRepository.storageSet(
                        appContext, repoUrl, scraperId, payload.optString("key"), value
                    )
                    "{}"
                }
                "storageClear" -> {
                    NuvioRepository.storageClear(appContext, repoUrl, scraperId)
                    "{}"
                }
                else -> JSONObject().put("error", "unknown op").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "bridge error").toString()
        }
    }

    // ---- crypto ----

    private fun b64Decode(value: String?): ByteArray =
        runCatching { Base64.decode(value ?: "", Base64.DEFAULT) }
            .getOrElse { ByteArray(0) }

    private fun b64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun doCrypto(p: JSONObject): JSONObject {
        val out = JSONObject()
        when (val algo = p.optString("algo")) {
            "md5", "sha1", "sha256", "sha512" -> {
                val digestName = when (algo) {
                    "md5" -> "MD5"
                    "sha1" -> "SHA-1"
                    "sha256" -> "SHA-256"
                    else -> "SHA-512"
                }
                val digest = MessageDigest.getInstance(digestName)
                    .digest(b64Decode(p.optString("dataB64")))
                out.put("dataB64", b64Encode(digest))
            }
            "hmac-sha1", "hmac-sha256" -> {
                val mac = Mac.getInstance(
                    if (algo == "hmac-sha1") "HmacSHA1" else "HmacSHA256"
                )
                mac.init(SecretKeySpec(b64Decode(p.optString("keyB64")), mac.algorithm))
                out.put("dataB64", b64Encode(mac.doFinal(b64Decode(p.optString("dataB64")))))
            }
            "random" -> {
                val bytes = ByteArray(p.optInt("n", 16).coerceIn(1, 1024))
                SecureRandom().nextBytes(bytes)
                out.put("dataB64", b64Encode(bytes))
            }
            "aes-encrypt", "aes-decrypt" -> {
                val encrypt = algo == "aes-encrypt"
                val mode = p.optString("mode", "CBC")
                val padding = p.optString("padding", "Pkcs7")
                var data = b64Decode(p.optString("dataB64"))
                val cipherPadding = when (padding) {
                    "NoPadding", "ZeroPadding" -> "NoPadding"
                    else -> "PKCS5Padding"
                }
                if (padding == "ZeroPadding" && encrypt) {
                    val rem = data.size % 16
                    if (rem != 0) data = data + ByteArray(16 - rem)
                }
                val cipher = Cipher.getInstance("AES/$mode/$cipherPadding")
                val key = SecretKeySpec(b64Decode(p.optString("keyB64")), "AES")
                if (mode == "ECB") {
                    cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key)
                } else {
                    cipher.init(
                        if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                        key,
                        IvParameterSpec(b64Decode(p.optString("ivB64")))
                    )
                }
                var result = cipher.doFinal(data)
                if (padding == "ZeroPadding" && !encrypt) {
                    var end = result.size
                    while (end > 0 && result[end - 1] == 0.toByte()) end--
                    result = result.copyOf(end)
                }
                out.put("dataB64", b64Encode(result))
            }
            else -> out.put("error", "unsupported crypto op $algo")
        }
        return out
    }

    // ---- fetch (async bridge) ----

    suspend fun fetchAsync(requestJson: String): String {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                doFetch(JSONObject(requestJson)).toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "fetch failed").toString()
            }
        }
    }

    private fun doFetch(p: JSONObject): JSONObject {
        val url = p.optString("url")
        val method = p.optString("method", "GET").uppercase()
        val builder = Request.Builder().url(url)
        var contentType: String? = null
        val headers = p.optJSONObject("headers")
        var hasUserAgent = false
        if (headers != null) {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.optString(key)
                if (key.equals("content-type", ignoreCase = true)) contentType = value
                if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
                builder.header(key, value)
            }
        }
        // Same default as SkyStream's engine: a plain HTTP-client UA gets
        // 403 from many source sites, so present a browser UA whenever the
        // scraper did not set its own. (Native HTTP has no CORS; a 403 here
        // is the site refusing the client, and the UA is the usual reason.)
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        // Cookies come from the persistent jar on httpClient; a
        // Cookie header supplied by the scraper suppresses it, as
        // the old manual jar check did.
        val bodyBytes: ByteArray? = when {
            p.has("bodyB64") && !p.isNull("bodyB64") ->
                b64Decode(p.optString("bodyB64"))
            p.has("body") && !p.isNull("body") ->
                p.optString("body").toByteArray(Charsets.UTF_8)
            else -> null
        }
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> {
                if (bodyBytes != null) {
                    builder.delete(bodyBytes.toRequestBody(contentType?.toMediaTypeOrNull()))
                } else {
                    builder.delete()
                }
            }
            else -> {
                val body = (bodyBytes ?: ByteArray(0))
                    .toRequestBody(contentType?.toMediaTypeOrNull())
                when (method) {
                    "POST" -> builder.post(body)
                    "PUT" -> builder.put(body)
                    "PATCH" -> builder.patch(body)
                    else -> builder.method(method, body)
                }
            }
        }
        val response = httpClient.newCall(builder.build()).execute()
        return response.use { resp ->
            // 8.83: Set-Cookie storage is the persistent jar's job
            // (OkHttp feeds it from this response); the response
            // shape is unchanged — set-cookie stays hidden from the
            // headers object, exactly as before.
            val rawBytes = resp.body?.bytes() ?: ByteArray(0)
            val capped = if (rawBytes.size > MAX_BODY_BYTES) {
                rawBytes.copyOf(MAX_BODY_BYTES)
            } else {
                rawBytes
            }
            val charset = resp.body?.contentType()?.charset(Charsets.UTF_8)
                ?: Charsets.UTF_8
            val headersOut = JSONObject()
            resp.headers.forEach { (name, value) ->
                val key = name.lowercase()
                if (key == "set-cookie") return@forEach
                headersOut.put(
                    key,
                    if (headersOut.has(key)) headersOut.optString(key) + ", " + value else value
                )
            }
            JSONObject()
                .put("status", resp.code)
                .put("statusText", resp.message)
                .put("url", resp.request.url.toString())
                .put("headers", headersOut)
                .put("bodyText", String(capped, charset))
                .put("bodyB64", b64Encode(capped))
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MAX_BODY_BYTES = 15 * 1024 * 1024
        private val HTTP = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

object NuvioEngine {

    enum class Mode { STREAMS, SETTINGS, PROBE }

    private const val DEFAULT_TIMEOUT_MS = 60_000L

    /**
     * Runs one scraper and returns the parsed result payload:
     * {streams: [...]} or {layout: [...]} or {error: "..."}.
     */
    suspend fun run(
        context: Context,
        repoUrl: String,
        scraper: NuvioScraperInfo,
        code: String,
        settings: JSONObject,
        tmdbKey: String?,
        mode: Mode,
        tmdbId: String = "",
        mediaType: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JSONObject {
        val bridge = NuvioHostBridge(context.applicationContext, repoUrl, scraper.id)
        val raw = withTimeoutOrNull(timeoutMs) {
            // dokar quickjs-kt builder: the block is a QuickJs receiver
            // lambda (QuickJs.() -> T), NOT a (QuickJs) -> T parameter
            // lambda — passing an explicit parameter fails overload
            // resolution. The builder is inline, so the suspend
            // evaluate/await calls below splice into this coroutine.
            quickJs(Dispatchers.Default) {
                defineBinding(
                    "nuvioCall",
                    object : FunctionBinding<String?> {
                        override fun invoke(args: Array<Any?>): String? =
                            bridge.callSync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "nuvioFetchAsync",
                    object : AsyncFunctionBinding<String> {
                        override suspend fun invoke(args: Array<Any?>): String =
                            bridge.fetchAsync(args.firstOrNull() as? String ?: "{}")
                    }
                )
                defineBinding(
                    "nuvioSleepAsync",
                    object : AsyncFunctionBinding<Any?> {
                        override suspend fun invoke(args: Array<Any?>): Any? {
                            val ms = (args.firstOrNull() as? Number)?.toLong() ?: 0L
                            delay(ms.coerceIn(0L, 30_000L))
                            return null
                        }
                    }
                )
                // Batch 1: tmdbKey is deliberately NOT forwarded
                // to the polyfill — the user's TMDB key must never
                // reach plugin JS (see NuvioPolyfill). The parameter
                // is retained so existing callers need no change;
                // host-side TMDB work uses the key directly, in
                // NuvioStreamService / NuvioTmdb, not via the script.
                evaluate<Any?>(
                    NuvioPolyfill.build(settings.toString()),
                    "nuvio-polyfill.js"
                )
                evaluate<Any?>(wrapScraper(code), "scraper.js")
                val callScript = when (mode) {
                    Mode.SETTINGS -> SETTINGS_CALL
                    Mode.PROBE -> PROBE_CALL
                    else -> streamsCall(tmdbId, mediaType, season, episode)
                }
                val fromEval = runCatching {
                    evaluate<String>(callScript, "nuvio-call.js")
                }.getOrNull()
                // Both call scripts are async IIFEs: evaluate() returns the
                // Promise object's own string form ("Promise { <state>: ... }"),
                // never the payload. The real payload always lands in the
                // bridge via __nuvio_result before evaluate() returns, so the
                // bridge wins; the eval text is only a fallback when it
                // actually looks like JSON. (Verified on the real engine —
                // preferring fromEval silently discarded every result.)
                when {
                    bridge.resultJson != null -> bridge.resultJson
                    bridge.resultDeferred.isCompleted -> bridge.resultDeferred.await()
                    !fromEval.isNullOrBlank() && fromEval.trimStart().startsWith("{") -> fromEval
                    else -> bridge.resultJson
                }
            }
        } ?: bridge.resultJson
        if (raw.isNullOrBlank()) {
            return JSONObject().put("error", "Timed out or no result")
        }
        return runCatching { JSONObject(raw) }
            .getOrElse { JSONObject().put("error", "Bad result payload") }
    }

    /**
     * Load test only: evaluates the polyfill + scraper and reports whether
     * getStreams / onSettings exist. No network calls are made by the probe
     * itself. Returns the probe payload, or {"error": ...} when the code
     * does not even load (syntax errors, missing host APIs).
     */
    suspend fun probe(
        context: Context,
        repoUrl: String,
        scraper: NuvioScraperInfo,
        code: String,
        settings: JSONObject,
        tmdbKey: String?,
    ): JSONObject {
        return runCatching {
            run(
                context, repoUrl, scraper, code, settings, tmdbKey,
                Mode.PROBE, timeoutMs = 20_000L
            )
        }.getOrElse { e ->
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Runs onSettings() and parses the form layout. */
    suspend fun fetchSettingsLayout(
        context: Context,
        repoUrl: String,
        scraper: NuvioScraperInfo,
        code: String,
        settings: JSONObject,
        tmdbKey: String?,
    ): List<NuvioSettingsField> {
        val result = run(
            context, repoUrl, scraper, code, settings, tmdbKey,
            Mode.SETTINGS, timeoutMs = 30_000L
        )
        return NuvioParse.parseSettingsLayout(result.optJSONArray("layout"))
    }

    private fun wrapScraper(code: String): String =
        "var module = { exports: {} };\n" +
            "var exports = module.exports;\n" +
            "var __filename = '';\nvar __dirname = '';\n" +
            "(function() {\n" + code + "\n})();"

    private fun streamsCall(
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): String {
        val seasonArg = season?.toString() ?: "undefined"
        val episodeArg = episode?.toString() ?: "undefined"
        return "(async function () {\n" +
            "  try {\n" +
            "    var getStreams = (module.exports && module.exports.getStreams) || globalThis.getStreams;\n" +
            "    if (!getStreams) { __nuvio_result(JSON.stringify({ error: 'getStreams not found' })); return JSON.stringify({ error: 'getStreams not found' }); }\n" +
            "    var out = await getStreams(" + JSONObject.quote(tmdbId) + ", " +
            JSONObject.quote(mediaType) + ", " + seasonArg + ", " + episodeArg + ");\n" +
            "    var payload = JSON.stringify({ streams: out || [] });\n" +
            "    __nuvio_result(payload);\n" +
            "    return payload;\n" +
            "  } catch (e) {\n" +
            "    var errPayload = JSON.stringify({ error: (e && e.message) ? e.message : String(e) });\n" +
            "    __nuvio_result(errPayload);\n" +
            "    return errPayload;\n" +
            "  }\n" +
            "})();"
    }

    private const val PROBE_CALL =
        "(function () {\n" +
            "  try {\n" +
            "    var gs = (module.exports && module.exports.getStreams) || globalThis.getStreams;\n" +
            "    var st = (module.exports && module.exports.onSettings) || globalThis.onSettings;\n" +
            "    var p = JSON.stringify({ probe: true, gs: typeof gs === 'function', st: typeof st === 'function' });\n" +
            "    __nuvio_result(p);\n" +
            "    return p;\n" +
            "  } catch (e) {\n" +
            "    var ep = JSON.stringify({ error: (e && e.message) ? e.message : String(e) });\n" +
            "    __nuvio_result(ep);\n" +
            "    return ep;\n" +
            "  }\n" +
            "})();"

    private const val SETTINGS_CALL =
        "(async function () {\n" +
            "  try {\n" +
            "    var onSettings = (module.exports && module.exports.onSettings) || globalThis.onSettings;\n" +
            "    if (typeof onSettings !== 'function') { var empty = JSON.stringify({ layout: [] }); __nuvio_result(empty); return empty; }\n" +
            "    var layout = await onSettings();\n" +
            "    var payload = JSON.stringify({ layout: layout || [] });\n" +
            "    __nuvio_result(payload);\n" +
            "    return payload;\n" +
            "  } catch (e) {\n" +
            "    var errPayload = JSON.stringify({ error: (e && e.message) ? e.message : String(e) });\n" +
            "    __nuvio_result(errPayload);\n" +
            "    return errPayload;\n" +
            "  }\n" +
            "})();"
}

/** Small helper used by the service layer: raw streams array → results. */
fun parseNuvioStreams(
    result: JSONObject,
    scraper: NuvioScraperInfo,
): List<NuvioStreamResult> {
    val arr: JSONArray = result.optJSONArray("streams") ?: return emptyList()
    val out = mutableListOf<NuvioStreamResult>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        NuvioParse.parseStreamResult(obj, scraper.id, scraper.name)?.let { out += it }
    }
    return out
}
