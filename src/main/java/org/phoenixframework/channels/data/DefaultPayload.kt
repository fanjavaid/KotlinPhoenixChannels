package org.phoenixframework.channels.data

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

object DefaultPayload : Payload {

    private val node = ObjectNode(JsonNodeFactory.instance)

    override fun getTextValue(key: String): String? {
        return node.get(key)?.textValue()
    }
}