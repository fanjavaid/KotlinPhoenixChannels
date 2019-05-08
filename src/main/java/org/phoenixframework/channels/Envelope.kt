package org.phoenixframework.channels

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.gson.annotations.SerializedName
import org.phoenixframework.channels.data.Payload

// To fix UnrecognizedPropertyException.
@JsonIgnoreProperties(ignoreUnknown = true)
class Envelope(
        @SerializedName(value = "topic") val topic: String,
        @SerializedName(value = "event") val event: String,
        @SerializedName(value = "payload") val payload: Payload?,
        @SerializedName(value = "ref") private val ref: String?,
        @SerializedName(value = "join_ref") private val join_ref: String?
) {

    constructor() : this("", "", null, null, null)

    /**
     * Helper to retrieve the value of "join_ref" from the payload
     *
     * @return The join_ref string or null if not found
     */
    val joinRef: String?
        get() = join_ref ?: payload?.getTextValue("join_ref")

    /**
     * Helper to retrieve the value of "status" from the payload
     *
     * @return The status string or null if not found
     */
    val responseStatus: String?
        get() = payload?.getTextValue("status")

    /**
     * Helper to retrieve the value of "reason" from the payload
     *
     * @return The reason string or null if not found
     */
    val reason: String?
        get() = payload?.getTextValue("reason")

    /**
     * Helper to retrieve the value of "ref" from the payload
     *
     * @return The ref string or null if not found
     */
    fun getRef(): String? {
        return ref ?: payload?.getTextValue("red")
    }

    override fun toString(): String {
        return "Envelope{" +
                "topic='" + topic + '\''.toString() +
                ", event='" + event + '\''.toString() +
                ", payload=" + payload +
                '}'.toString()
    }
}
