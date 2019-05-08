package org.phoenixframework.channels.data

import com.google.gson.Gson

class GsonConfiguration(private val gson: Gson = Gson()) : PhoenixChannelConfiguration {

    override fun <T> fromJson(rawJson: String, clazz: Class<T>): T {
        return gson.fromJson(rawJson, clazz)
    }

    override fun <T> toJson(data: T): String? {
        return gson.toJson(data)
    }
}