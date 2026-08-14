import android.content.Context
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object XhsFetcher {

    private val XHS_PATTERN = Regex(
        "(https?://)?(www\\.)?(xhslink\\.com|xiaohongshu\\.com)/\\S+"
    )

    fun extractLink(text: String): String? {
        return XHS_PATTERN.find(text)?.value?.let {
            if (it.startsWith("http")) it else "https://$it"
        }
    }

    suspend fun fetchContent(context: Context, url: String): String? {
        return withTimeoutOrNull(15_000) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val webView = WebView(context)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                        domStorageEnabled = true
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // 等 JS 渲染完再抓，延迟 2 秒
                            webView.postDelayed({
                                webView.evaluateJavascript(
                                    """
                                (function() {
                                    var el = document.querySelector('.note-content') 
                                        || document.querySelector('#detail-desc')
                                        || document.querySelector('article')
                                        || document.body;
                                    return el ? el.innerText.substring(0, 1500) : '';
                                })()
                                """.trimIndent()
                                ) { result ->
                                    val cleaned = result
                                        ?.trim('"')
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\\"", "\"")
                                        ?.replace("\\u003C", "<")
                                        ?.replace("\\u003E", ">")
                                        ?.replace("\\u0026", "&")
                                        ?.trim()
                                    if (!cont.isCompleted) {
                                        cont.resume(cleaned?.takeIf { it.length > 30 })
                                    }
                                    try {
                                        webView.stopLoading()
                                        webView.clearHistory()
                                        webView.destroy()
                                    } catch (_: Exception) {
                                    }
                                }
                            }, 2000)
                        }

                        override fun onReceivedError(
                            view: WebView, request: WebResourceRequest, error: WebResourceError
                        ) {
                            if (!cont.isCompleted) cont.resume(null)
                            try {
                                webView.stopLoading()
                                webView.clearHistory()
                                webView.destroy()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    webView.loadUrl(url)

                    cont.invokeOnCancellation {
                        try {
                            webView.stopLoading()
                            webView.clearHistory()
                            webView.destroy()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }}}