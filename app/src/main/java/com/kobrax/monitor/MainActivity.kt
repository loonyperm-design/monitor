package com.kobrax.monitor

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var lanClient: KobraXLanClient? = null
    private val channelId = "kobra_x_print_alerts"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidKobraBridge(), "AndroidKobraBridge")

        lanClient = KobraXLanClient(
            onStatusChange = { statusText, isConnected, isError ->
                runOnUiThread {
                    val safeText = JSONObject.quote(statusText)
                    webView.evaluateJavascript(
                        "window.onNativeConnectionStatus && window.onNativeConnectionStatus($safeText, $isConnected, $isError);",
                        null
                    )
                }
            },
            onPacketReceived = { topic, payloadJson ->
                runOnUiThread {
                    val safeTopic = JSONObject.quote(topic)
                    val safePayload = JSONObject.quote(payloadJson)
                    webView.evaluateJavascript(
                        "window.onNativeMqttPacket && window.onNativeMqttPacket($safeTopic, $safePayload);",
                        null
                    )
                }
            }
        )

        webView.loadUrl("file:///android_asset/kobra_x_monitor.html")
    }

    inner class AndroidKobraBridge {
        @JavascriptInterface
        fun connectPrinter(ip: String) {
            lanClient?.connectToPrinter(ip.trim())
        }

        @JavascriptInterface
        fun notifyPrintFinished(fileName: String, totalLayers: String, bedTemp: String) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(this@MainActivity, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Anycubic Kobra X — Печать завершена (100%)!")
                .setContentText("Файл: $fileName ($totalLayers слоев). Стол: $bedTemp")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(20030, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Оповещения об окончании печати Kobra X",
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        lanClient?.disconnect()
        super.onDestroy()
    }
}
