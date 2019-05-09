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

    private val socketOpenCallback = mockk<SocketOpenCallback>(relaxed = true)
    private val socketCloseCallback = mockk<SocketCloseCallback>(relaxed = true)
    private val socketMessageCallback = mockk<MessageCallback>(relaxed = true)
    private val socketErrorCallback = mockk<ErrorCallback>(relaxed = true)

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
            verify(exactly = 1) { socketOpenCallback() }
        }

        "Channel subscribe" {
            val defaultEnvelope = Envelope()
            var testedEnvelope: Envelope? = defaultEnvelope
            val callback = { envelope: Envelope? ->
                testedEnvelope = envelope
            }

            socket.connect()
            socket.chan("rooms:lobby").join().receive("ok", callback)

            // TODO change this to Coroutine
            Thread.sleep(1000)

            testedEnvelope shouldNotBe defaultEnvelope
            testedEnvelope?.topic shouldBe "rooms:lobby"
        }
    }
}