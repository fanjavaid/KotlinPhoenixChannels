package org.phoenixframework.channels

import org.phoenixframework.channels.data.JsonPayload
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*

class Push internal constructor(
        private val channel: Channel,
        private val event: String,
        val payload: JsonPayload?,
        timeout: Long
) {

    val recHooks = HashMap<String, MutableList<IMessageCallback>>()

    private val timeoutHook = TimeoutHook(timeout)

    internal var receivedEnvelope: Envelope? = null
        private set

    private var refEvent: String? = null

    internal var isSent = false
        private set

    var ref: String? = null
        private set

    private class TimeoutHook(val ms: Long) {

        var callback: ITimeoutCallback? = null

        var timerTask: TimerTask? = null

        fun hasCallback() = this.callback != null
    }

    /**
     * Registers for notifications on status messages
     *
     * @param status   The message status to register callbacks on
     * @param callback The callback handler
     * @return This instance's self
     */
    fun receive(status: String, callback: IMessageCallback): Push {
        if (this.receivedEnvelope != null) {
            val receivedStatus = this.receivedEnvelope!!.responseStatus
            if (receivedStatus != null && receivedStatus == status) {
                callback.onMessage(this.receivedEnvelope!!)
            }
        }
        synchronized(recHooks) {
            var statusHooks: MutableList<IMessageCallback>? = this.recHooks[status]
            if (statusHooks == null) {
                statusHooks = ArrayList()
                this.recHooks[status] = statusHooks
            }
            statusHooks.add(callback)
        }

        return this
    }

    /**
     * Registers for notification of message response timeout
     *
     * @param callback The callback handler called when timeout is reached
     * @return This instance's self
     */
    fun timeout(callback: ITimeoutCallback): Push {
        if (this.timeoutHook.hasCallback()) {
            throw IllegalStateException("Only a single after hook can be applied to a Push")
        }

        this.timeoutHook.callback = callback

        return this
    }

    @Throws(IOException::class)
    fun send() {
        this.ref = channel.socket.makeRef()
        log.trace("Push send, ref={}", ref)

        this.refEvent = Socket.replyEventName(ref)
        this.receivedEnvelope = null

        this.channel.on(this.refEvent, object : IMessageCallback {
            override fun onMessage(envelope: Envelope?) {
                receivedEnvelope = envelope
                matchReceive(receivedEnvelope?.responseStatus, envelope)
                cancelRefEvent()
                cancelTimeout()
            }
        })

        this.startTimeout()
        this.isSent = true
        val envelope = Envelope(this.channel.topic, this.event, this.payload, this.ref, this.channel.joinRef())
        this.channel.socket.push(envelope)
    }

    private fun reset() {
        this.cancelRefEvent()
        this.refEvent = null
        this.receivedEnvelope = null
        this.isSent = false
    }

    private fun cancelRefEvent() {
        this.channel.off(this.refEvent)
    }

    private fun cancelTimeout() {
        this.timeoutHook.timerTask!!.cancel()
        this.timeoutHook.timerTask = null
    }

    private fun createTimerTask(): TimerTask {
        val callback = Runnable {
            this@Push.cancelRefEvent()
            if (this@Push.timeoutHook.hasCallback()) {
                this@Push.timeoutHook.callback!!.onTimeout()
            }
        }

        return object : TimerTask() {
            override fun run() {
                callback.run()
            }
        }
    }

    private fun matchReceive(status: String?, envelope: Envelope?) {
        synchronized(recHooks) {
            val statusCallbacks = this.recHooks[status]
            if (statusCallbacks != null) {
                for (callback in statusCallbacks) {
                    callback.onMessage(envelope)
                }
            }
        }
    }

    private fun startTimeout() {
        createTimerTask().let { timerTask ->
            timeoutHook.timerTask = timerTask
            channel.scheduleTask(timerTask, this.timeoutHook.ms)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Push::class.java)
    }
}