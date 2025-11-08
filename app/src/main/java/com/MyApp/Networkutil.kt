package com.example.myapp.utils

import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {

    fun openSocket(): Socket? {
        return try {
            val socket = Socket()
            // Hardcoded IP and port:
            socket.connect(InetSocketAddress("197.251.240.87", 54835), 5000)
            socket
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
