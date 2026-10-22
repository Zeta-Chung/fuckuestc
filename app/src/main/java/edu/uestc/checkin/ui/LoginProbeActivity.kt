package edu.uestc.checkin.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import edu.uestc.checkin.storage.SecretStore

class LoginProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = SecretStore(this).load() ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "可见登录对照页。日常定时运行不依赖此页。" })
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = config.userAgent
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = WebViewClient()
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val cookie = "MULTIFACTOR_BROWSER_FINGERPRINT=${config.fingerprint}; Path=/; Secure"
        CookieManager.getInstance().setCookie("https://idas.uestc.edu.cn", cookie) {
            CookieManager.getInstance().flush()
            webView.loadUrl("https://idas.uestc.edu.cn/authserver/login?service=https%3A%2F%2Fjzsz.uestc.edu.cn%2F")
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().setCookie(
            "https://idas.uestc.edu.cn",
            "MULTIFACTOR_BROWSER_FINGERPRINT=; Path=/; Max-Age=0; Secure"
        ) { CookieManager.getInstance().flush() }
        super.onDestroy()
    }
}
