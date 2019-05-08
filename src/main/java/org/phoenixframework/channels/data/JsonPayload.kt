package org.phoenixframework.channels.data

open class JsonPayload : HashMap<String, Any>() {
    fun getTextValue(key: String): String? {
        return get(key) as? String
    }
}