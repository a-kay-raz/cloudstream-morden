package com.lagradost.cloudstream3.nuvio

/**
 * The JavaScript environment Nuvio scrapers run in, modelled on the
 * surface SkyStream's host provides: fetch/XHR backed by a native
 * async binding, timers on a native sleep binding, URL/URLSearchParams,
 * Buffer, TextEncoder, WebCrypto subset + a CryptoJS-compatible shim
 * (native hashes/AES), localStorage (host-persisted per scraper), a
 * minimal axios, and a minimal cheerio. Synchronous host work goes
 * through one JSON bridge function, `nuvioCall(json) -> json`, which
 * keeps the QuickJS type-conversion surface at zero; HTTP and sleeps
 * use the async bindings `nuvioFetchAsync` / `nuvioSleepAsync`, which
 * hand real promises back to the script.
 *
 * Placeholder __NUVIO_SETTINGS__ is substituted by the engine with
 * a JSON literal before evaluation.
 *
 * Batch 1: the user's TMDB key is NEVER injected into plugin JS.
 * Previously it was exposed as G.TMDB_API_KEY / process.env, so any
 * third-party scraper could read it and exfiltrate it. Scrapers do
 * not need it: the host resolves the TMDB id itself and passes it
 * as a call argument (see NuvioStreamService / NuvioEngine.run),
 * and host-side TMDB calls still use the key from prefs directly.
 * G.TMDB_API_KEY / G.TMDB_KEY / process.env.TMDB_API_KEY are
 * therefore always null here.
 */
object NuvioPolyfill {

    fun build(settingsJson: String): String =
        SOURCE
            .replace("__NUVIO_SETTINGS__", settingsJson)
            .replace("__NUVIO_TMDB_KEY__", "null")

    private const val SOURCE = """
(function () {
  var G = globalThis;
  function hostCall(op, payload) {
    try {
      var out = nuvioCall(JSON.stringify({ op: op, payload: payload || {} }));
      return out ? JSON.parse(out) : null;
    } catch (e) {
      return { error: String((e && e.message) || e) };
    }
  }
  G.__nuvio_host = hostCall;
  G.__nuvio_result = function (payload) { hostCall('result', { data: payload }); };

  var SETTINGS = __NUVIO_SETTINGS__;
  G.SCRAPER_SETTINGS = SETTINGS;
  G.NUVIO_SETTINGS = SETTINGS;
  G.SETTINGS = SETTINGS;
  G.settings = SETTINGS;
  var TMDBK = __NUVIO_TMDB_KEY__;
  G.TMDB_API_KEY = TMDBK
  G.TMDB_KEY = TMDBK;
  G.process = {
    env: { TMDB_API_KEY: TMDBK, NODE_ENV: 'production' },
    platform: 'android',
    version: '',
    nextTick: function (fn) { Promise.resolve().then(fn); }
  };
  G.global = G;
  G.window = G;
  G.self = G;
  if (typeof G.queueMicrotask !== 'function') {
    G.queueMicrotask = function (fn) { Promise.resolve().then(fn); };
  }

  // ------------------------------------------------------------- console
  var consoleObj = {};
  ['log', 'info', 'warn', 'error', 'debug', 'trace'].forEach(function (m) {
    consoleObj[m] = function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var a = arguments[i];
        if (typeof a === 'string') parts.push(a);
        else { try { parts.push(JSON.stringify(a)); } catch (e) { parts.push(String(a)); } }
      }
      hostCall('log', { level: m, message: parts.join(' ') });
    };
  });
  consoleObj.assert = function (c, msg) { if (!c) consoleObj.error('Assertion failed: ' + (msg || '')); };
  consoleObj.time = function () {};
  consoleObj.timeEnd = function () {};
  consoleObj.count = function () {};
  consoleObj.dir = consoleObj.log;
  G.console = consoleObj;

  // ------------------------------------------------------------- timers
  // Timers run on the host's async sleep binding: each timer is an
  // independent promise chain, so no host-side tick pumping is needed.
  var timers = {};
  var timerSeq = 0;
  G.setTimeout = function (fn, delay) {
    if (typeof fn === 'string') { var code = fn; fn = function () { eval(code); }; }
    var id = ++timerSeq;
    var rec = { cancelled: false };
    timers[id] = rec;
    var args = Array.prototype.slice.call(arguments, 2);
    nuvioSleepAsync(Math.max(0, delay || 0)).then(function () {
      if (rec.cancelled) return;
      delete timers[id];
      try { fn.apply(null, args); } catch (e) {}
    });
    return id;
  };
  G.setInterval = function (fn, delay) {
    var id = ++timerSeq;
    var rec = { cancelled: false };
    timers[id] = rec;
    var args = Array.prototype.slice.call(arguments, 2);
    var d = Math.max(1, delay || 0);
    function step() {
      if (rec.cancelled) return;
      nuvioSleepAsync(d).then(function () {
        if (rec.cancelled) return;
        try { fn.apply(null, args); } catch (e) {}
        step();
      });
    }
    step();
    return id;
  };
  G.clearTimeout = function (id) {
    var rec = timers[id];
    if (rec) { rec.cancelled = true; delete timers[id]; }
  };
  G.clearInterval = G.clearTimeout;
  G.setImmediate = function (fn) {
    var args = Array.prototype.slice.call(arguments, 1);
    return G.setTimeout(function () { fn.apply(null, args); }, 0);
  };
  G.clearImmediate = G.clearTimeout;
  G.__nuvio_tick = function () {};

  // ------------------------------------------------------------- bytes / base64 / utf8
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function bytesToB64(bytes) {
    var out = ''; var i;
    for (i = 0; i < bytes.length; i += 3) {
      var a = bytes[i];
      var b = i + 1 < bytes.length ? bytes[i + 1] : 0;
      var c = i + 2 < bytes.length ? bytes[i + 2] : 0;
      var n = (a << 16) | (b << 8) | c;
      out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] +
        (i + 1 < bytes.length ? B64[(n >> 6) & 63] : '=') +
        (i + 2 < bytes.length ? B64[n & 63] : '=');
    }
    return out;
  }
  function b64ToBytes(str) {
    var s = String(str).replace(/[^A-Za-z0-9+/=]/g, '');
    var out = [];
    for (var i = 0; i < s.length; i += 4) {
      var e1 = B64.indexOf(s.charAt(i));
      var e2 = B64.indexOf(s.charAt(i + 1));
      var e3 = s.charAt(i + 2) === '=' ? 0 : B64.indexOf(s.charAt(i + 2));
      var e4 = s.charAt(i + 3) === '=' ? 0 : B64.indexOf(s.charAt(i + 3));
      var n = (e1 << 18) | (e2 << 12) | (e3 << 6) | e4;
      out.push((n >> 16) & 255);
      if (s.charAt(i + 2) !== '=' && i + 2 < s.length) out.push((n >> 8) & 255);
      if (s.charAt(i + 3) !== '=' && i + 3 < s.length) out.push(n & 255);
    }
    return new Uint8Array(out);
  }
  function utf8ToBytes(str) {
    var out = [];
    str = String(str);
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) { out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F)); }
      else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
        var c2 = str.charCodeAt(i + 1);
        if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
          var cp = 0x10000 + ((c - 0xD800) * 0x400) + (c2 - 0xDC00);
          out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
          i++;
        } else out.push(0xEF, 0xBF, 0xBD);
      } else if (c >= 0xDC00 && c <= 0xDFFF) out.push(0xEF, 0xBF, 0xBD);
      else out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
    }
    return new Uint8Array(out);
  }
  function bytesToUtf8(bytes) {
    var out = ''; var i = 0;
    while (i < bytes.length) {
      var b = bytes[i]; var cp;
      if (b < 0x80) { cp = b; i += 1; }
      else if ((b & 0xE0) === 0xC0) { cp = ((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F); i += 2; }
      else if ((b & 0xF0) === 0xE0) { cp = ((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F); i += 3; }
      else if ((b & 0xF8) === 0xF0) { cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) | ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F); i += 4; }
      else { cp = 0xFFFD; i += 1; }
      if (cp <= 0xFFFF) out += String.fromCharCode(cp);
      else { cp -= 0x10000; out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF)); }
    }
    return out;
  }
  function hexToBytes(hex) {
    var out = [];
    var h = String(hex).replace(/[^0-9a-fA-F]/g, '');
    for (var i = 0; i + 1 < h.length; i += 2) out.push(parseInt(h.substr(i, 2), 16));
    return new Uint8Array(out);
  }
  function bytesToHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      var h = bytes[i].toString(16);
      if (h.length < 2) out += '0';
      out += h;
    }
    return out;
  }
  function latin1ToBytes(str) {
    var out = new Uint8Array(str.length);
    for (var i = 0; i < str.length; i++) out[i] = str.charCodeAt(i) & 0xFF;
    return out;
  }
  function bytesToLatin1(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return out;
  }
  G.btoa = function (str) {
    str = String(str);
    var bytes = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c > 0xFF) throw new Error('Invalid character');
      bytes.push(c);
    }
    return bytesToB64(bytes);
  };
  G.atob = function (str) { return bytesToLatin1(b64ToBytes(str)); };

  G.TextEncoder = function () {};
  G.TextEncoder.prototype.encode = function (str) { return utf8ToBytes(str == null ? '' : String(str)); };
  G.TextEncoder.prototype.encoding = 'utf-8';
  G.TextDecoder = function (enc) { this.encoding = enc || 'utf-8'; };
  G.TextDecoder.prototype.decode = function (buf) {
    if (!buf) return '';
    var bytes;
    if (buf instanceof Uint8Array) bytes = buf;
    else if (buf.buffer) bytes = new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength);
    else bytes = new Uint8Array(buf);
    if (String(this.encoding).toLowerCase().indexOf('latin') >= 0) return bytesToLatin1(bytes);
    return bytesToUtf8(bytes);
  };

  // ------------------------------------------------------------- Buffer
  function wrapBuffer(bytes) {
    bytes.toString = function (enc) {
      enc = enc || 'utf8';
      if (enc === 'base64') return bytesToB64(bytes);
      if (enc === 'hex') return bytesToHex(bytes);
      if (enc === 'latin1' || enc === 'binary') return bytesToLatin1(bytes);
      return bytesToUtf8(bytes);
    };
    return bytes;
  }
  function bufferFrom(data, enc) {
    var bytes;
    if (typeof data === 'string') {
      enc = enc || 'utf8';
      if (enc === 'base64') bytes = b64ToBytes(data);
      else if (enc === 'hex') bytes = hexToBytes(data);
      else if (enc === 'latin1' || enc === 'binary') bytes = latin1ToBytes(data);
      else bytes = utf8ToBytes(data);
    } else if (data instanceof Uint8Array) bytes = data;
    else if (Array.isArray(data)) bytes = new Uint8Array(data);
    else if (data && data.type === 'Buffer' && Array.isArray(data.data)) bytes = new Uint8Array(data.data);
    else bytes = new Uint8Array(0);
    return wrapBuffer(bytes);
  }
  var BufferObj = {
    from: bufferFrom,
    alloc: function (n) { return wrapBuffer(new Uint8Array(n || 0)); },
    allocUnsafe: function (n) { return wrapBuffer(new Uint8Array(n || 0)); },
    concat: function (list, total) {
      var len = total;
      if (len === undefined) { len = 0; for (var i = 0; i < list.length; i++) len += list[i].length; }
      var out = new Uint8Array(len);
      var off = 0;
      for (var j = 0; j < list.length; j++) { out.set(list[j], off); off += list[j].length; }
      return wrapBuffer(out);
    },
    byteLength: function (s, enc) { return bufferFrom(s, enc).length; },
    isBuffer: function (b) { return b instanceof Uint8Array; }
  };
  G.Buffer = BufferObj;

  // ------------------------------------------------------------- URL
  function URLSearchParams(init) {
    this._list = [];
    if (init) {
      if (typeof init === 'string') {
        var q = init.charAt(0) === '?' ? init.substring(1) : init;
        var pairs = q.split('&');
        for (var i = 0; i < pairs.length; i++) {
          if (!pairs[i]) continue;
          var eq = pairs[i].indexOf('=');
          var k = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
          var v = eq < 0 ? '' : pairs[i].substring(eq + 1);
          this.append(decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' ')));
        }
      } else if (Array.isArray(init)) {
        for (var j = 0; j < init.length; j++) this.append(init[j][0], init[j][1]);
      } else if (typeof init === 'object') {
        for (var key in init) this.append(key, init[key]);
      }
    }
  }
  URLSearchParams.prototype.append = function (k, v) { this._list.push([String(k), String(v)]); };
  URLSearchParams.prototype.set = function (k, v) {
    k = String(k);
    var done = false;
    var out = [];
    for (var i = 0; i < this._list.length; i++) {
      if (this._list[i][0] === k) { if (!done) { out.push([k, String(v)]); done = true; } }
      else out.push(this._list[i]);
    }
    if (!done) out.push([k, String(v)]);
    this._list = out;
  };
  URLSearchParams.prototype.get = function (k) {
    for (var i = 0; i < this._list.length; i++) if (this._list[i][0] === String(k)) return this._list[i][1];
    return null;
  };
  URLSearchParams.prototype.getAll = function (k) {
    var out = [];
    for (var i = 0; i < this._list.length; i++) if (this._list[i][0] === String(k)) out.push(this._list[i][1]);
    return out;
  };
  URLSearchParams.prototype.has = function (k) { return this.get(k) !== null; };
  URLSearchParams.prototype.delete = function (k) {
    this._list = this._list.filter(function (p) { return p[0] !== String(k); });
  };
  URLSearchParams.prototype.forEach = function (fn) {
    for (var i = 0; i < this._list.length; i++) fn(this._list[i][1], this._list[i][0], this);
  };
  URLSearchParams.prototype.entries = function () {
    var list = this._list; var i = 0;
    return { next: function () { return i < list.length ? { value: list[i++], done: false } : { value: undefined, done: true }; } };
  };
  URLSearchParams.prototype.toString = function () {
    return this._list.map(function (p) {
      return encodeURIComponent(p[0]).replace(/%20/g, '+') + '=' + encodeURIComponent(p[1]).replace(/%20/g, '+');
    }).join('&');
  };
  G.URLSearchParams = URLSearchParams;

  function URL(url, base) {
    var input = String(url);
    if (base && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(input)) {
      var b = base instanceof URL ? base : new URL(String(base));
      if (input.charAt(0) === '/') input = b.origin + input;
      else {
        var dir = b.pathname.substring(0, b.pathname.lastIndexOf('/') + 1);
        input = b.origin + dir + input;
      }
    }
    var m = input.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)?\/\/([^\/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/);
    this.protocol = ''; this.host = ''; this.hostname = ''; this.port = '';
    this.pathname = '/'; this.searchParams = new URLSearchParams();
    this._search = ''; this._hash = '';
    if (m) {
      this.protocol = m[1] || '';
      this.host = m[2] || '';
      var hp = this.host.split(':');
      this.hostname = hp[0];
      this.port = hp.length > 1 ? hp[1] : '';
      this.pathname = m[3] || '/';
      if (m[4] !== undefined) { this._search = '?' + m[4]; this.searchParams = new URLSearchParams(m[4]); }
      if (m[5] !== undefined) this._hash = '#' + m[5];
    } else {
      var parts = input.split('?');
      this.pathname = parts[0] || '/';
      if (parts[1] !== undefined) { this._search = '?' + parts[1]; this.searchParams = new URLSearchParams(parts[1]); }
    }
    var self = this;
    Object.defineProperty(this, 'search', {
      get: function () { var s = self.searchParams.toString(); return s ? '?' + s : ''; },
      set: function (v) { self.searchParams = new URLSearchParams(v || ''); }
    });
    Object.defineProperty(this, 'hash', {
      get: function () { return self._hash; },
      set: function (v) { self._hash = v ? (String(v).charAt(0) === '#' ? String(v) : '#' + v) : ''; }
    });
    Object.defineProperty(this, 'href', {
      get: function () { return self.toString(); },
      set: function (v) { var u = new URL(v); self.protocol = u.protocol; self.host = u.host; self.hostname = u.hostname; self.port = u.port; self.pathname = u.pathname; self.searchParams = u.searchParams; self._hash = u._hash; }
    });
    Object.defineProperty(this, 'origin', {
      get: function () { return self.protocol && self.host ? self.protocol + '//' + self.host : 'null'; }
    });
  }
  URL.prototype.toString = function () {
    var s = this.searchParams.toString();
    var base = (this.protocol ? this.protocol + '//' + this.host : '') + this.pathname;
    return base + (s ? '?' + s : '') + this._hash;
  };
  URL.createObjectURL = function () { return 'blob:nuvio'; };
  URL.revokeObjectURL = function () {};
  G.URL = URL;

  // ------------------------------------------------------------- fetch / XHR
  function Headers(init) {
    this._map = {};
    if (init) {
      if (Array.isArray(init)) { for (var i = 0; i < init.length; i++) this.append(init[i][0], init[i][1]); }
      else if (init instanceof Headers) { var self = this; init.forEach(function (v, k) { self.set(k, v); }); }
      else if (typeof init === 'object') { for (var k in init) this.set(k, init[k]); }
    }
  }
  Headers.prototype.get = function (n) { var v = this._map[String(n).toLowerCase()]; return v === undefined ? null : v; };
  Headers.prototype.set = function (n, v) { this._map[String(n).toLowerCase()] = String(v); };
  Headers.prototype.append = function (n, v) {
    var k = String(n).toLowerCase();
    this._map[k] = this._map[k] === undefined ? String(v) : this._map[k] + ', ' + v;
  };
  Headers.prototype.has = function (n) { return this._map[String(n).toLowerCase()] !== undefined; };
  Headers.prototype.delete = function (n) { delete this._map[String(n).toLowerCase()]; };
  Headers.prototype.forEach = function (fn) { for (var k in this._map) fn(this._map[k], k, this); };
  G.Headers = Headers;

  function Response(bodyText, bodyB64, init) {
    this.status = init.status || 0;
    this.statusText = init.statusText || '';
    this.url = init.url || '';
    this.headers = init.headers || new Headers();
    this.ok = this.status >= 200 && this.status < 300;
    this.redirected = false;
    this.type = 'basic';
    this._text = bodyText || '';
    this._b64 = bodyB64 || '';
    this.bodyUsed = false;
  }
  Response.prototype.text = function () { this.bodyUsed = true; return Promise.resolve(this._text); };
  Response.prototype.json = function () {
    this.bodyUsed = true;
    try { return Promise.resolve(JSON.parse(this._text)); } catch (e) { return Promise.reject(e); }
  };
  Response.prototype.arrayBuffer = function () {
    this.bodyUsed = true;
    return Promise.resolve(b64ToBytes(this._b64).buffer);
  };
  Response.prototype.blob = function () {
    var self = this;
    return Promise.resolve({
      size: self._text.length,
      type: self.headers.get('content-type') || '',
      text: function () { return Promise.resolve(self._text); },
      arrayBuffer: function () { return self.arrayBuffer(); }
    });
  };
  Response.prototype.clone = function () {
    return new Response(this._text, this._b64, { status: this.status, statusText: this.statusText, url: this.url, headers: this.headers });
  };
  G.Response = Response;

  function Request(url, opts) {
    opts = opts || {};
    this.url = String(url);
    this.method = (opts.method || 'GET').toUpperCase();
    this.headers = opts.headers instanceof Headers ? opts.headers : new Headers(opts.headers || {});
    this.body = opts.body || null;
  }
  G.Request = Request;

  G.fetch = function (url, opts) {
    if (url instanceof Request) { opts = Object.assign({}, { method: url.method, headers: url.headers, body: url.body }, opts || {}); url = url.url; }
    opts = opts || {};
    var method = (opts.method || 'GET').toUpperCase();
    var headers = {};
    var h = opts.headers instanceof Headers ? opts.headers : new Headers(opts.headers || {});
    h.forEach(function (v, k) { headers[k] = v; });
    var body = null; var bodyB64 = null;
    if (opts.body != null) {
      if (typeof opts.body === 'string') body = opts.body;
      else if (opts.body instanceof URLSearchParams) body = opts.body.toString();
      else if (opts.body instanceof Uint8Array) bodyB64 = bytesToB64(opts.body);
      else if (opts.body && typeof opts.body === 'object' && Array.isArray(opts.body._entries)) {
        body = opts.body._entries.map(function (p) { return encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]); }).join('&');
      } else body = String(opts.body);
    }
    return nuvioFetchAsync(JSON.stringify({
      url: String(url), method: method, headers: headers, body: body, bodyB64: bodyB64
    })).then(function (out) {
      var res = null;
      try { res = out ? JSON.parse(out) : null; } catch (e) { res = null; }
      if (!res || res.error) throw new Error((res && res.error) || 'fetch failed');
      var rh = new Headers(res.headers || {});
      return new Response(res.bodyText || '', res.bodyB64 || '', {
        status: res.status || 0, statusText: res.statusText || '', url: res.url || String(url), headers: rh
      });
    });
  };

  function XMLHttpRequest() {
    this.readyState = 0; this.status = 0; this.statusText = '';
    this.responseText = ''; this.response = '';
    this.responseType = ''; this.timeout = 0;
    this.onreadystatechange = null; this.onload = null; this.onerror = null; this.onloadend = null;
    this._headers = {}; this._respHeaders = {};
    this.withCredentials = false;
  }
  XMLHttpRequest.prototype.open = function (method, url) {
    this._method = method; this._url = url; this.readyState = 1; this._fire();
  };
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) { this._headers[k] = v; };
  XMLHttpRequest.prototype.getResponseHeader = function (k) {
    var v = this._respHeaders[String(k).toLowerCase()];
    return v === undefined ? null : v;
  };
  XMLHttpRequest.prototype.getAllResponseHeaders = function () {
    var out = [];
    for (var k in this._respHeaders) out.push(k + ': ' + this._respHeaders[k]);
    return out.join('\r\n');
  };
  XMLHttpRequest.prototype._fire = function () {
    if (this.onreadystatechange) { try { this.onreadystatechange(); } catch (e) {} }
  };
  XMLHttpRequest.prototype.send = function (body) {
    var self = this;
    this.readyState = 2; this._fire();
    nuvioFetchAsync(JSON.stringify({
      url: String(this._url), method: (this._method || 'GET').toUpperCase(),
      headers: this._headers, body: body != null ? String(body) : null, bodyB64: null
    })).then(function (out) {
      var res = null;
      try { res = out ? JSON.parse(out) : null; } catch (e) { res = null; }
      if (!res || res.error) throw new Error((res && res.error) || 'XHR failed');
      self.status = res.status || 0;
      self._respHeaders = {};
      var rh = res.headers || {};
      for (var k in rh) self._respHeaders[String(k).toLowerCase()] = rh[k];
      self.responseText = res.bodyText || '';
      self.response = self.responseType === 'json'
        ? (function () { try { return JSON.parse(self.responseText); } catch (e) { return null; } })()
        : self.responseText;
      self.readyState = 4; self._fire();
      if (self.onload) { try { self.onload(); } catch (e) {} }
      if (self.onloadend) { try { self.onloadend(); } catch (e) {} }
    }).catch(function (err) {
      self.readyState = 4; self._fire();
      if (self.onerror) { try { self.onerror(err); } catch (e) {} }
      if (self.onloadend) { try { self.onloadend(); } catch (e) {} }
    });
  };
  XMLHttpRequest.prototype.abort = function () {};
  G.XMLHttpRequest = XMLHttpRequest;

  // ------------------------------------------------------------- storage
  function makeStorage(persistent) {
    var mem = {};
    return {
      getItem: function (k) {
        k = String(k);
        if (persistent) {
          var v = hostCall('storageGet', { key: k });
          return v && v.value != null ? v.value : null;
        }
        return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null;
      },
      setItem: function (k, v) {
        k = String(k); v = String(v);
        if (persistent) hostCall('storageSet', { key: k, value: v }); else mem[k] = v;
      },
      removeItem: function (k) {
        k = String(k);
        if (persistent) hostCall('storageSet', { key: k, value: null }); else delete mem[k];
      },
      clear: function () {
        if (persistent) hostCall('storageClear', {}); else mem = {};
      },
      key: function () { return null; },
      length: 0
    };
  }
  G.localStorage = makeStorage(true);
  G.sessionStorage = makeStorage(false);

  // ------------------------------------------------------------- crypto + CryptoJS
  function cryptoOp(payload) {
    var res = hostCall('crypto', payload);
    if (!res || res.error) throw new Error((res && res.error) || 'crypto failed');
    return res;
  }
  function toBytes(data) {
    if (data == null) return new Uint8Array(0);
    if (data instanceof Uint8Array) return data;
    if (data && data._bytes instanceof Uint8Array) return data._bytes;
    if (typeof data === 'string') return utf8ToBytes(data);
    return utf8ToBytes(String(data));
  }
  function WordArray(bytes) { this._bytes = bytes || new Uint8Array(0); }
  WordArray.prototype.toString = function (enc) {
    enc = enc || CryptoJS.enc.Hex;
    return enc.stringify(this);
  };
  WordArray.prototype.concat = function (other) {
    var a = this._bytes; var b = toBytes(other);
    var out = new Uint8Array(a.length + b.length);
    out.set(a); out.set(b, a.length);
    return new WordArray(out);
  };
  WordArray.prototype.clone = function () { return new WordArray(new Uint8Array(this._bytes)); };

  var CryptoJS = {};
  CryptoJS.enc = {
    Hex: {
      stringify: function (wa) { return bytesToHex(toBytes(wa)); },
      parse: function (s) { return new WordArray(hexToBytes(s)); }
    },
    Base64: {
      stringify: function (wa) { return bytesToB64(toBytes(wa)); },
      parse: function (s) { return new WordArray(b64ToBytes(s)); }
    },
    Utf8: {
      stringify: function (wa) { return bytesToUtf8(toBytes(wa)); },
      parse: function (s) { return new WordArray(utf8ToBytes(s)); }
    },
    Latin1: {
      stringify: function (wa) { return bytesToLatin1(toBytes(wa)); },
      parse: function (s) { return new WordArray(latin1ToBytes(s)); }
    }
  };
  CryptoJS.lib = {
    WordArray: {
      create: function (data, enc) {
        if (data instanceof WordArray) return data;
        if (typeof data === 'string' && enc) return enc.parse(data);
        return new WordArray(toBytes(data));
      },
      random: function (n) {
        var res = cryptoOp({ algo: 'random', n: n || 16 });
        return new WordArray(b64ToBytes(res.dataB64 || ''));
      }
    }
  };
  function hashWA(algo, data) {
    var res = cryptoOp({ algo: algo, dataB64: bytesToB64(toBytes(data)) });
    return new WordArray(b64ToBytes(res.dataB64 || ''));
  }
  CryptoJS.MD5 = function (d) { return hashWA('md5', d); };
  CryptoJS.SHA1 = function (d) { return hashWA('sha1', d); };
  CryptoJS.SHA256 = function (d) { return hashWA('sha256', d); };
  CryptoJS.SHA512 = function (d) { return hashWA('sha512', d); };
  CryptoJS.HmacSHA256 = function (d, key) {
    var res = cryptoOp({ algo: 'hmac-sha256', dataB64: bytesToB64(toBytes(d)), keyB64: bytesToB64(toBytes(key)) });
    return new WordArray(b64ToBytes(res.dataB64 || ''));
  };
  CryptoJS.HmacSHA1 = function (d, key) {
    var res = cryptoOp({ algo: 'hmac-sha1', dataB64: bytesToB64(toBytes(d)), keyB64: bytesToB64(toBytes(key)) });
    return new WordArray(b64ToBytes(res.dataB64 || ''));
  };

  function evpBytesToKey(passwordBytes, saltBytes, keyLen, ivLen) {
    var out = new Uint8Array(0);
    var prev = new Uint8Array(0);
    while (out.length < keyLen + ivLen) {
      var input = new Uint8Array(prev.length + passwordBytes.length + (saltBytes ? saltBytes.length : 0));
      input.set(prev);
      input.set(passwordBytes, prev.length);
      if (saltBytes) input.set(saltBytes, prev.length + passwordBytes.length);
      var res = cryptoOp({ algo: 'md5', dataB64: bytesToB64(input) });
      prev = b64ToBytes(res.dataB64 || '');
      var merged = new Uint8Array(out.length + prev.length);
      merged.set(out); merged.set(prev, out.length);
      out = merged;
    }
    return {
      key: new WordArray(out.subarray(0, keyLen)),
      iv: new WordArray(out.subarray(keyLen, keyLen + ivLen))
    };
  }
  function CipherParams(dataBytes, key, iv, salt) {
    this.ciphertext = new WordArray(dataBytes);
    this.key = key || null;
    this.iv = iv || null;
    this.salt = salt || null;
  }
  CipherParams.prototype.toString = function () {
    if (this.salt) {
      var marker = utf8ToBytes('Salted__');
      var all = new Uint8Array(marker.length + this.salt.length + this.ciphertext._bytes.length);
      all.set(marker);
      all.set(this.salt, marker.length);
      all.set(this.ciphertext._bytes, marker.length + this.salt.length);
      return bytesToB64(all);
    }
    return bytesToB64(this.ciphertext._bytes);
  };
  CryptoJS.lib.CipherParams = { create: function (o) { var cp = new CipherParams(new Uint8Array(0)); if (o) { cp.ciphertext = o.ciphertext || cp.ciphertext; cp.key = o.key || null; cp.iv = o.iv || null; cp.salt = o.salt || null; } return cp; } };
  CryptoJS.format = {
    OpenSSL: {
      stringify: function (cp) { return cp.toString(); },
      parse: function (str) {
        var bytes = b64ToBytes(String(str).replace(/\s/g, ''));
        var head = bytesToLatin1(bytes.subarray(0, 8));
        if (head === 'Salted__') {
          return new CipherParams(bytes.subarray(16), null, null, bytes.subarray(8, 16));
        }
        return new CipherParams(bytes, null, null, null);
      }
    }
  };
  CryptoJS.mode = { CBC: { _name: 'CBC' }, ECB: { _name: 'ECB' }, CFB: { _name: 'CFB' }, OFB: { _name: 'OFB' }, CTR: { _name: 'CTR' } };
  CryptoJS.pad = { Pkcs7: { _name: 'Pkcs7' }, NoPadding: { _name: 'NoPadding' }, ZeroPadding: { _name: 'ZeroPadding' }, Iso97971: { _name: 'Pkcs7' }, AnsiX923: { _name: 'Pkcs7' } };

  function aesCrypt(encrypt, data, key, cfg) {
    cfg = cfg || {};
    var mode = cfg.mode && cfg.mode._name ? cfg.mode._name : 'CBC';
    if (mode !== 'CBC' && mode !== 'ECB') throw new Error('Nuvio host: unsupported AES mode ' + mode);
    var padding = cfg.padding && cfg.padding._name ? cfg.padding._name : 'Pkcs7';
    var keyBytes = toBytes(key);
    var ivBytes = cfg.iv ? toBytes(cfg.iv) : new Uint8Array(16);
    var res = cryptoOp({
      algo: encrypt ? 'aes-encrypt' : 'aes-decrypt',
      dataB64: bytesToB64(toBytes(data)),
      keyB64: bytesToB64(keyBytes),
      ivB64: bytesToB64(ivBytes),
      mode: mode,
      padding: padding
    });
    return b64ToBytes(res.dataB64 || '');
  }
  CryptoJS.AES = {
    encrypt: function (data, key, cfg) {
      cfg = cfg || {};
      if (typeof key === 'string') {
        var saltRes = cryptoOp({ algo: 'random', n: 8 });
        var salt = b64ToBytes(saltRes.dataB64 || '');
        var derived = evpBytesToKey(utf8ToBytes(key), salt, 32, 16);
        var ct = aesCrypt(true, toBytes(data), derived.key, { iv: derived.iv, mode: cfg.mode, padding: cfg.padding });
        return new CipherParams(ct, derived.key, derived.iv, salt);
      }
      var ct2 = aesCrypt(true, toBytes(data), key, cfg);
      return new CipherParams(ct2, key instanceof WordArray ? key : null, cfg.iv || null, null);
    },
    decrypt: function (data, key, cfg) {
      cfg = cfg || {};
      var ctBytes;
      var salt = null;
      if (typeof data === 'string') {
        var parsed = CryptoJS.format.OpenSSL.parse(data);
        ctBytes = parsed.ciphertext._bytes;
        salt = parsed.salt;
      } else if (data instanceof CipherParams) {
        ctBytes = data.ciphertext._bytes;
        salt = data.salt;
      } else {
        ctBytes = toBytes(data);
      }
      if (typeof key === 'string') {
        var derived = evpBytesToKey(utf8ToBytes(key), salt, 32, 16);
        return new WordArray(aesCrypt(false, ctBytes, derived.key, { iv: derived.iv, mode: cfg.mode, padding: cfg.padding }));
      }
      return new WordArray(aesCrypt(false, ctBytes, key, cfg));
    }
  };
  CryptoJS.enc.Base64url = {
    stringify: function (wa) { return bytesToB64(toBytes(wa)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); },
    parse: function (s) { return new WordArray(b64ToBytes(String(s).replace(/-/g, '+').replace(/_/g, '/'))); }
  };
  G.CryptoJS = CryptoJS;

  G.crypto = {
    getRandomValues: function (arr) {
      var res = cryptoOp({ algo: 'random', n: arr.byteLength });
      var bytes = b64ToBytes(res.dataB64 || '');
      var view = new Uint8Array(arr.buffer, arr.byteOffset || 0, arr.byteLength);
      view.set(bytes.subarray(0, arr.byteLength));
      return arr;
    },
    randomUUID: function () {
      var res = cryptoOp({ algo: 'random', n: 16 });
      var b = b64ToBytes(res.dataB64 || '');
      b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
      function h(x) { var s = x.toString(16); return s.length < 2 ? '0' + s : s; }
      return h(b[0]) + h(b[1]) + h(b[2]) + h(b[3]) + '-' + h(b[4]) + h(b[5]) + '-' + h(b[6]) + h(b[7]) + '-' + h(b[8]) + h(b[9]) + '-' + h(b[10]) + h(b[11]) + h(b[12]) + h(b[13]) + h(b[14]) + h(b[15]);
    },
    subtle: {
      digest: function (algo, data) {
        var name = String((algo && algo.name) || algo).toLowerCase().replace('-', '');
        var bytes = data instanceof Uint8Array ? data : new Uint8Array(data && data.buffer ? data.buffer : data);
        try {
          var res = cryptoOp({ algo: name, dataB64: bytesToB64(bytes) });
          return Promise.resolve(b64ToBytes(res.dataB64 || '').buffer);
        } catch (e) { return Promise.reject(e); }
      }
    }
  };

  // ------------------------------------------------------------- axios shim
  function axiosReq(cfg, defaults) {
    cfg = cfg || {};
    var merged = {};
    var src;
    for (src in (defaults || {})) merged[src] = defaults[src];
    for (src in cfg) merged[src] = cfg[src];
    cfg = merged;
    var url = cfg.url || '';
    if (cfg.baseURL && url.indexOf('http') !== 0) {
      url = String(cfg.baseURL).replace(/\/$/, '') + '/' + url.replace(/^\//, '');
    }
    if (cfg.params) {
      var qs = new URLSearchParams(cfg.params).toString();
      if (qs) url += (url.indexOf('?') >= 0 ? '&' : '?') + qs;
    }
    var headers = {};
    if (cfg.headers) { for (var hk in cfg.headers) headers[hk] = cfg.headers[hk]; }
    var data = cfg.data;
    var body = null;
    if (data != null) {
      if (typeof data === 'string') body = data;
      else if (data instanceof URLSearchParams) body = data.toString();
      else { body = JSON.stringify(data); if (!headers['Content-Type'] && !headers['content-type']) headers['Content-Type'] = 'application/json'; }
    }
    return G.fetch(url, { method: (cfg.method || 'get').toUpperCase(), headers: headers, body: body }).then(function (r) {
      return r.text().then(function (t) {
        var parsed = t;
        var ct = r.headers.get('content-type') || '';
        if (ct.indexOf('json') >= 0 || cfg.responseType === 'json') {
          try { parsed = JSON.parse(t); } catch (e) {}
        }
        var respHeaders = {};
        r.headers.forEach(function (v, k) { respHeaders[k] = v; });
        var resp = { data: parsed, status: r.status, statusText: r.statusText, headers: respHeaders, config: cfg };
        var validate = cfg.validateStatus;
        var bad = validate ? !validate(r.status) : (r.status >= 400);
        if (bad) {
          var err = new Error('Request failed with status code ' + r.status);
          err.response = resp; err.config = cfg; err.isAxiosError = true;
          throw err;
        }
        return resp;
      });
    });
  }
  function makeAxios(defaults) {
    var inst = function (cfg) { return axiosReq(cfg, defaults); };
    inst.get = function (url, cfg) { return axiosReq(Object.assign({}, cfg || {}, { url: url, method: 'get' }), defaults); };
    inst.post = function (url, data, cfg) { return axiosReq(Object.assign({}, cfg || {}, { url: url, method: 'post', data: data }), defaults); };
    inst.put = function (url, data, cfg) { return axiosReq(Object.assign({}, cfg || {}, { url: url, method: 'put', data: data }), defaults); };
    inst.patch = function (url, data, cfg) { return axiosReq(Object.assign({}, cfg || {}, { url: url, method: 'patch', data: data }), defaults); };
    inst.delete = function (url, cfg) { return axiosReq(Object.assign({}, cfg || {}, { url: url, method: 'delete' }), defaults); };
    inst.head = function (url, cfg) { return axiosReq(Object.assign({}, cfg || {}, { url: url, method: 'head' }), defaults); };
    inst.request = function (cfg) { return axiosReq(cfg, defaults); };
    inst.create = function (d2) { return makeAxios(Object.assign({}, defaults || {}, d2 || {})); };
    inst.defaults = defaults || { headers: { common: {} } };
    inst.isAxiosError = function () { return false; };
    return inst;
  }
  var axiosModule = makeAxios(null);

  // ------------------------------------------------------------- cheerio (minimal)
  function decodeEntities(s) {
    if (!s || s.indexOf('&') < 0) return s || '';
    return s.replace(/&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);/g, function (whole, ent) {
      if (ent === 'amp') return '&';
      if (ent === 'lt') return '<';
      if (ent === 'gt') return '>';
      if (ent === 'quot') return '"';
      if (ent === 'apos') return "'";
      if (ent === 'nbsp') return ' ';
      if (ent.charAt(0) === '#') {
        var code = ent.charAt(1) === 'x' || ent.charAt(1) === 'X'
          ? parseInt(ent.substring(2), 16) : parseInt(ent.substring(1), 10);
        return isNaN(code) ? whole : String.fromCharCode(code);
      }
      return whole;
    });
  }
  var VOID_TAGS = { area: 1, base: 1, br: 1, col: 1, embed: 1, hr: 1, img: 1, input: 1, link: 1, meta: 1, param: 1, source: 1, track: 1, wbr: 1 };
  function parseHTML(html) {
    html = String(html || '');
    var root = { tag: '#root', attrs: {}, children: [], parent: null };
    var stack = [root];
    function top() { return stack[stack.length - 1]; }
    function addText(t) { if (t) top().children.push({ text: t, parent: top() }); }
    var i = 0; var n = html.length;
    while (i < n) {
      var lt = html.indexOf('<', i);
      if (lt < 0) { addText(html.substring(i)); break; }
      if (lt > i) addText(html.substring(i, lt));
      if (html.substr(lt, 4) === '<!--') { var ec = html.indexOf('-->', lt); i = ec < 0 ? n : ec + 3; continue; }
      if (html.substr(lt, 2) === '<!' || html.substr(lt, 2) === '<?') { var gd = html.indexOf('>', lt); i = gd < 0 ? n : gd + 1; continue; }
      var gt = html.indexOf('>', lt);
      if (gt < 0) { addText(html.substring(lt)); break; }
      var inside = html.substring(lt + 1, gt);
      if (inside.charAt(0) === '/') {
        var ctag = inside.substring(1).trim().toLowerCase().split(/\s/)[0];
        for (var s = stack.length - 1; s > 0; s--) {
          if (stack[s].tag === ctag) { stack.length = s; break; }
        }
        i = gt + 1; continue;
      }
      var selfClose = /\/\s*$/.test(inside);
      if (selfClose) inside = inside.replace(/\/\s*$/, '');
      var m = inside.match(/^([a-zA-Z][a-zA-Z0-9-]*)/);
      if (!m) { i = gt + 1; continue; }
      var tag = m[1].toLowerCase();
      var attrStr = inside.substring(m[0].length);
      var attrs = {};
      var attrRe = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
      var am;
      while ((am = attrRe.exec(attrStr)) !== null) {
        var av = am[2] !== undefined ? am[2] : (am[3] !== undefined ? am[3] : (am[4] !== undefined ? am[4] : ''));
        attrs[am[1].toLowerCase()] = decodeEntities(av);
      }
      var node = { tag: tag, attrs: attrs, children: [], parent: top() };
      top().children.push(node);
      i = gt + 1;
      if (tag === 'script' || tag === 'style') {
        var close = html.toLowerCase().indexOf('</' + tag, i);
        var raw = close < 0 ? html.substring(i) : html.substring(i, close);
        if (raw) node.children.push({ text: raw, parent: node });
        if (close < 0) i = n;
        else { var gt2 = html.indexOf('>', close); i = gt2 < 0 ? n : gt2 + 1; }
        continue;
      }
      if (!VOID_TAGS[tag] && !selfClose) stack.push(node);
    }
    return root;
  }
  function isElement(node) { return !!node && !!node.tag && node.tag !== '#root'; }
  function elementChildren(node) {
    var out = [];
    var ch = node.children || [];
    for (var i = 0; i < ch.length; i++) if (isElement(ch[i])) out.push(ch[i]);
    return out;
  }
  function nodeText(node) {
    if (!node) return '';
    if (node.tag === undefined) return decodeEntities(node.text || '');
    var out = '';
    var ch = node.children || [];
    for (var i = 0; i < ch.length; i++) out += nodeText(ch[i]);
    return out;
  }
  function serializeNode(node) {
    if (node.tag === undefined) return node.text || '';
    var out = '<' + node.tag;
    for (var k in node.attrs) out += ' ' + k + '="' + node.attrs[k] + '"';
    out += '>';
    if (VOID_TAGS[node.tag]) return out;
    var ch = node.children || [];
    for (var i = 0; i < ch.length; i++) out += serializeNode(ch[i]);
    return out + '</' + node.tag + '>';
  }
  function serializeChildren(node) {
    var out = '';
    var ch = node.children || [];
    for (var i = 0; i < ch.length; i++) out += serializeNode(ch[i]);
    return out;
  }
  function collectDescendants(node, out) {
    var ch = node.children || [];
    for (var i = 0; i < ch.length; i++) {
      if (isElement(ch[i])) { out.push(ch[i]); collectDescendants(ch[i], out); }
    }
  }
  function parseCompound(s) {
    var comp = { tag: null, id: null, classes: [], attrs: [], pseudos: [] };
    var re = /(\*)|([a-zA-Z][\w-]*)|#([\w-]+)|\.([\w-]+)|\[([^\]=*^$|~\s]+)(?:\s*([*^$|~]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]|:([\w-]+)(?:\(([^)]*)\))?/g;
    var m;
    var firstToken = true;
    while ((m = re.exec(s)) !== null) {
      if (m[1] !== undefined) { comp.tag = '*'; }
      else if (m[2] !== undefined) { if (firstToken) comp.tag = m[2].toLowerCase(); }
      else if (m[3] !== undefined) comp.id = m[3];
      else if (m[4] !== undefined) comp.classes.push(m[4]);
      else if (m[5] !== undefined) {
        comp.attrs.push({
          name: m[5].toLowerCase(), op: m[6] || null,
          value: m[7] !== undefined ? m[7] : (m[8] !== undefined ? m[8] : (m[9] !== undefined ? m[9].trim() : null))
        });
      } else if (m[10] !== undefined) comp.pseudos.push({ name: m[10], arg: m[11] });
      firstToken = false;
    }
    return comp;
  }
  function elementSiblings(node) { return node.parent ? elementChildren(node.parent) : [node]; }
  function matchCompound(node, comp) {
    if (!isElement(node)) return false;
    if (comp.tag && comp.tag !== '*' && node.tag !== comp.tag) return false;
    if (comp.id !== null && node.attrs.id !== comp.id) return false;
    if (comp.classes.length) {
      var cls = (node.attrs.class || '').split(/\s+/);
      for (var i = 0; i < comp.classes.length; i++) if (cls.indexOf(comp.classes[i]) < 0) return false;
    }
    for (var a = 0; a < comp.attrs.length; a++) {
      var at = comp.attrs[a];
      var v = node.attrs[at.name];
      if (v === undefined) return false;
      if (at.op === '=' && v !== at.value) return false;
      if (at.op === '*=' && v.indexOf(at.value) < 0) return false;
      if (at.op === '^=' && v.indexOf(at.value) !== 0) return false;
      if (at.op === '$=' && v.substring(v.length - at.value.length) !== at.value) return false;
      if (at.op === '~=' && v.split(/\s+/).indexOf(at.value) < 0) return false;
      if (at.op === '|=' && v !== at.value && v.indexOf(at.value + '-') !== 0) return false;
    }
    for (var p = 0; p < comp.pseudos.length; p++) {
      var ps = comp.pseudos[p];
      if (ps.name === 'first' || ps.name === 'last') continue; // set-level, handled by caller
      var sibs = elementSiblings(node);
      var idx = sibs.indexOf(node);
      if (ps.name === 'first-child' && idx !== 0) return false;
      if (ps.name === 'last-child' && idx !== sibs.length - 1) return false;
      if (ps.name === 'nth-child') {
        var nth = parseInt(ps.arg, 10);
        if (!isNaN(nth) && idx !== nth - 1) return false;
      }
      if (ps.name === 'empty') { if ((node.children || []).length) return false; }
      if (ps.name === 'parent') { if (!(node.children || []).length) return false; }
    }
    return true;
  }
  function queryAll(bases, selector) {
    var groups = String(selector).split(',');
    var outAll = [];
    var seen = {};
    for (var g = 0; g < groups.length; g++) {
      var group = groups[g].trim();
      if (!group) continue;
      var tokens = group.replace(/\s*>\s*/g, ' > ').split(/\s+/).filter(function (t) { return t.length > 0; });
      var current = bases.slice();
      var childOnly = false;
      for (var t = 0; t < tokens.length; t++) {
        if (tokens[t] === '>') { childOnly = true; continue; }
        var comp = parseCompound(tokens[t]);
        var next = [];
        for (var b = 0; b < current.length; b++) {
          var cands = [];
          if (childOnly) cands = elementChildren(current[b]);
          else collectDescendants(current[b], cands);
          for (var c = 0; c < cands.length; c++) if (matchCompound(cands[c], comp)) next.push(cands[c]);
        }
        for (var q = 0; q < comp.pseudos.length; q++) {
          if (comp.pseudos[q].name === 'first') next = next.slice(0, 1);
          if (comp.pseudos[q].name === 'last') next = next.slice(next.length - 1);
        }
        current = next;
        childOnly = false;
      }
      for (var o = 0; o < current.length; o++) {
        var key = current[o]._uid;
        if (!key) { key = current[o]._uid = 'n' + Math.random() + '_' + o; }
        if (!seen[key]) { seen[key] = true; outAll.push(current[o]); }
      }
    }
    return outAll;
  }
  function CheerioWrap(nodes, root) {
    this._nodes = nodes || [];
    this._root = root;
    this.length = this._nodes.length;
    for (var i = 0; i < this._nodes.length; i++) this[i] = this._nodes[i];
  }
  function W(nodes, root) { return new CheerioWrap(nodes, root); }
  CheerioWrap.prototype.each = function (fn) {
    this._nodes.forEach(function (el, i) { fn.call(el, i, el); });
    return this;
  };
  CheerioWrap.prototype.map = function (fn) {
    var out = [];
    this._nodes.forEach(function (el, i) {
      var v = fn.call(el, i, el);
      if (v != null) out.push(v);
    });
    return { get: function () { return out; }, length: out.length, toArray: function () { return out; } };
  };
  CheerioWrap.prototype.get = function (i) {
    if (i === undefined) return this._nodes.slice();
    if (i < 0) return this._nodes[this._nodes.length + i];
    return this._nodes[i];
  };
  CheerioWrap.prototype.toArray = function () { return this._nodes.slice(); };
  CheerioWrap.prototype.eq = function (i) {
    var n = i < 0 ? this._nodes[this._nodes.length + i] : this._nodes[i];
    return W(n ? [n] : [], this._root);
  };
  CheerioWrap.prototype.first = function () { return this.eq(0); };
  CheerioWrap.prototype.last = function () { return this.eq(-1); };
  CheerioWrap.prototype.text = function () {
    var out = '';
    for (var i = 0; i < this._nodes.length; i++) out += nodeText(this._nodes[i]);
    return out;
  };
  CheerioWrap.prototype.html = function () {
    if (!this._nodes.length) return null;
    return serializeChildren(this._nodes[0]);
  };
  CheerioWrap.prototype.attr = function (name) {
    if (!this._nodes.length) return undefined;
    return this._nodes[0].attrs[String(name).toLowerCase()];
  };
  CheerioWrap.prototype.prop = function (name) { return this.attr(name); };
  CheerioWrap.prototype.val = function () { return this.attr('value'); };
  CheerioWrap.prototype.data = function (name) {
    var v = this.attr('data-' + name);
    if (v === undefined) return undefined;
    if (v === 'true') return true;
    if (v === 'false') return false;
    var num = Number(v);
    if (!isNaN(num) && v.trim() !== '') return num;
    try { return JSON.parse(v); } catch (e) { return v; }
  };
  CheerioWrap.prototype.find = function (sel) { return W(queryAll(this._nodes, sel), this._root); };
  CheerioWrap.prototype.children = function (sel) {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var kids = elementChildren(this._nodes[i]);
      for (var j = 0; j < kids.length; j++) out.push(kids[j]);
    }
    var w = W(out, this._root);
    return sel ? w.filter(sel) : w;
  };
  CheerioWrap.prototype.parent = function () {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var p = this._nodes[i].parent;
      if (p && isElement(p) && out.indexOf(p) < 0) out.push(p);
    }
    return W(out, this._root);
  };
  CheerioWrap.prototype.parents = function (sel) {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var p = this._nodes[i].parent;
      while (p && isElement(p)) { if (out.indexOf(p) < 0) out.push(p); p = p.parent; }
    }
    var w = W(out, this._root);
    return sel ? w.filter(sel) : w;
  };
  CheerioWrap.prototype.closest = function (sel) {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var node = this._nodes[i];
      while (node && isElement(node)) {
        var w = W([node], this._root);
        if (w.is(sel)) { out.push(node); break; }
        node = node.parent;
      }
    }
    return W(out, this._root);
  };
  CheerioWrap.prototype.siblings = function (sel) {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var sibs = elementSiblings(this._nodes[i]);
      for (var j = 0; j < sibs.length; j++) if (sibs[j] !== this._nodes[i] && out.indexOf(sibs[j]) < 0) out.push(sibs[j]);
    }
    var w = W(out, this._root);
    return sel ? w.filter(sel) : w;
  };
  CheerioWrap.prototype.next = function () {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var sibs = elementSiblings(this._nodes[i]);
      var idx = sibs.indexOf(this._nodes[i]);
      if (idx >= 0 && idx + 1 < sibs.length) out.push(sibs[idx + 1]);
    }
    return W(out, this._root);
  };
  CheerioWrap.prototype.prev = function () {
    var out = [];
    for (var i = 0; i < this._nodes.length; i++) {
      var sibs = elementSiblings(this._nodes[i]);
      var idx = sibs.indexOf(this._nodes[i]);
      if (idx > 0) out.push(sibs[idx - 1]);
    }
    return W(out, this._root);
  };
  CheerioWrap.prototype.filter = function (sel) {
    if (typeof sel === 'function') {
      var kept = [];
      this._nodes.forEach(function (el, i) { if (sel.call(el, i, el)) kept.push(el); });
      return W(kept, this._root);
    }
    return W(this._nodes.filter(function (el) { return matchCompound(el, parseCompound(String(sel))); }), this._root);
  };
  CheerioWrap.prototype.not = function (sel) {
    var excluded = queryAll(this._root ? [this._root] : [], sel);
    return W(this._nodes.filter(function (el) { return excluded.indexOf(el) < 0; }), this._root);
  };
  CheerioWrap.prototype.has = function (sel) {
    return W(this._nodes.filter(function (el) { return queryAll([el], sel).length > 0; }), this._root);
  };
  CheerioWrap.prototype.is = function (sel) {
    if (typeof sel === 'function') return this._nodes.some(function (el, i) { return sel.call(el, i, el); });
    for (var i = 0; i < this._nodes.length; i++) {
      if (matchCompound(this._nodes[i], parseCompound(String(sel)))) return true;
    }
    return false;
  };
  CheerioWrap.prototype.hasClass = function (name) {
    if (!this._nodes.length) return false;
    return (this._nodes[0].attrs.class || '').split(/\s+/).indexOf(name) >= 0;
  };
  CheerioWrap.prototype.addClass = function (name) {
    this._nodes.forEach(function (el) {
      var cls = (el.attrs.class || '').split(/\s+/).filter(Boolean);
      if (cls.indexOf(name) < 0) cls.push(name);
      el.attrs.class = cls.join(' ');
    });
    return this;
  };
  CheerioWrap.prototype.removeClass = function (name) {
    this._nodes.forEach(function (el) {
      el.attrs.class = (el.attrs.class || '').split(/\s+/).filter(function (c) { return c && c !== name; }).join(' ');
    });
    return this;
  };
  CheerioWrap.prototype.remove = function () {
    this._nodes.forEach(function (el) {
      if (el.parent) {
        var idx = el.parent.children.indexOf(el);
        if (idx >= 0) el.parent.children.splice(idx, 1);
      }
    });
    return this;
  };
  function makeDollar(root) {
    var dollar = function (input, context) {
      if (typeof input === 'string') {
        var trimmed = input.replace(/^\s+/, '');
        if (trimmed.charAt(0) === '<') {
          var frag = parseHTML(input);
          return W(elementChildren(frag), root);
        }
        var base = [root];
        if (context) base = context._nodes || [context];
        return W(queryAll(base, input), root);
      }
      if (input && input.tag) return W([input], root);
      if (input && input._nodes) return input;
      if (Array.isArray(input)) return W(input.filter(function (x) { return x && x.tag; }), root);
      if (typeof input === 'function') { try { input(); } catch (e) {} return W([], root); }
      return W([], root);
    };
    dollar.html = function () { return serializeChildren(root); };
    dollar.text = function () { return nodeText(root); };
    dollar.root = function () { return W([root], root); };
    return dollar;
  }
  var cheerioModule = {
    load: function (html) {
      var root = parseHTML(String(html || ''));
      return makeDollar(root);
    }
  };

  // ------------------------------------------------------------- require + misc globals
  G.require = function (name) {
    name = String(name);
    if (name === 'cheerio' || name === 'cheerio-without-node-native') return cheerioModule;
    if (name === 'crypto-js') return CryptoJS;
    if (name === 'axios') return axiosModule;
    if (name === 'node-fetch') return G.fetch;
    if (name === 'buffer') return { Buffer: BufferObj };
    if (name === 'url') return { URL: G.URL, URLSearchParams: G.URLSearchParams, parse: function (u) { return new G.URL(u); } };
    if (name === 'querystring') return {
      parse: function (s) { var p = new URLSearchParams(s); var o = {}; p.forEach(function (v, k) { o[k] = v; }); return o; },
      stringify: function (o) { return new URLSearchParams(o).toString(); }
    };
    if (name === 'util') return {
      promisify: function (f) { return f; },
      TextEncoder: G.TextEncoder, TextDecoder: G.TextDecoder,
      inspect: function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } }
    };
    if (name === 'events') return { EventEmitter: function () { this.on = function () { return this; }; this.emit = function () { return false; }; } };
    return {};
  };

  G.navigator = {
    userAgent: 'Mozilla/5.0 (Linux; Android 13; Nuvio) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
    platform: 'Android', language: 'en-US', languages: ['en-US'], onLine: true
  };
  G.location = { href: 'about:blank', protocol: 'about:', host: '', hostname: '', pathname: 'blank', search: '', hash: '', origin: 'null' };
  G.document = {
    createElement: function (tag) {
      return {
        tagName: String(tag).toUpperCase(), style: {}, children: [], textContent: '',
        setAttribute: function (k, v) { (this._attrs = this._attrs || {})[k] = v; },
        getAttribute: function (k) { return (this._attrs || {})[k] || null; },
        appendChild: function (c) { this.children.push(c); return c; }
      };
    },
    querySelector: function () { return null; },
    querySelectorAll: function () { return []; },
    getElementById: function () { return null; },
    getElementsByTagName: function () { return []; },
    addEventListener: function () {},
    documentElement: {}, body: {}, head: {}, cookie: ''
  };
  G.AbortController = function () {
    this.signal = { aborted: false, addEventListener: function () {}, removeEventListener: function () {}, throwIfAborted: function () {} };
  };
  G.AbortController.prototype.abort = function () { this.signal.aborted = true; };
  G.FormData = function () { this._entries = []; };
  G.FormData.prototype.append = function (k, v) { this._entries.push([String(k), String(v)]); };
  G.FormData.prototype.set = function (k, v) {
    this.delete(k);
    this.append(k, v);
  };
  G.FormData.prototype.get = function (k) {
    for (var i = 0; i < this._entries.length; i++) if (this._entries[i][0] === String(k)) return this._entries[i][1];
    return null;
  };
  G.FormData.prototype.getAll = function (k) {
    return this._entries.filter(function (p) { return p[0] === String(k); }).map(function (p) { return p[1]; });
  };
  G.FormData.prototype.has = function (k) { return this.get(k) !== null; };
  G.FormData.prototype.delete = function (k) {
    var key = String(k);
    this._entries = this._entries.filter(function (p) { return p[0] !== key; });
  };
  G.FormData.prototype.forEach = function (fn) {
    for (var i = 0; i < this._entries.length; i++) fn(this._entries[i][1], this._entries[i][0], this);
  };
  G.Blob = function (parts, opts) {
    this._parts = parts || [];
    this.type = (opts && opts.type) || '';
    var size = 0;
    var text = '';
    for (var i = 0; i < this._parts.length; i++) {
      var p = this._parts[i];
      if (typeof p === 'string') { size += utf8ToBytes(p).length; text += p; }
      else if (p instanceof Uint8Array) { size += p.length; text += bytesToUtf8(p); }
    }
    this.size = size;
    this._text = text;
  };
  G.Blob.prototype.text = function () { return Promise.resolve(this._text); };
  G.Blob.prototype.arrayBuffer = function () { return Promise.resolve(utf8ToBytes(this._text).buffer); };
})();
"""
}



