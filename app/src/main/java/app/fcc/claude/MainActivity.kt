package app.fcc.claude

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.system.Os
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.JsResult
import android.webkit.CookieManager
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

class MainActivity : Activity(), TerminalViewClient, TerminalSessionClient, Rootfs.Listener {

    private enum class Mode { CLAUDE, SHELL, DOCTOR, LOG }

    companion object {
        private const val REQ_TREE = 41
    }

    // ---------- Paleta ----------
    private val cBg = Color.parseColor("#0A0B10")
    private val cSurface = Color.parseColor("#12141C")
    private val cSurface2 = Color.parseColor("#1C1F2D")
    private val cAccent = Color.parseColor("#8B6CFF")
    private val cAccent2 = Color.parseColor("#5B8CFF")
    private val cOk = Color.parseColor("#5EE3A1")
    private val cWarn = Color.parseColor("#FFD479")
    private val cErr = Color.parseColor("#FF6B7A")
    private val cText = Color.parseColor("#ECEEF5")
    private val cDim = Color.parseColor("#7C8299")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private var adminOverlay: LinearLayout? = null
    private var adminWeb: WebView? = null
    private var lastExternalUrl = ""
    private var lastExternalAt = 0L
    // conversas, sessões e sincronização
    private lateinit var store: ConversationStore
    private val sessions = HashMap<String, TerminalSession>()       // conversa -> sessão do terminal
    private val sessionConv = HashMap<TerminalSession, String>()    // sessão -> conversa
    private val syncPool = Executors.newSingleThreadExecutor()
    private val lastSyncAt = HashMap<String, Long>()
    private var pickingFor: String? = null
    private lateinit var convChip: TextView
    private val authPopupWebViews = mutableSetOf<WebView>()
    private lateinit var terminalView: TerminalView
    private lateinit var termWrap: LinearLayout
    private lateinit var termCard: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var setupStatus: TextView
    private lateinit var setupPct: TextView
    private lateinit var setupSize: TextView
    private lateinit var barTrack: FrameLayout
    private lateinit var barFill: View
    private lateinit var errBox: TextView
    private lateinit var errButtons: LinearLayout
    private lateinit var chipText: TextView
    private lateinit var chipDot: View
    private lateinit var ctrlKey: TextView
    private var session: TerminalSession? = null
    private var ctrlOn = false
    private var kbVisible = false
    private var fontPx = 0
    private var lastError = ""

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- helpers de estilo ----------
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }
    private fun ripple(bg: Drawable) = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null)
    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun logo(size: Int, sp: Float) = TextView(this).apply {
        text = "F"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cAccent, cAccent2))
            .apply { shape = GradientDrawable.OVAL }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun pill(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = ripple(rounded(color, 20))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontPx = dp(13)
        applyTerminalColors()
        store = ConversationStore(this)
        frame = FrameLayout(this)
        frame.addView(buildUi(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect(); root.getWindowVisibleDisplayFrame(r)
            kbVisible = root.rootView.height - r.bottom > dp(120)
        }
        requestNotificationPermission()
        startKeepAlive()
        FccServer.listener = { st, msg -> ui.post { if (!isFinishing) onServerState(st, msg) } }
        refreshConvChip()
        if (Rootfs.isReady(this)) {
            FccServer.ensure(this)
            openConversation(store.active().id)
        } else beginExtraction()
    }

    override fun onResume() {
        super.onResume()
        adminWeb?.onResume()
        if (::store.isInitialized) {
            val c = store.active()
            if (c.treeUri != null && System.currentTimeMillis() - (lastSyncAt[c.id] ?: 0L) > 10_000) syncQuiet(c)
        }
    }

    override fun onPause() {
        adminWeb?.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (::store.isInitialized) syncQuiet(store.active())
        super.onStop()
    }

    override fun onDestroy() {
        authPopupWebViews.toList().forEach { popup ->
            (popup.parent as? android.view.ViewGroup)?.removeView(popup)
            try { popup.stopLoading(); popup.destroy() } catch (_: Exception) {}
        }
        authPopupWebViews.clear()
        adminWeb?.let { try { it.stopLoading(); it.destroy() } catch (_: Exception) {} }
        adminWeb = null
        Rootfs.detach(this)
        sessions.values.toSet().forEach { try { it.finishIfRunning() } catch (_: Exception) {} }
        sessions.clear()
        sessionConv.clear()
        syncPool.shutdown()
        if (isFinishing) {
            FccServer.listener = null
            FccServer.stop()
            stopService(Intent(this, KeepAliveService::class.java))
        }
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    private fun startKeepAlive() {
        try { startForegroundService(Intent(this, KeepAliveService::class.java)) } catch (_: Exception) {}
    }

    // ================================================================ UI
    private fun buildUi(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            fitsSystemWindows = true
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, dp(60)))

        val center = FrameLayout(this)
        center.addView(buildSetup(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        termWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        termWrap.addView(buildChips(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        termCard = FrameLayout(this).apply {
            background = rounded(cBg, 16)
            clipToOutline = true
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        terminalView = TerminalView(this, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(this@MainActivity)
            setTextSize(fontPx)
            setTypeface(try { Typeface.createFromAsset(assets, "fonts/mono.ttf") } catch (_: Exception) { Typeface.MONOSPACE })
            keepScreenOn = true
        }
        termCard.addView(terminalView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cSurface2, 14)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        bannerText = label("Sessão encerrada", 14f, cText)
        banner.addView(bannerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        banner.addView(pill("Shell", cSurface) { startTerminal(Mode.SHELL) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        banner.addView(pill("Reiniciar", cAccent) { startTerminal(Mode.CLAUDE) })
        termCard.addView(banner, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(8), 0, dp(8), dp(8)) })

        termWrap.addView(termCard, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            .apply { setMargins(dp(8), dp(4), dp(8), 0) })
        center.addView(termWrap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        root.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(buildKeys(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun buildHeader(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }
        h.addView(logo(36, 18f))
        h.addView(label("FCC Claude", 17f, cText, true).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cSurface, 14)
            setPadding(dp(10), dp(6), dp(12), dp(6))
        }
        chipDot = View(this).apply { background = rounded(cWarn, 5) }
        chip.addView(chipDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) })
        chipText = label("iniciando", 12f, cDim)
        chip.addView(chipText)
        h.addView(chip)

        val menu = TextView(this).apply {
            text = "⋮"; gravity = Gravity.CENTER; setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = ripple(rounded(Color.TRANSPARENT, 22))
            setOnClickListener { showMenu(it) }
        }
        h.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        return h
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 9, 0, "Configurar provedores (Admin)")
            menu.add(0, 11, 1, "Conversas…")
            menu.add(0, 12, 2, "Pasta desta conversa (escolher/alterar)")
            menu.add(0, 13, 3, "Sincronizar agora")
            menu.add(0, 14, 4, "Relatório da sincronização")
            menu.add(0, 15, 5, "Reiniciar servidor FCC")
            menu.add(0, 1, 6, "Colar")
            menu.add(0, 2, 7, "Fonte maior")
            menu.add(0, 3, 8, "Fonte menor")
            menu.add(0, 4, 9, "Limpar tela")
            menu.add(0, 5, 10, "Reiniciar Claude")
            menu.add(0, 6, 11, "Abrir shell")
            menu.add(0, 7, 12, "Diagnóstico (fcc-doctor)")
            menu.add(0, 8, 13, "Servidor FCC: status e log")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    9 -> openAdmin()
                    11 -> showConversations()
                    12 -> pickFolder(store.active())
                    13 -> syncManual(store.active())
                    14 -> showReport(store.active())
                    15 -> { FccServer.restart(this@MainActivity); Toast.makeText(this@MainActivity, "Reiniciando o servidor FCC…", Toast.LENGTH_SHORT).show() }
                    1 -> pasteFromClipboard()
                    2 -> changeFont(+2)
                    3 -> changeFont(-2)
                    4 -> send("\u000c")
                    5 -> startTerminal(Mode.CLAUDE)
                    6 -> startTerminal(Mode.SHELL)
                    7 -> startTerminal(Mode.DOCTOR)
                    8 -> startTerminal(Mode.LOG)
                }
                true
            }
            show()
        }
    }

    private fun buildChips(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        fun chip(t: String, onClick: () -> Unit) {
            row.addView(TextView(this).apply {
                text = t; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = ripple(rounded(cSurface, 16))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        convChip = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = ripple(rounded(cAccent, 16))
            setOnClickListener { showConversations() }
        }
        row.addView(convChip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        chip("⚙ Admin") { openAdmin() }
        chip("/login") { send("/login\r") }
        chip("/model") { send("/model\r") }
        chip("/help") { send("/help\r") }
        chip("/clear") { send("/clear\r") }
        chip("Ctrl+C") { send("\u0003") }
        chip("Enter ↵") { send("\r") }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun buildSetup(): View {
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        setupBox.addView(logo(76, 36f))
        setupBox.addView(label("FCC Claude", 26f, cText, true).apply {
            setPadding(0, dp(20), 0, dp(6)); gravity = Gravity.CENTER })
        setupStatus = label("Preparando o ambiente Linux e o Claude Code.\nIsso acontece só na primeira vez.", 14f, cDim)
            .apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(28)) }
        setupBox.addView(setupStatus)
        setupPct = label("0%", 34f, cText, true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        setupBox.addView(setupPct)
        barTrack = FrameLayout(this).apply { background = rounded(cSurface2, 4) }
        barFill = View(this).apply { background = rounded(cAccent, 4) }
        barTrack.addView(barFill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        setupBox.addView(barTrack, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)))
        setupSize = label("", 12f, cDim).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        setupBox.addView(setupSize)

        errBox = label("", 12f, cErr).apply {
            visibility = View.GONE
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            background = rounded(cSurface, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        setupBox.addView(errBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        errButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE
        }
        errButtons.addView(pill("Copiar erro", cSurface2) { copyError() },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(10) })
        errButtons.addView(pill("Tentar novamente", cAccent) { retryExtraction() })
        setupBox.addView(errButtons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        return setupBox
    }

    private fun buildKeys(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setBackgroundColor(cBg)
        }
        fun key(t: String, sp: Float = 13f, onClick: () -> Unit): TextView = TextView(this).apply {
            text = t; gravity = Gravity.CENTER; setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            background = ripple(rounded(cSurface2, 12))
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        key("ESC", 12f) { send("\u001b") }
        key("TAB", 12f) { send("\t") }
        ctrlKey = key("CTRL", 12f) { setCtrl(!ctrlOn) }
        key("←", 16f) { send("\u001b[D") }
        key("↑", 16f) { send("\u001b[A") }
        key("↓", 16f) { send("\u001b[B") }
        key("→", 16f) { send("\u001b[C") }
        key("⌨", 17f) { toggleKeyboard() }
        return row
    }

    private fun applyTerminalColors() {
        val p = Properties()
        p["background"] = "#0A0B10"; p["foreground"] = "#ECEEF5"; p["cursor"] = "#8B6CFF"
        val pal = listOf("#1C1F2D", "#FF6B7A", "#5EE3A1", "#FFD479", "#6C9CFF", "#B58CFF", "#5EE3E3", "#D5D9E8",
            "#3A3F55", "#FF8A96", "#86F0BC", "#FFE29B", "#8DB4FF", "#CDA9FF", "#8DF0F0", "#FFFFFF")
        pal.forEachIndexed { i, c -> p["color$i"] = c }
        TerminalColors.COLOR_SCHEME.updateWith(p)
    }

    // ================================================================ estado / utilidades
    private fun setStatus(text: String, color: Int) {
        chipText.text = text
        chipDot.background = rounded(color, 5)
    }

    private fun setCtrl(on: Boolean) {
        ctrlOn = on
        ctrlKey.background = ripple(rounded(if (on) cAccent else cSurface2, 12))
    }

    private fun send(s: String) { session?.write(s) }

    private fun changeFont(delta: Int) {
        fontPx = (fontPx + dp(delta)).coerceIn(dp(8), dp(26))
        terminalView.setTextSize(fontPx)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.emulator?.paste(t)
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun showKeyboard() {
        terminalView.requestFocus()
        terminalView.post { imm().showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun toggleKeyboard() {
        if (kbVisible) imm().hideSoftInputFromWindow(terminalView.windowToken, 0)
        else { terminalView.requestFocus(); imm().showSoftInput(terminalView, 0) }
    }

    private fun copyError() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("erro", lastError))
        Toast.makeText(this, "Erro copiado", Toast.LENGTH_SHORT).show()
    }

    // ================================================================ 1º uso: extração
    private fun beginExtraction() {
        setupBox.visibility = View.VISIBLE
        termWrap.visibility = View.GONE
        setStatus("preparando", cWarn)
        Rootfs.attach(this, this)
    }

    private fun retryExtraction() {
        errBox.visibility = View.GONE
        errButtons.visibility = View.GONE
        setupStatus.text = "Preparando o ambiente Linux e o Claude Code.\nIsso acontece só na primeira vez."
        setStatus("preparando", cWarn)
        Rootfs.retry(this)
    }

    override fun onProgress(fraction: Float, doneBytes: Long, totalBytes: Long) {
        ui.post {
            setupPct.text = "${(fraction * 100).toInt()}%"
            setupSize.text = "${doneBytes / 1048576} MB de ${totalBytes / 1048576} MB"
            barFill.layoutParams = (barFill.layoutParams as FrameLayout.LayoutParams)
                .apply { width = (barTrack.width * fraction).toInt() }
        }
    }

    override fun onFinished(error: Throwable?) {
        ui.post {
            if (error == null) {
                if (session == null) { FccServer.ensure(this); openConversation(store.active().id) }
            } else {
                var msg = "${error.javaClass.simpleName}: ${error.message}"
                error.cause?.let { msg += "\ncausa: ${it.javaClass.simpleName}: ${it.message}" }
                lastError = msg
                setStatus("erro", cErr)
                setupStatus.text = "Não foi possível preparar o ambiente."
                errBox.text = msg
                errBox.visibility = View.VISIBLE
                errButtons.visibility = View.VISIBLE
            }
        }
    }

    // ================================================================ Admin (painel de provedores do FCC)
    private fun fccPort(): Int = try {
        File(filesDir, "tmp/fcc-port").readText().trim().toInt()
    } catch (_: Exception) { 8082 }

    private fun openExternalWebUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir o navegador.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun disposeAuthPopup(popup: WebView) {
        if (!authPopupWebViews.remove(popup)) return
        (popup.parent as? android.view.ViewGroup)?.removeView(popup)
        popup.stopLoading()
        popup.destroy()
    }

    private fun routeAuthPopup(popup: WebView, url: String): Boolean {
        if (url == "about:blank") return false
        openExternal(Uri.parse(url))
        popup.post { disposeAuthPopup(popup) }
        return true
    }

    private fun openAdmin() {
        if (adminOverlay == null) {
            val ov = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(cBg)
                fitsSystemWindows = true
                isClickable = true
            }
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(cSurface)
                setPadding(dp(16), dp(8), dp(10), dp(8))
            }
            bar.addView(label("Configurar provedores", 16f, cText, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            bar.addView(pill("Fechar", cAccent) { closeAdmin() })
            ov.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val web = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                setBackgroundColor(cBg)
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message
                    ): Boolean {
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        val popup = WebView(this@MainActivity).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(cBg)
                            alpha = 0f
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean = routeAuthPopup(view, request.url.toString())

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                    routeAuthPopup(view, url)
                            }
                        }
                        frame.addView(popup, FrameLayout.LayoutParams(1, 1))
                        authPopupWebViews.add(popup)
                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
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
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
                        if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                        if (uri.scheme == "http" || uri.scheme == "https") {
                            openExternalWebUrl(uri.toString())
                            return true
                        }
                        return false
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            view.loadDataWithBaseURL(null,
                                "<html><body style='background:#0A0B10;color:#ECEEF5;font-family:sans-serif;padding:24px'>" +
                                "<h3>Servidor FCC indisponível</h3><p>Abra o Claude primeiro (menu ⋮ → Reiniciar Claude) " +
                                "e espere aparecer <b>Servidor FCC pronto</b>. Depois abra este painel de novo.</p></body></html>",
                                "text/html", "utf-8", null)
                        }
                    }
                }
            }
            ov.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            frame.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            adminOverlay = ov
            adminWeb = web
        }
        adminWeb?.loadUrl("http://127.0.0.1:${fccPort()}/admin")
        adminOverlay?.visibility = View.VISIBLE
    }

    private fun isLocalHost(u: Uri): Boolean = u.host == "127.0.0.1" || u.host == "localhost"

    /** Abre a página oficial de login em Chrome Custom Tabs (ou no navegador padrão, se não houver). */
    private fun openExternal(uri: Uri) {
        val now = System.currentTimeMillis()
        if (uri.toString() == lastExternalUrl && now - lastExternalAt < 3000) return // evita abrir 2x
        lastExternalUrl = uri.toString(); lastExternalAt = now
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(this, "Nenhum navegador encontrado para abrir o login", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ================================================================ Conversas
    private fun refreshConvChip() {
        val c = store.active()
        convChip.text = "💬 ${c.name}" + if (c.treeUri != null) "  📁" else ""
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSingleLine(true); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showConversations() {
        val items = store.items.toList()
        val labels = items.map { c ->
            (if (c.id == store.activeId) "●  " else "     ") + c.name +
                (if (c.treeUri != null) "   📁" else "") +
                (if (sessions[c.id]?.isRunning == true) "   ▶" else "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Conversas")
            .setItems(labels) { _, i -> switchTo(items[i].id) }
            .setPositiveButton("Nova conversa") { _, _ ->
                promptText("Nome da conversa", "Conversa ${store.items.size + 1}") { n ->
                    val c = store.add(n); switchTo(c.id)
                }
            }
            .setNeutralButton("Gerenciar atual") { _, _ -> manageActive() }
            .setNegativeButton("Fechar", null).show()
    }

    private fun manageActive() {
        val c = store.active()
        val opts = arrayOf("Renomear", "Escolher / alterar pasta do Android", "Remover pasta (voltar para /root)",
            "Sincronizar agora", "Relatório da sincronização", "Excluir conversa")
        AlertDialog.Builder(this).setTitle(c.name).setItems(opts) { _, i ->
            when (i) {
                0 -> promptText("Renomear conversa", c.name) { n -> if (n.isNotEmpty()) { c.name = n; store.save(); refreshConvChip() } }
                1 -> pickFolder(c)
                2 -> removeFolder(c)
                3 -> syncManual(c)
                4 -> showReport(c)
                5 -> deleteConversation(c)
            }
        }.setNegativeButton("Fechar", null).show()
    }

    private fun switchTo(id: String) {
        val prev = store.active()
        if (prev.id != id) syncQuiet(prev)       // ao sair de uma conversa, envia o que mudou
        openConversation(id)
    }

    private fun deleteConversation(c: Conversation) {
        AlertDialog.Builder(this).setTitle("Excluir “${c.name}”?")
            .setMessage("A sessão e a cópia local do app serão removidas. Os arquivos que já estão na pasta do Android NÃO são apagados.")
            .setPositiveButton("Excluir") { _, _ ->
                sessions.remove(c.id)?.let { sessionConv.remove(it); it.finishIfRunning() }
                if (session != null && sessionConv[session] == null) session = null
                c.treeUri?.let { t -> if (store.items.none { it.id != c.id && it.treeUri == t }) Workspace.releasePermission(this, t) }
                Workspace.deleteAll(this, c.id)
                store.remove(c.id)
                refreshConvChip()
                openConversation(store.active().id)
            }.setNegativeButton("Cancelar", null).show()
    }

    // ================================================================ Pasta do Android (SAF)
    private fun pickFolder(c: Conversation) {
        pickingFor = c.id
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        try { startActivityForResult(i, REQ_TREE) }
        catch (_: Exception) { Toast.makeText(this, "Este aparelho não tem seletor de pastas.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val id = pickingFor; pickingFor = null
        val uri = data?.data
        val c = if (id != null) store.find(id) else null
        if (resultCode != RESULT_OK || uri == null || c == null) return
        val other = store.items.firstOrNull { it.id != c.id && it.treeUri == uri.toString() }
        if (other != null) {
            Toast.makeText(this, "Esta pasta já é usada pela conversa “${other.name}”.", Toast.LENGTH_LONG).show(); return
        }
        try { Workspace.takePermission(this, uri) } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível guardar a permissão da pasta: ${e.message}", Toast.LENGTH_LONG).show(); return
        }
        val old = c.treeUri
        val newTree = uri.toString()
        showBusy("Preparando a nova pasta…")
        syncPool.execute {
            if (old != null && old != newTree) {                       // despede-se da pasta antiga
                Workspace.sync(this, c)
                if (store.items.none { it.id != c.id && it.treeUri == old }) Workspace.releasePermission(this, old)
            }
            if (old != newTree) Workspace.resetForNewFolder(this, c.id)
            c.treeUri = newTree; store.save()
            ui.post {
                sessions.remove(c.id)?.let { sessionConv.remove(it); it.finishIfRunning() }
                refreshConvChip()
                openConversation(c.id)       // sincroniza (baixa o conteúdo da pasta) e abre a sessão em /workspace
            }
        }
    }

    private fun removeFolder(c: Conversation) {
        val t = c.treeUri ?: run { Toast.makeText(this, "Esta conversa já usa /root.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Remover a pasta?")
            .setMessage("Os arquivos continuam na pasta do Android. A cópia local será arquivada e a conversa volta a usar /root.")
            .setPositiveButton("Remover") { _, _ ->
                showBusy("Sincronizando antes de remover…")
                syncPool.execute {
                    Workspace.sync(this, c)
                    if (store.items.none { it.id != c.id && it.treeUri == t }) Workspace.releasePermission(this, t)
                    Workspace.resetForNewFolder(this, c.id)
                    c.treeUri = null; store.save()
                    ui.post {
                        sessions.remove(c.id)?.let { sessionConv.remove(it); it.finishIfRunning() }
                        refreshConvChip(); openConversation(c.id)
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ================================================================ Sincronização
    /** Em segundo plano, sem diálogo: só avisa (toast) quando há conflitos ou erros. */
    private fun syncQuiet(c: Conversation) {
        if (c.treeUri == null) return
        syncPool.execute {
            val rep = Workspace.sync(this, c)
            lastSyncAt[c.id] = System.currentTimeMillis()
            if (!rep.isClean()) ui.post {
                Toast.makeText(this, "Sincronização de “${c.name}”: ${rep.summary()} — veja o relatório no menu ⋮", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncManual(c: Conversation) {
        if (c.treeUri == null) {
            Toast.makeText(this, "Esta conversa usa /root. Escolha uma pasta do Android primeiro.", Toast.LENGTH_LONG).show(); return
        }
        Toast.makeText(this, "Sincronizando…", Toast.LENGTH_SHORT).show()
        syncPool.execute {
            val rep = Workspace.sync(this, c)
            lastSyncAt[c.id] = System.currentTimeMillis()
            ui.post { showSyncResult(c, rep) }
        }
    }

    private fun showSyncResult(c: Conversation, rep: SyncReport) {
        val tv = TextView(this).apply { text = rep.details(); setPadding(dp(20), dp(8), dp(20), dp(8)); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        AlertDialog.Builder(this).setTitle("“${c.name}”: ${rep.summary()}")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).show()
    }

    private fun showReport(c: Conversation) {
        val f = Workspace.reportFile(this, c.id)
        val text = if (f.exists()) f.readText() else "Ainda não houve sincronização desta conversa."
        val tv = TextView(this).apply { this.text = text; setPadding(dp(20), dp(8), dp(20), dp(8)); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        AlertDialog.Builder(this).setTitle("Relatório — ${c.name}")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null).show()
    }

    private fun showBusy(msg: String) {
        setupBox.visibility = View.VISIBLE
        termWrap.visibility = View.GONE
        errBox.visibility = View.GONE; errButtons.visibility = View.GONE
        setupStatus.text = msg
        setupPct.text = ""; setupSize.text = ""
        barFill.layoutParams = (barFill.layoutParams as FrameLayout.LayoutParams).apply { width = 0 }
    }

    private fun onServerState(st: FccServer.State, msg: String) {
        if (st == FccServer.State.FAILED) {
            Toast.makeText(this, "Servidor FCC com problema: ${msg.take(300)}\nMenu ⋮ → Servidor FCC: status e log", Toast.LENGTH_LONG).show()
        }
    }

    private fun closeAdmin() {
        adminOverlay?.visibility = View.GONE
        imm().hideSoftInputFromWindow(frame.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val ov = adminOverlay
        if (ov != null && ov.visibility == View.VISIBLE) {
            if (adminWeb?.canGoBack() == true) adminWeb?.goBack() else closeAdmin()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ================================================================ terminal
    /** Reinicia (ou abre) o terminal da conversa ativa. */
    private fun startTerminal(mode: Mode) = openConversation(store.active().id, mode, true)

    /** Abre a conversa: sincroniza a pasta (se houver) e então mostra/cria a sessão do terminal. */
    private fun openConversation(id: String, mode: Mode = Mode.CLAUDE, forceNew: Boolean = false) {
        val c = store.find(id) ?: return
        store.setActive(id)
        refreshConvChip()
        val existing = sessions[id]
        if (!forceNew && mode == Mode.CLAUDE && existing != null && existing.isRunning) {
            setupBox.visibility = View.GONE; termWrap.visibility = View.VISIBLE
            attachSession(existing, mode)
            return
        }
        if (c.treeUri != null) {
            showBusy("Sincronizando “${c.name}”…")
            setStatus("sincronizando", cWarn)
            syncPool.execute {
                val rep = Workspace.sync(this, c) { msg -> ui.post { setupStatus.text = "Sincronizando “${c.name}”…\n$msg" } }
                lastSyncAt[c.id] = System.currentTimeMillis()
                ui.post {
                    if (isFinishing || store.activeId != c.id) return@post
                    if (!rep.isClean()) showSyncResult(c, rep)
                    startSessionFor(c, mode)
                }
            }
        } else startSessionFor(c, mode)
    }

    private fun attachSession(s: TerminalSession, mode: Mode) {
        session = s
        banner.visibility = if (s.isRunning) View.GONE else View.VISIBLE
        terminalView.attachSession(s)
        when (mode) {
            Mode.CLAUDE -> setStatus(if (s.isRunning) "Claude Code · ativo" else "encerrada", if (s.isRunning) cOk else cDim)
            Mode.SHELL -> setStatus("shell", cAccent2)
            Mode.DOCTOR -> setStatus("diagnóstico", cAccent2)
            Mode.LOG -> setStatus("servidor FCC", cAccent2)
        }
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        ui.postDelayed({ showKeyboard() }, 400)
    }

    private fun startSessionFor(c: Conversation, mode: Mode) {
        setupBox.visibility = View.GONE
        termWrap.visibility = View.VISIBLE
        banner.visibility = View.GONE
        val old = sessions[c.id]
        val s = try { createSession(c, mode) } catch (e: Exception) {
            setupBox.visibility = View.VISIBLE; termWrap.visibility = View.GONE
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            errBox.text = lastError; errBox.visibility = View.VISIBLE; errButtons.visibility = View.VISIBLE
            setupStatus.text = "Não foi possível abrir o terminal."
            setStatus("erro", cErr)
            return
        }
        sessions[c.id] = s
        sessionConv[s] = c.id
        if (old != null) { sessionConv.remove(old); old.finishIfRunning() }
        attachSession(s, mode)
    }

    private fun createSession(c: Conversation, mode: Mode): TerminalSession {
        Proot.prepareRootfs(this)
        // Pasta de trabalho: com pasta escolhida → cópia privada montada em /workspace; sem pasta → /root.
        val binds = ArrayList<String>()
        var cwd = "/root"
        if (c.treeUri != null) {
            val ws = Workspace.dir(this, c.id).apply { mkdirs() }
            binds.add("${ws.path}:/workspace")
            cwd = "/workspace"
        }
        val command = when (mode) {
            Mode.CLAUDE -> "exec /usr/local/bin/fcc-app-launch"
            Mode.SHELL -> "exec bash -l"
            Mode.DOCTOR -> "fcc-doctor; echo; echo '[Enter para abrir um shell]'; read; exec bash -l"
            Mode.LOG -> "fcc-ctl status; echo; fcc-ctl logs 80; echo; exec bash -l"
        }
        val args = Proot.args(this, binds, cwd, emptyList(), listOf("/bin/bash", "-lc", command))
        return TerminalSession(Proot.binary(this), filesDir.path, args.toTypedArray(), Proot.env(this), 4000, this)
    }

    // ================================================================ TerminalSessionClient
    override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        ui.post {
            // ao encerrar a sessão de trabalho, envia o que mudou na pasta da conversa
            sessionConv[finishedSession]?.let { cid -> store.find(cid)?.let { syncQuiet(it) } }
            if (finishedSession !== session) return@post   // sessão antiga sendo substituída
            setStatus("encerrada", cDim)
            bannerText.text = "Sessão encerrada (código ${finishedSession.exitStatus})"
            banner.visibility = View.VISIBLE
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { pasteFromClipboard() }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ================================================================ TerminalViewClient
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) { changeFont(if (scale > 1f) 1 else -1); return 1f }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = ctrlOn
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (ctrlOn) {
            val c = codePoint.toChar().uppercaseChar().code
            ui.post { setCtrl(false) }
            if (c in 0x40..0x5F) { session.write(String(Character.toChars(c and 0x1F))); return true }
        }
        return false
    }
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "error", e) }
}
