package az.simplesoft.footballintelligence

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import az.simplesoft.footballintelligence.data.ForegroundBookmakerCaptureSession
import az.simplesoft.footballintelligence.data.bookmakerCaptureDelayMillis
import az.simplesoft.footballintelligence.data.shouldCollectPublicOdds
import java.util.concurrent.atomic.AtomicBoolean

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

@Composable
internal fun MisliPublicPageCollector(repository: FootballScreenRepository, previousCheck: Long?, onCaptured: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val latestPreviousCheck by rememberUpdatedState(previousCheck)
    val captureScope = rememberCoroutineScope()
    val parser = remember { MisliOddsProvider() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val captureSession = remember(activity) { ForegroundBookmakerCaptureSession() }
    val reloadScheduled = remember(activity) { AtomicBoolean(false) }
    val publicUrl = "https://www.misli.az/idman-novleri/futbol"
    val initialLoadDelay = bookmakerCaptureDelayMillis(previousCheck, System.currentTimeMillis())

    val webView = remember(activity, captureSession, reloadScheduled) {
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                private fun isForeground() = shouldCollectPublicOdds(activity.lifecycle.currentState)

                private fun scheduleReload(view: WebView, delayMillis: Long) {
                    if (!isForeground() || !reloadScheduled.compareAndSet(false, true)) return
                    handler.postDelayed({
                        reloadScheduled.set(false)
                        if (!activity.isFinishing && isForeground()) view.reload()
                    }, delayMillis.coerceAtLeast(0L))
                }

                private fun finishCapture(view: WebView, generation: Long, capturedAtMillis: Long) {
                    if (!captureSession.isCurrent(generation) || !isForeground()) {
                        captureSession.gate.cancelInProgress()
                        return
                    }
                    captureSession.gate.finish(capturedAtMillis)
                    latestOnCaptured()
                    scheduleReload(view, BOOKMAKER_CAPTURE_INTERVAL_MILLIS)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val uri = runCatching { java.net.URI(url) }.getOrNull()
                    if (uri == null || uri.host !in setOf("misli.az", "www.misli.az") || uri.path != "/idman-novleri/futbol") return
                    if (!isForeground()) {
                        captureSession.invalidate()
                        return
                    }
                    val pageFinishedAt = System.currentTimeMillis()
                    if (!captureSession.gate.tryStart(pageFinishedAt)) {
                        if (!captureSession.gate.isCaptureInProgress()) {
                            scheduleReload(view, captureSession.gate.millisUntilAllowed(pageFinishedAt))
                        }
                        return
                    }
                    val generation = captureSession.currentGeneration()
                    handler.removeCallbacksAndMessages(null)
                    reloadScheduled.set(false)
                    handler.postDelayed({
                        if (!isForeground() || !captureSession.isCurrent(generation)) {
                            captureSession.gate.cancelInProgress()
                            return@postDelayed
                        }
                        view.evaluateJavascript(
                            "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,100000),timeZoneId:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'})",
                        ) { encoded ->
                            if (!isForeground() || !captureSession.isCurrent(generation)) {
                                captureSession.gate.cancelInProgress()
                                return@evaluateJavascript
                            }
                            val checkedAt = System.currentTimeMillis()
                            captureScope.launch {
                                try {
                                    if (!isForeground() || !captureSession.isCurrent(generation)) return@launch
                                    val page = withContext(Dispatchers.Default) {
                                        val decoded = org.json.JSONTokener(encoded ?: "null").nextValue()
                                        val json = when (decoded) {
                                            is org.json.JSONObject -> decoded
                                            is String -> org.json.JSONObject(decoded)
                                            else -> error("Страница не вернула видимые данные.")
                                        }
                                        MisliVisiblePage(
                                            url = json.optString("url"), title = json.optString("title"),
                                            visibleText = json.optString("text"), timeZoneId = json.optString("timeZoneId", "UTC"),
                                        )
                                    }
                                    if (!isForeground() || !captureSession.isCurrent(generation)) return@launch
                                    val fixtures = repository.fixtureIdentities()
                                    val result = withContext(Dispatchers.Default) { parser.parseVisiblePage(page, checkedAt, fixtures) }
                                    if (!isForeground() || !captureSession.isCurrent(generation)) return@launch
                                    val status = if (result.snapshots.isEmpty()) OddsProviderStatus.UNAVAILABLE else OddsProviderStatus.LIVE
                                    repository.saveBookmakerCapture("misli", checkedAt, status, result.snapshots, result.detail)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (isForeground() && captureSession.isCurrent(generation)) {
                                        persistBookmakerCaptureFailure(
                                            repository, "misli", checkedAt,
                                            error.message ?: "Не удалось прочитать видимые данные публичной страницы.",
                                        )
                                    }
                                } finally {
                                    if (captureScope.isActive && !activity.isFinishing && isForeground() && captureSession.isCurrent(generation)) {
                                        finishCapture(view, generation, checkedAt)
                                    } else {
                                        captureSession.gate.cancelInProgress()
                                    }
                                }
                            }
                        }
                    }, 4_000)
                }

                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val uri = runCatching { java.net.URI(url) }.getOrNull()
                    return uri == null || uri.scheme != "https" || uri.host !in setOf("misli.az", "www.misli.az")
                }
            }
            if (initialLoadDelay == 0L) {
                if (shouldCollectPublicOdds(activity.lifecycle.currentState)) loadUrl(publicUrl)
            } else {
                handler.postDelayed({
                    if (!activity.isFinishing && shouldCollectPublicOdds(activity.lifecycle.currentState)) loadUrl(publicUrl)
                }, initialLoadDelay)
            }
        }
    }

    DisposableEffect(activity, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    captureSession.invalidate()
                    reloadScheduled.set(false)
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    val delay = bookmakerCaptureDelayMillis(latestPreviousCheck, System.currentTimeMillis())
                    handler.postDelayed({
                        if (!activity.isFinishing && shouldCollectPublicOdds(activity.lifecycle.currentState)) webView.loadUrl(publicUrl)
                    }, delay)
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            captureSession.invalidate()
            reloadScheduled.set(false)
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.size(1.dp).alpha(0.01f))
}

/** Reads only rendered text from eTopaz's ordinary public pre-match page. */
@Composable
internal fun TopazPublicPageCollector(repository: FootballScreenRepository, previousCheck: Long?, onCaptured: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val latestPreviousCheck by rememberUpdatedState(previousCheck)
    val captureScope = rememberCoroutineScope()
    val parser = remember { TopazOddsProvider() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val captureSession = remember(activity) { ForegroundBookmakerCaptureSession() }
    val reloadScheduled = remember(activity) { AtomicBoolean(false) }
    val publicUrl = TopazOddsProvider.PUBLIC_PREMATCH_URL
    val initialLoadDelay = bookmakerCaptureDelayMillis(previousCheck, System.currentTimeMillis())

    val webView = remember(activity, captureSession, reloadScheduled) {
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                private fun isForeground() = shouldCollectPublicOdds(activity.lifecycle.currentState)

                private fun scheduleReload(view: WebView, delayMillis: Long) {
                    if (!isForeground() || !reloadScheduled.compareAndSet(false, true)) return
                    handler.postDelayed({
                        reloadScheduled.set(false)
                        if (!activity.isFinishing && isForeground()) view.reload()
                    }, delayMillis.coerceAtLeast(0L))
                }

                private fun finishCapture(view: WebView, generation: Long, capturedAtMillis: Long) {
                    if (!captureSession.isCurrent(generation) || !isForeground()) {
                        captureSession.gate.cancelInProgress()
                        return
                    }
                    captureSession.gate.finish(capturedAtMillis)
                    latestOnCaptured()
                    scheduleReload(view, BOOKMAKER_CAPTURE_INTERVAL_MILLIS)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!TopazOddsProvider.isSupportedPublicRoute(url)) return
                    if (!isForeground()) {
                        captureSession.invalidate()
                        return
                    }
                    val pageFinishedAt = System.currentTimeMillis()
                    if (!captureSession.gate.tryStart(pageFinishedAt)) {
                        if (!captureSession.gate.isCaptureInProgress()) {
                            scheduleReload(view, captureSession.gate.millisUntilAllowed(pageFinishedAt))
                        }
                        return
                    }
                    val generation = captureSession.currentGeneration()
                    handler.removeCallbacksAndMessages(null)
                    reloadScheduled.set(false)
                    handler.postDelayed({
                        if (!isForeground() || !captureSession.isCurrent(generation)) {
                            captureSession.gate.cancelInProgress()
                            return@postDelayed
                        }
                        view.evaluateJavascript(
                            "JSON.stringify({title:document.title,url:location.href,text:(document.body?.innerText||'').slice(0,100000),timeZoneId:Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC'})",
                        ) { encoded ->
                            if (!isForeground() || !captureSession.isCurrent(generation)) {
                                captureSession.gate.cancelInProgress()
                                return@evaluateJavascript
                            }
                            val checkedAt = System.currentTimeMillis()
                            captureScope.launch {
                                try {
                                    if (!isForeground() || !captureSession.isCurrent(generation)) return@launch
                                    val page = withContext(Dispatchers.Default) {
                                        val decoded = org.json.JSONTokener(encoded ?: "null").nextValue()
                                        val json = when (decoded) {
                                            is org.json.JSONObject -> decoded
                                            is String -> org.json.JSONObject(decoded)
                                            else -> error("Страница eTopaz не вернула видимые события.")
                                        }
                                        MisliVisiblePage(
                                            url = json.optString("url"), title = json.optString("title"),
                                            visibleText = json.optString("text"), timeZoneId = json.optString("timeZoneId", "UTC"),
                                        )
                                    }
                                    if (!isForeground() || !captureSession.isCurrent(generation)) return@launch
                                    val fixtures = repository.fixtureIdentities()
                                    val result = withContext(Dispatchers.Default) { parser.parseVisiblePage(page, checkedAt, fixtures) }
                                    if (!isForeground() || !captureSession.isCurrent(generation)) return@launch
                                    val status = if (result.snapshots.isEmpty()) OddsProviderStatus.UNAVAILABLE else OddsProviderStatus.LIVE
                                    repository.saveBookmakerCapture("topaz", checkedAt, status, result.snapshots, result.detail)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (isForeground() && captureSession.isCurrent(generation)) {
                                        persistBookmakerCaptureFailure(
                                            repository, "topaz", checkedAt,
                                            error.message ?: "Не удалось прочитать видимые данные публичной страницы.",
                                        )
                                    }
                                } finally {
                                    if (captureScope.isActive && !activity.isFinishing && isForeground() && captureSession.isCurrent(generation)) {
                                        finishCapture(view, generation, checkedAt)
                                    } else {
                                        captureSession.gate.cancelInProgress()
                                    }
                                }
                            }
                        }
                    }, 5_000)
                }

                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !TopazOddsProvider.isSupportedPublicRoute(url)
            }
            if (initialLoadDelay == 0L) {
                if (shouldCollectPublicOdds(activity.lifecycle.currentState)) loadUrl(publicUrl)
            } else {
                handler.postDelayed({
                    if (!activity.isFinishing && shouldCollectPublicOdds(activity.lifecycle.currentState)) loadUrl(publicUrl)
                }, initialLoadDelay)
            }
        }
    }

    DisposableEffect(activity, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    captureSession.invalidate()
                    reloadScheduled.set(false)
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView.onResume()
                    val delay = bookmakerCaptureDelayMillis(latestPreviousCheck, System.currentTimeMillis())
                    handler.postDelayed({
                        if (!activity.isFinishing && shouldCollectPublicOdds(activity.lifecycle.currentState)) webView.loadUrl(publicUrl)
                    }, delay)
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            captureSession.invalidate()
            reloadScheduled.set(false)
            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.size(1.dp).alpha(0.01f))
}
