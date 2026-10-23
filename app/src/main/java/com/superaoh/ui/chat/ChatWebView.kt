package com.superaoh.ui.chat

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.superaoh.container.GameInputBridge
import com.superaoh.core.AiConfig
import com.superaoh.ui.AiSettingsActivity
import java.io.ByteArrayInputStream
import java.util.Locale

/** Offline, appassets-only WebView for zen-ai-chat-ui. */
class ChatWebView(
    private val activity: Activity,
    private val controller: ChatSessionController,
    private val onClose: Runnable? = null,
    private val isGameMode: Boolean = false,
    private val onCaptureScreen: Runnable? = null,
) : FrameLayout(activity), ChatSessionController.Observer {

    companion object {
        const val REQUEST_IMAGE = 7361
        const val REQUEST_FILE = 7362
        private const val ASSET_PREFIX = "https://appassets.androidplatform.net/assets/chatui/"
    }

    private val webView = WebView(activity)
    @Volatile private var ready = false

    init {
        setBackgroundColor(0xFF0B1019.toInt())
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.textZoom = 100
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                android.app.AlertDialog.Builder(activity)
                    .setMessage(message.take(300))
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetResponse(request.url.toString()) ?: blockedResponse()

            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                assetResponse(url) ?: blockedResponse()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith(ASSET_PREFIX)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !url.startsWith(ASSET_PREFIX)

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(ASSET_PREFIX + "index.html")) controller.addObserver(this@ChatWebView)
            }
        }
        webView.addJavascriptInterface(Bridge(), "SuperAohNative")
        addView(webView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView.loadUrl(ASSET_PREFIX + "index.html")
    }

    private fun assetResponse(url: String): WebResourceResponse? {
        if (!url.startsWith(ASSET_PREFIX)) return null
        val raw = url.removePrefix(ASSET_PREFIX).substringBefore('?').substringBefore('#')
        if (raw.isBlank() || raw.contains("..") || raw.startsWith('/')) return blockedResponse()
        val mime = when (raw.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
        return try {
            WebResourceResponse(mime, "UTF-8", activity.assets.open("chatui/$raw"))
        } catch (_: Throwable) {
            WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    override fun onChatEvent(type: String, payload: Map<String, Any?>) {
        if (!ready && type != "snapshot") return
        val json = com.superaoh.core.Json.write(payload)
        val typeJson = com.superaoh.core.Json.write(type)
        post { webView.evaluateJavascript("window.SuperAoh&&window.SuperAoh.receive($typeJson,$json)", null) }
    }

    fun refreshGameState() {
        if (isGameMode && ready) sendSnapshot(mapOf("controlsEnabled" to GameInputBridge.isControlsEnabled()))
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_IMAGE && requestCode != REQUEST_FILE) return false
        if (resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                if (requestCode == REQUEST_IMAGE) controller.attachImage(uri)
                else controller.attachFile(uri)
            }
        }
        return true
    }

    fun onBackPressed(onHandled: Runnable, onUnhandled: Runnable) {
        webView.evaluateJavascript("window.SuperAoh&&window.SuperAoh.back()") { result ->
            if (result == "true") onHandled.run() else onUnhandled.run()
        }
    }

    fun destroy() {
        controller.removeObserver(this)
        ready = false
        if (isGameMode) GameInputBridge.setControlsEnabled(false)
        webView.removeJavascriptInterface("SuperAohNative")
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface fun isGameMode(): Boolean = isGameMode
        @JavascriptInterface fun onReady() {
            ready = true
            controller.onHostResumed()
            post { sendProfiles() }
        }
        @JavascriptInterface fun send(text: String) { post { controller.send(text.take(16000)) } }
        @JavascriptInterface fun cancel() { post { controller.cancel() } }
        @JavascriptInterface fun newSession() { post { controller.newSession() } }
        @JavascriptInterface fun openSession(id: String) { post { if (validId(id)) controller.openSession(id) } }
        @JavascriptInterface fun deleteSession(id: String) { post { if (validId(id)) controller.deleteSession(id) } }
        @JavascriptInterface fun renameSession(id: String, title: String) {
            post { if (validId(id)) controller.renameSession(id, title.take(100)) }
        }
        @JavascriptInterface fun removeImage(index: Int) { post { controller.removePendingImage(index) } }
        @JavascriptInterface fun chooseImage() {
            post {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                activity.startActivityForResult(intent, REQUEST_IMAGE)
            }
        }

        /**
         * 选择任意文件。
         *
         * type 直接给通配符（不限格式）。注意：不要在注释里写出
         * 通配符的字面量，它的结尾会和注释结束符撞上，把注释提前截断。
         *
         * 不设 EXTRA_ALLOW_MULTIPLE：多选在部分国产 ROM 的
         * DocumentsUI 上返回 clipData 而不是 data，处理分支会翻倍，
         * 而一次拖一个大文件（APK/save/zip）本来就是主要场景。
         */
        @JavascriptInterface fun chooseFile() {
            post {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    activity.startActivityForResult(intent, REQUEST_FILE)
                } catch (t: Throwable) {
                    Toast.makeText(activity, "没有可用的文件选择器：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface fun removeFile(index: Int) { post { controller.removePendingFile(index) } }
        @JavascriptInterface fun addImageData(dataUrl: String, name: String): Boolean {
            return try {
                val comma = dataUrl.indexOf(',')
                require(comma in 1..80 && dataUrl.substring(0, comma).startsWith("data:image/", true))
                val encoded = dataUrl.substring(comma + 1)
                require(encoded.length <= 8 * 1024 * 1024)
                val bytes = if (Build.VERSION.SDK_INT >= 26) java.util.Base64.getDecoder().decode(encoded)
                    else android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                controller.saveExternalImage(bytes, name)
            } catch (_: Throwable) { false }
        }
        @JavascriptInterface fun openSettings() {
            post { activity.startActivity(Intent(activity, AiSettingsActivity::class.java)) }
        }
        @JavascriptInterface fun selectProfile(id: String) { post { selectProfileInternal(id) } }
        @JavascriptInterface fun captureScreen() {
            post {
                if (onCaptureScreen != null) onCaptureScreen.run()
                else Toast.makeText(activity, "游戏截图只在游戏内 AI 面板中可用", Toast.LENGTH_SHORT).show()
            }
        }
        /**
         * 打开工具清单面板。
         *
         * 面板内容由原生侧生成：工具注册中心是唯一权威来源，
         * 前端不该自己维护一份可能过期的清单。
         */
        @JavascriptInterface fun listTools() {
            post {
                val rows = ArrayList<Map<String, Any?>>()
                try {
                    val reg = com.superaoh.core.ToolRuntime.get(activity).provider()
                    for (t in reg.visibleTools()) {
                        val params = t.params()
                        rows.add(mapOf(
                            "name" to t.name(),
                            "description" to t.description(),
                            "source" to t.source(),
                            "needsWorkspace" to t.needsWorkspace(),
                            "params" to params.keys.toList(),
                            "required" to t.required()
                        ))
                    }
                } catch (t: Throwable) {
                    Toast.makeText(activity, "读取工具清单失败：" + t.message, Toast.LENGTH_SHORT).show()
                }
                val payload = mapOf("tools" to rows)
                val json = com.superaoh.core.Json.write(payload)
                webView.evaluateJavascript("window.SuperAoh&&window.SuperAoh.receive('tools',$json)", null)
            }
        }

        @JavascriptInterface fun toggleGameControl(enabled: Boolean) {
            post {
                if (!isGameMode) return@post
                if (!enabled) {
                    GameInputBridge.setControlsEnabled(false)
                    sendSnapshot(mapOf("controlsEnabled" to false))
                    Toast.makeText(activity, "AI 游戏点击已关闭", Toast.LENGTH_SHORT).show()
                } else {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("授权 AI 控制游戏？")
                        .setMessage("AI 可向游戏画面发送单次点击。仅开放受限游戏工具，不允许任意代码或反射调用。")
                        .setNegativeButton("取消") { dialog, _ ->
                            dialog.dismiss()
                            sendSnapshot(mapOf("controlsEnabled" to false))
                        }
                        .setPositiveButton("授权") { dialog, _ ->
                            dialog.dismiss()
                            val active = GameInputBridge.setControlsEnabled(true)
                            sendSnapshot(mapOf("controlsEnabled" to active))
                            Toast.makeText(activity, if (active) "AI 游戏点击已授权" else "游戏尚未就绪，授权失败", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            }
        }
        @JavascriptInterface fun close() { post { if (onClose != null) onClose.run() else activity.finish() } }
        @JavascriptInterface fun retry(id: String) {
            post { Toast.makeText(activity, "重新生成暂未开放，请复制原问题后重新发送", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,100}"))

    private fun sendProfiles() {
        val profiles = AiConfig.listProfiles(activity)
        val active = AiConfig.getActive(activity)
        sendSnapshot(mapOf(
            "profiles" to profiles.map { mapOf("id" to it.id, "name" to it.name, "model" to it.model) },
            "activeProfileId" to active?.id,
            "isGameMode" to isGameMode,
            "controlsEnabled" to (isGameMode && GameInputBridge.isControlsEnabled())
        ))
    }

    private fun sendSnapshot(extra: Map<String, Any?> = emptyMap()) {
        val base = linkedMapOf<String, Any?>(
            "messages" to emptyList<Any>(), "sessions" to emptyList<Any>(),
            "sessionId" to controller.currentSessionId(), "title" to controller.state.title,
            "busy" to controller.state.busy, "busyRound" to controller.state.busyRound,
            "model" to (AiConfig.getActive(activity)?.let { "${it.name} · ${it.model}" } ?: "未配置模型"),
            "pendingImages" to emptyList<Any>(), "contextText" to controller.state.contextText
        )
        val current = controller.snapshotForUi()
        base.putAll(current)
        base.putAll(extra)
        val json = com.superaoh.core.Json.write(base)
        webView.evaluateJavascript("window.SuperAoh&&window.SuperAoh.receive('snapshot',$json)", null)
    }

    private fun selectProfileInternal(id: String) {
        if (controller.state.busy) {
            Toast.makeText(activity, "请先停止当前任务，再切换模型", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = AiConfig.listProfiles(activity).firstOrNull { it.id == id } ?: return
        if (!profile.usable()) {
            Toast.makeText(activity, "该模型配置不完整", Toast.LENGTH_SHORT).show()
            return
        }
        AiConfig.setActive(activity, id)
        controller.onHostResumed()
        sendProfiles()
    }

    // Asset base URL is declared in the primary companion object.
}
