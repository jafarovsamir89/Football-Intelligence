package az.simplesoft.footballintelligence

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-build-only public-page proof tool: normal WebView, no login, stealth, hidden API, or bypass. */
class BookmakerWebViewProbeActivity : Activity() {
    private val pages = listOf(
        "Misli.az" to "https://www.misli.az/idman-novleri/futbol",
        "eTopaz" to "https://etopaz.az/home?sport-id=1%3Asr%3Asport%3A1",
    )
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var pageStatus: TextView
    private var pageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val next = Button(this).apply {
            text = "Load next public operator page"
            setOnClickListener { pageIndex = (pageIndex + 1) % pages.size; loadCurrentPage() }
        }
        val capture = Button(this).apply {
            text = "Capture currently rendered public page"
            setOnClickListener { captureVisibleText(webView.url ?: pages[pageIndex].second) }
        }
        pageStatus = TextView(this).apply { textSize = 12f }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageStatus.text = "Rendered ${pages[pageIndex].first}; waiting for ordinary page JavaScript…"
                    handler.postDelayed({ captureVisibleText(url) }, 4_000)
                }
            }
        }
        root.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(capture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(pageStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadCurrentPage()
    }

    private fun loadCurrentPage() {
        val (name, url) = pages[pageIndex]
        pageStatus.text = "Loading public page: $name"
        webView.loadUrl(url)
    }

    private fun captureVisibleText(url: String) {
        webView.evaluateJavascript(
            "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,7000)})",
        ) { result ->
            Log.i("BookmakerWebViewPOC", "${pages[pageIndex].first} visible DOM $url: ${result?.take(9_000)}")
            pageStatus.text = "Captured visible rendered text for ${pages[pageIndex].first}. See logcat tag BookmakerWebViewPOC."
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }
}
