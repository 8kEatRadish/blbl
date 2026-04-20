package blbl.cat3399.feature.cast

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
import java.util.concurrent.atomic.AtomicInteger
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
    private var multicastLock: WifiManager.MulticastLock? = null
    private var sessionState: String = "stopped"
    private var reverseSessionId: String? = null
    private var lastPlayUrl: String = ""
    private var lastPlayAtMs: Long = 0L
    private val activeClientCount = AtomicInteger(0)
    private val connectionSeq = AtomicInteger(0)

    private val deviceUuid: String by lazy { loadOrCreateDeviceUuid() }
    private val deviceIdHex: String by lazy { deviceUuid.replace("-", "").take(12).uppercase(Locale.US).padEnd(12, '0') }
    private val publicKeyHex: String by lazy {
        // AirPlay TXT "pk" is expected to be a 64-hex string (32 bytes public key format).
        // We expose a stable pseudo key to satisfy client-side format checks.
        MessageDigest.getInstance("SHA-256")
            .digest(deviceUuid.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { b -> "%02x".format(Locale.US, b) }
    }
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
        acquireMulticastLock()
        startServer()
        AppLog.i(TAG, "airplay service created uuid=$deviceUuid")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        CastSessionCoordinator.clearProtocol(PROTOCOL_AIRPLAY)
        unregisterNsd()
        runCatching { multicastLock?.release() }
        multicastLock = null
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
                    if (activeClientCount.incrementAndGet() > MAX_ACTIVE_CLIENTS) {
                        activeClientCount.decrementAndGet()
                        AppLog.w(TAG, "airplay reject busy from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
                        runCatching { writeServiceUnavailable(client) }
                        runCatching { client.close() }
                        continue
                    }
                    val connectionId = connectionSeq.incrementAndGet()
                    AppLog.d(TAG, "airplay accepted conn=$connectionId from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
                    scope.launch {
                        runCatching { handleClient(client = client, connectionId = connectionId) }
                            .onFailure { AppLog.w(TAG, "handle client failed conn=$connectionId", it) }
                        runCatching { client.close() }
                        activeClientCount.decrementAndGet()
                    }
                }
            }
    }

    private fun handleClient(
        client: Socket,
        connectionId: Int,
    ) {
        val connStartMs = System.currentTimeMillis()
        var closeReason = "normal"
        client.soTimeout = 5_000
        client.keepAlive = true
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val headBytes = readUntilHeaderEnd(input, HEADER_MAX_BYTES)
        if (headBytes.isEmpty()) {
            closeReason = "empty-header"
            return
        }
        val headText = String(headBytes, StandardCharsets.ISO_8859_1)
        val headParts = headText.split("\r\n\r\n", limit = 2)
        val lines = headParts.firstOrNull()?.split("\r\n").orEmpty()
        if (lines.isEmpty()) {
            closeReason = "no-header-lines"
            return
        }
        val requestLine = lines.first().trim()
        val reqParts = requestLine.split(' ')
        if (reqParts.size < 2) {
            closeReason = "bad-request-line"
            writeResponse(output, status = "400 Bad Request", body = "bad request", contentType = "text/plain")
            return
        }
        val method = reqParts[0].uppercase(Locale.US)
        val requestTarget = reqParts[1]
        val route = normalizeRequestTarget(requestTarget)
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
        val requestPhase = classifyAirPlayPhase(method = method, path = route.path)
        val reqStartMs = System.currentTimeMillis()
        AppLog.d(
            TAG,
            "airplay request conn=$connectionId phase=$requestPhase method=$method path=${route.path} query=${route.query.ifBlank { "-" }} cseq=${headers["cseq"].orEmpty().ifBlank { "-" }} session=${headers["x-apple-session-id"].orEmpty().ifBlank { "-" }} ua=${summarizeHeader(headers["user-agent"])} ct=${summarizeHeader(headers["content-type"])} from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}",
        )
        val requesterId = buildRequesterId(client = client, headers = headers)

        val transferEncoding = headers["transfer-encoding"].orEmpty()
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        var responseStatus = "200 OK"
        var responseBodyBytes = 0
        var responseContentType = "text/plain"
        fun respond(
            status: String,
            body: String,
            contentType: String,
            extraHeaders: Map<String, String> = emptyMap(),
            connectionHeader: String = "close",
            includeContentLength: Boolean = true,
        ) {
            responseStatus = status
            responseBodyBytes = body.toByteArray(StandardCharsets.UTF_8).size
            responseContentType = contentType
            writeResponse(
                output = output,
                status = status,
                body = body,
                contentType = contentType,
                extraHeaders = extraHeaders,
                connectionHeader = connectionHeader,
                includeContentLength = includeContentLength,
                requestHeaders = headers,
            )
            AppLog.d(
                TAG,
                "airplay response conn=$connectionId phase=$requestPhase status=$status bodyBytes=$responseBodyBytes elapsedMs=${System.currentTimeMillis() - reqStartMs}",
            )
        }
        if (!transferEncoding.contains("chunked", ignoreCase = true) && contentLength > BODY_MAX_BYTES) {
            AppLog.w(TAG, "airplay request too large contentLength=$contentLength")
            closeReason = "payload-too-large"
            respond(status = "413 Payload Too Large", body = "payload too large", contentType = "text/plain")
            return
        }
        val bodyBytes =
            if (transferEncoding.contains("chunked", ignoreCase = true)) {
                readChunkedBody(input = input, maxBytes = BODY_MAX_BYTES)
            } else if (contentLength > 0) {
                readFixedBytes(input, contentLength)
            } else {
                ByteArray(0)
            }
        val body = String(bodyBytes, StandardCharsets.UTF_8)

        when {
            method == "OPTIONS" -> {
                respond(
                    status = "200 OK",
                    body = "",
                    contentType = "text/plain",
                    extraHeaders =
                        mapOf(
                            "Public" to "OPTIONS, SETUP, GET, POST, PUT, TEARDOWN, FLUSH, PLAY, PAUSE, RECORD",
                            "Server" to "AirTunes/220.68",
                            "Apple-Jack-Status" to "connected; type=digital",
                            "Audio-Latency" to "0",
                        ),
                )
            }

            method == "GET" && route.path == "/server-info" -> {
                AppLog.d(TAG, "airplay server-info")
                respond(
                    status = "200 OK",
                    body = buildServerInfoPlist(),
                    contentType = "text/x-apple-plist+xml",
                )
            }

            method == "GET" && route.path == "/info" -> {
                AppLog.d(TAG, "airplay info")
                respond(
                    status = "200 OK",
                    body = buildInfoPlist(),
                    contentType = "text/x-apple-plist+xml",
                )
            }

            method == "GET" && route.path == "/playback-info" -> {
                AppLog.d(TAG, "airplay playback-info state=$sessionState")
                val snap = CastPlaybackBridge.snapshot()
                respond(
                    status = "200 OK",
                    body = buildPlaybackInfoPlist(snapshot = snap),
                    contentType = "text/x-apple-plist+xml",
                )
            }

            method == "POST" && route.path == "/reverse" -> {
                reverseSessionId = headers["x-apple-session-id"]?.trim()
                AppLog.i(TAG, "airplay reverse session=${reverseSessionId.orEmpty()}")
                respond(
                    status = "101 Switching Protocols",
                    body = "",
                    contentType = "text/plain",
                    extraHeaders =
                        mapOf(
                            "Upgrade" to "PTTH/1.0",
                        ),
                    connectionHeader = "Upgrade",
                    includeContentLength = false,
                )
                // Keep reverse channel alive; many iPhone senders require this upgraded socket
                // to stay open, otherwise they show "cannot connect".
                holdReverseChannel(client = client, input = input)
                closeReason = "reverse-channel-end"
            }

            (method == "POST" || method == "SETUP") && route.path == "/setup" -> {
                AppLog.i(TAG, "airplay setup phase=control")
                respond(
                    status = "200 OK",
                    body = buildSetupPlist(),
                    contentType = "text/x-apple-plist+xml",
                )
            }

            method == "POST" && route.path == "/pair-setup" -> {
                AppLog.i(TAG, "airplay pair-setup phase=auth")
                respond(status = "200 OK", body = "", contentType = "application/octet-stream")
            }

            method == "POST" && route.path == "/pair-verify" -> {
                AppLog.i(TAG, "airplay pair-verify phase=auth")
                respond(status = "200 OK", body = "", contentType = "application/octet-stream")
            }

            method == "POST" && (route.path == "/fp-setup" || route.path == "/auth-setup") -> {
                AppLog.i(TAG, "airplay fairplay setup endpoint=${route.path}")
                respond(status = "200 OK", body = "", contentType = "application/octet-stream")
            }

            (method == "POST" || method == "PUT" || method == "PLAY") && route.path == "/play" -> {
                val allowControl = CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_AIRPLAY, requesterId, reason = "play")
                if (!allowControl) {
                    closeReason = "conflict-owner"
                    respond(status = "409 Conflict", body = "another sender is active", contentType = "text/plain")
                    return
                }
                val url = extractPlayUrl(body = body, headers = headers)
                if (url.isNullOrBlank()) {
                    val controlled = CastPlaybackBridge.play()
                    AppLog.i(TAG, "airplay play without url controlled=$controlled")
                    if (!controlled) {
                        CastSessionCoordinator.releaseIfOwner(PROTOCOL_AIRPLAY, requesterId, reason = "play-no-url-no-controller")
                        closeReason = "play-missing-url"
                        respond(
                            status = "400 Bad Request",
                            body = "missing url",
                            contentType = "text/plain",
                        )
                        return
                    }
                } else {
                    val now = System.currentTimeMillis()
                    val duplicated = lastPlayUrl == url && now - lastPlayAtMs in 0..DUPLICATE_PLAY_WINDOW_MS
                    if (duplicated) {
                        AppLog.i(TAG, "airplay duplicate play ignored")
                        respond(status = "200 OK", body = "", contentType = "text/plain")
                        return
                    }
                    lastPlayUrl = url
                    lastPlayAtMs = now
                    sessionState = "playing"
                    AppLog.i(TAG, "airplay play url=$url")
                    DlnaCastIntentRouter.handleIncomingUri(applicationContext, url)
                }
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }

            method == "POST" && route.path == "/stop" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_AIRPLAY, requesterId, reason = "stop")) {
                    closeReason = "conflict-owner"
                    respond(status = "409 Conflict", body = "another sender is active", contentType = "text/plain")
                    return
                }
                sessionState = "stopped"
                val controlled = CastPlaybackBridge.stop()
                AppLog.i(TAG, "airplay stop controlled=$controlled")
                CastSessionCoordinator.releaseIfOwner(PROTOCOL_AIRPLAY, requesterId, reason = "stop")
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }

            method == "POST" && route.path == "/rate" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_AIRPLAY, requesterId, reason = "rate")) {
                    closeReason = "conflict-owner"
                    respond(status = "409 Conflict", body = "another sender is active", contentType = "text/plain")
                    return
                }
                val value = parseRateValue(path = route.rawTarget, body = body)
                sessionState = if (value <= 0.0) "paused" else "playing"
                val controlled =
                    if (value <= 0.0) {
                        CastPlaybackBridge.pause()
                    } else {
                        CastPlaybackBridge.play()
                    }
                AppLog.i(TAG, "airplay rate value=$value state=$sessionState controlled=$controlled")
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }

            method == "GET" && route.path == "/scrub" -> {
                // iPhone may poll /scrub for current progress.
                val snap = CastPlaybackBridge.snapshot()
                val durationSec = (snap?.durationMs ?: 0L).coerceAtLeast(0L) / 1000.0
                val positionSec = (snap?.positionMs ?: 0L).coerceAtLeast(0L) / 1000.0
                val responseBody = "duration: ${"%.6f".format(Locale.US, durationSec)}\r\nposition: ${"%.6f".format(Locale.US, positionSec)}\r\n"
                respond(status = "200 OK", body = responseBody, contentType = "text/parameters")
            }

            method == "POST" && route.path == "/scrub" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_AIRPLAY, requesterId, reason = "scrub")) {
                    closeReason = "conflict-owner"
                    respond(status = "409 Conflict", body = "another sender is active", contentType = "text/plain")
                    return
                }
                // AirPlay seek command: position can be in path query or body.
                val posSec = parseScrubPositionSec(path = route.rawTarget, body = body)
                val controlled = if (posSec != null && posSec >= 0.0) CastPlaybackBridge.seekTo((posSec * 1000.0).toLong()) else false
                AppLog.i(TAG, "airplay scrub seekSec=${posSec ?: -1.0} controlled=$controlled")
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }

            method == "POST" && (route.path == "/photo" || route.path == "/feedback" || route.path == "/record" || route.path == "/flush" || route.path == "/teardown" || route.path == "/stream") -> {
                AppLog.d(TAG, "airplay endpoint accepted path=${route.path}")
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }

            else -> {
                // Return 200 for unknown control endpoints to avoid clients dropping this receiver early.
                AppLog.d(TAG, "airplay unknown endpoint path=${route.path} rawTarget=${route.rawTarget}")
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }
        }
        AppLog.i(
            TAG,
            "airplay request done conn=$connectionId phase=$requestPhase method=$method path=${route.path} status=$responseStatus respType=$responseContentType bodyBytes=$responseBodyBytes elapsedMs=${System.currentTimeMillis() - reqStartMs}",
        )
        AppLog.i(
            TAG,
            "airplay connection closed conn=$connectionId reason=$closeReason totalMs=${System.currentTimeMillis() - connStartMs}",
        )
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
                setAttribute("features", AIRPLAY_FEATURES_TXT)
                setAttribute("flags", "0x4")
                setAttribute("model", AIRPLAY_MODEL)
                setAttribute("srcvers", AIRPLAY_SRC_VERS)
                setAttribute("pi", deviceUuid)
                setAttribute("pk", publicKeyHex)
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
                setAttribute("ft", AIRPLAY_FEATURES_TXT)
                setAttribute("am", AIRPLAY_MODEL)
                setAttribute("vs", AIRPLAY_SRC_VERS)
                setAttribute("sf", "0x4")
                setAttribute("vn", "65537")
                setAttribute("pk", publicKeyHex)
            }
        AppLog.i(
            TAG,
            "airplay nsd profile model=$AIRPLAY_MODEL srcvers=$AIRPLAY_SRC_VERS features=$AIRPLAY_FEATURES_TXT deviceid=$deviceIdColon pi=$deviceUuid pkLen=${publicKeyHex.length}",
        )

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

    private fun acquireMulticastLock() {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = manager.createMulticastLock("blbl-airplay-cast").apply {
            setReferenceCounted(false)
        }
        runCatching { lock.acquire() }
            .onSuccess {
                multicastLock = lock
                AppLog.i(TAG, "airplay multicast lock acquired")
            }
            .onFailure { AppLog.w(TAG, "airplay acquire multicast lock failed", it) }
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

    private fun readChunkedBody(
        input: BufferedInputStream,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input).trim()
            if (sizeLine.isBlank()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) {
                while (true) {
                    val trailer = readAsciiLine(input)
                    if (trailer.isBlank()) break
                }
                break
            }
            val remain = (maxBytes - out.size()).coerceAtLeast(0)
            if (remain <= 0) {
                skipBytes(input, size + 2)
                continue
            }
            val chunk = readFixedBytes(input, min(size, remain))
            if (chunk.isNotEmpty()) out.write(chunk)
            if (size > remain) {
                skipBytes(input, size - remain)
            }
            skipBytes(input, 2)
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: BufferedInputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() >= 8 * 1024) break
        }
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun skipBytes(
        input: BufferedInputStream,
        count: Int,
    ) {
        var remaining = count.coerceAtLeast(0)
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong())
            if (skipped > 0) {
                remaining -= skipped.toInt()
                continue
            }
            if (input.read() < 0) break
            remaining--
        }
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: String,
        body: String,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
        connectionHeader: String = "close",
        includeContentLength: Boolean = true,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val cseq = requestHeaders["cseq"]?.trim().orEmpty()
        val sessionId = requestHeaders["x-apple-session-id"]?.trim().orEmpty().ifBlank { reverseSessionId.orEmpty() }
        val sb =
            StringBuilder().apply {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                if (includeContentLength) {
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                }
                append("Connection: ").append(connectionHeader).append("\r\n")
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

    private fun writeServiceUnavailable(client: Socket) {
        val output = BufferedOutputStream(client.getOutputStream())
        writeResponse(
            output = output,
            status = "503 Service Unavailable",
            body = "receiver busy",
            contentType = "text/plain",
        )
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

    private fun buildRequesterId(
        client: Socket,
        headers: Map<String, String>,
    ): String {
        val sessionId = headers["x-apple-session-id"]?.trim().orEmpty()
        if (sessionId.isNotBlank()) return "sid:$sessionId"
        val host = client.inetAddress?.hostAddress?.trim().orEmpty()
        return if (host.isNotBlank()) "ip:$host" else "ip:unknown"
    }

    private fun buildServerInfoPlist(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>deviceid</key><string>$deviceIdColon</string>
            <key>features</key><integer>${airplayFeaturesToPlistInteger(AIRPLAY_FEATURES_TXT)}</integer>
            <key>model</key><string>$AIRPLAY_MODEL</string>
            <key>protovers</key><string>1.1</string>
            <key>srcvers</key><string>$AIRPLAY_SRC_VERS</string>
            <key>statusFlags</key><integer>4</integer>
            <key>pi</key><string>$deviceUuid</string>
            <key>pk</key><data>$publicKeyHex</data>
          </dict>
        </plist>
        """.trimIndent()

    private fun buildInfoPlist(): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
          <dict>
            <key>deviceid</key><string>$deviceIdColon</string>
            <key>deviceID</key><string>$deviceIdColon</string>
            <key>features</key><integer>${airplayFeaturesToPlistInteger(AIRPLAY_FEATURES_TXT)}</integer>
            <key>model</key><string>$AIRPLAY_MODEL</string>
            <key>name</key><string>Blbl AirPlay</string>
            <key>protovers</key><string>1.1</string>
            <key>srcvers</key><string>$AIRPLAY_SRC_VERS</string>
            <key>sourceVersion</key><string>$AIRPLAY_SRC_VERS</string>
            <key>statusFlags</key><integer>4</integer>
            <key>pi</key><string>$deviceUuid</string>
            <key>pk</key><data>$publicKeyHex</data>
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

    private fun summarizeHeader(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isBlank()) return "-"
        return if (v.length <= 96) v else "${v.take(96)}..."
    }

    private fun classifyAirPlayPhase(
        method: String,
        path: String,
    ): String {
        if (path == "/info" || path == "/server-info") return "discovery"
        if (path == "/pair-setup" || path == "/pair-verify" || path == "/fp-setup" || path == "/auth-setup") return "auth"
        if (path == "/setup" || path == "/reverse") return "setup"
        if (path == "/play" || path == "/rate" || path == "/scrub" || path == "/stop") return "control"
        if (path == "/record" || path == "/flush" || path == "/teardown" || path == "/stream") return "media"
        if (method == "OPTIONS") return "capability"
        return "other"
    }

    private fun airplayFeaturesToPlistInteger(txt: String): Long {
        val parts = txt.split(',').map { it.trim().removePrefix("0x").removePrefix("0X") }
        val low = parts.getOrNull(0)?.toLongOrNull(16) ?: 0L
        val high = parts.getOrNull(1)?.toLongOrNull(16) ?: 0L
        return (high shl 32) or (low and 0xFFFFFFFFL)
    }

    companion object {
        private const val TAG = "AirPlayReceiver"
        private const val PROTOCOL_AIRPLAY = "airplay"
        // Discovery-first profile:
        // keep iOS mirror-list visibility (feature bits) while still returning stable pi/pk identifiers.
        private const val AIRPLAY_FEATURES_TXT = "0x5A7FFFF7,0x1E"
        private const val AIRPLAY_MODEL = "AppleTV3,2"
        private const val AIRPLAY_SRC_VERS = "220.68"
        private const val PREFS_NAME = "airplay_cast"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val HEADER_MAX_BYTES = 16 * 1024
        private const val BODY_MAX_BYTES = 512 * 1024
        private const val DUPLICATE_PLAY_WINDOW_MS = 1500L
        private const val MAX_ACTIVE_CLIENTS = 32
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
