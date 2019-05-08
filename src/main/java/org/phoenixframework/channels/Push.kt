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

    val recHooks = HashMap<String, MutableList<MessageCallback>>()

    private val timeoutHook = TimeoutHook(timeout)

    internal var receivedEnvelope: Envelope? = null
        private set

    private var refEvent: String? = null

    var isSent = false
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
    fun receive(status: String, callback: MessageCallback): Push {
        if (this.receivedEnvelope != null) {
            val receivedStatus = this.receivedEnvelope?.responseStatus
            if (receivedStatus != null && receivedStatus == status) {
                callback.invoke(this.receivedEnvelope)
            }
        }
        synchronized(recHooks) {
            var statusHooks: MutableList<MessageCallback>? = this.recHooks[status]
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
        val ref = channel.socket.makeRef()
        log.trace("Push send, ref={}", ref)

        this.ref = ref
        refEvent = Socket.replyEventName(ref)
        receivedEnvelope = null

        channel.on(this.refEvent) {
            receivedEnvelope = it
            matchReceive(receivedEnvelope?.responseStatus, it)
            cancelRefEvent()
            cancelTimeout()
        }


        startTimeout()
        isSent = true
        val envelope = Envelope(this.channel.topic, this.event, this.payload, this.ref, this.channel.joinRef())
        channel.socket.push(envelope)
    }

    private fun reset() {
        cancelRefEvent()
        refEvent = null
        receivedEnvelope = null
        isSent = false
    }

    private fun cancelRefEvent() {
        this.channel.off(this.refEvent)
    }

    private fun cancelTimeout() {
        timeoutHook.timerTask?.cancel()
        timeoutHook.timerTask = null
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
                    callback.invoke(envelope)
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