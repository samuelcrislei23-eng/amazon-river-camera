package com.amazonriver.camera

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Pede uma foto ao app "Amazon River Camera Companion" rodando no celular, na
 * mesma rede Wi-Fi. É só um cliente HTTP bem simples, sem bibliotecas externas:
 * manda um pedido, recebe o JPEG de volta. Só funciona na rede local — não passa
 * pela internet.
 */
object PhoneCamera {
    fun fetchCapture(hostPort: String, timeoutMs: Int): ByteArray? {
        val trimmed = hostPort.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(":")
        val host = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8765
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().write(
                    "GET /capture HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()
                )
                val all = s.getInputStream().readBytes()
                val sep = headerEnd(all) ?: return null
                val status = String(all, 0, minOf(32, all.size))
                if (!status.contains("200")) return null
                all.copyOfRange(sep, all.size)
            }
        } catch (_: Throwable) { null }
    }

    private fun headerEnd(b: ByteArray): Int? {
        for (i in 0 until b.size - 3) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i + 4
        }
        return null
    }
}
