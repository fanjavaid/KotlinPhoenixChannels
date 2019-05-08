package org.phoenixframework.channels.data

interface Payload {
    fun getTextValue(key: String): String?
}