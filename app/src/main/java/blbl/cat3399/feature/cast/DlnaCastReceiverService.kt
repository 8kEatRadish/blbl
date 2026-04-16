package blbl.cat3399.feature.cast

import android.app.Service
import android.content.Context
import android.content.Intent
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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

    @Volatile
    private var currentTransportUri: String = ""

    @Volatile
    private var currentTransportState: String = "STOPPED"

    @Volatile
    private var currentVolume: Int = 35

    @Volatile
    private var lastRoutedTransportUri: String = ""

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
                    AppLog.d(TAG, "http accepted from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}")
                    scope.launch {
                        runCatching { handleHttpClient(client) }
                            .onFailure { AppLog.w(TAG, "handle http client failed", it) }
                        runCatching { client.close() }
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
                runCatching {
                    socket.joinGroup(group)
                }.onFailure {
                    AppLog.w(TAG, "join multicast group failed", it)
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

    private fun handleHttpClient(client: Socket) {
        client.soTimeout = 5_000
        // Use ISO-8859-1 for raw HTTP framing so Content-Length (bytes) matches read unit.
        // Later we decode body bytes as UTF-8.
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1))
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))

        val requestLine = reader.readLine()?.trim().orEmpty()
        if (requestLine.isBlank()) return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeHttpResponse(writer, status = "400 Bad Request", body = "bad request", contentType = "text/plain")
            return
        }
        val method = parts[0].uppercase(Locale.US)
        val requestTarget = parts[1]
        val routePath = normalizeRoutePath(requestTarget)
        AppLog.d(
            TAG,
            "http request method=$method path=$routePath rawTarget=$requestTarget from=${client.inetAddress?.hostAddress.orEmpty()}:${client.port}",
        )
        val headers = readHeaders(reader)
        val expect = headers["expect"].orEmpty()
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        AppLog.d(TAG, "http body meta expect=$expect transferEncoding=$transferEncoding contentLength=$contentLength")
        if (expect.contains("100-continue", ignoreCase = true)) {
            AppLog.d(TAG, "sending 100-continue for $routePath")
            writeContinueResponse(writer)
        }
        val body = readHttpBody(reader = reader, headers = headers)

        when {
            method == "GET" && routePath == DESCRIPTION_PATH -> {
                writeHttpResponse(writer, status = "200 OK", body = buildDeviceDescriptionXml(), contentType = XML_CONTENT_TYPE)
            }

            method == "GET" && routePath == SCPD_AVTRANSPORT_PATH -> {
                writeHttpResponse(writer, status = "200 OK", body = AV_TRANSPORT_SCPD_XML, contentType = XML_CONTENT_TYPE)
            }

            method == "GET" && routePath == SCPD_RENDERING_PATH -> {
                writeHttpResponse(writer, status = "200 OK", body = RENDERING_CONTROL_SCPD_XML, contentType = XML_CONTENT_TYPE)
            }

            method == "GET" && routePath == SCPD_CONNECTION_PATH -> {
                writeHttpResponse(writer, status = "200 OK", body = CONNECTION_MANAGER_SCPD_XML, contentType = XML_CONTENT_TYPE)
            }

            method == "POST" && routePath == CONTROL_AVTRANSPORT_PATH -> {
                val soapAction = headers["soapaction"].orEmpty().trim().trim('"')
                val action = soapAction.substringAfter('#', "")
                val soapBody = handleAvTransportAction(action = action, body = body)
                writeHttpResponse(writer, status = "200 OK", body = soapBody, contentType = XML_CONTENT_TYPE)
            }

            method == "POST" && routePath == CONTROL_RENDERING_PATH -> {
                val soapAction = headers["soapaction"].orEmpty().trim().trim('"')
                val action = soapAction.substringAfter('#', "")
                val soapBody = handleRenderingAction(action = action, body = body)
                writeHttpResponse(writer, status = "200 OK", body = soapBody, contentType = XML_CONTENT_TYPE)
            }

            method == "POST" && routePath == CONTROL_CONNECTION_PATH -> {
                val soapAction = headers["soapaction"].orEmpty().trim().trim('"')
                val action = soapAction.substringAfter('#', "")
                val soapBody = handleConnectionAction(action = action)
                writeHttpResponse(writer, status = "200 OK", body = soapBody, contentType = XML_CONTENT_TYPE)
            }

            else -> {
                writeHttpResponse(writer, status = "404 Not Found", body = "not found", contentType = "text/plain")
            }
        }
    }

    private fun writeContinueResponse(writer: BufferedWriter) {
        writer.write("HTTP/1.1 100 Continue\r\n")
        writer.write("\r\n")
        writer.flush()
    }

    private fun readHttpBody(
        reader: BufferedReader,
        headers: Map<String, String>,
    ): String {
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        return if (transferEncoding.contains("chunked", ignoreCase = true)) {
            String(readChunkedBodyBytes(reader), StandardCharsets.UTF_8)
        } else {
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (contentLength <= 0) {
                ""
            } else {
                String(readFixedLengthBodyBytes(reader, contentLength), StandardCharsets.UTF_8)
            }
        }
    }

    private fun readFixedLengthBodyBytes(
        reader: BufferedReader,
        contentLength: Int,
    ): ByteArray {
        val chars = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(chars, read, contentLength - read)
            if (n <= 0) break
            read += n
        }
        if (read <= 0) return ByteArray(0)
        val bytes = ByteArray(read)
        for (i in 0 until read) {
            bytes[i] = chars[i].code.toByte()
        }
        return bytes
    }

    private fun readChunkedBodyBytes(reader: BufferedReader): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = reader.readLine()?.trim().orEmpty()
            if (sizeLine.isBlank()) continue
            val sizeHex = sizeLine.substringBefore(';').trim()
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            if (chunkSize <= 0) {
                // Consume optional trailer headers.
                while (true) {
                    val trailer = reader.readLine() ?: break
                    if (trailer.isBlank()) break
                }
                break
            }
            val chunk = readFixedLengthBodyBytes(reader, chunkSize)
            if (chunk.isNotEmpty()) out.write(chunk)
            // Consume chunk ending CRLF.
            reader.read()
            reader.read()
        }
        return out.toByteArray()
    }

    private fun handleAvTransportAction(action: String, body: String): String {
        return when (action) {
            "SetAVTransportURI" -> {
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
                // Many senders probe this action before playback queueing.
                val uri = extractSoapValue(body, "NextURI").orEmpty()
                AppLog.i(TAG, "SetNextAVTransportURI nextUri=$uri")
                soapResponse("u:SetNextAVTransportURIResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "Play" -> {
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
                currentTransportState = "PAUSED_PLAYBACK"
                val controlled = CastPlaybackBridge.pause()
                AppLog.i(TAG, "Pause controlled=$controlled")
                soapResponse("u:PauseResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            "Stop" -> {
                currentTransportState = "STOPPED"
                val controlled = CastPlaybackBridge.stop()
                AppLog.i(TAG, "Stop controlled=$controlled")
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
                val unit = extractSoapValue(body, "Unit").orEmpty()
                val target = extractSoapValue(body, "Target").orEmpty()
                val seekMs = parseDlnaSeekTargetMs(target)
                val controlled = if (seekMs != null) CastPlaybackBridge.seekTo(seekMs) else false
                AppLog.i(TAG, "Seek unit=$unit target=$target seekMs=${seekMs ?: -1L} controlled=$controlled")
                soapResponse("u:SeekResponse", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }

            else -> {
                AppLog.w(TAG, "unsupported avtransport action=$action")
                soapResponse("u:${action}Response", "urn:schemas-upnp-org:service:AVTransport:1", "")
            }
        }
    }

    private fun handleRenderingAction(action: String, body: String): String {
        return when (action) {
            "GetVolume" -> {
                soapResponse(
                    "u:GetVolumeResponse",
                    "urn:schemas-upnp-org:service:RenderingControl:1",
                    "<CurrentVolume>$currentVolume</CurrentVolume>",
                )
            }

            "SetVolume" -> {
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

    private fun handleConnectionAction(action: String): String {
        return when (action) {
            "GetProtocolInfo" -> {
                soapResponse(
                    "u:GetProtocolInfoResponse",
                    "urn:schemas-upnp-org:service:ConnectionManager:1",
                    "<Source></Source>" +
                        "<Sink>http-get:*:*:*,rtsp-rtp-udp:*:*:*,rtsp-rtp-udp:*:video/mp4:*</Sink>",
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
        return runCatching {
            val all =
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { Collections.list(it.inetAddresses) }
                    .mapNotNull { addr ->
                        val host = addr.hostAddress?.trim().orEmpty()
                        if (host.isBlank() || host.contains(':') || addr.isLoopbackAddress) null else host
                    }
            if (peer != null) {
                all.firstOrNull { isSameSubnet24(it, peer) } ?: all.firstOrNull()
            } else {
                all.firstOrNull()
            }
        }.getOrNull()
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

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            map[name] = value
        }
        return map
    }

    private fun writeHttpResponse(
        writer: BufferedWriter,
        status: String,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $status\r\n")
        writer.write("Content-Type: $contentType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
        writer.flush()
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
