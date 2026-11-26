package com.cardash.dashboard.nav

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

internal object Http {
    data class Resp(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    fun post(url: String, jsonBody: String, headers: Map<String, String>): Resp =
        request(url, "POST", jsonBody, headers)

    fun get(url: String, headers: Map<String, String>): Resp =
        request(url, "GET", null, headers)

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Resp(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
