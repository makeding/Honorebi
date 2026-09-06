package com.beeregg2001.komorebi.util

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessConfiguration
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessUrlConnection
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@UnstableApi
class TsReadExDataSource(
    private val nativeLib: NativeLib,
    var tsArgs: Array<String>,
    private val fileSizeBytesRef: AtomicLong? = null,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val cloudflareAccessConfiguration: () -> CloudflareAccessConfiguration = { CloudflareAccessConfiguration() },
    private val growingHttpStream: Boolean = false,
) : BaseDataSource(true) {

    private var handle: Long = 0
    private var connection: HttpURLConnection? = null
    private var edcbSocket: Socket? = null

    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var opened = false
    private var baseDataSpec: DataSpec? = null
    private var sourceReadPosition = 0L
    private var growingIdleRetries = 0

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 20000)
    private val tempArray = ByteArray(188 * 20000)
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(188 * 30000)

    private val nwtvId: Int
    private var lastCloseRequestTime = 0L

    companion object {
        private const val CMD_EPG_SRV_RELAY_VIEW_STREAM = 301
        private const val CMD_EPG_SRV_NWTV_ID_SET_CH = 1073
        private const val CMD_EPG_SRV_NWTV_ID_CLOSE = 1074
        private const val CMD_SUCCESS = 1
        private const val TAG = "TsReadExDataSource"
        private const val GROWING_FILE_RETRY_INTERVAL_MS = 3_000L
        private const val GROWING_FILE_MAX_IDLE_RETRIES = 20
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private val edcbTunerLock = ReentrantLock()
        private var nwtvIdCounter = 500
    }

    init {
        edcbTunerLock.withLock {
            nwtvId = nwtvIdCounter++
            if (nwtvIdCounter > 10000) nwtvIdCounter = 500
        }
    }

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        this.uri = dataSpec.uri
        baseDataSpec = dataSpec
        sourceReadPosition = dataSpec.position
        growingIdleRetries = 0
        transferInitializing(dataSpec)

        try {
            handle = nativeLib.openFilter(tsArgs)
        } catch (e: Exception) {
            throw IOException("Failed to open native filter", e)
        }

        try {
            if (dataSpec.uri.scheme == "edcb") {
                edcbTunerLock.withLock { openEdcbStream(dataSpec.uri) }
            } else {
                openHttpStream(dataSpec)
            }
        } catch (error: Throwable) {
            releaseOpenedResources()
            baseDataSpec = null
            throw error
        }

        transferStarted(dataSpec)
        opened = true

        // ★ 核心: ExoPlayer の暴走する末尾シークを完全に封殺するため、常に LENGTH_UNSET を返す
        return C.LENGTH_UNSET.toLong()
    }

    private fun openHttpStream(dataSpec: DataSpec) {
        connection = CloudflareAccessUrlConnection.open(
            initialUrl = dataSpec.uri.toString(),
            configuration = cloudflareAccessConfiguration,
            requestHeaders = requestHeaders + dataSpec.httpRequestHeaders +
                if (dataSpec.position > 0) mapOf("Range" to "bytes=${dataSpec.position}-") else emptyMap(),
        ) {
            connectTimeout = 8000
            readTimeout = 8000
            doInput = true
        }
        val responseCode = connection?.responseCode ?: -1
        if (!HttpByteRangePolicy.acceptsResponse(dataSpec.position, responseCode)) {
            throw HttpStatusException(
                responseCode,
                if (dataSpec.position > 0L && responseCode == HttpURLConnection.HTTP_OK) {
                    "Server ignored byte-range request at position ${dataSpec.position}"
                } else {
                    "Server returned code $responseCode"
                }
            )
        }

        val contentLength = connection?.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
        HttpByteRangePolicy.resourceLength(
            requestPosition = dataSpec.position,
            contentLength = contentLength,
            contentRange = connection?.getHeaderField("Content-Range"),
        )?.let { totalLength ->
            fileSizeBytesRef?.updateAndGet { knownLength -> maxOf(knownLength, totalLength) }
        }

        inputStream = BufferedInputStream(connection!!.inputStream, 188 * 50000)
    }

    private fun openEdcbStream(uri: Uri) {
        val ip = uri.host ?: throw IOException("Host not found")
        val port = if (uri.port != -1) uri.port else 4510
        val onid = uri.getQueryParameter("onid")?.toIntOrNull() ?: 0
        val tsid = uri.getQueryParameter("tsid")?.toIntOrNull() ?: 0
        val sid = uri.getQueryParameter("sid")?.toIntOrNull() ?: 0

        var targetProcessId = 0
        val startTime = System.currentTimeMillis()

        cleanupEdcbSessionSynchronous(ip, port)

        while (System.currentTimeMillis() - startTime < 10000) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 4000
                    socket.connect(InetSocketAddress(ip, port), 2000)

                    val body = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
                    body.putInt(26); body.putInt(1); body.putShort(onid.toShort()); body.putShort(tsid.toShort()); body.putShort(sid.toShort()); body.putInt(1); body.putInt(nwtvId); body.putInt(2)

                    sendEdcbCommand(socket.getOutputStream(), CMD_EPG_SRV_NWTV_ID_SET_CH, body.array())

                    val (ret, size) = readEdcbResponseHeader(socket.getInputStream())
                    if (ret == CMD_SUCCESS && size >= 4) {
                        val resData = readExactBytes(socket.getInputStream(), size)
                        if (resData != null) {
                            targetProcessId = ByteBuffer.wrap(resData).order(ByteOrder.LITTLE_ENDIAN).getInt()
                            if (targetProcessId != 0) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SetCh fail: ${e.message}")
            }
            Thread.sleep(1000)
        }

        if (targetProcessId == 0) throw IOException("EDCB SetCh failed (Tuner could not start)")

        val relayStartTime = System.currentTimeMillis()
        var relaySocket: Socket? = null
        var relayConnected = false

        while (System.currentTimeMillis() - relayStartTime < 10000) {
            try {
                val s = Socket()
                s.soTimeout = 15000
                s.connect(InetSocketAddress(ip, port), 3000)

                val relayReq = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(targetProcessId).array()
                sendEdcbCommand(s.getOutputStream(), CMD_EPG_SRV_RELAY_VIEW_STREAM, relayReq)

                val (retRelay, _) = readEdcbResponseHeader(s.getInputStream())
                if (retRelay == CMD_SUCCESS) {
                    relaySocket = s; relayConnected = true; break
                } else {
                    s.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay attempt fail: ${e.message}")
            }
            Thread.sleep(1000)
        }

        if (!relayConnected || relaySocket == null) {
            cleanupEdcbSessionSynchronous(ip, port)
            throw IOException("EDCB Relay failed.")
        }

        this.edcbSocket = relaySocket
        this.inputStream = BufferedInputStream(edcbSocket!!.getInputStream(), 188 * 30000)
    }

    private fun cleanupEdcbSessionSynchronous(ip: String, port: Int) {
        val now = System.currentTimeMillis()
        if (now - lastCloseRequestTime < 1000) return

        try {
            Socket().use { s ->
                s.soTimeout = 2000
                s.connect(InetSocketAddress(ip, port), 1500)
                val closeReq = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(nwtvId).array()
                sendEdcbCommand(s.getOutputStream(), CMD_EPG_SRV_NWTV_ID_CLOSE, closeReq)
                readEdcbResponseHeader(s.getInputStream())
                lastCloseRequestTime = System.currentTimeMillis()
            }
        } catch (e: Exception) { }
    }

    private fun cleanupEdcbSessionAsynchronous(ip: String, port: Int) {
        Thread { edcbTunerLock.withLock { cleanupEdcbSessionSynchronous(ip, port) } }.start()
    }

    private fun sendEdcbCommand(outStream: OutputStream, cmd: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd); header.putInt(data.size)
        outStream.write(header.array())
        if (data.isNotEmpty()) outStream.write(data)
        outStream.flush()
    }

    private fun readEdcbResponseHeader(ins: InputStream): Pair<Int, Int> {
        val header = readExactBytes(ins, 8) ?: throw IOException("EDCB Header missing")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return Pair(buf.getInt(), buf.getInt())
    }

    private fun readExactBytes(ins: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = ins.read(buffer, totalRead, length - totalRead)
            if (read == -1) return null
            totalRead += read
        }
        return buffer
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (inputStream == null) return C.RESULT_END_OF_INPUT

        var total = 0
        while (total < length) {
            val processed = nativeLib.popDataBuffer(handle, outputBuffer, length - total)
            if (processed > 0) {
                outputBuffer.position(0)
                outputBuffer.get(buffer, offset + total, processed)
                total += processed
            } else {
                val readCount = inputStream?.read(tempArray) ?: -1
                if (readCount == -1) {
                    if (tryReopenGrowingHttpStream()) continue
                    return if (total > 0) total else C.RESULT_END_OF_INPUT
                }
                if (readCount > 0) {
                    sourceReadPosition += readCount
                    growingIdleRetries = 0
                    inputBuffer.clear()
                    inputBuffer.put(tempArray, 0, readCount)
                    nativeLib.pushDataBuffer(handle, inputBuffer, readCount)
                }
            }
        }
        if (total > 0) bytesTransferred(total)
        return total
    }

    private fun tryReopenGrowingHttpStream(): Boolean {
        val originalSpec = baseDataSpec ?: return false
        if (!growingHttpStream || originalSpec.uri.scheme == "edcb" ||
            growingIdleRetries >= GROWING_FILE_MAX_IDLE_RETRIES
        ) return false

        growingIdleRetries += 1
        runCatching { inputStream?.close() }
        connection?.disconnect()
        inputStream = null
        connection = null
        return try {
            Thread.sleep(GROWING_FILE_RETRY_INTERVAL_MS)
            openHttpStream(
                originalSpec.buildUpon()
                    .setPosition(sourceReadPosition)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            )
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (error: HttpStatusException) {
            if (error.responseCode != HTTP_RANGE_NOT_SATISFIABLE) throw error
            Log.d(TAG, "Growing MPEG-TS source has not advanced yet", error)
            true
        } catch (error: IOException) {
            Log.d(TAG, "Growing MPEG-TS source has not advanced yet", error)
            true
        }
    }

    override fun close() {
        if (opened) {
            transferEnded(); opened = false
        }
        try {
            uri?.let {
                if (it.scheme == "edcb") {
                    val ip = it.host
                    val port = if (it.port != -1) it.port else 4510
                    if (ip != null) cleanupEdcbSessionAsynchronous(ip, port)
                }
            }
            releaseOpenedResources()
        } finally {
            inputStream = null; connection = null; edcbSocket = null; baseDataSpec = null
        }
    }

    private fun releaseOpenedResources() {
        runCatching { inputStream?.close() }
        connection?.disconnect()
        runCatching { edcbSocket?.close() }
        inputStream = null
        connection = null
        edcbSocket = null
        if (handle != 0L) {
            nativeLib.closeFilter(handle)
            handle = 0L
        }
    }

    private class HttpStatusException(
        val responseCode: Int,
        message: String,
    ) : IOException(message)
}
