package com.cosmic.ai.ui.screens.projects

import android.content.Context
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.cosmic.ai.data.local.datastore.AppPreferences
import com.cosmic.ai.ui.theme.CosmicBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

private const val PREVIEW_HOST = "cosmic-preview.local"
private const val MAX_HTML_BYTES = 1_000_000
private const val MAX_ASSET_BYTES = 5_000_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlPreviewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    val projectUriText by preferences.currentProjectUri.collectAsState(initial = null)
    val projectUri = remember(projectUriText) { projectUriText?.let(Uri::parse) }
    var html by remember(projectUri) { mutableStateOf<String?>(null) }
    var loadError by remember(projectUri) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val console = remember { mutableStateListOf<String>() }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(projectUri, reload) {
        html = null
        loadError = null
        console.clear()
        if (projectUri == null) {
            loadError = "Select a project folder before opening a preview."
        } else {
            val result = withContext(Dispatchers.IO) { readProjectHtml(context, projectUri) }
            html = result.first
            loadError = result.second
        }
    }
    LaunchedEffect(html, reload, webView) {
        val source = html ?: return@LaunchedEffect
        webView?.loadDataWithBaseURL(
            "https://$PREVIEW_HOST/preview/index.html", securePreviewHtml(source), "text/html", "UTF-8", null
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("HTML Preview") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Reload preview") } }
        )
    }) { insets ->
        CosmicBackdrop(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loadError != null -> Text(loadError.orEmpty(), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                    html == null -> Text("Loading project preview…", Modifier.padding(20.dp))
                    else -> key(projectUri) {
                        AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { viewContext ->
                            WebView(viewContext).also { view ->
                                webView = view
                                view.settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    allowFileAccessFromFileURLs = false
                                    allowUniversalAccessFromFileURLs = false
                                    javaScriptCanOpenWindowsAutomatically = false
                                    setSupportMultipleWindows(false)
                                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }
                                view.webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                        val line = "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                                        view.post {
                                            if (console.size >= 200) console.removeAt(0)
                                            console.add(line.take(1000))
                                        }
                                        return true
                                    }
                                }
                                view.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        request.url.host != PREVIEW_HOST

                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                                        interceptProjectAsset(context, projectUri, request.url)

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) view.post { console.add("ERROR: ${error.description}") }
                                    }
                                }
                            }
                        }
                    )
                    }
                }
                if (console.isNotEmpty()) {
                    Text("Console", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.fillMaxWidth().weight(.35f, fill = false).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(console.toList()) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(webView) {
        val ownedView = webView
        onDispose {
            ownedView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
    }
}

internal fun securePreviewHtml(source: String): String {
    val csp = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self' data:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'none'; object-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'\">"
    val viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=5\">"
    val head = Regex("(?i)<head(?:\\s[^>]*)?>")
    val injection = csp + if (Regex("(?i)<meta[^>]+name=[\"']viewport[\"']").containsMatchIn(source)) "" else viewport
    val match = head.find(source)
    return if (match != null) {
        source.replaceRange(match.range, match.value + injection)
    } else {
        "<!doctype html><html><head>$injection</head><body>$source</body></html>"
    }
}

private suspend fun readProjectHtml(context: Context, rootUri: Uri): Pair<String?, String?> {
    return try {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: return null to "The selected project folder is no longer available."
        val file = root.findFile("index.html")?.takeIf { it.isFile }
            ?: return null to "No index.html was found in the selected project folder."
        if (file.length() > MAX_HTML_BYTES) return null to "index.html exceeds the 1 MB preview limit."
        val input = context.contentResolver.openInputStream(file.uri) ?: return null to "Could not read index.html."
        val bytes = input.use { readCapped(it, MAX_HTML_BYTES) } ?: return null to "index.html exceeds the preview limit."
        bytes.toString(Charsets.UTF_8) to null
    } catch (e: SecurityException) {
        null to "Access to the selected project folder was revoked. Select it again to preview."
    } catch (e: Exception) {
        null to "Could not load the project preview."
    }
}

private fun readCapped(input: InputStream, limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun interceptProjectAsset(context: Context, projectUri: Uri?, requestUri: Uri): WebResourceResponse {
    if (requestUri.host != PREVIEW_HOST || requestUri.scheme != "https" || projectUri == null) return blockedResponse()
    val encoded = requestUri.path.orEmpty().removePrefix("/preview/").removePrefix("/")
    val path = runCatching { Uri.decode(encoded) }.getOrNull() ?: return blockedResponse()
    val segments = path.split('/')
    if (path.isBlank() || path.contains('\\') || segments.any { it.isBlank() || it == "." || it == ".." }) return blockedResponse()
    return try {
        val root = DocumentFile.fromTreeUri(context, projectUri) ?: return blockedResponse()
        var file = root
        for ((index, segment) in segments.withIndex()) {
            file = file.findFile(segment) ?: return missingResponse()
            if (index < segments.lastIndex && !file.isDirectory) return missingResponse()
        }
        if (!file.isFile || file.length() > MAX_ASSET_BYTES) return missingResponse()
        val input = context.contentResolver.openInputStream(file.uri) ?: return missingResponse()
        val contentType = mimeFor(file.name.orEmpty())
        val bytes = input.use { readCapped(it, MAX_ASSET_BYTES) } ?: return missingResponse()
        WebResourceResponse(contentType, if (contentType.startsWith("text/") || contentType.contains("javascript") || contentType.contains("json")) "UTF-8" else null, bytes.inputStream())
    } catch (_: Exception) { missingResponse() }
}

private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js", "mjs" -> "application/javascript"
    "json", "map" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    else -> "application/octet-stream"
}

private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked by COSMIC preview policy", emptyMap(), ByteArrayInputStream(ByteArray(0)))
private fun missingResponse() = WebResourceResponse("text/plain", "UTF-8", 404, "Local project asset unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
