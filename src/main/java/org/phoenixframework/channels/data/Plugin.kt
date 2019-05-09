package org.phoenixframework.channels.data

import java.io.IOException

object Plugin : PhoenixChannelConfiguration {

    private var configuration: PhoenixChannelConfiguration = GsonConfiguration()

    fun setConfiguration(configuration: PhoenixChannelConfiguration) {
        this.configuration = configuration
    }

    @Throws(IOException::class)
    override fun <T> fromJson(rawJson: String, clazz: Class<T>): T {
        return configuration.fromJson(rawJson, clazz)
    }

    @Throws(IOException::class)
    override fun <T> toJson(data: T): String? {
        return configuration.toJson(data)
    }

    internal fun toPayload(data: Any): JsonPayload? {
        val rawJson = toJson(data) ?: return null
        return fromJson(rawJson, JsonPayload::class.java)
    }
}
