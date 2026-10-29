package com.kobrax.monitor

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Нативный LAN-клиент для Anycubic Kobra X (modelId: 20030).
 * Реализует протокол LAN Mode без Anycubic Cloud:
 * 1. GET http://<IP>:18910/info -> получение 32-символьного токена
 * 2. Вычисление подписи: sign = MD5(MD5(token[0..16]) + ts + nonce)
 * 3. POST http://<IP>:18910/ctrl?ts=...&nonce=...&sign=...&did=...
 * 4. Расшифровка ответа AES-128-CBC (ключ = token[16..32], IV = data.token)
 * 5. Прямое подключение к локальному TLS MQTT-брокеру принтера на порту 9883 (MQTT 3.1.1)
 *    и подписка на топики телеметрии anycubic/anycubicCloud/v1/printer/+/20030/<deviceId>/#
 */
class KobraXLanClient(
    private val onStatusChange: (String, Boolean, Boolean) -> Unit,
    private val onPacketReceived: (String, String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var activeSocket: SSLSocket? = null

    fun disconnect() {
        running.set(false)
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        activeSocket = null
    }

    fun connectToPrinter(printerIp: String) {
        disconnect()
        running.set(true)

        thread(name = "KobraXLanWorker", isDaemon = true) {
            try {
                onStatusChange("Шаг 1/3: Опрос http://$printerIp:18910/info...", false, false)
                val infoJson = httpGet("http://$printerIp:18910/info")
                val token = infoJson.optString("token", "")
                val modelId = infoJson.optString("modelId", "20030").ifEmpty { "20030" }

                if (token.length < 32) {
                    onStatusChange("Ошибка: принтер не вернул 32-байтный токен (Проверь LAN Mode!)", false, true)
                    return@thread
                }

                // Шаг 2: Формирование подписи и запрос к /ctrl
                onStatusChange("Шаг 2/3: Рукопожатие /ctrl и расшифровка AES-128-CBC...", false, false)
                val keyPart1 = token.substring(0, 16)
                val aesKeyBytes = token.substring(16, 32).toByteArray(StandardCharsets.UTF_8)

                val ts = System.currentTimeMillis().toString()
                val nonce = UUID.randomUUID().toString().substring(0, 8)
                val did = "android-" + UUID.randomUUID().toString().substring(0, 8)

                val innerMd5 = md5Hex(keyPart1)
                val sign = md5Hex(innerMd5 + ts + nonce)

                val ctrlUrl = "http://$printerIp:18910/ctrl?ts=$ts&nonce=$nonce&sign=$sign&did=$did"
                val ctrlJson = httpPost(ctrlUrl)
                val dataObj = ctrlJson.optJSONObject("data")
                    ?: throw IllegalStateException("Пустой блок data в ответе /ctrl: $ctrlJson")

                val ivStr = dataObj.getString("token")
                val encryptedBase64 = dataObj.getString("info")

                val decryptedStr = decryptAesCbc(
                    cipherTextBase64 = encryptedBase64,
                    keyBytes = aesKeyBytes,
                    ivBytes = ivStr.toByteArray(StandardCharsets.UTF_8)
                )
                val credsJson = JSONObject(decryptedStr)

                val deviceId = credsJson.optString("deviceId", "")
                val mqttUser = credsJson.optString("username", "")
                val mqttPass = credsJson.optString("password", "")

                // Отправляем пакет успешного рукопожатия в инспектор
                onPacketReceived(
                    "anycubic/lan/handshake/info",
                    JSONObject().apply {
                        put("type", "handshake")
                        put("action", "authenticated")
                        put("modelId", modelId)
                        put("deviceId", deviceId)
                    }.toString()
                )

                // Шаг 3: Подключение к TLS MQTT :9883
                onStatusChange("Шаг 3/3: Подключение к MQTT TLS $printerIp:9883...", false, false)
                runMqttTlsLoop(printerIp, 9883, modelId, deviceId, mqttUser, mqttPass)

            } catch (e: Exception) {
                if (running.get()) {
                    onStatusChange("Ошибка LAN (${printerIp}): ${e.message ?: "Недоступен порт 18910/9883"}", false, true)
                }
            }
        }
    }

    private fun runMqttTlsLoop(
        host: String,
        port: Int,
        modelId: String,
        deviceId: String,
        username: String,
        password: String
    ) {
        val trustAllCerts = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val rawSocket = sslContext.socketFactory.createSocket() as SSLSocket
        activeSocket = rawSocket
        rawSocket.connect(InetSocketAddress(host, port), 6000)
        rawSocket.soTimeout = 45000
        rawSocket.startHandshake()

        val input = DataInputStream(BufferedInputStream(rawSocket.inputStream))
        val output = BufferedOutputStream(rawSocket.outputStream)

        // 1. Отправка пакета MQTT CONNECT (3.1.1)
        val clientId = "kobrax_android_" + UUID.randomUUID().toString().substring(0, 6)
        val connectPacket = buildMqttConnectPacket(clientId, username, password)
        output.write(connectPacket)
        output.flush()

        // Чтение CONNACK
        val connAckHeader = input.readUnsignedByte()
        val connAckLen = readRemainingLength(input)
        val connAckBody = ByteArray(connAckLen)
        input.readFully(connAckBody)
        if ((connAckHeader shr 4) != 2 || (connAckBody.size >= 2 && connAckBody[1].toInt() != 0)) {
            throw IllegalStateException("Брокер отклонил MQTT CONNECT (код: ${connAckBody.getOrNull(1)})")
        }

        onStatusChange("Подключен к Kobra X ($host:9883) · Перехват пакетов", true, false)

        // 2. Подписка на все топики принтера
        val wildcardTopic = "anycubic/anycubicCloud/v1/printer/+/$modelId/$deviceId/#"
        output.write(buildMqttSubscribePacket(1, wildcardTopic))
        output.flush()

        // 3. Отправка начальных запросов состояния (только чтение / query)
        val queryTypes = listOf("info", "print", "temp", "multiColorBox", "axis", "camera")
        for (qType in queryTypes) {
            val action = if (qType == "multiColorBox") "getInfo" else "query"
            val qTopic = "anycubic/anycubicCloud/v1/web/printer/$modelId/$deviceId/$qType"
            val qPayload = JSONObject().apply {
                put("type", qType)
                put("action", action)
                put("timestamp", System.currentTimeMillis())
                put("msgid", UUID.randomUUID().toString())
                put("data", JSONObject.NULL)
            }.toString()
            output.write(buildMqttPublishPacket(qTopic, qPayload))
        }
        output.flush()

        var lastPingTime = System.currentTimeMillis()

        // 4. Основной цикл чтения пакетов MQTT PUBLISH
        while (running.get() && !rawSocket.isClosed) {
            val header = input.readUnsignedByte()
            val packetType = header shr 4
            val remLen = readRemainingLength(input)
            val payloadBytes = ByteArray(remLen)
            input.readFully(payloadBytes)

            if (packetType == 3 && remLen > 2) {
                // PUBLISH packet
                val topicLen = ((payloadBytes[0].toInt() and 0xFF) shl 8) or (payloadBytes[1].toInt() and 0xFF)
                val qos = (header shr 1) and 0x03
                val headerOffset = 2 + topicLen + if (qos > 0) 2 else 0
                if (headerOffset <= payloadBytes.size) {
                    val topicStr = String(payloadBytes, 2, topicLen, StandardCharsets.UTF_8)
                    val msgStr = String(payloadBytes, headerOffset, payloadBytes.size - headerOffset, StandardCharsets.UTF_8)
                    onPacketReceived(topicStr, msgStr)
                }
            }

            // Поддержание соединения (PINGREQ каждые 20 секунд)
            val now = System.currentTimeMillis()
            if (now - lastPingTime > 20_000) {
                output.write(byteArrayOf(0xC0.toByte(), 0x00))
                output.flush()
                lastPingTime = now
            }
        }
    }

    private fun httpGet(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun httpPost(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.outputStream.use { it.write(ByteArray(0)) }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decryptAesCbc(cipherTextBase64: String, keyBytes: ByteArray, ivBytes: ByteArray): String {
        val cipherBytes = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val decrypted = cipher.doFinal(cipherBytes)
        return String(decrypted, StandardCharsets.UTF_8)
    }

    private fun buildMqttConnectPacket(clientId: String, username: String, password: String): ByteArray {
        val body = ByteArrayOutputStream()
        // Protocol Name "MQTT" + Level 4 (3.1.1)
        writeMqttString(body, "MQTT")
        body.write(4)
        // Connect Flags: CleanSession (0x02) | Username (0x80) | Password (0x40)
        body.write(0xC2)
        // Keep Alive = 60 sec
        body.write(0)
        body.write(60)
        // Payload
        writeMqttString(body, clientId)
        writeMqttString(body, username)
        writeMqttString(body, password)

        return wrapFixedHeader(0x10, body.toByteArray())
    }

    private fun buildMqttSubscribePacket(packetId: Int, topic: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((packetId shr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeMqttString(body, topic)
        body.write(0) // QoS 0
        return wrapFixedHeader(0x82, body.toByteArray())
    }

    private fun buildMqttPublishPacket(topic: String, jsonPayload: String): ByteArray {
        val body = ByteArrayOutputStream()
        writeMqttString(body, topic)
        body.write(jsonPayload.toByteArray(StandardCharsets.UTF_8))
        return wrapFixedHeader(0x30, body.toByteArray())
    }

    private fun writeMqttString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.write((bytes.size shr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun wrapFixedHeader(firstByte: Int, variableAndPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(firstByte)
        var x = variableAndPayload.size
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
        out.write(variableAndPayload)
        return out.toByteArray()
    }

    private fun readRemainingLength(input: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val encodedByte = input.readUnsignedByte()
            value += (encodedByte and 127) * multiplier
            if ((encodedByte and 128) == 0) break
            multiplier *= 128
        }
        return value
    }
}
