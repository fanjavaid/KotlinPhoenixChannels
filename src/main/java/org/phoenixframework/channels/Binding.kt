package org.phoenixframework.channels

internal class Binding(val event: String?, val callback: IMessageCallback) {

    override fun toString(): String {
        return "Binding{" +
                "event='" + event + '\''.toString() +
                ", callback=" + callback +
                '}'.toString()
    }
}
