# Phoenix Channel Client for Java and Android

KotlinPhonixChannels is a Java and Android client library for the Channels API in the [Phoenix Framework](http://www.phoenixframework.org/). Its primary purpose is to ease development of real time messaging apps for Android using an Elixir/Phoenix backend. For more about the Elixir language and the massively scalable and reliable systems you can build with Phoenix, see http://elixir-lang.org and http://www.phoenixframework.org.

## Including the Library

- Add `http://dl.bintray.com/eoinsha/java-phoenix-channels` as a Maven repository
- Add KotlinPhonixChannels as an app dependency:
```
dependencies {
  ...
  compile('com.github.eoinsha:JavaPhoenixChannels:1.0') {
      exclude module: 'groovy-all'
  }
```

# Examples

## Example Android App

For a full sample Android chat app, check out the repository at https://github.com/eoinsha/PhoenixChatAndroid

The quick examples below are used with the [Phoenix Chat Example](https://github.com/chrismccord/phoenix_chat_example)


## Example using Kotlin
```kotlin
val socket = Socket("ws://localhost:4000/socket/websocket")
socket.connect()

val channel = socket.chan("rooms:lobby", null)
channel.join()
        .receive("ignore") {
            println("IGNORE")
        }
        .receive("ok") { envelope ->
            println("JOINED with $envelope")
        }

channel.on("new:msg") { envelope ->
    println("NEW MESSAGE: $envelope")
}

channel.on(ChannelEvent.CLOSE.phxEvent) { envelope ->
    println("CLOSED: $envelope")
}

channel.on(ChannelEvent.ERROR.phxEvent) { envelope ->
    println("ERROR: $envelope")
}

val payload = JsonPayload().apply {
    put("user", "john")
    put("body", "message")
}
channel.push("new:msg", payload)

// You also can send object as payload
data class Message(val user: String, val message: String)
channel.pushData("new:msg", Message("doe", "hi!"))
```

# Contributing

To contribute, see the [contribution guidelines and instructions](./CONTRIBUTING.md).
