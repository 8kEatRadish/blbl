package blbl.cat3399.feature.cast

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal AirPlay receiver:
 * - Publishes _airplay._tcp and _raop._tcp via Android NSD (mDNS)
 * - Accepts basic AirPlay HTTP control endpoints (/play, /stop, /rate, /server-info)
 * - Extracts media URL and reuses the same playback routing used by DLNA
 */
class AirPlayReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0
    private val running = AtomicBoolean(false)

    private var nsdManager: NsdManager? = null
    private var airplayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var sessionState: String = "stopped"
    private var reverseSessionId: String? = null
    private var lastPlayUrl: String = ""
    private var lastPlayAtMs: Long = 0L

    private val deviceUuid: String by lazy { loadOrCreateDeviceUuid() }
    private val deviceIdHex: String by lazy { deviceUuid.replace("-", "").take(12).uppercase(Locale.US).padEnd(12, '0') }
    private val deviceIdColon: String by lazy {
        buildString {
            for (i in deviceIdHex.indices step 2) {
                if (i > 0) append(':')
                append(deviceIdHex.substring(i, i + 2))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!running.compareAndSet(false, true)) return
        startServer()
        AppLog.i(TAG, "airplay service created uuid=$deviceUuid")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        unregisterNsd()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        scope.cancel()
        super.onDestroy()
        AppLog.i(TAG, "airplay service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        serverJob =
            scope.launch {
                val server =
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(0))
                    }
                serverSocket = server
                port = server.localPort
                AppLog.i(TAG, "airplay http server started port=$port")
                registerNsd()
                while (running.get()) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    AppLog.d(TAG, "airplay accepted from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
                    scope.launch {
                        runCatching { handleClient(client) }
                            .onFailure { AppLog.w(TAG, "handle client failed", it) }
                        runCatching { client.close() }
                    }
                }
            }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 5_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val headBytes = readUntilHeaderEnd(input, HEADER_MAX_BYTES)
        if (headBytes.isEmpty()) return
        val headText = String(headBytes, StandardCharsets.ISO_8859_1)
        val headParts = headText.split("\r\n\r\n", limit = 2)
        val lines = headParts.firstOrNull()?.split("\r\n").orEmpty()
        if (lines.isEmpty()) return
        val requestLine = lines.first().trim()
        val reqParts = requestLine.split(' ')
        if (reqParts.size < 2) {
            writeResponse(output, status = "400 Bad Request", body = "bad request", contentType = "text/plain")
            return
        }
        val method = reqParts[0].uppercase(Locale.US)
        val requestTarget = reqParts[1]
        val route = normalizeRequestTarget(requestTarget)
        AppLog.d(
            TAG,
            "airplay request method=$method path=${route.path} query=${route.query.ifBlank { "-" }} from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}",
        )
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            headers[name] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, BODY_MAX_BYTES) ?: 0
        val bodyBytes = if (contentLength > 0) readFixedBytes(input, contentLength) else ByteArray(0)
        val body = String(bodyBytes, StandardCharsets.UTF_8)

        when {
            method == "OPTIONS" -> {
                writeResponse(
                    output,
                    status = "200 OK",
                    body = "",
                    contentType = "text/plain",
                    extraHeaders =
                        mapOf(
                            "Public" to "OPTIONS, SETUP, GET, POST, PUT, TEARDOWN, FLUSH, PLAY, PAUSE",
                            "Server" to "AirTunes/220.68",
                            "Apple-Jack-Status" to "connected; type=digital",
                            "Audio-Latency" to "0",
                        ),
                    requestHeaders = headers,
                )
            }

            method == "GET" && route.path == "/server-info" -> {
                AppLog.d(TAG, "airplay server-info")
                writeResponse(
                    output,
                    status = "200 OK",
                    body = buildServerInfoPlist(),
                    contentType = "text/x-apple-plist+xml",
                    requestHeaders = headers,
                )
            }

            method == "GET" && route.path == "/info" -> {
                AppLog.d(TAG, "airplay info")
                writeResponse(
                    output,
                    status = "200 OK",
                    body = buildInfoPlist(),
                    contentType = "text/x-apple-plist+xml",
                    requestHeaders = headers,
                )
            }

            method == "GET" && route.path == "/playback-info" -> {
                AppLog.d(TAG, "airplay playback-info state=$sessionState")
                val snap = CastPlaybackBridge.snapshot()
                writeResponse(
                    output,
                    status = "200 OK",
                    body = buildPlaybackInfoPlist(snapshot = snap),
                    contentType = "text/x-apple-plist+xml",
                    requestHeaders = headers,
                )
            }

            method == "POST" && route.path == "/reverse" -> {
                reverseSessionId = headers["x-apple-session-id"]?.trim()
                AppLog.i(TAG, "airplay reverse session=${reverseSessionId.orEmpty()}")
                writeResponse(
                    output,
                    status = "101 Switching Protocols",
                    body = "",
                    contentType = "text/plain",
                    extraHeaders =
                        mapOf(
                            "Upgrade" to "PTTH/1.0",
                            "Connection" to "Upgrade",
                        ),
                    requestHeaders = headers,
                )
                // Keep reverse channel alive; many iPhone senders require this upgraded socket
                // to stay open, otherwise they show "cannot connect".
                holdReverseChannel(client = client, input = input)
            }

            method == "POST" && route.path == "/setup" -> {
                AppLog.i(TAG, "airplay setup")
                writeResponse(
                    output,
                    status = "200 OK",
                    body = buildSetupPlist(),
                    contentType = "text/x-apple-plist+xml",
                    requestHeaders = headers,
                )
            }

            method == "POST" && route.path == "/play" -> {
                val url = extractPlayUrl(body = body, headers = headers)
                if (url.isNullOrBlank()) {
                    val controlled = CastPlaybackBridge.play()
                    AppLog.i(TAG, "airplay play without url controlled=$controlled")
                    if (!controlled) {
                        writeResponse(
                            output,
                            status = "400 Bad Request",
                            body = "missing url",
                            contentType = "text/plain",
                            requestHeaders = headers,
                        )
                        return
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val duplicated = lastPlayUrl == url && now - lastPlayAtMs in 0..DUPLICATE_PLAY_WINDOW_MS
                    if (duplicated) {
                        AppLog.i(TAG, "airplay duplicate play ignored")
                        writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
                        return
                    }
                    lastPlayUrl = url
                    lastPlayAtMs = now
                    sessionState = "playing"
                    AppLog.i(TAG, "airplay play url=$url")
                    DlnaCastIntentRouter.handleIncomingUri(applicationContext, url)
                }
                writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
            }

            method == "POST" && route.path == "/stop" -> {
                sessionState = "stopped"
                val controlled = CastPlaybackBridge.stop()
                AppLog.i(TAG, "airplay stop controlled=$controlled")
                writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
            }

            method == "POST" && route.path == "/rate" -> {
                val value = parseRateValue(path = route.rawTarget, body = body)
                sessionState = if (value <= 0.0) "paused" else "playing"
                val controlled =
                    if (value <= 0.0) {
                        CastPlaybackBridge.pause()
                    } else {
                        CastPlaybackBridge.play()
                    }
                AppLog.i(TAG, "airplay rate value=$value state=$sessionState controlled=$controlled")
                writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
            }

            method == "GET" && route.path == "/scrub" -> {
                // iPhone may poll /scrub for current progress.
                val snap = CastPlaybackBridge.snapshot()
                val durationSec = (snap?.durationMs ?: 0L).coerceAtLeast(0L) / 1000.0
                val positionSec = (snap?.positionMs ?: 0L).coerceAtLeast(0L) / 1000.0
                val responseBody = "duration: ${"%.6f".format(Locale.US, durationSec)}\r\nposition: ${"%.6f".format(Locale.US, positionSec)}\r\n"
                writeResponse(output, status = "200 OK", body = responseBody, contentType = "text/parameters", requestHeaders = headers)
            }

            method == "POST" && route.path == "/scrub" -> {
                // AirPlay seek command: position can be in path query or body.
                val posSec = parseScrubPositionSec(path = route.rawTarget, body = body)
                val controlled = if (posSec != null && posSec >= 0.0) CastPlaybackBridge.seekTo((posSec * 1000.0).toLong()) else false
                AppLog.i(TAG, "airplay scrub seekSec=${posSec ?: -1.0} controlled=$controlled")
                writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
            }

            method == "POST" && (route.path == "/photo" || route.path == "/feedback") -> {
                AppLog.d(TAG, "airplay endpoint accepted path=${route.path}")
                writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
            }

            else -> {
                // Return 200 for unknown control endpoints to avoid clients dropping this receiver early.
                AppLog.d(TAG, "airplay unknown endpoint path=${route.path} rawTarget=${route.rawTarget}")
                writeResponse(output, status = "200 OK", body = "", contentType = "text/plain", requestHeaders = headers)
            }
        }
    }

    /**
     * AirPlay senders are not consistent:
     * - Some send `/play?x=1`
     * - Some may send absolute URI `http://host:port/play?...`
     * Normalize everything to a stable `path` for route matching.
     */
    private fun normalizeRequestTarget(requestTarget: String): RouteTarget {
        val target =
            if (requestTarget.startsWith("http://", true) || requestTarget.startsWith("https://", true)) {
                requestTarget.substringAfter("://", requestTarget).substringAfter('/', "/")
            } else {
                requestTarget
            }
        val normalized = if (target.startsWith('/')) target else "/$target"
        val noFragment = normalized.substringBefore('#')
        val path = noFragment.substringBefore('?')
        val query = noFragment.substringAfter('?', "")
        return RouteTarget(
            rawTarget = noFragment,
            path = if (path.isBlank()) "/" else path,
            query = query,
        )
    }

    private fun registerNsd() {
        val mgr = applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr
        val airplayName = "Blbl AirPlay (${android.os.Build.MODEL})"
        val raopName = "$deviceIdHex@$airplayName"

        val airplayInfo =
            NsdServiceInfo().apply {
                serviceName = airplayName
                serviceType = "_airplay._tcp"
                this.port = this@AirPlayReceiverService.port
                setAttribute("deviceid", deviceIdColon)
                setAttribute("features", "0x5A7FFFF7,0x1E")
                setAttribute("flags", "0x4")
                setAttribute("model", "AppleTV3,2")
                setAttribute("srcvers", "220.68")
                setAttribute("rhd", "5.6.0.0")
                setAttribute("fv", "p20.10.00.4102")
                setAttribute("pk", deviceUuid.replace("-", ""))
                setAttribute("gcgl", "0")
                setAttribute("acl", "0")
                setAttribute("rsf", "0x0")
                setAttribute("vv", "2")
                setAttribute("pi", deviceUuid)
            }

        val raopInfo =
            NsdServiceInfo().apply {
                serviceName = raopName
                serviceType = "_raop._tcp"
                this.port = this@AirPlayReceiverService.port
                setAttribute("txtvers", "1")
                setAttribute("ch", "2")
                setAttribute("cn", "0,1")
                setAttribute("et", "0,3,5")
                setAttribute("md", "0,1,2")
                setAttribute("sr", "44100")
                setAttribute("ss", "16")
                setAttribute("tp", "UDP")
                setAttribute("da", "true")
                setAttribute("sv", "false")
                setAttribute("ft", "0x5A7FFFF7,0x1E")
                setAttribute("am", "AppleTV3,2")
                setAttribute("vs", "220.68")
                setAttribute("sf", "0x4")
                setAttribute("pk", deviceUuid.replace("-", ""))
                setAttribute("vn", "65537")
                setAttribute("txtvers", "1")
            }

        val airListener =
            object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "airplay register failed code=$errorCode name=${serviceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "airplay unregister failed code=$errorCode name=${serviceInfo.serviceName}")
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    AppLog.i(TAG, "airplay registered name=${serviceInfo.serviceName} type=${serviceInfo.serviceType} port=${serviceInfo.port}")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    AppLog.i(TAG, "airplay unregistered name=${serviceInfo.serviceName}")
                }
            }
        val raListener =
            object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "raop register failed code=$errorCode name=${serviceInfo.serviceName}")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "raop unregister failed code=$errorCode name=${serviceInfo.serviceName}")
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    AppLog.i(TAG, "raop registered name=${serviceInfo.serviceName} type=${serviceInfo.serviceType} port=${serviceInfo.port}")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    AppLog.i(TAG, "raop unregistered name=${serviceInfo.serviceName}")
                }
            }
        airplayListener = airListener
        raopListener = raListener
        runCatching { mgr.registerService(airplayInfo, NsdManager.PROTOCOL_DNS_SD, airListener) }
            .onFailure { AppLog.w(TAG, "register airplay failed", it) }
        runCatching { mgr.registerService(raopInfo, NsdManager.PROTOCOL_DNS_SD, raListener) }
            .onFailure { AppLog.w(TAG, "register raop failed", it) }
    }

    private fun unregisterNsd() {
        val mgr = nsdManager ?: return
        airplayListener?.let { l ->
            runCatching { mgr.unregisterService(l) }
            airplayListener = null
        }
        raopListener?.let { l ->
            runCatching { mgr.unregisterService(l) }
            raopListener = null
        }
    }

    private fun extractPlayUrl(body: String, headers: Map<String, String>): String? {
        // Different AirPlay sender versions place URL in different places:
        // 1) Content-Location header
        // 2) body line "Content-Location: ..."
        // 3) plist key Content-Location
        // 4) any http(s) URL fallback in body
        val headerUrl = headers["content-location"]?.trim().orEmpty()
        if (headerUrl.startsWith("http://", true) || headerUrl.startsWith("https://", true)) return headerUrl

        val lineRegex = Regex("(?im)^Content-Location:\\s*(\\S+)\\s*$")
        lineRegex.find(body)?.groupValues?.getOrNull(1)?.trim()?.let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) return it
        }

        val plistRegex = Regex("<key>\\s*Content-Location\\s*</key>\\s*<string>(.*?)</string>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        plistRegex.find(body)?.groupValues?.getOrNull(1)?.trim()?.let {
            if (it.startsWith("http://", true) || it.startsWith("https://", true)) return it
        }

        val httpRegex = Regex("(https?://[^\\s\"'<>]+)")
        return httpRegex.find(body)?.groupValues?.getOrNull(1)
    }

    private fun parseScrubPositionSec(path: String, body: String): Double? {
        val pathValue =
            Regex("(?i)(?:\\?|&)position=([0-9]+(?:\\.[0-9]+)?)")
                .find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
        if (pathValue != null) return pathValue

        val bodyValue =
            Regex("(?i)position\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
        return bodyValue
    }

    private fun parseRateValue(path: String, body: String): Double {
        val pathValue =
            Regex("(?i)(?:\\?|&)value=([-]?[0-9]+(?:\\.[0-9]+)?)")
                .find(path)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
        if (pathValue != null) return pathValue

        val bodyValue =
            Regex("(?i)(?:^|\\s)value\\s*[:=]\\s*([-]?[0-9]+(?:\\.[0-9]+)?)")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
        return bodyValue ?: 1.0
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var matched = 0
        val pattern = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < limit) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            if (b.toByte() == pattern[matched]) {
                matched++
                if (matched == 4) break
            } else {
                matched = if (b.toByte() == pattern[0]) 1 else 0
            }
        }
        return out.toByteArray()
    }

    private fun readFixedBytes(input: BufferedInputStream, size: Int): ByteArray {
        val target = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(target, read, size - read)
            if (n <= 0) break
            read += n
        }
        return if (read == size) target else target.copyOf(min(read, size))
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: String,
        body: String,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val cseq = requestHeaders["cseq"]?.trim().orEmpty()
        val sessionId = requestHeaders["x-apple-session-id"]?.trim().orEmpty().ifBlank { reverseSessionId.orEmpty() }
        val sb =
            StringBuilder().apply {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n")
                append("Server: AirTunes/220.68\r\n")
                append("Date: ").append(httpDate()).append("\r\n")
                if (cseq.isNotBlank()) append("CSeq: ").append(cseq).append("\r\n")
                if (sessionId.isNotBlank()) append("X-Apple-Session-ID: ").append(sessionId).append("\r\n")
                for ((k, v) in extraHeaders) {
                    append(k).append(": ").append(v).append("\r\n")
                }
                append("\r\n")
            }
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun holdReverseChannel(
        client: Socket,
        input: BufferedInputStream,
    ) {
        runCatching {
            client.soTimeout = REVERSE_SOCKET_TIMEOUT_MS
            AppLog.i(TAG, "airplay reverse channel hold start from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
            val buf = ByteArray(1024)
            while (running.get() && !client.isClosed) {
                val read =
                    try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Keep waiting; long-lived reverse channel may be idle for long periods.
                        continue
                    }
                if (read < 0) break
                // Reverse channel payload is optional for now; we only need to keep session alive.
            }
        }.onFailure { AppLog.w(TAG, "airplay reverse channel failed", it) }
        AppLog.i(TAG, "airplay reverse channel hold end")
    }

    private fun buildServerInfoPlist(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>deviceid</key><string>$deviceIdColon</string>
            <key>features</key><integer>2251799813685247</integer>
            <key>model</key><string>AppleTV3,2</string>
            <key>protovers</key><string>1.1</string>
            <key>srcvers</key><string>220.68</string>
          </dict>
        </plist>
        """.trimIndent()

    private fun buildInfoPlist(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>deviceID</key><string>$deviceIdColon</string>
            <key>features</key><integer>2251799813685247</integer>
            <key>model</key><string>AppleTV3,2</string>
            <key>name</key><string>Blbl AirPlay</string>
            <key>protovers</key><string>1.1</string>
            <key>sourceVersion</key><string>220.68</string>
            <key>statusFlags</key><integer>4</integer>
            <key>pi</key><string>$deviceUuid</string>
          </dict>
        </plist>
        """.trimIndent()

    private fun buildSetupPlist(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>eventPort</key><integer>$port</integer>
            <key>timingPort</key><integer>$port</integer>
            <key>dataPort</key><integer>$port</integer>
          </dict>
        </plist>
        """.trimIndent()

    private fun buildPlaybackInfoPlist(snapshot: CastPlaybackBridge.Snapshot?): String {
        val durationSec = (snapshot?.durationMs ?: 0L).coerceAtLeast(0L) / 1000.0
        val positionSec = (snapshot?.positionMs ?: 0L).coerceAtLeast(0L) / 1000.0
        val rate = if (snapshot?.isPlaying == true || sessionState == "playing") 1 else 0
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>readyToPlay</key><true/>
            <key>playbackBufferEmpty</key><false/>
            <key>playbackBufferFull</key><true/>
            <key>playbackLikelyToKeepUp</key><true/>
            <key>duration</key><real>${"%.6f".format(Locale.US, durationSec)}</real>
            <key>position</key><real>${"%.6f".format(Locale.US, positionSec)}</real>
            <key>rate</key><real>$rate</real>
          </dict>
        </plist>
        """.trimIndent()
    }

    private fun loadOrCreateDeviceUuid(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_UUID, null)?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val seed =
            buildString {
                append(packageName)
                append('|')
                append(android.os.Build.MANUFACTURER)
                append('|')
                append(android.os.Build.MODEL)
                append('|')
                append(android.os.Build.ID)
            }
        val hash = MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(StandardCharsets.UTF_8))
        val derived = UUID.nameUUIDFromBytes(hash).toString()
        prefs.edit().putString(KEY_DEVICE_UUID, derived).apply()
        return derived
    }

    companion object {
        private const val TAG = "AirPlayReceiver"
        private const val PREFS_NAME = "airplay_cast"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val HEADER_MAX_BYTES = 16 * 1024
        private const val BODY_MAX_BYTES = 512 * 1024
        private const val DUPLICATE_PLAY_WINDOW_MS = 1500L
        private val REVERSE_SOCKET_TIMEOUT_MS = 120.seconds.inWholeMilliseconds.toInt()

        fun syncService(context: Context, enabled: Boolean) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AirPlayReceiverService::class.java)
            if (enabled) {
                runCatching { appContext.startService(intent) }
                    .onFailure { AppLog.w(TAG, "start airplay service failed", it) }
            } else {
                runCatching { appContext.stopService(intent) }
                    .onFailure { AppLog.w(TAG, "stop airplay service failed", it) }
            }
        }
    }

    private data class RouteTarget(
        val rawTarget: String,
        val path: String,
        val query: String,
    )

    private fun httpDate(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }
}
