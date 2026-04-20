package blbl.cat3399.feature.cast

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.os.IBinder
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Minimal DLNA DMR receiver:
 * - SSDP discovery (M-SEARCH + NOTIFY alive/byebye)
 * - Device description + control endpoints (AVTransport / Rendering / Connection)
 * - Receives playback URL via SetAVTransportURI + Play and routes into app player
 */
class DlnaCastReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServerJob: Job? = null
    private var ssdpJob: Job? = null
    private var ssdpAnnounceJob: Job? = null
    private var httpServerSocket: ServerSocket? = null
    private var httpPort: Int = 0
    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val running = AtomicBoolean(false)
    private val activeHttpClientCount = AtomicInteger(0)
    private val httpConnectionSeq = AtomicInteger(0)

    @Volatile
    private var currentTransportUri: String = ""

    @Volatile
    private var currentTransportState: String = "STOPPED"

    @Volatile
    private var currentVolume: Int = 35

    @Volatile
    private var lastRoutedTransportUri: String = ""

    @Volatile
    private var currentPlayMode: String = "NORMAL"

    private val deviceUdn: String by lazy { "uuid:${loadOrCreateDeviceUuid()}" }

    override fun onCreate() {
        super.onCreate()
        if (!running.compareAndSet(false, true)) return
        acquireMulticastLock()
        startHttpServer()
        startSsdpResponder()
        AppLog.i(TAG, "dlna service created udn=$deviceUdn")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        CastSessionCoordinator.clearProtocol(PROTOCOL_DLNA)
        runCatching { sendSsdpNotify(byebye = true) }
        runCatching { httpServerSocket?.close() }
        runCatching { multicastSocket?.close() }
        runCatching { multicastLock?.release() }
        httpServerSocket = null
        multicastSocket = null
        multicastLock = null
        httpServerJob?.cancel()
        ssdpJob?.cancel()
        ssdpAnnounceJob?.cancel()
        scope.cancel()
        super.onDestroy()
        AppLog.i(TAG, "dlna service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHttpServer() {
        httpServerJob =
            scope.launch {
                val server =
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(0))
                    }
                httpServerSocket = server
                httpPort = server.localPort
                AppLog.i(TAG, "http server started port=$httpPort")
                while (running.get()) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (activeHttpClientCount.incrementAndGet() > MAX_ACTIVE_HTTP_CLIENTS) {
                        activeHttpClientCount.decrementAndGet()
                        AppLog.w(TAG, "http reject busy from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
                        runCatching { writeServiceUnavailable(client) }
                        runCatching { client.close() }
                        continue
                    }
                    val connectionId = httpConnectionSeq.incrementAndGet()
                    AppLog.d(TAG, "http accepted conn=$connectionId from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
                    scope.launch {
                        runCatching { handleHttpClient(client = client, connectionId = connectionId) }
                            .onFailure { AppLog.w(TAG, "handle http client failed conn=$connectionId", it) }
                        runCatching { client.close() }
                        activeHttpClientCount.decrementAndGet()
                    }
                }
            }
    }

    private fun startSsdpResponder() {
        ssdpJob =
            scope.launch {
                val socket =
                    MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(SSDP_PORT))
                    }
                multicastSocket = socket
                val group = InetAddress.getByName(SSDP_HOST)
                val joined = joinSsdpGroupOnAvailableInterfaces(socket, group)
                if (!joined) {
                    AppLog.w(TAG, "join multicast group failed on all interfaces")
                    return@launch
                }
                AppLog.i(TAG, "ssdp responder started")
                ssdpAnnounceJob?.cancel()
                ssdpAnnounceJob =
                    scope.launch {
                        // Burst announce on start so apps that only do passive discovery can see us quickly.
                        repeat(2) {
                            sendSsdpNotify(byebye = false)
                            delay(1_000L)
                        }
                        while (running.get()) {
                            delay(SSDP_ALIVE_INTERVAL_MS)
                            sendSsdpNotify(byebye = false)
                        }
                    }
                val buffer = ByteArray(8 * 1024)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!ok) break
                    val request = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    val headers = parseHttpHeaders(request)
                    val method = request.lineSequence().firstOrNull()?.trim().orEmpty()
                    if (!method.startsWith("M-SEARCH", ignoreCase = true)) continue
                    val man = headers["man"].orEmpty()
                    if (!man.contains("ssdp:discover", ignoreCase = true)) continue
                    val st = headers["st"]?.trim().orEmpty()
                    if (!isSupportedSearchTarget(st)) continue
                    AppLog.d(TAG, "m-search from=${packet.address.hostAddress}:${packet.port} st=$st")
                    sendSsdpResponse(st = st, address = packet.address, port = packet.port)
                }
            }
    }

    private fun handleHttpClient(
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

        val headerBytes = readUntilHeaderEnd(input, HTTP_HEADER_MAX_BYTES)
        if (headerBytes.isEmpty()) return
        val requestHeader = String(headerBytes, StandardCharsets.ISO_8859_1)
        val headerBlocks = requestHeader.split("\r\n\r\n", limit = 2)
        val lines = headerBlocks.firstOrNull()?.split("\r\n").orEmpty()
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isBlank()) return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeHttpResponse(output, status = "400 Bad Request", body = "bad request", contentType = "text/plain")
            return
        }
        val method = parts[0].uppercase(Locale.US)
        val requestTarget = parts[1]
        val routePath = normalizeRoutePath(requestTarget)
        val reqStartMs = System.currentTimeMillis()
        AppLog.d(
            TAG,
            "http request conn=$connectionId method=$method path=$routePath rawTarget=$requestTarget from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}",
        )
        val headers = parseHttpHeaders(requestHeader)
        AppLog.d(
            TAG,
            "http request headers conn=$connectionId soap=${summarizeHeader(headers["soapaction"])} ua=${summarizeHeader(headers["user-agent"])} callback=${summarizeHeader(headers["callback"])} sid=${headers["sid"].orEmpty().ifBlank { "-" }}",
        )
        val requesterId = buildRequesterId(client)
        val expect = headers["expect"].orEmpty()
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        AppLog.d(TAG, "http body meta expect=$expect transferEncoding=$transferEncoding contentLength=$contentLength")
        var responseStatus = "200 OK"
        var responseBodyBytes = 0
        fun respond(
            status: String,
            body: String,
            contentType: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ) {
            responseStatus = status
            val responseBody = if (method == "HEAD") "" else body
            responseBodyBytes = responseBody.toByteArray(StandardCharsets.UTF_8).size
            writeHttpResponse(
                output = output,
                status = status,
                body = responseBody,
                contentType = contentType,
                extraHeaders = extraHeaders,
            )
            AppLog.d(
                TAG,
                "http response conn=$connectionId method=$method path=$routePath status=$status bodyBytes=$responseBodyBytes elapsedMs=${System.currentTimeMillis() - reqStartMs}",
            )
        }
        if (!transferEncoding.contains("chunked", ignoreCase = true) && contentLength > HTTP_BODY_MAX_BYTES) {
            closeReason = "payload-too-large"
            respond(status = "413 Payload Too Large", body = "payload too large", contentType = "text/plain")
            return
        }
        if (expect.contains("100-continue", ignoreCase = true)) {
            AppLog.d(TAG, "sending 100-continue for $routePath")
            writeContinueResponse(output)
        }
        val body = readHttpBody(input = input, headers = headers)

        when {
            (method == "GET" || method == "HEAD") && routePath == DESCRIPTION_PATH -> {
                respond(status = "200 OK", body = buildDeviceDescriptionXml(), contentType = XML_CONTENT_TYPE)
            }

            (method == "GET" || method == "HEAD") && routePath == SCPD_AVTRANSPORT_PATH -> {
                respond(status = "200 OK", body = AV_TRANSPORT_SCPD_XML, contentType = XML_CONTENT_TYPE)
            }

            (method == "GET" || method == "HEAD") && routePath == SCPD_RENDERING_PATH -> {
                respond(status = "200 OK", body = RENDERING_CONTROL_SCPD_XML, contentType = XML_CONTENT_TYPE)
            }

            (method == "GET" || method == "HEAD") && routePath == SCPD_CONNECTION_PATH -> {
                respond(status = "200 OK", body = CONNECTION_MANAGER_SCPD_XML, contentType = XML_CONTENT_TYPE)
            }

            method == "SUBSCRIBE" && routePath == EVENT_PATH -> {
                val sid = headers["sid"].orEmpty().ifBlank { "$deviceUdn-event" }
                val timeout = headers["timeout"].orEmpty().ifBlank { "Second-1800" }
                AppLog.i(TAG, "dlna event subscribe conn=$connectionId sid=$sid timeout=$timeout")
                respond(
                    status = "200 OK",
                    body = "",
                    contentType = "text/plain",
                    extraHeaders = mapOf("SID" to sid, "TIMEOUT" to timeout),
                )
            }

            method == "UNSUBSCRIBE" && routePath == EVENT_PATH -> {
                val sid = headers["sid"].orEmpty()
                AppLog.i(TAG, "dlna event unsubscribe conn=$connectionId sid=${sid.ifBlank { "-" }}")
                respond(status = "200 OK", body = "", contentType = "text/plain")
            }

            method == "POST" && routePath == CONTROL_AVTRANSPORT_PATH -> {
                val soapAction = headers["soapaction"].orEmpty().trim().trim('"')
                val action = soapAction.substringAfter('#', "")
                val soapBody = handleAvTransportAction(action = action, body = body, requesterId = requesterId)
                respond(status = "200 OK", body = soapBody, contentType = XML_CONTENT_TYPE)
            }

            method == "POST" && routePath == CONTROL_RENDERING_PATH -> {
                val soapAction = headers["soapaction"].orEmpty().trim().trim('"')
                val action = soapAction.substringAfter('#', "")
                val soapBody = handleRenderingAction(action = action, body = body, requesterId = requesterId)
                respond(status = "200 OK", body = soapBody, contentType = XML_CONTENT_TYPE)
            }

            method == "POST" && routePath == CONTROL_CONNECTION_PATH -> {
                val soapAction = headers["soapaction"].orEmpty().trim().trim('"')
                val action = soapAction.substringAfter('#', "")
                val soapBody = handleConnectionAction(action = action, requesterId = requesterId)
                respond(status = "200 OK", body = soapBody, contentType = XML_CONTENT_TYPE)
            }

            else -> {
                closeReason = "unknown-path"
                respond(status = "404 Not Found", body = "not found", contentType = "text/plain")
            }
        }
        AppLog.i(
            TAG,
            "http request done conn=$connectionId method=$method path=$routePath status=$responseStatus bodyBytes=$responseBodyBytes elapsedMs=${System.currentTimeMillis() - reqStartMs}",
        )
        AppLog.i(TAG, "http connection closed conn=$connectionId reason=$closeReason totalMs=${System.currentTimeMillis() - connStartMs}")
    }

    private fun writeContinueResponse(output: BufferedOutputStream) {
        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readHttpBody(
        input: BufferedInputStream,
        headers: Map<String, String>,
    ): String {
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        return if (transferEncoding.contains("chunked", ignoreCase = true)) {
            String(readChunkedBodyBytes(input, HTTP_BODY_MAX_BYTES), StandardCharsets.UTF_8)
        } else {
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (contentLength <= 0) {
                ""
            } else {
                String(readFixedLengthBodyBytes(input, contentLength), StandardCharsets.UTF_8)
            }
        }
    }

    private fun readFixedLengthBodyBytes(
        input: BufferedInputStream,
        contentLength: Int,
    ): ByteArray {
        val target = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(target, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
        return if (read == contentLength) target else target.copyOf(min(read, contentLength))
    }

    private fun readChunkedBodyBytes(
        input: BufferedInputStream,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input).trim()
            if (sizeLine.isBlank()) continue
            val sizeHex = sizeLine.substringBefore(';').trim()
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                // Consume optional trailer headers.
                while (true) {
                    val trailer = readAsciiLine(input)
                    if (trailer.isBlank()) break
                }
                break
            }
            val remain = (maxBytes - out.size()).coerceAtLeast(0)
            if (remain <= 0) {
                skipBytes(input, chunkSize + 2)
                continue
            }
            val chunk = readFixedLengthBodyBytes(input, min(chunkSize, remain))
            if (chunk.isNotEmpty()) out.write(chunk)
            if (chunkSize > remain) {
                skipBytes(input, chunkSize - remain)
            }
            skipBytes(input, 2) // chunk ending CRLF
        }
        return out.toByteArray()
    }

    private fun readUntilHeaderEnd(
        input: BufferedInputStream,
        limit: Int,
    ): ByteArray {
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

    private fun handleAvTransportAction(
        action: String,
        body: String,
        requesterId: String,
    ): String {
        return when (action) {
            "SetAVTransportURI" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "SetAVTransportURI")) {
                    AppLog.i(TAG, "ignore SetAVTransportURI from non-owner requester=$requesterId")
                    return soapResponse("u:SetAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
                }
                val uri = extractSoapValue(body, "CurrentURI").orEmpty()
                if (currentTransportUri != uri) {
                    // New transport target should be routed only once on next Play.
                    lastRoutedTransportUri = ""
                }
                currentTransportUri = uri
                currentTransportState = "STOPPED"
                AppLog.i(TAG, "SetAVTransportURI uri=$uri")
                soapResponse("u:SetAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "SetNextAVTransportURI" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "SetNextAVTransportURI")) {
                    AppLog.i(TAG, "ignore SetNextAVTransportURI from non-owner requester=$requesterId")
                    return soapResponse("u:SetNextAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
                }
                // Many senders probe this action before playback queueing.
                val uri = extractSoapValue(body, "NextURI").orEmpty()
                AppLog.i(TAG, "SetNextAVTransportURI nextUri=$uri")
                soapResponse("u:SetNextAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "Play" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "Play")) {
                    AppLog.i(TAG, "ignore Play from non-owner requester=$requesterId")
                    return soapResponse("u:PlayResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
                }
                val uri = currentTransportUri
                if (uri.isNotBlank()) {
                    val alreadyRouted = lastRoutedTransportUri == uri
                    if (alreadyRouted && CastPlaybackBridge.hasController()) {
                        // Many control points repeatedly call Play while polling state.
                        // If same URI is already opened, just resume current player.
                        val controlled = CastPlaybackBridge.play()
                        currentTransportState = if (controlled) "PLAYING" else "TRANSITIONING"
                        AppLog.i(TAG, "Play resume existing uri controlled=$controlled")
                    } else {
                        currentTransportState = "TRANSITIONING"
                        DlnaCastIntentRouter.handleIncomingUri(applicationContext, uri)
                        lastRoutedTransportUri = uri
                        currentTransportState = "PLAYING"
                        AppLog.i(TAG, "Play route new uri=$uri")
                    }
                } else {
                    val controlled = CastPlaybackBridge.play()
                    AppLog.i(TAG, "Play without uri controlled=$controlled")
                }
                soapResponse("u:PlayResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "Pause" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "Pause")) {
                    AppLog.i(TAG, "ignore Pause from non-owner requester=$requesterId")
                    return soapResponse("u:PauseResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
                }
                currentTransportState = "PAUSED_PLAYBACK"
                val controlled = CastPlaybackBridge.pause()
                AppLog.i(TAG, "Pause controlled=$controlled")
                soapResponse("u:PauseResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "Stop" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "Stop")) {
                    AppLog.i(TAG, "ignore Stop from non-owner requester=$requesterId")
                    return soapResponse("u:StopResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
                }
                currentTransportState = "STOPPED"
                val controlled = CastPlaybackBridge.stop()
                AppLog.i(TAG, "Stop controlled=$controlled")
                CastSessionCoordinator.releaseIfOwner(PROTOCOL_DLNA, requesterId, reason = "Stop")
                soapResponse("u:StopResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "GetTransportInfo" -> {
                soapResponse(
                    "u:GetTransportInfoResponse",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "<CurrentTransportState>$currentTransportState</CurrentTransportState>" +
                        "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
                        "<CurrentSpeed>1</CurrentSpeed>",
                )
            }

            "GetMediaInfo" -> {
                val snap = CastPlaybackBridge.snapshot()
                val duration = formatHmsFromMs((snap?.durationMs ?: 0L).coerceAtLeast(0L))
                soapResponse(
                    "u:GetMediaInfoResponse",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "<NrTracks>1</NrTracks>" +
                        "<MediaDuration>$duration</MediaDuration>" +
                        "<CurrentURI>${xmlEscape(currentTransportUri)}</CurrentURI>" +
                        "<CurrentURIMetaData></CurrentURIMetaData>" +
                        "<NextURI></NextURI>" +
                        "<NextURIMetaData></NextURIMetaData>" +
                        "<PlayMedium>NETWORK</PlayMedium>" +
                        "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>" +
                        "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>",
                )
            }

            "GetPositionInfo" -> {
                val snap = CastPlaybackBridge.snapshot()
                val duration = formatHmsFromMs((snap?.durationMs ?: 0L).coerceAtLeast(0L))
                val position = formatHmsFromMs((snap?.positionMs ?: 0L).coerceAtLeast(0L))
                soapResponse(
                    "u:GetPositionInfoResponse",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "<Track>1</Track>" +
                        "<TrackDuration>$duration</TrackDuration>" +
                        "<TrackMetaData></TrackMetaData>" +
                        "<TrackURI>${xmlEscape(currentTransportUri)}</TrackURI>" +
                        "<RelTime>$position</RelTime>" +
                        "<AbsTime>$position</AbsTime>" +
                        "<RelCount>0</RelCount>" +
                        "<AbsCount>0</AbsCount>",
                )
            }

            "Seek" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "Seek")) {
                    AppLog.i(TAG, "ignore Seek from non-owner requester=$requesterId")
                    return soapResponse("u:SeekResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
                }
                val unit = extractSoapValue(body, "Unit").orEmpty()
                val target = extractSoapValue(body, "Target").orEmpty()
                val seekMs = parseDlnaSeekTargetMs(target)
                val controlled = if (seekMs != null) CastPlaybackBridge.seekTo(seekMs) else false
                AppLog.i(TAG, "Seek unit=$unit target=$target seekMs=${seekMs ?: -1L} controlled=$controlled")
                soapResponse("u:SeekResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "GetDeviceCapabilities" -> {
                soapResponse(
                    "u:GetDeviceCapabilitiesResponse",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "<PlayMedia>NETWORK</PlayMedia>" +
                        "<RecMedia>NOT_IMPLEMENTED</RecMedia>" +
                        "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>",
                )
            }

            "GetTransportSettings" -> {
                soapResponse(
                    "u:GetTransportSettingsResponse",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "<PlayMode>$currentPlayMode</PlayMode>" +
                        "<RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>",
                )
            }

            "SetPlayMode" -> {
                val mode = extractSoapValue(body, "NewPlayMode").orEmpty().ifBlank { "NORMAL" }
                currentPlayMode = mode
                AppLog.i(TAG, "SetPlayMode mode=$mode")
                soapResponse("u:SetPlayModeResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "GetCurrentTransportActions" -> {
                val actions =
                    when (currentTransportState) {
                        "PLAYING" -> "Pause,Stop,Seek"
                        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
                        else -> "Play,Stop"
                    }
                soapResponse(
                    "u:GetCurrentTransportActionsResponse",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "<Actions>$actions</Actions>",
                )
            }

            else -> {
                AppLog.w(TAG, "unsupported avtransport action=$action")
                soapResponse("u:${action}Response", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
        }
    }

    private fun handleRenderingAction(
        action: String,
        body: String,
        requesterId: String,
    ): String {
        return when (action) {
            "GetVolume" -> {
                soapResponse(
                    "u:GetVolumeResponse",
                    "urn:schemas-upnp-org:service:RenderingControl:1",
                    "<CurrentVolume>$currentVolume</CurrentVolume>",
                )
            }

            "SetVolume" -> {
                if (!CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "SetVolume")) {
                    AppLog.i(TAG, "ignore SetVolume from non-owner requester=$requesterId")
                    return soapResponse("u:SetVolumeResponse", "urn:schemas-upnp-org:service:RenderingControl:1", "")
                }
                val volume = extractSoapValue(body, "DesiredVolume")?.toIntOrNull()?.coerceIn(0, 100)
                if (volume != null) currentVolume = volume
                AppLog.i(TAG, "SetVolume volume=${volume ?: -1}")
                soapResponse("u:SetVolumeResponse", "urn:schemas-upnp-org:service:RenderingControl:1", "")
            }

            "GetMute" -> {
                soapResponse("u:GetMuteResponse", "urn:schemas-upnp-org:service:RenderingControl:1", "<CurrentMute>0</CurrentMute>")
            }

            "SetMute" -> {
                soapResponse("u:SetMuteResponse", "urn:schemas-upnp-org:service:RenderingControl:1", "")
            }

            else -> {
                AppLog.w(TAG, "unsupported rendering action=$action")
                soapResponse("u:${action}Response", "urn:schemas-upnp-org:service:RenderingControl:1", "")
            }
        }
    }

    private fun handleConnectionAction(
        action: String,
        requesterId: String,
    ): String {
        CastSessionCoordinator.tryAcquireOrTouch(PROTOCOL_DLNA, requesterId, reason = "Connection:$action")
        return when (action) {
            "GetProtocolInfo" -> {
                soapResponse(
                    "u:GetProtocolInfoResponse",
                    "urn:schemas-upnp-org:service:ConnectionManager:1",
                    "<Source></Source>" +
                        "<Sink>http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:video/x-matroska:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,rtsp-rtp-udp:*:*:*</Sink>",
                )
            }

            "GetCurrentConnectionIDs" -> {
                soapResponse(
                    "u:GetCurrentConnectionIDsResponse",
                    "urn:schemas-upnp-org:service:ConnectionManager:1",
                    "<ConnectionIDs>0</ConnectionIDs>",
                )
            }

            "GetCurrentConnectionInfo" -> {
                soapResponse(
                    "u:GetCurrentConnectionInfoResponse",
                    "urn:schemas-upnp-org:service:ConnectionManager:1",
                    "<RcsID>-1</RcsID>" +
                        "<AVTransportID>0</AVTransportID>" +
                        "<ProtocolInfo></ProtocolInfo>" +
                        "<PeerConnectionManager></PeerConnectionManager>" +
                        "<PeerConnectionID>-1</PeerConnectionID>" +
                        "<Direction>Input</Direction>" +
                        "<Status>OK</Status>",
                )
            }

            else -> {
                AppLog.w(TAG, "unsupported connection action=$action")
                soapResponse("u:${action}Response", "urn:schemas-upnp-org:service:ConnectionManager:1", "")
            }
        }
    }

    private fun sendSsdpResponse(
        st: String,
        address: InetAddress,
        port: Int,
    ) {
        val location = buildLocationUrl(address)
        if (location.isNullOrBlank()) {
            AppLog.w(TAG, "skip ssdp response because location is unavailable st=$st")
            return
        }
        val usn =
            if (st.startsWith("uuid:", ignoreCase = true)) {
                deviceUdn
            } else {
                "$deviceUdn::$st"
            }
        val payload =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("DATE: ${httpDate()}\r\n")
                append("EXT:\r\n")
                append("LOCATION: $location\r\n")
                append("SERVER: Android/1.0 UPnP/1.0 Blbl/${BuildConfig.VERSION_NAME}\r\n")
                append("ST: $st\r\n")
                append("USN: $usn\r\n")
                append("\r\n")
            }
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            DatagramSocket().use { socket ->
                socket.send(DatagramPacket(bytes, bytes.size, address, port))
            }
        }.onFailure {
            AppLog.w(TAG, "send ssdp response failed st=$st addr=${address.hostAddress}:$port", it)
        }
    }

    private fun sendSsdpNotify(byebye: Boolean) {
        val location = buildLocationUrl(null)
        if (!byebye && location.isNullOrBlank()) {
            AppLog.w(TAG, "skip ssdp alive notify because location is unavailable")
            return
        }
        val nts = if (byebye) "ssdp:byebye" else "ssdp:alive"
        val targets = ssdpNotifyTargets()
        if (targets.isEmpty()) return
        AppLog.d(TAG, "send ssdp notify nts=$nts targets=${targets.size}")

        runCatching {
            DatagramSocket().use { socket ->
                for ((nt, usn) in targets) {
                    val payload =
                        buildString {
                            append("NOTIFY * HTTP/1.1\r\n")
                            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
                            if (!byebye) {
                                append("CACHE-CONTROL: max-age=$SSDP_MAX_AGE_SEC\r\n")
                                append("LOCATION: $location\r\n")
                                append("SERVER: Android/1.0 UPnP/1.0 Blbl/${BuildConfig.VERSION_NAME}\r\n")
                            }
                            append("NT: $nt\r\n")
                            append("NTS: $nts\r\n")
                            append("USN: $usn\r\n")
                            append("\r\n")
                        }
                    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                    socket.send(
                        DatagramPacket(
                            bytes,
                            bytes.size,
                            InetAddress.getByName(SSDP_HOST),
                            SSDP_PORT,
                        ),
                    )
                }
            }
        }.onFailure {
            AppLog.w(TAG, "send ssdp notify failed nts=$nts", it)
        }
    }

    private fun ssdpNotifyTargets(): List<Pair<String, String>> {
        return listOf(
            "upnp:rootdevice" to "$deviceUdn::upnp:rootdevice",
            deviceUdn to deviceUdn,
            "urn:schemas-upnp-org:device:MediaRenderer:1" to "$deviceUdn::urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1" to "$deviceUdn::urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1" to "$deviceUdn::urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1" to "$deviceUdn::urn:schemas-upnp-org:service:ConnectionManager:1",
        )
    }

    private fun buildDeviceDescriptionXml(): String {
        val location = buildLocationUrl(null).orEmpty()
        val baseUrl = location.substringBefore(DESCRIPTION_PATH, missingDelimiterValue = location).trimEnd('/') + "/"
        val friendlyName = "Blbl TV (${android.os.Build.MODEL})"
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion>
                <major>1</major>
                <minor>0</minor>
              </specVersion>
              <URLBase>$baseUrl</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>${xmlEscape(friendlyName)}</friendlyName>
                <manufacturer>blbl</manufacturer>
                <manufacturerURL>https://github.com/</manufacturerURL>
                <modelDescription>Blbl DLNA Renderer</modelDescription>
                <modelName>Blbl TV</modelName>
                <modelNumber>${xmlEscape(BuildConfig.VERSION_NAME)}</modelNumber>
                <serialNumber>${xmlEscape(deviceUdn.removePrefix("uuid:"))}</serialNumber>
                <UDN>$deviceUdn</UDN>
                <presentationURL>$baseUrl</presentationURL>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                    <SCPDURL>$SCPD_AVTRANSPORT_PATH</SCPDURL>
                    <controlURL>$CONTROL_AVTRANSPORT_PATH</controlURL>
                    <eventSubURL>$EVENT_PATH</eventSubURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                    <SCPDURL>$SCPD_RENDERING_PATH</SCPDURL>
                    <controlURL>$CONTROL_RENDERING_PATH</controlURL>
                    <eventSubURL>$EVENT_PATH</eventSubURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                    <SCPDURL>$SCPD_CONNECTION_PATH</SCPDURL>
                    <controlURL>$CONTROL_CONNECTION_PATH</controlURL>
                    <eventSubURL>$EVENT_PATH</eventSubURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
    }

    private fun buildLocationUrl(peerAddress: InetAddress?): String? {
        val ip = findLocalIpv4Address(peerAddress) ?: return null
        if (httpPort <= 0) return null
        return "http://$ip:$httpPort$DESCRIPTION_PATH"
    }

    private fun findLocalIpv4Address(peerAddress: InetAddress?): String? {
        val peer = peerAddress?.hostAddress?.takeIf { !it.contains(':') }
        val candidates = collectLocalIpv4Candidates()
        if (candidates.isEmpty()) return null
        return if (peer != null) {
            candidates.firstOrNull { isSameSubnet24(it, peer) } ?: candidates.firstOrNull()
        } else {
            candidates.firstOrNull()
        }
    }

    private fun collectLocalIpv4Candidates(): List<String> {
        val preferred = mutableListOf<String>()
        val fallback = mutableListOf<String>()

        runCatching {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = cm?.activeNetwork
            val linkProps: LinkProperties? = if (active != null) cm?.getLinkProperties(active) else null
            val activeV4 =
                linkProps?.linkAddresses
                    ?.mapNotNull { it.address?.hostAddress?.trim() }
                    ?.firstOrNull { isUsableIpv4(it) }
            if (!activeV4.isNullOrBlank()) {
                preferred += activeV4
            }
        }

        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val ordered = interfaces.sortedWith(compareByDescending<NetworkInterface> { isPreferredInterfaceName(it.name) })
            for (itf in ordered) {
                if (!itf.isUp || itf.isLoopback) continue
                val hosts =
                    Collections.list(itf.inetAddresses)
                        .mapNotNull { addr -> addr.hostAddress?.trim() }
                        .filter { isUsableIpv4(it) }
                if (hosts.isEmpty()) continue
                if (isPreferredInterfaceName(itf.name)) {
                    preferred += hosts
                } else {
                    fallback += hosts
                }
            }
        }

        return (preferred + fallback).distinct()
    }

    private fun joinSsdpGroupOnAvailableInterfaces(
        socket: MulticastSocket,
        group: InetAddress,
    ): Boolean {
        var joined = false
        val interfaces =
            runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
                .getOrNull()
                .orEmpty()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .sortedWith(compareByDescending<NetworkInterface> { isPreferredInterfaceName(it.name) })

        for (itf in interfaces) {
            runCatching {
                socket.joinGroup(InetSocketAddress(group, SSDP_PORT), itf)
            }.onSuccess {
                joined = true
                AppLog.i(TAG, "joined ssdp group iface=${itf.name}")
            }
        }

        if (joined) return true

        runCatching { socket.joinGroup(group) }
            .onSuccess {
                joined = true
                AppLog.i(TAG, "joined ssdp group legacy")
            }
            .onFailure { AppLog.w(TAG, "join ssdp group legacy failed", it) }

        return joined
    }

    private fun isUsableIpv4(host: String): Boolean {
        if (host.isBlank() || host.contains(':')) return false
        if (host.startsWith("127.")) return false
        return true
    }

    private fun isPreferredInterfaceName(name: String?): Boolean {
        val n = name?.lowercase(Locale.US).orEmpty()
        return n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en")
    }

    private fun parseHttpHeaders(request: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val normalized = request.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            map[name] = value
        }
        return map
    }

    private fun buildRequesterId(client: Socket): String {
        val host = client.inetAddress?.hostAddress?.trim().orEmpty()
        return if (host.isNotBlank()) "ip:$host" else "ip:unknown"
    }

    private fun summarizeHeader(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.isBlank()) return "-"
        return if (v.length <= 96) v else "${v.take(96)}..."
    }

    private fun writeHttpResponse(
        output: BufferedOutputStream,
        status: String,
        body: String,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header =
            buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n")
                append("Server: Blbl-DLNA/${BuildConfig.VERSION_NAME}\r\n")
                append("Date: ${httpDate()}\r\n")
                for ((k, v) in extraHeaders) {
                    append("$k: $v\r\n")
                }
                append("\r\n")
            }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeServiceUnavailable(client: Socket) {
        val output = BufferedOutputStream(client.getOutputStream())
        writeHttpResponse(output, status = "503 Service Unavailable", body = "receiver busy", contentType = "text/plain")
    }

    private fun soapResponse(
        actionTag: String,
        serviceNs: String,
        innerXml: String,
    ): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <$actionTag xmlns:u="$serviceNs">$innerXml</$actionTag>
          </s:Body>
        </s:Envelope>
        """.trimIndent()

    private fun extractSoapValue(body: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val value = regex.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (value.isBlank()) return null
        return xmlUnescape(value)
    }

    private fun xmlEscape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun xmlUnescape(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun isSupportedSearchTarget(st: String): Boolean {
        if (st.equals("ssdp:all", ignoreCase = true)) return true
        if (st.equals("upnp:rootdevice", ignoreCase = true)) return true
        if (st.equals(deviceUdn, ignoreCase = true)) return true
        return st.equals("urn:schemas-upnp-org:device:MediaRenderer:1", ignoreCase = true) ||
            st.equals("urn:schemas-upnp-org:service:AVTransport:1", ignoreCase = true) ||
            st.equals("urn:schemas-upnp-org:service:RenderingControl:1", ignoreCase = true) ||
            st.equals("urn:schemas-upnp-org:service:ConnectionManager:1", ignoreCase = true)
    }

    private fun acquireMulticastLock() {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = manager.createMulticastLock("blbl-dlna-cast").apply {
            setReferenceCounted(false)
        }
        runCatching { lock.acquire() }
            .onSuccess { multicastLock = lock }
            .onFailure { AppLog.w(TAG, "acquire multicast lock failed", it) }
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

    private fun httpDate(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date())
    }

    private fun formatHmsFromMs(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun parseDlnaSeekTargetMs(target: String): Long? {
        val safe = target.trim().removePrefix("REL_TIME=").removePrefix("ABS_TIME=")
        if (safe.isBlank()) return null
        val value = safe.removePrefix("npt=").substringBefore('-').trim()
        if (value.isBlank()) return null
        // Supports "hh:mm:ss(.mmm)" and raw seconds "123.45".
        if (!value.contains(':')) {
            return value.toDoubleOrNull()?.let { (it * 1000.0).toLong() }?.coerceAtLeast(0L)
        }
        val parts = value.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toDoubleOrNull() ?: return null
        val totalMs = ((h * 3600L + m * 60L) * 1000L + (sec * 1000.0).toLong())
        return totalMs.coerceAtLeast(0L)
    }

    private fun isSameSubnet24(ipA: String, ipB: String): Boolean {
        val a = ipA.split('.')
        val b = ipB.split('.')
        if (a.size != 4 || b.size != 4) return false
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
    }

    /**
     * Some control points append query params to control URLs, and a few may send absolute URI.
     * Normalize request target to just the path for stable endpoint matching.
     */
    private fun normalizeRoutePath(requestTarget: String): String {
        val target =
            if (requestTarget.startsWith("http://", true) || requestTarget.startsWith("https://", true)) {
                requestTarget.substringAfter("://", requestTarget).substringAfter('/', "/")
            } else {
                requestTarget
            }
        val normalized = if (target.startsWith('/')) target else "/$target"
        return normalized.substringBefore('?').substringBefore('#')
    }

    companion object {
        fun syncService(context: Context, enabled: Boolean) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, DlnaCastReceiverService::class.java)
            if (enabled) {
                runCatching { appContext.startService(intent) }
                    .onFailure { AppLog.w(TAG, "start dlna service failed", it) }
            } else {
                runCatching { appContext.stopService(intent) }
                    .onFailure { AppLog.w(TAG, "stop dlna service failed", it) }
            }
        }

        private const val TAG = "DlnaCastReceiver"
        private const val PROTOCOL_DLNA = "dlna"
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_MAX_AGE_SEC = 1800
        private const val SSDP_ALIVE_INTERVAL_MS = 30_000L
        private const val DESCRIPTION_PATH = "/dlna/description.xml"
        private const val SCPD_AVTRANSPORT_PATH = "/dlna/scpd/avtransport.xml"
        private const val SCPD_RENDERING_PATH = "/dlna/scpd/rendering.xml"
        private const val SCPD_CONNECTION_PATH = "/dlna/scpd/connection.xml"
        private const val CONTROL_AVTRANSPORT_PATH = "/dlna/control/avtransport"
        private const val CONTROL_RENDERING_PATH = "/dlna/control/rendering"
        private const val CONTROL_CONNECTION_PATH = "/dlna/control/connection"
        private const val EVENT_PATH = "/dlna/event"
        private const val XML_CONTENT_TYPE = "text/xml; charset=\"utf-8\""
        private const val PREFS_NAME = "dlna_cast"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val HTTP_HEADER_MAX_BYTES = 16 * 1024
        private const val HTTP_BODY_MAX_BYTES = 1024 * 1024
        private const val MAX_ACTIVE_HTTP_CLIENTS = 48

        private val AV_TRANSPORT_SCPD_XML =
            """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <actionList>
                <action><name>SetAVTransportURI</name></action>
                <action><name>SetNextAVTransportURI</name></action>
                <action><name>Play</name></action>
                <action><name>Pause</name></action>
                <action><name>Stop</name></action>
                <action><name>GetTransportInfo</name></action>
                <action><name>GetMediaInfo</name></action>
                <action><name>GetPositionInfo</name></action>
                <action><name>Seek</name></action>
                <action><name>GetDeviceCapabilities</name></action>
                <action><name>GetTransportSettings</name></action>
                <action><name>SetPlayMode</name></action>
                <action><name>GetCurrentTransportActions</name></action>
              </actionList>
            </scpd>
            """.trimIndent()

        private val RENDERING_CONTROL_SCPD_XML =
            """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <actionList>
                <action><name>GetVolume</name></action>
                <action><name>SetVolume</name></action>
                <action><name>GetMute</name></action>
                <action><name>SetMute</name></action>
              </actionList>
            </scpd>
            """.trimIndent()

        private val CONNECTION_MANAGER_SCPD_XML =
            """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <actionList>
                <action><name>GetProtocolInfo</name></action>
                <action><name>GetCurrentConnectionIDs</name></action>
                <action><name>GetCurrentConnectionInfo</name></action>
              </actionList>
            </scpd>
            """.trimIndent()
    }
}
