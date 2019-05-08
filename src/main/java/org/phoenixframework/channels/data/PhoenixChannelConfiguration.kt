package org.phoenixframework.channels.data

import java.io.IOException

interface PhoenixChannelConfiguration {
    @Throws(IOException::class)
    fun <T> fromJson(rawJson: String, clazz: Class<T>): T

    fun <T> toJson(data: T): String?
}