package org.phoenixframework.channels

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.phoenixframework.channels.data.EmptyPayload
import org.phoenixframework.channels.data.JsonPayload
import org.phoenixframework.channels.data.Plugin
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Socket @JvmOverloads constructor(
        private val endpointUri: String,
        private val heartbeatInterval: Int = DEFAULT_HEARTBEAT_INTERVAL
) {

    private val channels = ArrayList<Channel>()

    private val errorCallbacks = Collections.newSetFromMap(HashMap<ErrorCallback, Boolean>())

    private var heartbeatTimerTask: TimerTask? = null

    private val httpClient = OkHttpClient.Builder()

    private val messageCallbacks = Collections.newSetFromMap(HashMap<MessageCallback, Boolean>())

    private var reconnectOnFailure = true

    private var reconnectTimerTask: TimerTask? = null

    private var refNo = 1

    private val sendBuffer = LinkedBlockingQueue<RequestBody>()

    private val socketCloseCallbacks = Collections
            .newSetFromMap(HashMap<SocketCloseCallback, Boolean>())

    private val socketOpenCallbacks = Collections
            .newSetFromMap(HashMap<SocketOpenCallback, Boolean>())

    private val timer = Timer("Reconnect Timer for $endpointUri")

    private var webSocket: WebSocket? = null

    /**
     * Annotated WS Endpoint. Private member to prevent confusion with "onConn*" registration
     * methods.
     */
    private val wsListener = PhoenixWSListener()

    /**
     * @return true if the socket connection is connected
     */
    val isConnected: Boolean
        get() = webSocket !=
                null

    inner class PhoenixWSListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.trace("WebSocket onOpen: {}", webSocket)
            this@Socket.webSocket = webSocket
            cancelReconnectTimer()

            startHeartbeatTimer()

            for (socketOpenCallback in socketOpenCallbacks) {
                socketOpenCallback.invoke()
            }

            this@Socket.flushSendBuffer()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log.trace("onMessage: {}", text)

            try {
                val envelope = Plugin.fromJson(text, Envelope::class.java)
                synchronized(channels) {
                    for (channel in channels) {
                        if (channel.isMember(envelope)) {
                            channel.trigger(envelope.event, envelope)
                        }
                    }
                }

                for (callback in messageCallbacks) {
                    callback.invoke(envelope)
                }
            } catch (e: IOException) {
                log.error("Failed to read message payload", e)
            }

        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.toString())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.trace("WebSocket onClose {}/{}", code, reason)
            this@Socket.webSocket = null

            for (socketCloseCallback in socketCloseCallbacks) {
                socketCloseCallback.invoke()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.warn("WebSocket connection error", t)
            try {
                //TODO if there are multiple errorCallbacks do we really want to trigger
                //the same channel error callbacks multiple times?
                triggerChannelError()
                for (callback in errorCallbacks) {
                    callback.invoke(t.message ?: "")
                }
            } finally {
                // Assume closed on failure
                if (this@Socket.webSocket != null) {
                    try {
                        this@Socket.webSocket!!.close(1001 /*CLOSE_GOING_AWAY*/, "EOF received")
                    } finally {
                        this@Socket.webSocket = null
                    }
                }
                if (reconnectOnFailure) {
                    scheduleReconnectTimer()
                }
            }
        }
    }

    init {
        log.trace("PhoenixSocket({})", endpointUri)
    }

    /**
     * Retrieve a channel instance for the specified topic
     *
     * @param topic   The channel topic
     * @param payload The message payload
     * @return A Channel instance to be used for sending and receiving events for the topic
     */
    fun chan(topic: String, payload: JsonPayload? = null): Channel {
        log.trace("chan: {}, {}", topic, payload)
        val channel = Channel(topic, this@Socket, payload)
        synchronized(channels) {
            channels.add(channel)
        }
        return channel
    }

    @Throws(IOException::class)
    fun connect() {
        log.trace("connect")
        disconnect()
        // No support for ws:// or ws:// in okhttp. See https://github.com/square/okhttp/issues/1652
        val httpUrl = this.endpointUri.replaceFirst("^ws:".toRegex(), "http:")
                .replaceFirst("^wss:".toRegex(), "https:")
        val request = Request.Builder().url(httpUrl).build()
        webSocket = httpClient
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .pingInterval(5, TimeUnit.SECONDS)
                .build()
                .newWebSocket(request, wsListener)
    }

    @Throws(IOException::class)
    fun disconnect() {
        log.trace("disconnect")
        webSocket?.close(1001 /*CLOSE_GOING_AWAY*/, "Disconnected by client")
        cancelHeartbeatTimer()
        cancelReconnectTimer()
    }

    /**
     * Register a callback for SocketEvent.ERROR events
     *
     * @param callback The callback to receive CLOSE events
     * @return This Socket instance
     */
    fun onClose(callback: SocketCloseCallback): Socket {
        this.socketCloseCallbacks.add(callback)
        return this
    }

    /**
     * Register a callback for SocketEvent.ERROR events
     *
     * @param callback The callback to receive ERROR events
     * @return This Socket instance
     */
    fun onError(callback: ErrorCallback): Socket {
        this.errorCallbacks.add(callback)
        return this
    }

    /**
     * Register a callback for SocketEvent.MESSAGE events
     *
     * @param callback The callback to receive MESSAGE events
     * @return This Socket instance
     */
    fun onMessage(callback: MessageCallback): Socket {
        this.messageCallbacks.add(callback)
        return this
    }

    /**
     * Register a callback for SocketEvent.OPEN events
     *
     * @param callback The callback to receive OPEN events
     * @return This Socket instance
     */
    fun onOpen(callback: SocketOpenCallback): Socket {
        cancelReconnectTimer()
        this.socketOpenCallbacks.add(callback)
        return this
    }

    /**
     * Sends a message envelope on this socket
     *
     * @param envelope The message envelope
     * @return This socket instance
     * @throws IOException Thrown if the message cannot be sent
     */
    @Throws(IOException::class)
    fun push(envelope: Envelope): Socket {
        val node = JsonPayload()
        node["topic"] = envelope.topic
        node["event"] = envelope.event
        node["ref"] = envelope.getRef() ?: ""
        node["join_ref"] = envelope.joinRef ?: ""
        node["payload"] = envelope.payload ?: EmptyPayload
        val json = Plugin.toJson<Any>(node)

        log.trace("push: {}, isConnected:{}, JSON:{}", envelope, isConnected, json)

        val body = json!!.toRequestBody("text/xml".toMediaTypeOrNull())

        if (this.isConnected) {
            webSocket?.send(json)
        } else {
            this.sendBuffer.add(body)
        }

        return this
    }

    /**
     * Should the socket attempt to reconnect if websocket.onFailure is called.
     *
     * @param reconnectOnFailure reconnect value
     */
    fun reconectOnFailure(reconnectOnFailure: Boolean) {
        this.reconnectOnFailure = reconnectOnFailure
    }

    /**
     * Removes the specified channel if it is known to the socket
     *
     * @param channel The channel to be removed
     */
    fun remove(channel: Channel) {
        synchronized(channels) {
            val chanIter = channels.iterator()
            while (chanIter.hasNext()) {
                if (chanIter.next() === channel) {
                    chanIter.remove()
                    break
                }
            }
        }
    }

    fun removeAllChannels() {
        synchronized(channels) {
            channels.clear()
        }
    }

    override fun toString(): String {
        return "PhoenixSocket{" +
                "endpointUri='" + endpointUri + '\''.toString() +
                ", channels(" + channels.size + ")=" + channels +
                ", refNo=" + refNo +
                ", webSocket=" + webSocket +
                '}'.toString()
    }

    @Synchronized
    internal fun makeRef(): String {
        refNo = (refNo + 1) % Integer.MAX_VALUE
        return Integer.toString(refNo)
    }

    private fun cancelHeartbeatTimer() {
        if (this@Socket.heartbeatTimerTask != null) {
            this@Socket.heartbeatTimerTask!!.cancel()
        }
    }

    private fun cancelReconnectTimer() {
        if (this@Socket.reconnectTimerTask != null) {
            this@Socket.reconnectTimerTask!!.cancel()
        }
    }

    private fun flushSendBuffer() {
        while (this.isConnected && !this.sendBuffer.isEmpty()) {
            val body = this.sendBuffer.remove()
            this.webSocket!!.send(body.toString())
        }
    }

    /**
     * Sets up and schedules a timer task to make repeated reconnect attempts at configured
     * intervals
     */
    private fun scheduleReconnectTimer() {
        cancelReconnectTimer()
        cancelHeartbeatTimer()

        this@Socket.reconnectTimerTask = object : TimerTask() {
            override fun run() {
                log.trace("reconnectTimerTask run")
                try {
                    this@Socket.connect()
                } catch (e: Exception) {
                    log.error("Failed to reconnect to " + this@Socket.wsListener, e)
                }

            }
        }
        timer!!.schedule(this@Socket.reconnectTimerTask!!, RECONNECT_INTERVAL_MS.toLong())
    }

    private fun startHeartbeatTimer() {
        this@Socket.heartbeatTimerTask = object : TimerTask() {
            override fun run() {
                log.trace("heartbeatTimerTask run")
                if (this@Socket.isConnected) {
                    try {
                        val envelope = Envelope(
                                "phoenix",
                                "heartbeat",
                                EmptyPayload,
                                this@Socket.makeRef(), null
                        )
                        this@Socket.push(envelope)
                    } catch (e: Exception) {
                        log.error("Failed to send heartbeat", e)
                    }

                }
            }
        }

        timer!!.schedule(this@Socket.heartbeatTimerTask!!, this@Socket.heartbeatInterval.toLong(),
                this@Socket.heartbeatInterval.toLong())
    }

    private fun triggerChannelError() {
        synchronized(channels) {
            for (channel in channels) {
                channel.trigger(ChannelEvent.ERROR.phxEvent, null)
            }
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(Socket::class.java)

        val RECONNECT_INTERVAL_MS = 5000

        private val DEFAULT_HEARTBEAT_INTERVAL = 7000

        internal fun replyEventName(ref: String): String {
            return "chan_reply_$ref"
        }
    }
}
