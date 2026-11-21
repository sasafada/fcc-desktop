package app.fcc.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.webkit.WebViewAssetLoader
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.Properties

/**
 * Hospeda a interface (WebView com HTML/CSS/JS dos assets) e conecta-a ao ChatController.
 * O terminal NÃO faz parte da experiência normal: fica numa tela técnica opcional (Configurações → Avançado).
 */
class MainActivity : Activity(), TerminalViewClient, TerminalSessionClient, Rootfs.Listener, ChatController.Host {

    companion object {
        private const val REQ_TREE = 41
        private const val REQ_FILE = 42
        private const val UI_URL = "https://appassets.androidplatform.net/assets/ui/index.html"
        private const val UI_HOST = "appassets.androidplatform.net"
    }

    private enum class DiagMode { SHELL, DOCTOR, LOG, FCC }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var web: WebView
    private lateinit var controller: ChatController
    private var pickingFor: String? = null

    // painel de provedores (Admin do FCC)
    private var adminOverlay: LinearLayout? = null
    private var adminWeb: WebView? = null
    private val popups = mutableListOf<WebView>()
    private var lastExternalUrl = ""
    private var lastExternalAt = 0L

    // terminal técnico (opcional)
    private var termOverlay: LinearLayout? = null
    private var terminalView: TerminalView? = null
    private var termSession: TerminalSession? = null
    private var fontPx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ================================================================ ciclo de vida
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontPx = dp(13)
        frame = FrameLayout(this).apply { fitsSystemWindows = true; setBackgroundColor(Color.parseColor("#0A0B0F")) }
        setContentView(frame)
        controller = ChatController(this, this)
        setupWeb()
        requestNotificationPermission()
        try { startForegroundService(Intent(this, KeepAliveService::class.java)) } catch (_: Exception) {}
        if (Rootfs.isReady(this)) controller.start() else beginExtraction()
    }

    override fun onResume() {
        super.onResume()
        adminWeb?.onResume()
        controller.onForeground()
    }

    override fun onPause() { adminWeb?.onPause(); super.onPause() }

    override fun onStop() { controller.onBackground(); super.onStop() }

    override fun onDestroy() {
        popups.forEach { try { it.destroy() } catch (_: Exception) {} }
        popups.clear()
        Rootfs.detach(this)
        adminWeb?.destroy()
        termSession?.finishIfRunning()
        if (isFinishing) {
            controller.shutdown()
            FccServer.stop()
            stopService(Intent(this, KeepAliveService::class.java))
        }
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            termOverlay?.visibility == View.VISIBLE -> closeTerminal()
            adminOverlay?.visibility == View.VISIBLE -> { if (adminWeb?.canGoBack() == true) adminWeb?.goBack() else closeAdmin() }
            else -> web.evaluateJavascript("window.FCC?FCC.back():false") { r -> if (r != "true") finish() }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    // ================================================================ interface (WebView com os assets)
    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) { ui.post { controller.onMessage(json) } }
    }

    private fun setupWeb() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0B0F"))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false          // a interface vem só dos assets, por https interno
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.host == UI_HOST) return false
                    if (u.scheme == "http" || u.scheme == "https") openExternal(u)   // nunca navega para fora da interface
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "AndroidBridge")
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        web.loadUrl(UI_URL)
    }

    // ================================================================ ChatController.Host
    override fun js(code: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) web.evaluateJavascript(code, null)
        else ui.post { web.evaluateJavascript(code, null) }
    }

    override fun pickFolder(convId: String) {
        pickingFor = convId
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        try { startActivityForResult(i, REQ_TREE) }
        catch (_: Exception) { Toast.makeText(this, "Este aparelho não tem seletor de pastas.", Toast.LENGTH_LONG).show() }
    }

    override fun pickAttachment() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_FILE) }
        catch (_: Exception) { Toast.makeText(this, "Este aparelho não tem seletor de arquivos.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_TREE -> { val id = pickingFor; pickingFor = null; if (id != null) controller.onFolderPicked(id, data.data) }
            REQ_FILE -> {
                val uris = ArrayList<Uri>()
                data.data?.let { uris.add(it) }
                data.clipData?.let { cd -> for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { uris.add(it) } }
                if (uris.isNotEmpty()) controller.onAttachments(uris)
            }
        }
    }

    override fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("FCC AI", text))
    }

    override fun openUrl(url: String) { openExternal(Uri.parse(url)) }

    override fun retryExtraction() { controller.setBusy("Preparando o app", "Tentando novamente…", -1.0); Rootfs.retry(this) }

    /** Abre a página oficial de login em Chrome Custom Tabs (ou no navegador padrão, se não houver). */
    private fun openExternal(uri: Uri) {
        val now = System.currentTimeMillis()
        if (uri.toString() == lastExternalUrl && now - lastExternalAt < 3000) return
        lastExternalUrl = uri.toString(); lastExternalAt = now
        try { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri) }
        catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_LONG).show() }
        }
    }

    // ================================================================ preparo do Linux (1º uso)
    private fun beginExtraction() {
        controller.setBusy("Preparando o app", "Extraindo o ambiente da IA (só na primeira vez)…", -1.0)
        Rootfs.attach(this, this)
    }

    override fun onProgress(fraction: Float, doneBytes: Long, totalBytes: Long) {
        ui.post { controller.setBusy("Preparando o app", "Extraindo o ambiente da IA (só na primeira vez)\n${doneBytes / 1048576} MB de ${totalBytes / 1048576} MB", fraction.toDouble()) }
    }

    override fun onFinished(error: Throwable?) {
        ui.post {
            if (error == null) controller.start()
            else controller.setBusy("Não foi possível preparar o app", "${error.javaClass.simpleName}: ${error.message}", null, true)
        }
    }

    // ================================================================ painel de provedores (Admin do FCC)
    private fun isLocalHost(u: Uri) = u.host == "127.0.0.1" || u.host == "localhost"

    private fun routePopup(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        openExternal(uri)
        view.stopLoading()
        ui.post { popups.remove(view); try { view.destroy() } catch (_: Exception) {} }
        return true
    }

    override fun openAdmin() {
        if (adminOverlay == null) {
            val ov = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#14161C")); isClickable = true }
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#1C1F28")); setPadding(dp(16), dp(10), dp(10), dp(10))
            }
            bar.addView(TextView(this).apply { text = "Provedores de IA"; setTextColor(Color.parseColor("#F5F6FA")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            bar.addView(pill("Fechar", Color.parseColor("#6C5CE7")) { closeAdmin() })
            ov.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val w = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // O painel abre o login OAuth com window.open(); cada janela vira um WebView invisível
                // cuja URL http(s) é encaminhada ao navegador (Custom Tabs). Sem isto: tela branca.
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        return if (u.scheme?.startsWith("http") == true && !isLocalHost(u)) { openExternal(u); true } else false
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) view.loadDataWithBaseURL(null,
                            "<html><body style='background:#14161C;color:#F5F6FA;font-family:sans-serif;padding:24px'><h3>Assistente indisponível</h3>" +
                                "<p>O assistente interno ainda está iniciando. Aguarde alguns instantes e abra este painel de novo.</p></body></html>",
                            "text/html", "utf-8", null)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                        val child = WebView(view.context)
                        child.settings.javaScriptEnabled = true
                        child.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean = routePopup(v, request.url)
                            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) { if (url != null) routePopup(v, Uri.parse(url)) }
                        }
                        popups.add(child)
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = child
                        resultMsg.sendToTarget()
                        return true
                    }
                    override fun onCloseWindow(window: WebView) { popups.remove(window); ui.post { try { window.destroy() } catch (_: Exception) {} } }
                    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                        AlertDialog.Builder(this@MainActivity).setMessage(message)
                            .setPositiveButton("OK") { _, _ -> result.confirm() }
                            .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                            .setOnCancelListener { result.cancel() }.show()
                        return true
                    }
                    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                        AlertDialog.Builder(this@MainActivity).setMessage(message)
                            .setPositiveButton("OK") { _, _ -> result.confirm() }
                            .setOnCancelListener { result.confirm() }.show()
                        return true
                    }
                }
            }
            ov.addView(w, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            frame.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            adminOverlay = ov; adminWeb = w
        }
        adminWeb?.loadUrl("http://127.0.0.1:${FccServer.port}/admin")
        adminOverlay?.visibility = View.VISIBLE
    }

    private fun closeAdmin() {
        adminOverlay?.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(frame.windowToken, 0)
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
        setOnClickListener { onClick() }
    }

    // ================================================================ terminal técnico (opcional, fora da experiência normal)
    override fun openTerminal() {
        if (termOverlay == null) {
            val p = Properties()
            p["background"] = "#0A0B0F"; p["foreground"] = "#F5F6FA"; p["cursor"] = "#6C5CE7"
            TerminalColors.COLOR_SCHEME.updateWith(p)
            val ov = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A0B0F")); isClickable = true }
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8)) }
            bar.addView(TextView(this).apply { text = "Terminal (diagnóstico)"; setTextColor(Color.parseColor("#F5F6FA")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            bar.addView(pill("Fechar", Color.parseColor("#6C5CE7")) { closeTerminal() })
            ov.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

            val tv = TerminalView(this, null).apply {
                isFocusable = true; isFocusableInTouchMode = true
                setTerminalViewClient(this@MainActivity)
                setTextSize(fontPx)
                setTypeface(try { Typeface.createFromAsset(assets, "fonts/mono.ttf") } catch (_: Exception) { Typeface.MONOSPACE })
                keepScreenOn = true
            }
            terminalView = tv
            ov.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
            fun chip(t: String, onClick: () -> Unit) {
                row.addView(TextView(this).apply {
                    text = t; setTextColor(Color.parseColor("#F5F6FA")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
                    setPadding(dp(13), dp(8), dp(13), dp(8))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#232634")); cornerRadius = dp(14).toFloat() }
                    setOnClickListener { onClick() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
            }
            chip("Shell") { startDiag(DiagMode.SHELL) }
            chip("Diagnóstico") { startDiag(DiagMode.DOCTOR) }
            chip("Log") { startDiag(DiagMode.LOG) }
            chip("FCC (TUI)") { startDiag(DiagMode.FCC) }
            chip("Esc") { termSession?.write("\u001b") }
            chip("Tab") { termSession?.write("\t") }
            chip("↑") { termSession?.write("\u001b[A") }
            chip("↓") { termSession?.write("\u001b[B") }
            chip("←") { termSession?.write("\u001b[D") }
            chip("→") { termSession?.write("\u001b[C") }
            chip("Ctrl+C") { termSession?.write("\u0003") }
            ov.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            frame.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            termOverlay = ov
        }
        termOverlay?.visibility = View.VISIBLE
        startDiag(DiagMode.SHELL)
    }

    private fun closeTerminal() {
        termOverlay?.visibility = View.GONE
        termSession?.finishIfRunning(); termSession = null
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(frame.windowToken, 0)
    }

    private fun startDiag(mode: DiagMode) {
        val old = termSession
        val s = try {
            Proot.prepareRootfs(this)
            val c = controller.store.active()
            val ws = Workspace.dir(this, c.id).apply { mkdirs() }
            val command = when (mode) {
                DiagMode.SHELL -> "exec bash -l"
                DiagMode.DOCTOR -> "fcc-doctor; echo; echo '[Enter para abrir um shell]'; read; exec bash -l"
                DiagMode.LOG -> "fcc-ctl status; echo; fcc-ctl logs 80; echo; exec bash -l"
                DiagMode.FCC -> "exec /usr/local/bin/fcc-app-launch"
            }
            val args = Proot.args(this, listOf("${ws.path}:/workspace"), "/workspace", listOf("IS_SANDBOX=1"), listOf("/bin/bash", "-lc", command))
            TerminalSession(Proot.binary(this), filesDir.path, args.toTypedArray(), Proot.env(this), 4000, this)
        } catch (e: Exception) { Toast.makeText(this, "Não foi possível abrir o terminal: ${e.message}", Toast.LENGTH_LONG).show(); return }
        termSession = s
        old?.finishIfRunning()
        terminalView?.attachSession(s)
        terminalView?.requestFocus()
    }

    // ================================================================ TerminalSessionClient (usado só no terminal técnico)
    override fun onTextChanged(changedSession: TerminalSession) { terminalView?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) { copy(text) }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        termSession?.emulator?.paste(t)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun onSingleTapUp(e: MotionEvent) {
        val tv = terminalView ?: return
        tv.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(tv, 0)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun shouldUseFnKeyWorkaround() = false
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    // ================================================================ TerminalViewClient
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            fontPx = (fontPx + dp(if (scale > 1f) 1 else -1)).coerceIn(dp(8), dp(26)); terminalView?.setTextSize(fontPx); return 1f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) {
        val tv = terminalView ?: return
        tv.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(tv, 0)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun shouldUseFnKeyWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "error", e) }
}