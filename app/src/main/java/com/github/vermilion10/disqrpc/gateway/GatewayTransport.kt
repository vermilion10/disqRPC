package com.github.vermilion10.disqrpc.gateway

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal WebSocket abstraction so [GatewayClient] can be driven by a fake in tests. */
interface GatewayTransport {

    fun open(url: String, headers: Map<String, String>, listener: Listener): Connection

    interface Connection {
        fun send(text: String): Boolean
        fun close(code: Int, reason: String)
    }

    /** Callbacks may arrive on any thread. [onClosed]/[onFailure] fire at most once in total. */
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }
}

class OkHttpGatewayTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) : GatewayTransport {

    override fun open(
        url: String,
        headers: Map<String, String>,
        listener: GatewayTransport.Listener
    ): GatewayTransport.Connection {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()

        val finished = AtomicBoolean(false)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (finished.compareAndSet(false, true)) listener.onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finished.compareAndSet(false, true)) listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished.compareAndSet(false, true)) listener.onFailure(t)
            }
        })

        return object : GatewayTransport.Connection {
            override fun send(text: String) = socket.send(text)
            override fun close(code: Int, reason: String) {
                if (!socket.close(code, reason)) socket.cancel()
            }
        }
    }
}
