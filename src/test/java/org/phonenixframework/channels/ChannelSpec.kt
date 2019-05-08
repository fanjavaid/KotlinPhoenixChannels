package org.phonenixframework.channels

import io.kotlintest.*
import io.kotlintest.specs.StringSpec
import io.mockk.mockk
import io.mockk.verify
import org.phoenixframework.channels.*

/** This test class must be executed with https://github.com/chrismccord/phoenix_chat_example
 * running on local server
 */
class ChannelSpec : StringSpec() {

    private val socket = Socket("ws://localhost:4000/socket/websocket")

    private val socketOpenCallback = mockk<ISocketOpenCallback>(relaxUnitFun = true)
    private val socketCloseCallback = mockk<ISocketCloseCallback>(relaxUnitFun = true)
    private val socketMessageCallback = mockk<IMessageCallback>(relaxUnitFun = true)
    private val socketErrorCallback = mockk<IErrorCallback>(relaxUnitFun = true)

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)

        socket.onOpen(socketOpenCallback)
                .onClose(socketCloseCallback)
                .onMessage(socketMessageCallback)
                .onError(socketErrorCallback)
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
        super.afterTest(testCase, result)
        socket.disconnect()
    }

    init {
        "Socket connects" {
            socket.connect()
            verify(exactly = 1) { socketOpenCallback.onOpen() }
        }

        "Channel subscribe" {
            val defaultEnvelope = Envelope()
            var testedEnvelope: Envelope? = defaultEnvelope
            val callback = object : IMessageCallback {
                override fun onMessage(envelope: Envelope?) {
                    testedEnvelope = envelope
                }
            }

            socket.connect()
            socket.chan("rooms:lobby", null).join().receive("ok", callback)

            // TODO change this to Coroutine
            Thread.sleep(1000)

            testedEnvelope shouldNotBe defaultEnvelope
            testedEnvelope?.topic shouldBe "rooms:lobby"
        }
    }
}