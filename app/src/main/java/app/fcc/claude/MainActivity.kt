package app.fcc.claude

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.util.Properties
import kotlin.concurrent.thread

class MainActivity : Activity(), TerminalViewClient, TerminalSessionClient {

    // ---- Paleta própria ----
    private val bg = Color.parseColor("#0B0D12")
    private val panel = Color.parseColor("#151823")
    private val accent = Color.parseColor("#7C5CFF")
    private val textMain = Color.parseColor("#E6E8EF")
    private val textDim = Color.parseColor("#8A90A6")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var terminalView: TerminalView
    private lateinit var setupBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryBtn: Button
    private lateinit var ctrlBtn: Button
    private var session: TerminalSession? = null
    private var ctrlOn = false
    private var fontSizePx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontSizePx = dp(14)
        applyTerminalColors()
        setContentView(buildUi())
        if (Rootfs.isReady(this)) startTerminal() else startExtraction()
    }

    // ------------------------------------------------------------------ UI
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        // Barra superior
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(panel)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        header.addView(TextView(this).apply {
            text = "FCC Claude"
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Área central: tela de instalação + terminal
        val center = FrameLayout(this)
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        setupBox.addView(TextView(this).apply {
            text = "Preparando o FCC Claude"
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
        })
        statusText = TextView(this).apply {
            text = "Isso acontece só na primeira vez."
            setTextColor(textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }
        setupBox.addView(statusText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(accent)
        }
        setupBox.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)))
        retryBtn = Button(this).apply {
            text = "Tentar novamente"
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE; startExtraction() }
        }
        setupBox.addView(retryBtn)
        center.addView(setupBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(this@MainActivity)
            setTextSize(fontSizePx)
            setTypeface(Typeface.MONOSPACE)
            keepScreenOn = true
            visibility = View.GONE
        }
        center.addView(terminalView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Teclas extras
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun key(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minWidth = dp(48); minimumWidth = dp(48)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(panel) }
            setOnClickListener { action() }
            row.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { rightMargin = dp(6) })
        }
        key("ESC") { send("\u001b") }
        key("TAB") { send("\t") }
        ctrlBtn = key("CTRL") { setCtrl(!ctrlOn) }
        key("←") { send("\u001b[D") }
        key("↑") { send("\u001b[A") }
        key("↓") { send("\u001b[B") }
        key("→") { send("\u001b[C") }
        key("/") { send("/") }
        key("-") { send("-") }
        key("|") { send("|") }
        key("Colar") { pasteFromClipboard() }
        key("Reiniciar") { restartSession() }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bg)
            addView(row)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun applyTerminalColors() {
        val p = Properties()
        p["background"] = "#0B0D12"
        p["foreground"] = "#E6E8EF"
        p["cursor"] = "#7C5CFF"
        TerminalColors.COLOR_SCHEME.updateWith(p)
    }

    private fun setCtrl(on: Boolean) {
        ctrlOn = on
        (ctrlBtn.background as GradientDrawable).setColor(if (on) accent else panel)
    }

    private fun send(s: String) { session?.write(s) }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.emulator?.paste(t)
    }

    // ------------------------------------------------------------------ Instalação (1º uso)
    private fun startExtraction() {
        setupBox.visibility = View.VISIBLE
        terminalView.visibility = View.GONE
        statusText.text = "Extraindo o ambiente Linux e o Claude Code…"
        progress.progress = 0
        thread(name = "extract") {
            try {
                Rootfs.extract(this) { f ->
                    ui.post {
                        progress.progress = (f * 1000).toInt()
                        statusText.text = "Extraindo o ambiente… ${(f * 100).toInt()}%"
                    }
                }
                ui.post { startTerminal() }
            } catch (e: Throwable) {
                ui.post {
                    statusText.text = "Falha ao preparar: ${e.javaClass.simpleName}: ${e.message}"
                    retryBtn.visibility = View.VISIBLE
                }
            }
        }
    }

    // ------------------------------------------------------------------ Terminal
    private fun startTerminal() {
        setupBox.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
        val s = createSession()
        session = s
        terminalView.attachSession(s)
        terminalView.requestFocus()
    }

    private fun restartSession() {
        session?.finishIfRunning()
        startTerminal()
    }

    private fun createSession(): TerminalSession {
        val nl = applicationInfo.nativeLibraryDir
        val root = Rootfs.dir(this)
        val tmp = File(filesDir, "tmp").apply { mkdirs() }
        File(root, "etc/resolv.conf").apply {
            delete()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        val path = "/root/.local/bin:/root/.claude/local:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val proot = "$nl/libproot.so"
        val args = arrayOf(
            proot,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${tmp.path}:/tmp", "-b", "${tmp.path}:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "TERM=xterm-256color", "COLORTERM=truecolor", "PATH=$path",
            "/bin/bash", "-lc", "fcc-claude"
        )
        val env = arrayOf(
            "PROOT_TMP_DIR=${tmp.path}",
            "PROOT_LOADER=$nl/libproot-loader.so",
            "LD_LIBRARY_PATH=$nl",
            "PROOT_NO_SECCOMP=1",
            "TMPDIR=${tmp.path}"
        )
        return TerminalSession(proot, filesDir.path, args, env, 4000, this)
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ TerminalSessionClient
    override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        ui.post {
            statusText.text = "A sessão terminou (código ${finishedSession.exitStatus}). Toque em Reiniciar."
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) { pasteFromClipboard() }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ------------------------------------------------------------------ TerminalViewClient
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            fontSizePx = (fontSizePx + if (scale > 1f) 2 else -2).coerceIn(dp(8), dp(28))
            terminalView.setTextSize(fontSizePx)
            return 1f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(terminalView, 0)
    }
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
            if (c in 0x40..0x5F) {
                session.write(String(Character.toChars(c and 0x1F)))
                ui.post { setCtrl(false) }
                return true
            }
            ui.post { setCtrl(false) }
        }
        return false
    }
    override fun onEmulatorSet() {}

    // Logs (comuns às duas interfaces)
    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        android.util.Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "error", e) }
}
