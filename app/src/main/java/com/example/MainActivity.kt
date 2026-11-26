package com.aistudio.hopshield.vxrtkq

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WebViewScreen()
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@androidx.compose.runtime.Composable
fun WebViewScreen() {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(WebAppInterface(context), "Android")
                webViewClient = WebViewClient()
                loadUrl("file:///android_asset/index.html")
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

class WebAppInterface(private val context: android.content.Context) {
    @JavascriptInterface
    fun startVPN(hop1: String, hop2: String) {
        Toast.makeText(context, "Menyambung $hop1 -> $hop2", Toast.LENGTH_SHORT).show()
        // Di sini lu panggil OpenVPN
    }

    @JavascriptInterface
    fun stopVPN() {
        Toast.makeText(context, "VPN Diputus", Toast.LENGTH_SHORT).show()
        // Di sini lu matikan OpenVPN
    }
}
