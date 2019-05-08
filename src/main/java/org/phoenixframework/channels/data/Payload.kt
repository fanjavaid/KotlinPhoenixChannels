package org.phoenixframework.channels.data

internal interface Payload {
    fun getTextValue(key: String): String?
}