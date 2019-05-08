package org.phoenixframework.channels

/**
 * @param [envelope] The envelope containing the message payload and properties
 */
typealias MessageCallback = (envelope: Envelope?) -> Unit