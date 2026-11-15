package app.fcc.claude

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.Properties

class MainActivity : Activity(), TerminalViewClient, TerminalSessionClient, Rootfs.Listener {

    private enum class Mode { CLAUDE, SHELL, DOCTOR, LOG }

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
        frame = FrameLayout(this)
        frame.addView(buildUi(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect(); root.getWindowVisibleDisplayFrame(r)
            kbVisible = root.rootView.height - r.bottom > dp(120)
        }
        requestNotificationPermission()
        startKeepAlive()
        if (Rootfs.isReady(this)) startTerminal(Mode.CLAUDE) else beginExtraction()
    }

    override fun onDestroy() {
        Rootfs.detach(this)
        adminWeb?.destroy()
        session?.finishIfRunning()
        if (isFinishing) stopService(Intent(this, KeepAliveService::class.java))
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
            menu.add(0, 1, 0, "Colar")
            menu.add(0, 2, 1, "Fonte maior")
            menu.add(0, 3, 2, "Fonte menor")
            menu.add(0, 4, 3, "Limpar tela")
            menu.add(0, 5, 4, "Reiniciar Claude")
            menu.add(0, 6, 5, "Abrir shell")
            menu.add(0, 7, 6, "Diagnóstico (fcc-doctor)")
            menu.add(0, 8, 7, "Ver log do servidor")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    9 -> openAdmin()
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
                if (session == null) startTerminal(Mode.CLAUDE)
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
                setBackgroundColor(cBg)
                webViewClient = object : WebViewClient() {
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
    private fun startTerminal(mode: Mode) {
        setupBox.visibility = View.GONE
        termWrap.visibility = View.VISIBLE
        banner.visibility = View.GONE
        val old = session
        val s = try { createSession(mode) } catch (e: Exception) {
            setupBox.visibility = View.VISIBLE; termWrap.visibility = View.GONE
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            errBox.text = lastError; errBox.visibility = View.VISIBLE; errButtons.visibility = View.VISIBLE
            setupStatus.text = "Não foi possível abrir o terminal."
            setStatus("erro", cErr)
            return
        }
        session = s
        old?.finishIfRunning()
        terminalView.attachSession(s)
        when (mode) {
            Mode.CLAUDE -> setStatus("Claude Code · ativo", cOk)
            Mode.SHELL -> setStatus("shell", cAccent2)
            Mode.DOCTOR -> setStatus("diagnóstico", cAccent2)
            Mode.LOG -> setStatus("log do servidor", cAccent2)
        }
        terminalView.requestFocus()
        ui.postDelayed({ showKeyboard() }, 500)
    }

    private fun createSession(mode: Mode): TerminalSession {
        val nl = applicationInfo.nativeLibraryDir
        val rootDir = Rootfs.dir(this)
        val tmp = File(filesDir, "tmp").apply { mkdirs() }

        File(rootDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs(); delete()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        // Lançador (sobe o FCC Server com diagnóstico e abre o Claude Code)
        val launcher = File(rootDir, "usr/local/bin/fcc-app-launch")
        launcher.parentFile?.mkdirs()
        assets.open("fcc-app-launch.sh").use { i -> launcher.outputStream().use { o -> i.copyTo(o) } }
        try { Os.chmod(launcher.path, 493) } catch (_: Exception) {}

        val command = when (mode) {
            Mode.CLAUDE -> "exec /usr/local/bin/fcc-app-launch"
            Mode.SHELL -> "exec bash -l"
            Mode.DOCTOR -> "fcc-doctor; echo; echo '[Enter para abrir um shell]'; read; exec bash -l"
            Mode.LOG -> "echo '--- log do servidor ---'; tail -n 150 /tmp/fcc-server.log 2>&1; echo; exec bash -l"
        }
        val path = "/root/.local/bin:/root/.claude/local:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val proot = "$nl/libproot.so"
        val args = arrayOf(
            proot, "--kill-on-exit", "--link2symlink", "-0", "-r", rootDir.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.path}:/tmp", "-b", "${tmp.path}:/dev/shm", "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "TERM=xterm-256color", "COLORTERM=truecolor", "PATH=$path",
            "/bin/bash", "-lc", command
        )
        val env = arrayOf(
            "PROOT_TMP_DIR=${tmp.path}", "PROOT_LOADER=$nl/libproot-loader.so",
            "LD_LIBRARY_PATH=$nl", "PROOT_NO_SECCOMP=1", "TMPDIR=${tmp.path}"
        )
        return TerminalSession(proot, filesDir.path, args, env, 4000, this)
    }

    // ================================================================ TerminalSessionClient
    override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        ui.post {
            if (finishedSession !== session) return@post // sessão antiga sendo substituída
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
