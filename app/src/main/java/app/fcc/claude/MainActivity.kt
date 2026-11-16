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
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
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

    // ---------- Paleta (tema claro) ----------
    private val cBg = Color.parseColor("#FFFFFF")
    private val cSurface = Color.parseColor("#F4F5FA")
    private val cSurface2 = Color.parseColor("#E9EBF5")
    private val cSoft = Color.parseColor("#EEEAFF")
    private val cBorder = Color.parseColor("#E3E5EF")
    private val cAccent = Color.parseColor("#6C4DFF")
    private val cAccent2 = Color.parseColor("#8B6CFF")
    private val cOk = Color.parseColor("#22C55E")
    private val cWarn = Color.parseColor("#F59E0B")
    private val cErr = Color.parseColor("#EF4444")
    private val cText = Color.parseColor("#14161F")
    private val cDim = Color.parseColor("#8A8FA3")
    private val cTerm = Color.parseColor("#0F1117")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private var adminOverlay: LinearLayout? = null
    private var adminWeb: WebView? = null
    private val popups = mutableListOf<WebView>()
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
    private lateinit var homeScroll: ScrollView
    private lateinit var controlsRow: View
    private lateinit var input: EditText
    private lateinit var folderTitle: TextView
    private lateinit var folderSub: TextView
    private lateinit var folderBtn: TextView
    private lateinit var railHome: TextView
    private lateinit var railTerm: TextView
    private var session: TerminalSession? = null
    private var kbVisible = false
    private var fontPx = 0
    private var lastError = ""

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // ---------- helpers de estilo ----------
    private fun rounded(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }
    private fun ripple(bg: Drawable) = RippleDrawable(ColorStateList.valueOf(0x30808090), bg, null)
    private fun isLight(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150
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
        this.text = text; setTextColor(if (isLight(color)) cText else Color.WHITE); gravity = Gravity.CENTER
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
        refreshConvChip()
        FccServer.listener = { st, msg -> ui.post { onServerState(st, msg) } }
        if (Rootfs.isReady(this)) { FccServer.ensure(this); openConversation(store.active().id) } else beginExtraction()
    }

    override fun onResume() {
        super.onResume()
        adminWeb?.onResume()
        // voltou ao primeiro plano: sincroniza a conversa ativa (no máximo a cada 10 s)
        val c = store.active()
        if (session != null && c.treeUri != null && System.currentTimeMillis() - (lastSyncAt[c.id] ?: 0L) > 10_000) {
            syncQuiet(c)
        }
    }

    override fun onPause() {
        adminWeb?.onPause()
        super.onPause()
    }

    override fun onStop() {
        syncQuiet(store.active())      // vai para segundo plano: envia o que mudou enquanto o processo está vivo
        super.onStop()
    }

    override fun onDestroy() {
        popups.forEach { try { it.destroy() } catch (_: Exception) {} }
        popups.clear()
        Rootfs.detach(this)
        adminWeb?.destroy()
        sessions.values.forEach { it.finishIfRunning() }
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
    private enum class Screen { HOME, TERMINAL, BUSY }

    private fun buildUi(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cBg)
            fitsSystemWindows = true
        }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        main.addView(buildTopBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val center = FrameLayout(this)
        homeScroll = buildHome()
        center.addView(homeScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        center.addView(buildSetup(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        termWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        termCard = FrameLayout(this).apply {
            background = rounded(cTerm, 20)
            clipToOutline = true
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        terminalView = TerminalView(this, null).apply {
            // a digitação acontece na caixa de entrada; o terminal só mostra a saída (e rola/seleciona)
            isFocusable = false
            isFocusableInTouchMode = false
            setTerminalViewClient(this@MainActivity)
            setTextSize(fontPx)
            setTypeface(try { Typeface.createFromAsset(assets, "fonts/mono.ttf") } catch (_: Exception) { Typeface.MONOSPACE })
            keepScreenOn = true
        }
        termCard.addView(terminalView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cSurface, 14)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        bannerText = label("Sessão encerrada", 14f, cText)
        banner.addView(bannerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        banner.addView(pill("Shell", cSurface2) { startTerminal(Mode.SHELL) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        banner.addView(pill("Reiniciar", cAccent) { startTerminal(Mode.CLAUDE) })
        termCard.addView(banner, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(8), 0, dp(8), dp(8)) })

        termWrap.addView(termCard, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            .apply { setMargins(dp(12), dp(2), dp(12), dp(4)) })
        center.addView(termWrap, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        main.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        controlsRow = buildControls()
        main.addView(controlsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        main.addView(buildInputCard(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

        root.addView(main, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(View(this).apply { setBackgroundColor(cBorder) }, LinearLayout.LayoutParams(dp(1), MATCH_PARENT))
        root.addView(buildRail(), LinearLayout.LayoutParams(dp(64), MATCH_PARENT))
        showScreen(Screen.HOME)
        return root
    }

    // ---------------------------------------------------------------- barra superior
    private fun buildTopBar(): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(8))
        }
        h.addView(logo(34, 17f))
        convChip = label("", 15f, cText, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(6), dp(8), dp(6))
            setOnClickListener { showConversations() }
        }
        h.addView(convChip, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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
        return h
    }

    // ---------------------------------------------------------------- trilho de navegação (direita)
    private fun buildRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(cBg)
            setPadding(0, dp(12), 0, dp(10))
        }
        fun item(icon: String, name: String, onClick: (View) -> Unit): TextView {
            val sp = SpannableString("$icon\n$name")
            sp.setSpan(RelativeSizeSpan(2.1f), 0, icon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val tv = TextView(this).apply {
                text = sp; gravity = Gravity.CENTER; setTextColor(cDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, dp(8), 0, dp(8))
                background = ripple(rounded(Color.TRANSPARENT, 14))
                setOnClickListener { onClick(it) }
            }
            rail.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(5), dp(2), dp(5), dp(2)) })
            return tv
        }
        railHome = item("🏠", "Início") { showScreen(Screen.HOME) }
        railTerm = item("💻", "Terminal") { ensureStarted(null) }
        item("💬", "Conversas") { showConversations() }
        item("📁", "Pasta") { pickFolder(store.active()) }
        item("⚙️", "Admin") { openAdmin() }
        item("⋯", "Mais") { showMenu(it) }
        rail.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        rail.addView(logo(40, 18f).apply { setOnClickListener { showMenu(it) } })
        return rail
    }

    private fun setRailActive(s: Screen) {
        fun mark(tv: TextView, on: Boolean) {
            tv.background = ripple(rounded(if (on) cSoft else Color.TRANSPARENT, 14))
            tv.setTextColor(if (on) cAccent else cDim)
        }
        mark(railHome, s == Screen.HOME)
        mark(railTerm, s == Screen.TERMINAL)
    }

    // ---------------------------------------------------------------- tela inicial
    private fun buildHome(): ScrollView {
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(16), dp(20))
        }
        col.addView(label("Olá! 👋", 30f, cText, true))
        col.addView(label("Como posso ajudar você hoje?", 30f, cText, true).apply { setPadding(0, dp(4), 0, dp(16)) })

        val fc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSoft, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        folderTitle = label("", 16f, cText, true)
        folderSub = label("", 13f, cDim).apply { setPadding(0, dp(4), 0, dp(10)) }
        folderBtn = pill("Escolher pasta", cAccent) { pickFolder(store.active()) }
        fc.addView(folderTitle); fc.addView(folderSub)
        fc.addView(folderBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        col.addView(fc, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })

        fun pair(a: View, b: View): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
            addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        }
        col.addView(pair(actionCard("💬", "Nova conversa") { newConversationFlow() },
            actionCard("📁", "Escolher pasta") { pickFolder(store.active()) }),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        col.addView(pair(actionCard("⚙️", "Provedores e APIs") { openAdmin() },
            actionCard("🔑", "Entrar (login)") { ensureStarted("/login") }),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(label("Você também pode pedir assim:", 14f, cDim).apply { setPadding(0, dp(18), 0, dp(10)) })
        fun sug(title: String, sub: String, prompt: String) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(rounded(cBg, 16, 1, cBorder))
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { ensureStarted(prompt) }
            }
            c.addView(label(title, 15f, cText, true))
            c.addView(label(sub, 13f, cDim).apply { setPadding(0, dp(2), 0, 0) })
            col.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        sug("Criar um projeto novo", "na pasta escolhida",
            "Crie um projeto novo aqui na pasta atual. Antes de começar, me pergunte o que eu quero construir.")
        sug("Entender este projeto", "ler os arquivos da pasta",
            "Leia os arquivos desta pasta e me explique o que o projeto faz e como está organizado.")
        sug("Corrigir erros", "rodar e consertar problemas",
            "Rode o projeto, encontre os erros e corrija.")
        sv.addView(col)
        return sv
    }

    private fun actionCard(icon: String, title: String, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(cSurface, 18))
            setPadding(dp(12), dp(14), dp(10), dp(14))
            setOnClickListener { onClick() }
        }
        c.addView(TextView(this).apply {
            text = icon; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = rounded(cBg, 12)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        c.addView(label(title, 14f, cText, true).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return c
    }

    private fun updateHome() {
        val c = store.active()
        when {
            c.treeUri != null -> {
                folderTitle.text = "📁 ${c.treeName ?: "Pasta do Android"}"
                folderSub.text = "Seus arquivos ficam nesta pasta do celular e são sincronizados com o Linux do app (/workspace)."
                folderBtn.text = "Alterar pasta"
            }
            c.internal -> {
                folderTitle.text = "📁 Pasta interna do app"
                folderSub.text = "Os arquivos ficam só dentro do app e não aparecem no gerenciador de arquivos. Escolha uma pasta do celular para acessá-los."
                folderBtn.text = "Escolher pasta do celular"
            }
            else -> {
                folderTitle.text = "Onde guardar seus projetos?"
                folderSub.text = "Escolha uma pasta do celular. Você poderá abrir e editar os arquivos pelo gerenciador de arquivos."
                folderBtn.text = "Escolher pasta"
            }
        }
    }

    private fun showScreen(s: Screen) {
        homeScroll.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        termWrap.visibility = if (s == Screen.TERMINAL) View.VISIBLE else View.GONE
        setupBox.visibility = if (s == Screen.BUSY) View.VISIBLE else View.GONE
        controlsRow.visibility = if (s == Screen.TERMINAL) View.VISIBLE else View.GONE
        setRailActive(s)
        if (s == Screen.HOME) updateHome()
    }

    // ---------------------------------------------------------------- menu "Mais"
    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 9, 0, "Configurar provedores (Admin)")
            menu.add(0, 11, 1, "Conversas…")
            menu.add(0, 12, 2, "Pasta desta conversa (escolher/alterar)")
            menu.add(0, 13, 3, "Sincronizar agora")
            menu.add(0, 14, 4, "Relatório da sincronização")
            menu.add(0, 15, 5, "Reiniciar servidor FCC")
            menu.add(0, 2, 6, "Fonte maior")
            menu.add(0, 3, 7, "Fonte menor")
            menu.add(0, 4, 8, "Limpar tela")
            menu.add(0, 5, 9, "Reiniciar Claude")
            menu.add(0, 6, 10, "Abrir shell")
            menu.add(0, 7, 11, "Diagnóstico (fcc-doctor)")
            menu.add(0, 8, 12, "Servidor FCC: status e log")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    9 -> openAdmin()
                    11 -> showConversations()
                    12 -> pickFolder(store.active())
                    13 -> syncManual(store.active())
                    14 -> showReport(store.active())
                    15 -> { FccServer.restart(this@MainActivity); Toast.makeText(this@MainActivity, "Reiniciando o servidor FCC…", Toast.LENGTH_SHORT).show() }
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

    private fun buildControls(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        fun chip(t: String, onClick: () -> Unit) {
            row.addView(TextView(this).apply {
                text = t; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = ripple(rounded(cSurface, 16))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        chip("Enter ↵") { send("\r") }
        chip("Esc") { send("\u001b") }
        chip("Tab") { send("\t") }
        chip("↑") { send("\u001b[A") }
        chip("↓") { send("\u001b[B") }
        chip("←") { send("\u001b[D") }
        chip("→") { send("\u001b[C") }
        chip("Ctrl+C") { send("\u0003") }
        chip("/login") { sendLine("/login") }
        chip("/model") { sendLine("/model") }
        chip("/help") { sendLine("/help") }
        chip("/clear") { sendLine("/clear") }
        chip("/resume") { sendLine("/resume") }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            visibility = View.GONE
        }
    }

    private fun buildInputCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cBg, 22, 2, cAccent)
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        input = EditText(this).apply {
            hint = "Pergunte qualquer coisa ou digite / para comandos"
            setHintTextColor(cDim); setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
        card.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun tool(t: String, onClick: () -> Unit) {
            bar.addView(TextView(this).apply {
                text = t; gravity = Gravity.CENTER; setTextColor(cDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                background = ripple(rounded(Color.TRANSPARENT, 19))
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
        }
        tool("/") {
            if (input.text.isEmpty()) input.setText("/") else input.append("/")
            input.setSelection(input.text.length); focusInput()
        }
        tool("📁") { pickFolder(store.active()) }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(TextView(this).apply {
            text = "➤"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = ripple(rounded(cAccent, 20))
            setOnClickListener { sendInput() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return card
    }

    private fun applyTerminalColors() {
        val p = Properties()
        p["background"] = "#0F1117"; p["foreground"] = "#ECEEF5"; p["cursor"] = "#8B6CFF"
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

    private fun send(s: String) { session?.write(s) }

    /** Texto + Enter. O Enter vai separado: o Claude Code trata "texto" + Enter colado de uma vez como colagem. */
    private fun sendLine(text: String) {
        val s = session ?: return
        if (text.contains('\n')) s.emulator?.paste(text) else s.write(text)
        ui.postDelayed({ s.write("\r") }, 180)
    }

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

    private fun focusInput() {
        input.requestFocus()
        input.post { imm().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun showKeyboard() { if (termWrap.visibility == View.VISIBLE) focusInput() }

    // ---------------------------------------------------------------- iniciar / enviar
    private fun configured(c: Conversation) = c.treeUri != null || c.internal

    /** Abre o terminal da conversa ativa (começando a sessão se preciso). [prefill] vai para a caixa de entrada. */
    private fun ensureStarted(prefill: String?) {
        if (prefill != null) { input.setText(prefill); input.setSelection(prefill.length) }
        val c = store.active()
        if (!configured(c)) { askFolder(c); return }
        val s = sessions[c.id]
        if (s != null && s.isRunning) {
            session = s
            showScreen(Screen.TERMINAL)
            attachSession(s, Mode.CLAUDE)
        } else {
            openConversation(c.id)
        }
    }

    private fun sendInput() {
        val text = input.text.toString()
        val c = store.active()
        val s = sessions[c.id]
        if (s == null || !s.isRunning) {          // ainda não há Claude rodando: inicia e mantém o texto na caixa
            ensureStarted(null)
            if (text.isNotBlank()) Toast.makeText(this, "Iniciando o Claude… envie de novo quando o prompt aparecer.", Toast.LENGTH_LONG).show()
            return
        }
        session = s
        if (termWrap.visibility != View.VISIBLE) { showScreen(Screen.TERMINAL); attachSession(s, Mode.CLAUDE) }
        if (text.isEmpty()) { s.write("\r"); return }
        input.setText("")
        sendLine(text)
    }

    private fun askFolder(c: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Onde guardar seus projetos?")
            .setMessage("Escolha uma pasta do celular para esta conversa. Você poderá abrir e editar os arquivos pelo gerenciador de arquivos.\n\nSem escolher, os arquivos ficam escondidos dentro do app.")
            .setPositiveButton("Escolher pasta") { _, _ -> pickFolder(c) }
            .setNeutralButton("Usar pasta interna") { _, _ ->
                c.internal = true; store.save(); refreshConvChip(); openConversation(c.id)
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun newConversationFlow() {
        promptText("Nome da conversa", "Conversa ${store.items.size + 1}") { n ->
            val c = store.add(n); switchTo(c.id)
        }
    }

    private fun copyError() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("erro", lastError))
        Toast.makeText(this, "Erro copiado", Toast.LENGTH_SHORT).show()
    }

    // ================================================================ 1º uso: extração
    private fun beginExtraction() {
        showScreen(Screen.BUSY)
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
                // O painel abre o login OAuth com window.open(): sem isto a WebView trocava a
                // própria página por "about:blank" (tela branca). Agora cada janela vira um
                // WebView invisível cuja URL é encaminhada ao navegador (Custom Tabs).
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                setBackgroundColor(cBg)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Qualquer coisa fora do painel local (site do provedor, login...) vai para o navegador.
                        val u = request.url
                        return if (u.scheme?.startsWith("http") == true && !isLocalHost(u)) { openExternal(u); true } else false
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
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                        val child = WebView(view.context)
                        child.settings.javaScriptEnabled = true
                        child.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                                routePopup(v, request.url)
                            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                                if (url != null) routePopup(v, Uri.parse(url))
                            }
                        }
                        popups.add(child)
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = child
                        resultMsg.sendToTarget()
                        return true
                    }
                    override fun onCloseWindow(window: WebView) {
                        popups.remove(window)
                        ui.post { try { window.destroy() } catch (_: Exception) {} }
                    }
                    // window.confirm()/alert() não aparecem sozinhos na WebView (o "Desconectar" ficava mudo).
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
            ov.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            frame.addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            adminOverlay = ov
            adminWeb = web
        }
        adminWeb?.loadUrl("http://127.0.0.1:${fccPort()}/admin")
        adminOverlay?.visibility = View.VISIBLE
    }

    private fun isLocalHost(u: Uri): Boolean = u.host == "127.0.0.1" || u.host == "localhost"

    /** Decide o destino de uma janela aberta pelo painel: http(s) vai para o navegador; about:blank é ignorado. */
    private fun routePopup(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        openExternal(uri)
        view.stopLoading()
        ui.post { popups.remove(view); try { view.destroy() } catch (_: Exception) {} }
        return true
    }

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
        convChip.text = "💬 ${c.name} ▾"
        if (::folderTitle.isInitialized) updateHome()
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
        val opts = arrayOf("Renomear", "Escolher / alterar pasta do Android", "Usar só a pasta interna (parar de sincronizar)",
            "Sincronizar agora", "Relatório da sincronização", "Excluir conversa")
        AlertDialog.Builder(this).setTitle(c.name).setItems(opts) { _, i ->
            when (i) {
                0 -> promptText("Renomear conversa", c.name) { n -> if (n.isNotEmpty()) { c.name = n; store.save(); refreshConvChip() } }
                1 -> pickFolder(c)
                2 -> useInternalFolder(c)
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
            // trocar uma pasta por outra arquiva a cópia antiga; quando antes não havia pasta, os arquivos são mantidos e enviados
            if (old != null && old != newTree) Workspace.resetForNewFolder(this, c.id)
            c.treeUri = newTree; c.internal = false; c.treeName = Workspace.displayName(this, uri); store.save()
            ui.post {
                sessions.remove(c.id)?.let { sessionConv.remove(it); it.finishIfRunning() }
                refreshConvChip()
                openConversation(c.id)       // sincroniza (baixa o conteúdo da pasta) e abre a sessão em /workspace
            }
        }
    }

    private fun useInternalFolder(c: Conversation) {
        val t = c.treeUri ?: run { Toast.makeText(this, "Esta conversa já usa a pasta interna.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Parar de sincronizar?")
            .setMessage("Os arquivos continuam na pasta do Android. A cópia dentro do app é mantida, mas deixa de ser sincronizada.")
            .setPositiveButton("Parar") { _, _ ->
                showBusy("Sincronizando antes de parar…")
                syncPool.execute {
                    Workspace.sync(this, c)
                    if (store.items.none { it.id != c.id && it.treeUri == t }) Workspace.releasePermission(this, t)
                    c.treeUri = null; c.treeName = null; c.internal = true; store.save()
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
        showScreen(Screen.BUSY)
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
        session = existing
        if (mode == Mode.CLAUDE && !configured(c)) {      // nunca começa em /root: antes escolhe a pasta
            showScreen(Screen.HOME)
            setStatus("escolha a pasta", cWarn)
            askFolder(c)
            return
        }
        if (!forceNew && mode == Mode.CLAUDE && existing != null && existing.isRunning) {
            showScreen(Screen.TERMINAL)
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
        showScreen(Screen.TERMINAL)
        banner.visibility = View.GONE
        val old = sessions[c.id]
        val s = try { createSession(c, mode) } catch (e: Exception) {
            showScreen(Screen.BUSY)
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
        // A sessão SEMPRE trabalha em /workspace (cópia privada da conversa). Nunca em /root.
        val ws = Workspace.dir(this, c.id).apply { mkdirs() }
        val binds = arrayListOf("${ws.path}:/workspace")
        val cwd = "/workspace"
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
