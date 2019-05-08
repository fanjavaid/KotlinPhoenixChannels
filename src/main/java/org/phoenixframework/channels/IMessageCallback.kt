package org.phoenixframework.channels

interface IMessageCallback {

    /**
     * @param envelope The envelope containing the message payload and properties
     */
    fun onMessage(envelope: Envelope?)
}
