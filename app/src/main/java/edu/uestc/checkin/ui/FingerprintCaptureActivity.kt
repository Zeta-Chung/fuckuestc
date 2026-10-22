package edu.uestc.checkin.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 在应用内打开 IDAS 登录页，登录成功进入 JZSZ 后读取
 * MULTIFACTOR_BROWSER_FINGERPRINT，通过结果返回给 MainActivity。
 * 需要使用者手动完成一次登录/验证，程序只负责抓取与填值。
 */
class FingerprintCaptureActivity : Activity() {
    companion object {
        const val EXTRA_FINGERPRINT = "fingerprint"
        private const val LOGIN_URL =
            "https://idas.uestc.edu.cn/authserver/login?service=https%3A%2F%2Fjzsz.uestc.edu.cn%2F"
        private const val IDAS_URL = "https://idas.uestc.edu.cn"
        private const val JZSZ_PREFIX = "https://jzsz.uestc.edu.cn"
    }

    private lateinit var status: TextView
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        status = TextView(this).apply {
            textSize = 14f
            text = "请用本人账号登录。登录成功进入打卡站点后会自动读取指纹，无需手动复制。"
        }
        root.addView(status)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    handler.postDelayed({ tryCapture(url) }, 800)
                }
            }
        }
        root.addView(webView)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(20), dp(20) + bars.top, dp(20), dp(16) + bars.bottom)
                insets
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.loadUrl(LOGIN_URL)
    }

    private fun tryCapture(url: String?) {
        if (delivered) return
        // 只有登录成功、跳到打卡站点后才认为指纹可用。
        if (url == null || !url.startsWith(JZSZ_PREFIX)) return
        val cookies = CookieManager.getInstance().getCookie(IDAS_URL) ?: return
        val value = cookies.split(';').asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("MULTIFACTOR_BROWSER_FINGERPRINT=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
        if (value.isEmpty()) {
            status.text = "未检测到浏览器指纹，可能账号无需该 Cookie；返回后可手动粘贴。"
            return
        }
        delivered = true
        status.text = "已获取浏览器指纹，正在返回…"
        setResult(RESULT_OK, Intent().putExtra(EXTRA_FINGERPRINT, value))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}