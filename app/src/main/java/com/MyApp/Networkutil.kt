package com.example.myapp.utils

import java.net.InetSocketAddress
import java.net.Socket

object NetworkUtils {

    fun openSocket(): Socket? {
        return try {
            val socket = Socket()
            // Hardcoded IP and port:
            socket.connect(InetSocketAddress("192.168.0.179", 54835), 5000)
            socket
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
