package org.phoenixframework.channels

import org.phoenixframework.channels.data.JsonPayload
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.LinkedBlockingDeque

/**
 * Encapsulation of a Phoenix channel: a Socket, a topic and the channel's state.
 */
class Channel(
        val topic: String,
        val socket: Socket,
        private val payload: JsonPayload?) {

    private val bindings = ArrayList<Binding>()

    private val channelTimer = Timer("Phx Rejoin timer for $topic")

    private val joinPush: Push

    private var joinedOnce = false

    private val pushBuffer = LinkedBlockingDeque<Push>()

    private var state = ChannelState.CLOSED

    val isJoined: Boolean = state === ChannelState.JOINED

    val isErrored: Boolean = state === ChannelState.ERRORED

    val isClosed: Boolean = state === ChannelState.CLOSED

    val isJoining: Boolean = state === ChannelState.JOINING

    init {
        joinPush = Push(this, ChannelEvent.JOIN.phxEvent, payload, DEFAULT_TIMEOUT).apply {
            receive("ok", object : IMessageCallback {
                override fun onMessage(envelope: Envelope?) {
                    this@Channel.state = ChannelState.JOINED
                }
            })
            timeout(object : ITimeoutCallback {
                override fun onTimeout() {
                    this@Channel.state = ChannelState.ERRORED
                }
            })
        }

        onClose(object : IMessageCallback {
            override fun onMessage(envelope: Envelope?) {
                this@Channel.state = ChannelState.CLOSED
                this@Channel.socket.remove(this@Channel)
            }
        })
        onError(object : IErrorCallback {
            override fun onError(reason: String) {
                this@Channel.state = ChannelState.ERRORED
                scheduleRejoinTimer()
            }
        })
        on(ChannelEvent.REPLY.phxEvent, object : IMessageCallback {
            override fun onMessage(envelope: Envelope?) {
                this@Channel.trigger(Socket.replyEventName(envelope?.getRef()), envelope)
            }
        })
    }

    /**
     * @return true if the socket is open and the channel has joined
     */
    private fun canPush(): Boolean {
        return this.socket.isConnected && this.state === ChannelState.JOINED
    }

    fun isMember(envelope: Envelope): Boolean {
        val topic = envelope.topic
        val event = envelope.event
        val joinRef = envelope.joinRef

        if (this.topic != topic) {
            return false
        }

        val isLifecycleEvent = ChannelEvent.getEvent(event) != null

        if (joinRef != null && isLifecycleEvent && joinRef !== this.joinRef()) {
            log.info("dropping outdated message topic: %s, event: %s, joinRef: %s",
                    topic, event, joinRef)
            return false
        }

        return true
    }

    /**
     * Initiates a channel join event
     *
     * @return This Push instance
     * @throws IllegalStateException Thrown if the channel has already been joined
     * @throws IOException           Thrown if the join could not be sent
     */
    @Throws(IllegalStateException::class, IOException::class)
    fun join(): Push {
        if (this.joinedOnce) {
            throw IllegalStateException(
                    "Tried to join multiple times. 'join' can only be invoked once per channel")
        }
        this.joinedOnce = true
        this.sendJoin()
        return this.joinPush
    }

    @Throws(IOException::class)
    fun leave(): Push {
        return this.push(ChannelEvent.LEAVE.phxEvent).receive("ok", object : IMessageCallback {
            override fun onMessage(envelope: Envelope?) {
                this@Channel.trigger(ChannelEvent.CLOSE.phxEvent, null)
            }
        })
    }

    /**
     * Unsubscribe for event notifications
     *
     * @param event The event name
     * @return The instance's self
     */
    fun off(event: String?): Channel {
        if (event == null) return this
        synchronized(bindings) {
            val bindingIter = bindings.iterator()
            while (bindingIter.hasNext()) {
                if (bindingIter.next().event == event) {
                    bindingIter.remove()
                    break
                }
            }
        }
        return this
    }

    /**
     * @param event    The event name
     * @param callback The callback to be invoked with the event's message
     * @return The instance's self
     */
    fun on(event: String?, callback: IMessageCallback): Channel {
        synchronized(bindings) {
            this.bindings.add(Binding(event, callback))
        }
        return this
    }

    private fun onClose(callback: IMessageCallback) {
        this.on(ChannelEvent.CLOSE.phxEvent, callback)
    }

    /**
     * Register an error callback for the channel
     *
     * @param callback Callback to be invoked on error
     */
    private fun onError(callback: IErrorCallback) {
        this.on(ChannelEvent.ERROR.phxEvent, object : IMessageCallback {
            override fun onMessage(envelope: Envelope?) {
                var reason: String? = null
                if (envelope != null) {
                    reason = envelope.reason
                }
                callback.onError(reason!!)
            }
        })
    }

    /**
     * Pushes a payload to be sent to the channel
     *
     * @param event   The event name
     * @param payload The message payload
     * @param timeout The number of milliseconds to wait before triggering a timeout
     * @return The Push instance used to send the message
     * @throws IOException           Thrown if the payload cannot be pushed
     * @throws IllegalStateException Thrown if the channel has not yet been joined
     */
    @Throws(IOException::class, IllegalStateException::class)
    private fun push(event: String, payload: JsonPayload?, timeout: Long): Push {
        if (!this.joinedOnce) {
            throw IllegalStateException("Unable to push event before channel has been joined")
        }
        val pushEvent = Push(this, event, payload, timeout)
        if (this.canPush()) {
            pushEvent.send()
        } else {
            this.pushBuffer.add(pushEvent)
        }
        return pushEvent
    }

    @Throws(IOException::class)
    @JvmOverloads
    fun push(event: String, payload: JsonPayload? = null): Push {
        return push(event, payload, DEFAULT_TIMEOUT)
    }

    @Throws(IOException::class)
    private fun rejoin() {
        this.sendJoin()
        while (!this.pushBuffer.isEmpty()) {
            this.pushBuffer.removeFirst().send()
        }
    }

    @Throws(IOException::class)
    private fun rejoinUntilConnected() {
        if (this.state === ChannelState.ERRORED) {
            if (this.socket.isConnected) {
                this.rejoin()
            } else {
                scheduleRejoinTimer()
            }
        }
    }

    fun scheduleRepeatingTask(timerTask: TimerTask, ms: Long) {
        this.channelTimer.schedule(timerTask, ms, ms)
    }

    fun scheduleTask(timerTask: TimerTask, ms: Long) {
        this.channelTimer.schedule(timerTask, ms)
    }

    override fun toString(): String {
        return "Channel{" +
                "topic='" + topic + '\''.toString() +
                ", message=" + payload +
                ", bindings(" + bindings.size + ")=" + bindings +
                '}'.toString()
    }

    /**
     * Triggers event signalling to all callbacks bound to the specified event.
     *
     * @param triggerEvent The event name
     * @param envelope     The message's envelope relating to the event or null if not relevant.
     */
    fun trigger(triggerEvent: String, envelope: Envelope?) {
        synchronized(bindings) {
            for (binding in bindings) {
                if (binding.event == triggerEvent) {
                    // Channel Events get the full envelope
                    binding.callback.onMessage(envelope!!)
                    break
                }
            }
        }
    }

    private fun scheduleRejoinTimer() {
        val rejoinTimerTask = object : TimerTask() {
            override fun run() {
                try {
                    this@Channel.rejoinUntilConnected()
                } catch (e: IOException) {
                    log.error("Failed to rejoin", e)
                }

            }
        }
        scheduleTask(rejoinTimerTask, Socket.RECONNECT_INTERVAL_MS.toLong())
    }

    @Throws(IOException::class)
    private fun sendJoin() {
        this.state = ChannelState.JOINING
        this.joinPush.send()
    }

    fun joinRef(): String? {
        return this.joinPush.ref
    }

    companion object {

        private val DEFAULT_TIMEOUT: Long = 5000

        private val log = LoggerFactory.getLogger(Channel::class.java)
    }
}
