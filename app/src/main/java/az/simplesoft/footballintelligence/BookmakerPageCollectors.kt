package az.simplesoft.footballintelligence

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import az.simplesoft.footballintelligence.data.FootballScreenRepository
import az.simplesoft.footballintelligence.data.MisliOddsProvider
import az.simplesoft.footballintelligence.data.TopazOddsProvider
import az.simplesoft.footballintelligence.data.OddsProviderStatus
import az.simplesoft.footballintelligence.data.MisliVisiblePage
import az.simplesoft.footballintelligence.data.BOOKMAKER_CAPTURE_INTERVAL_MILLIS
import az.simplesoft.footballintelligence.data.ForegroundBookmakerCaptureGate

/**
 * Reads only the visible rendered content of Misli's public football page. The WebView uses the
 * ordinary Android user agent and never visits account, ticket, API, or live-detail paths.
 */
private suspend fun persistBookmakerCaptureFailure(
    repository: FootballScreenRepository,
    providerId: String,
    checkedAtMillis: Long,
    detail: String,
) {
    try {
        repository.saveBookmakerCapture(providerId, checkedAtMillis, OddsProviderStatus.ERROR, emptyList(), detail)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("BookmakerCapture", "Could not persist $providerId page-capture failure.", error)
    }
}

@Composable internal fun MisliPublicPageCollector(repository:FootballScreenRepository,previousCheck:Long?,onCaptured:()->Unit) {
    val activity=LocalContext.current as ComponentActivity
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val captureScope=rememberCoroutineScope()
    val now=System.currentTimeMillis()
    val initialLoadDelay=previousCheck?.let { (BOOKMAKER_CAPTURE_INTERVAL_MILLIS-(now-it)).coerceAtLeast(0L) } ?: 0L
    val parser=remember { MisliOddsProvider() }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val webView=remember(activity) {
        WebView(activity).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            webViewClient=object:WebViewClient() {
                private val captureGate=ForegroundBookmakerCaptureGate()
                private var reloadScheduled=false

                private fun scheduleReload(view:WebView,delayMillis:Long) {
                    if(reloadScheduled) return
                    reloadScheduled=true
                    handler.postDelayed({
                        reloadScheduled=false
                        if(!activity.isFinishing) view.reload()
                    },delayMillis.coerceAtLeast(0L))
                }

                private fun finishCapture(view:WebView,capturedAtMillis:Long) {
                    captureGate.finish(capturedAtMillis)
                    latestOnCaptured()
                    scheduleReload(view,BOOKMAKER_CAPTURE_INTERVAL_MILLIS)
                }

                override fun onPageFinished(view:WebView,url:String) {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    if(uri==null || uri.host !in setOf("misli.az","www.misli.az") || uri.path!="/idman-novleri/futbol") return
                    val pageFinishedAt=System.currentTimeMillis()
                    if(!captureGate.tryStart(pageFinishedAt)) {
                        if(!captureGate.isCaptureInProgress()) {
                            scheduleReload(view,captureGate.millisUntilAllowed(pageFinishedAt))
                        }
                        return
                    }
                    handler.removeCallbacksAndMessages(null)
                    reloadScheduled=false
                    handler.postDelayed({
                        view.evaluateJavascript(
                            "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,100000),timeZoneId:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'})"
                        ) { encoded ->
                            val checkedAt=System.currentTimeMillis()
                            captureScope.launch {
                                try {
                                    val page=withContext(Dispatchers.Default) {
                                        val decoded=org.json.JSONTokener(encoded ?: "null").nextValue()
                                        val json=when(decoded) {
                                            is org.json.JSONObject -> decoded
                                            is String -> org.json.JSONObject(decoded)
                                            else -> error("Страница не вернула видимые данные.")
                                        }
                                        MisliVisiblePage(
                                            url=json.optString("url"),title=json.optString("title"),
                                            visibleText=json.optString("text"),timeZoneId=json.optString("timeZoneId","UTC")
                                        )
                                    }
                                    val fixtures=repository.fixtureIdentities()
                                    val result=withContext(Dispatchers.Default) { parser.parseVisiblePage(page,checkedAt,fixtures) }
                                    val status=if(result.snapshots.isEmpty()) OddsProviderStatus.UNAVAILABLE else OddsProviderStatus.LIVE
                                    repository.saveBookmakerCapture("misli",checkedAt,status,result.snapshots,result.detail)
                                } catch(error:CancellationException) {
                                    throw error
                                } catch(error:Exception) {
                                    persistBookmakerCaptureFailure(
                                        repository,"misli",checkedAt,error.message ?: "Не удалось прочитать видимые данные публичной страницы."
                                    )
                                } finally {
                                    if(captureScope.isActive&&!activity.isFinishing) finishCapture(view,checkedAt)
                                }
                            }
                        }
                    },4_000)
                }

                override fun shouldOverrideUrlLoading(view:WebView,url:String):Boolean {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    return uri==null || uri.scheme!="https" || uri.host !in setOf("misli.az","www.misli.az")
                }
            }
            val publicUrl="https://www.misli.az/idman-novleri/futbol"
            if(initialLoadDelay==0L) loadUrl(publicUrl) else handler.postDelayed({
                if(!activity.isFinishing) loadUrl(publicUrl)
            },initialLoadDelay)
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory={webView},modifier=Modifier.size(1.dp).alpha(0.01f))
}

/** Uses only the public eTopaz page and selects its ordinary visible "İdman" tab. */
@Composable internal fun TopazPublicPageCollector(repository:FootballScreenRepository,previousCheck:Long?,onCaptured:()->Unit) {
    val activity=LocalContext.current as ComponentActivity
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val captureScope=rememberCoroutineScope()
    val now=System.currentTimeMillis()
    val initialLoadDelay=previousCheck?.let { (BOOKMAKER_CAPTURE_INTERVAL_MILLIS-(now-it)).coerceAtLeast(0L) } ?: 0L
    val parser=remember { TopazOddsProvider() }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val webView=remember(activity) {
        WebView(activity).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            webViewClient=object:WebViewClient() {
                private val captureGate=ForegroundBookmakerCaptureGate()
                private var reloadScheduled=false

                private fun scheduleReload(view:WebView,delayMillis:Long) {
                    if(reloadScheduled) return
                    reloadScheduled=true
                    handler.postDelayed({
                        reloadScheduled=false
                        if(!activity.isFinishing) view.reload()
                    },delayMillis.coerceAtLeast(0L))
                }

                private fun finishCapture(view:WebView,capturedAtMillis:Long) {
                    captureGate.finish(capturedAtMillis)
                    latestOnCaptured()
                    scheduleReload(view,BOOKMAKER_CAPTURE_INTERVAL_MILLIS)
                }

                override fun onPageFinished(view:WebView,url:String) {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    if(uri==null||uri.host !in setOf("etopaz.az","www.etopaz.az")||uri.path!="/home") return
                    val pageFinishedAt=System.currentTimeMillis()
                    if(!captureGate.tryStart(pageFinishedAt)) {
                        if(!captureGate.isCaptureInProgress()) {
                            scheduleReload(view,captureGate.millisUntilAllowed(pageFinishedAt))
                        }
                        return
                    }
                    handler.removeCallbacksAndMessages(null)
                    reloadScheduled=false
                    // This clicks an ordinary visible tab by its exact accessible label; it does
                    // not navigate to a private route or inject any bookmaker request.
                    handler.postDelayed({
                        view.evaluateJavascript("""(function(){const els=[...document.querySelectorAll('button,[role="tab"],[role="button"],a,div,span')];const el=els.find(e=>e.innerText?.trim()==='İdman'&&e.getClientRects().length&&e.clientWidth>0&&e.clientHeight>0);if(!el)return false;el.click();return true;})()""") {
                            handler.postDelayed({
                                view.evaluateJavascript(
                                    "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,100000),timeZoneId:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'})"
                                ) { encoded ->
                                    val checkedAt=System.currentTimeMillis()
                                    captureScope.launch {
                                        try {
                                            val page=withContext(Dispatchers.Default) {
                                                val decoded=org.json.JSONTokener(encoded ?: "null").nextValue()
                                                val json=when(decoded) {
                                                    is org.json.JSONObject -> decoded
                                                    is String -> org.json.JSONObject(decoded)
                                                    else -> error("Страница eTopaz не вернула видимые данные.")
                                                }
                                                MisliVisiblePage(
                                                    url=json.optString("url"),title=json.optString("title"),
                                                    visibleText=json.optString("text"),timeZoneId=json.optString("timeZoneId","UTC")
                                                )
                                            }
                                            val fixtures=repository.fixtureIdentities()
                                            val result=withContext(Dispatchers.Default) { parser.parseVisiblePage(page,checkedAt,fixtures) }
                                            val status=if(result.snapshots.isEmpty()) OddsProviderStatus.UNAVAILABLE else OddsProviderStatus.LIVE
                                            repository.saveBookmakerCapture("topaz",checkedAt,status,result.snapshots,result.detail)
                                        } catch(error:CancellationException) {
                                            throw error
                                        } catch(error:Exception) {
                                            persistBookmakerCaptureFailure(
                                                repository,"topaz",checkedAt,error.message ?: "Не удалось прочитать видимые данные публичной страницы."
                                            )
                                        } finally {
                                            if(captureScope.isActive&&!activity.isFinishing) finishCapture(view,checkedAt)
                                        }
                                    }
                                }
                            },3_000)
                        }
                    },4_000)
                }

                override fun shouldOverrideUrlLoading(view:WebView,url:String):Boolean {
                    val uri=runCatching { java.net.URI(url) }.getOrNull()
                    return uri==null||uri.scheme!="https"||uri.host !in setOf("etopaz.az","www.etopaz.az")
                }
            }
            val publicUrl="https://etopaz.az/home?sport-id=1%3Asr%3Asport%3A1"
            if(initialLoadDelay==0L) loadUrl(publicUrl) else handler.postDelayed({
                if(!activity.isFinishing) loadUrl(publicUrl)
            },initialLoadDelay)
        }
    }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose { handler.removeCallbacksAndMessages(null);webView.stopLoading();webView.destroy() }
    }
    AndroidView(factory={webView},modifier=Modifier.size(1.dp).alpha(0.01f))
}
