package app.fcc.claude

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.Executors

/**
 * Cérebro do app: liga a interface (WebView) ao backend existente.
 * Conversas, histórico, pasta/sincronização, FCC Server e Claude Code (processo em modo -p).
 * Nada aqui desenha tela — tudo chega à interface como JSON pela ponte.
 */
class ChatController(private val ctx: Context, private val host: Host) {

    interface Host {
        fun js(code: String)
        fun pickFolder(convId: String)
        fun pickAttachment()
        fun openAdmin()
        fun openTerminal()
        fun openUrl(url: String)
        fun copy(text: String)
        fun retryExtraction()
    }

    private class Turn(val runner: ClaudeRunner?, val msg: Message, val builder: MessageBuilder) { @Volatile var stopRequested = false }
    private class SyncInfo(var state: String = "idle", var last: Long = 0, var summary: String = "")

    private val ui = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("fcc", Context.MODE_PRIVATE)
    val store = ConversationStore(ctx)
    private val chats = ChatStore(File(ctx.filesDir, "chats"))
    private val cache = HashMap<String, MutableList<Message>>()
    private val turns = HashMap<String, Turn>()
    private val syncInfo = HashMap<String, SyncInfo>()
    private val syncPool = Executors.newSingleThreadExecutor()
    private val lastSyncAt = HashMap<String, Long>()
    private val notices = ArrayList<Map<String, Any?>>()
    private var busy: Map<String, Any?>? = null
    private var started = false
    private val noticeFile = File(ctx.filesDir, "notices.json")

    init { loadNotices(); FccServer.listener = { st, msg -> ui.post { onServerState(st, msg) } } }

    // ------------------------------------------------------------------ ciclo de vida
    /** Chamado quando o Linux está pronto (extraído). */
    fun start() {
        started = true
        busy = null
        FccServer.ensure(ctx)
        pushAll()
    }

    fun setBusy(title: String?, text: String = "", pct: Double? = null, error: Boolean = false) {
        busy = if (title == null) null else linkedMapOf("title" to title, "text" to text, "pct" to (pct ?: -1.0), "error" to error)
        pushState()
    }

    fun onForeground() {
        val c = store.active()
        if (started && c.treeUri != null && System.currentTimeMillis() - (lastSyncAt[c.id] ?: 0L) > 10_000) syncQuiet(c)
    }

    fun onBackground() { if (started) syncQuiet(store.active()) }

    fun shutdown() {
        turns.values.forEach { it.runner?.stop() }
        FccServer.listener = null
    }

    // ------------------------------------------------------------------ mensagens vindas da interface
    @Suppress("UNCHECKED_CAST")
    fun onMessage(json: String) {
        val m = try { MiniJson.parse(json) as? Map<String, Any?> } catch (_: Exception) { null } ?: return
        when (m["a"] as? String) {
            "ready" -> pushAll()
            "send" -> send((m["text"] as? String).orEmpty(), (m["files"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> })
            "stop" -> stop(store.active().id)
            "newConv" -> { val c = store.add(""); store.setActive(c.id); pushAll(); host.js("FCC.go('chat')") }
            "openConv" -> (m["id"] as? String)?.let { openConv(it) }
            "renameConv" -> { val c = store.find(m["id"] as? String ?: ""); val n = (m["name"] as? String)?.trim().orEmpty(); if (c != null && n.isNotEmpty()) { c.name = n.take(80); store.save(); pushState() } }
            "deleteConv" -> (m["id"] as? String)?.let { deleteConv(it) }
            "pickFolder" -> host.pickFolder(store.active().id)
            "useInternal" -> useInternal(store.active())
            "syncNow" -> syncManual(store.active())
            "report" -> showReport(store.active())
            "admin" -> host.openAdmin()
            "restartServer" -> FccServer.restart(ctx)
            "terminal" -> host.openTerminal()
            "serverLog" -> showServerLog()
            "attach" -> host.pickAttachment()
            "ls" -> listDir(m["path"] as? String ?: "")
            "cat" -> readFile(m["path"] as? String ?: "")
            "copy" -> host.copy(m["text"] as? String ?: "")
            "open" -> (m["url"] as? String)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { host.openUrl(it) }
            "setting" -> setSetting(m["key"] as? String ?: "", m["value"])
            "profile" -> { prefs.edit().putString("user", (m["name"] as? String).orEmpty().take(40)).apply(); pushState() }
            "readNotices" -> { for (i in notices.indices) notices[i] = notices[i] + ("read" to true); saveNotices(); pushState() }
            "clearNotices" -> { notices.clear(); saveNotices(); pushState() }
            "retryExtract" -> host.retryExtraction()
        }
    }

    // ------------------------------------------------------------------ conversas
    private fun configured(c: Conversation) = c.treeUri != null || c.internal

    private fun msgsOf(id: String): MutableList<Message> = cache.getOrPut(id) { chats.load(id) }

    private fun openConv(id: String) {
        val prev = store.active()
        if (prev.id != id) syncQuiet(prev)
        if (store.find(id) == null) return
        store.setActive(id)
        pushAll()
        val c = store.active()
        if (c.treeUri != null) syncQuiet(c)
    }

    private fun deleteConv(id: String) {
        val c = store.find(id) ?: return
        turns.remove(id)?.runner?.stop()
        c.treeUri?.let { t -> if (store.items.none { it.id != id && it.treeUri == t }) Workspace.releasePermission(ctx, t) }
        Workspace.deleteAll(ctx, id)
        chats.delete(id); cache.remove(id)
        store.remove(id)
        pushAll()
    }

    // ------------------------------------------------------------------ envio / geração
    private fun send(text: String, files: List<Map<String, Any?>>) {
        val c = store.active()
        if (!configured(c)) { host.js("FCC.needFolder()"); return }
        if (turns.containsKey(c.id)) { toast("Aguarde a resposta terminar ou toque em parar."); return }
        val clean = text.trim()
        if (clean.isEmpty() && files.isEmpty()) return

        val ub = ArrayList<Block>()
        val paths = ArrayList<String>()
        for (f in files) {
            val name = f["name"] as? String ?: continue; val path = f["path"] as? String ?: continue
            ub.add(Block("file", name = name, detail = path)); paths.add(path)
        }
        if (clean.isNotEmpty()) ub.add(Block("text", clean))
        val list = msgsOf(c.id)
        val um = Message(role = "user", blocks = ub)
        val am = Message(role = "assistant", streaming = true)
        list.add(um); list.add(am)
        if (list.size == 2 && Regex("^Conversa \\d+$").matches(c.name)) {      // título automático
            c.name = (clean.ifEmpty { "Arquivos anexados" }).lineSequence().first().take(40); store.save()
        }
        chats.save(c.id, list)
        host.js("FCC.messages(${MiniJson.stringify(c.id)}, ${MiniJson.stringify(list.map { it.toMap() })})")
        var prompt = if (clean.isEmpty()) "Analise os arquivos anexados." else clean
        if (paths.isNotEmpty()) prompt += "\n\n[Arquivos anexados na pasta de trabalho: ${paths.joinToString(", ")}]"
        runTurn(c, am, prompt, attempt = 0)
    }

    private fun runTurn(c: Conversation, am: Message, prompt: String, attempt: Int) {
        val builder = MessageBuilder(am)
        val turn = Turn(null, am, builder)
        turns[c.id] = turn
        pushState(); pushMsg(c.id, am)
        Thread({
            try {
                // 1) assistente interno pronto?
                if (FccServer.state != FccServer.State.READY) {
                    note(c.id, am, "Preparando o assistente… (na primeira vez pode levar alguns minutos)", "wait")
                    FccServer.ensure(ctx)
                    val t0 = System.currentTimeMillis()
                    while (!turn.stopRequested && FccServer.state != FccServer.State.READY && FccServer.state != FccServer.State.FAILED &&
                        System.currentTimeMillis() - t0 < 8 * 60_000L) Thread.sleep(500)
                    if (turn.stopRequested) { finishTurn(c, am, turn, "Interrompido."); return@Thread }
                    if (FccServer.state != FccServer.State.READY) {
                        synchronized(am) { am.blocks.add(Block("error", text = "O assistente interno não iniciou. ${FccServer.lastError.take(300)}".trim())) }
                        finishTurn(c, am, turn, null); return@Thread
                    }
                    note(c.id, am, "", "")
                }
                // 1b) o provedor do modelo padrão está configurado? (evita 10 tentativas silenciosas)
                val pc = ProviderCheck.check(FccServer.port)
                if (!pc.ready) {
                    synchronized(am) { am.blocks.add(Block("error", text = pc.message)) }
                    finishTurn(c, am, turn, null); return@Thread
                }
                // 2) traz para o Linux o que mudou na pasta do Android
                if (c.treeUri != null) {
                    note(c.id, am, "Sincronizando arquivos da pasta…", "wait")
                    val rep = Workspace.sync(ctx, c)
                    lastSyncAt[c.id] = System.currentTimeMillis()
                    recordSync(c, rep)
                    note(c.id, am, "", "")
                }
                if (turn.stopRequested) { finishTurn(c, am, turn, "Interrompido."); return@Thread }
                // 3) executa o Claude Code
                Proot.prepareRootfs(ctx)
                val ws = Workspace.dir(ctx, c.id).apply { mkdirs() }
                val mode = prefs.getString("mode", "auto") ?: "auto"
                val inner = ArrayList<String>()
                inner += listOf("/root/.local/bin/fcc-claude", "-p", "--output-format", "stream-json", "--verbose",
                    "--include-partial-messages", "--permission-mode", mode,
                    "--append-system-prompt", SYSTEM_HINT)
                c.claudeSession?.let { inner += listOf("--resume", it) }
                inner += listOf("--", prompt)
                val guestEnv = arrayListOf("HOST=127.0.0.1", "PORT=${FccServer.port}", "IS_SANDBOX=1")
                val cmd = Proot.args(ctx, listOf("${ws.path}:/workspace"), "/workspace", guestEnv, inner)
                val env = Proot.env(ctx).associate { it.substringBefore('=') to it.substringAfter('=') }
                val runner = ClaudeRunner(cmd, env, ctx.filesDir)
                val running = Turn(runner, am, builder).also { it.stopRequested = turn.stopRequested }
                turns[c.id] = running
                val gate = Object(); var finished = false
                var lastPush = 0L
                runner.start(object : ClaudeRunner.Listener {
                    override fun onEvent(ev: ChatEvent) {
                        var changed = false
                        synchronized(am) { changed = builder.apply(ev) }
                        if (ev is ChatEvent.Session) { c.claudeSession = ev.id; store.save() }
                        if (ev is ChatEvent.Done && ev.sessionId != null) { c.claudeSession = ev.sessionId; store.save() }
                        if (changed) {
                            val now = System.currentTimeMillis()
                            if (ev is ChatEvent.Text && now - lastPush < 90) return
                            lastPush = now
                            ui.post { pushMsg(c.id, am) }
                        }
                    }
                    override fun onFinished(exitCode: Int, stoppedByUser: Boolean, stderrTail: String) {
                        // sessão antiga que não existe mais: recomeça sem --resume (uma vez)
                        val failedResume = !builder.finished && c.claudeSession != null && stderrTail.contains("No conversation found")
                        val resumeErr = am.blocks.any { it.kind == "error" && it.text.contains("No conversation found") }
                        if ((failedResume || resumeErr) && attempt == 0 && !stoppedByUser) {
                            synchronized(am) { am.blocks.removeAll { it.kind == "error" } }
                            c.claudeSession = null; store.save()
                            turns.remove(c.id)
                            ui.post { runTurn(c, am, prompt, 1) }
                            return
                        }
                        if (!builder.finished) {
                            synchronized(am) {
                                if (stoppedByUser) am.blocks.add(Block("text", "_Resposta interrompida._"))
                                else am.blocks.add(Block("error", text = (stderrTail.ifBlank { "O Claude Code encerrou sem responder (código $exitCode)." })))
                            }
                        }
                        finishTurn(c, am, running, null)
                    }
                })
            } catch (e: Exception) {
                synchronized(am) { am.blocks.add(Block("error", text = "${e.javaClass.simpleName}: ${e.message}")) }
                finishTurn(c, am, turn, null)
            }
        }, "turn-${c.id.take(6)}").also { it.isDaemon = true }.start()
    }

    private fun finishTurn(c: Conversation, am: Message, turn: Turn, extraText: String?) {
        synchronized(am) {
            if (extraText != null) am.blocks.add(Block("text", "_${extraText}_"))
            am.streaming = false; am.note = ""; am.noteKind = ""
            am.blocks.filter { it.kind == "tool" && it.status == "run" }.forEach { it.status = "ok" }
        }
        ui.post {
            turns.remove(c.id)
            try { chats.save(c.id, msgsOf(c.id)) } catch (_: Exception) {}
            pushMsg(c.id, am); pushState()
            if (c.treeUri != null) syncQuiet(c)                 // devolve para a pasta do Android o que a IA mudou
        }
    }

    private fun note(convId: String, am: Message, text: String, kind: String) {
        synchronized(am) { am.note = text; am.noteKind = kind }
        ui.post { pushMsg(convId, am) }
    }

    private fun stop(convId: String) {
        val t = turns[convId] ?: return
        t.stopRequested = true
        t.runner?.stop()
    }

    // ------------------------------------------------------------------ pasta + sincronização
    fun onFolderPicked(convId: String, uri: Uri?) {
        val c = store.find(convId) ?: return
        if (uri == null) return
        val other = store.items.firstOrNull { it.id != c.id && it.treeUri == uri.toString() }
        if (other != null) { toast("Esta pasta já é usada pela conversa “${other.name}”."); return }
        try { Workspace.takePermission(ctx, uri) } catch (e: Exception) { toast("Não foi possível guardar a permissão da pasta: ${e.message}"); return }
        val old = c.treeUri; val newTree = uri.toString()
        setBusy("Preparando a pasta", "Sincronizando seus arquivos…")
        syncPool.execute {
            if (old != null && old != newTree) {
                Workspace.sync(ctx, c)
                if (store.items.none { it.id != c.id && it.treeUri == old }) Workspace.releasePermission(ctx, old)
                Workspace.resetForNewFolder(ctx, c.id)
            }
            c.treeUri = newTree; c.internal = false; c.treeName = Workspace.displayName(ctx, uri); store.save()
            val rep = Workspace.sync(ctx, c) { msg -> ui.post { setBusy("Preparando a pasta", msg) } }
            lastSyncAt[c.id] = System.currentTimeMillis()
            ui.post { setBusy(null); recordSync(c, rep); pushAll(); if (!rep.isClean()) showSyncResult(c, rep) }
        }
    }

    private fun useInternal(c: Conversation) {
        val t = c.treeUri
        if (t == null) { c.internal = true; store.save(); pushState(); return }
        setBusy("Sincronizando", "Guardando as últimas mudanças antes de parar…")
        syncPool.execute {
            Workspace.sync(ctx, c)
            if (store.items.none { it.id != c.id && it.treeUri == t }) Workspace.releasePermission(ctx, t)
            c.treeUri = null; c.treeName = null; c.internal = true; store.save()
            ui.post { setBusy(null); pushAll() }
        }
    }

    private fun recordSync(c: Conversation, rep: SyncReport) {
        val si = syncInfo.getOrPut(c.id) { SyncInfo() }
        si.state = if (rep.errors.isNotEmpty()) "error" else "idle"; si.last = System.currentTimeMillis(); si.summary = rep.summary()
        if (!rep.isClean()) addNotice("Sincronização de “${c.name}”: ${rep.summary()}")
        pushState()
    }

    private fun syncQuiet(c: Conversation) {
        if (c.treeUri == null) return
        val si = syncInfo.getOrPut(c.id) { SyncInfo() }
        si.state = "syncing"; pushState()
        syncPool.execute {
            val rep = Workspace.sync(ctx, c)
            lastSyncAt[c.id] = System.currentTimeMillis()
            ui.post { recordSync(c, rep) }
        }
    }

    private fun syncManual(c: Conversation) {
        if (c.treeUri == null) { toast("Escolha uma pasta do celular para sincronizar."); return }
        val si = syncInfo.getOrPut(c.id) { SyncInfo() }
        si.state = "syncing"; pushState(); toast("Sincronizando…")
        syncPool.execute {
            val rep = Workspace.sync(ctx, c)
            lastSyncAt[c.id] = System.currentTimeMillis()
            ui.post { recordSync(c, rep); showSyncResult(c, rep) }
        }
    }

    private fun showSyncResult(c: Conversation, rep: SyncReport) {
        host.js("FCC.report(${MiniJson.stringify("“${c.name}”: ${rep.summary()}\n\n${rep.details()}")})")
    }

    private fun showReport(c: Conversation) {
        val f = Workspace.reportFile(ctx, c.id)
        val t = if (f.exists()) f.readText() else "Ainda não houve sincronização desta conversa."
        host.js("FCC.report(${MiniJson.stringify(t)})")
    }

    // ------------------------------------------------------------------ arquivos (navegador e anexos)
    private fun resolveInWorkspace(rel: String): File? {
        val base = Workspace.dir(ctx, store.active().id).canonicalFile
        val f = File(base, rel).canonicalFile
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun listDir(rel: String) {
        val id = store.active().id
        val reply: (Map<String, Any?>) -> Unit = { host.js("FCC.ls(${MiniJson.stringify(rel)}, ${MiniJson.stringify(it)})") }
        val dir = resolveInWorkspace(rel)
        if (dir == null || !dir.isDirectory) { reply(mapOf("error" to "Pasta não encontrada.")); return }
        val entries = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".fcc") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { linkedMapOf("name" to it.name, "dir" to it.isDirectory, "size" to if (it.isDirectory) 0L else it.length(), "mtime" to it.lastModified()) }
        if (id == store.active().id) reply(mapOf("entries" to entries))
    }

    private fun readFile(rel: String) {
        val f = resolveInWorkspace(rel)
        val reply: (Map<String, Any?>) -> Unit = { host.js("FCC.cat(${MiniJson.stringify(rel)}, ${MiniJson.stringify(it)})") }
        if (f == null || !f.isFile) { reply(mapOf("error" to "Arquivo não encontrado.")); return }
        try {
            val max = 200_000
            val bytes = f.inputStream().use { it.readNBytes(max + 1) }
            if (bytes.take(4096).any { it == 0.toByte() }) { reply(mapOf("error" to "Arquivo binário: não dá para exibir aqui.")); return }
            val truncated = bytes.size > max
            reply(mapOf("text" to String(bytes, 0, minOf(bytes.size, max), Charsets.UTF_8), "truncated" to truncated))
        } catch (e: Exception) { reply(mapOf("error" to "Não foi possível ler: ${e.message}")) }
    }

    /** Copia os arquivos escolhidos para <workspace>/anexos e devolve os nomes à interface. */
    fun onAttachments(uris: List<Uri>) {
        val c = store.active()
        if (!configured(c)) { host.js("FCC.needFolder()"); return }
        Thread {
            val out = ArrayList<Map<String, Any?>>()
            val dir = File(Workspace.dir(ctx, c.id), "anexos").apply { mkdirs() }
            for (u in uris) {
                try {
                    var name = "arquivo"
                    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q -> if (q.moveToFirst()) name = q.getString(0) ?: name }
                    name = name.replace(Regex("[\\\\/\u0000-\u001f]"), "_").trim().ifEmpty { "arquivo" }
                    var target = File(dir, name); var n = 1
                    while (target.exists()) { val dot = name.lastIndexOf('.'); target = File(dir, if (dot > 0) name.substring(0, dot) + "-$n" + name.substring(dot) else "$name-$n"); n++ }
                    ctx.contentResolver.openInputStream(u)?.use { i -> target.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16); var total = 0L
                        while (true) { val r = i.read(buf); if (r < 0) break; total += r; if (total > 50L * 1024 * 1024) throw java.io.IOException("Arquivo maior que 50 MB"); o.write(buf, 0, r) }
                    } } ?: throw java.io.IOException("Não foi possível abrir o arquivo")
                    out.add(mapOf("name" to target.name, "path" to "anexos/${target.name}"))
                } catch (e: Exception) { ui.post { toast("Anexo não adicionado: ${e.message}") } }
            }
            if (out.isNotEmpty()) ui.post { host.js("FCC.attached(${MiniJson.stringify(out)})") }
        }.start()
    }

    // ------------------------------------------------------------------ ajustes, avisos, servidor
    private fun setSetting(key: String, value: Any?) {
        when (key) {
            "mode" -> (value as? String)?.takeIf { it in setOf("auto", "bypassPermissions", "acceptEdits") }?.let { prefs.edit().putString("mode", it).apply() }
            "mascot" -> prefs.edit().putBoolean("mascot", value == true).apply()
        }
        pushState()
    }

    private fun onServerState(st: FccServer.State, msg: String) {
        if (st == FccServer.State.FAILED) addNotice("O assistente interno não iniciou: ${msg.take(160)}")
        pushState()
    }

    private fun showServerLog() {
        val sb = StringBuilder()
        fun tail(f: File, n: Int): String = try { f.readLines().takeLast(n).joinToString("\n") { if (it.length > 240) it.take(240) + "…" else it } } catch (_: Exception) { "(sem registro)" }
        sb.append("Estado: ${FccServer.state} · porta ${FccServer.port}\n\n— Saída do processo —\n").append(tail(FccServer.logFile(ctx), 40))
        sb.append("\n\n— Registro interno —\n").append(tail(File(Proot.rootfs(ctx), "root/.fcc/logs/server.log"), 20))
        host.js("FCC.log('Registro do assistente', ${MiniJson.stringify(sb.toString())})")
    }

    private fun addNotice(text: String) {
        notices.add(linkedMapOf("id" to System.nanoTime().toString(), "text" to text, "ts" to System.currentTimeMillis(), "read" to false))
        while (notices.size > 40) notices.removeAt(0)
        saveNotices()
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadNotices() {
        try { if (noticeFile.exists()) (MiniJson.parse(noticeFile.readText()) as? List<Any?>)?.forEach { (it as? Map<String, Any?>)?.let { m -> notices.add(m) } } } catch (_: Exception) {}
    }
    private fun saveNotices() { try { noticeFile.writeText(MiniJson.stringify(notices)) } catch (_: Exception) {} }

    private fun toast(t: String) { host.js("FCC.toast(${MiniJson.stringify(t)})") }

    // ------------------------------------------------------------------ envio de estado para a interface
    private fun versionName(): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }

    private fun preview(id: String): Pair<String, Long> {
        val l = msgsOf(id)
        val last = l.lastOrNull { it.plainText().isNotBlank() } ?: return "" to (store.find(id)?.createdAt ?: 0L)
        return last.plainText().replace(Regex("\\s+"), " ").take(90) to last.ts
    }

    private fun stateMap(): Map<String, Any?> {
        val c = store.active()
        val folder = when {
            c.treeUri != null -> mapOf("kind" to "saf", "name" to (c.treeName ?: "Pasta do Android"))
            c.internal -> mapOf("kind" to "internal", "name" to "")
            else -> mapOf("kind" to "none", "name" to "")
        }
        val si = syncInfo[c.id]
        val convs = store.items.map { x ->
            val (p, ts) = preview(x.id)
            linkedMapOf("id" to x.id, "name" to x.name, "preview" to p, "ts" to ts,
                "folderKind" to if (x.treeUri != null) "saf" else if (x.internal) "internal" else "none")
        }.sortedByDescending { (it["ts"] as? Long) ?: 0L }
        return linkedMapOf(
            "version" to versionName(), "busy" to busy,
            "user" to mapOf("name" to (prefs.getString("user", "") ?: "")),
            "mascot" to prefs.getBoolean("mascot", true),
            "conv" to mapOf("id" to c.id, "name" to c.name, "folder" to folder,
                "sync" to mapOf("state" to (si?.state ?: "idle"), "last" to (si?.last ?: 0L), "summary" to (si?.summary ?: ""))),
            "convs" to convs,
            "server" to mapOf("state" to FccServer.state.name, "port" to FccServer.port, "msg" to FccServer.lastError.take(160)),
            "gen" to mapOf("running" to turns.containsKey(c.id), "convId" to if (turns.containsKey(c.id)) c.id else ""),
            "notices" to notices,
            "settings" to mapOf("mode" to (prefs.getString("mode", "auto") ?: "auto")),
        )
    }

    fun pushState() { host.js("window.FCC&&FCC.state(${MiniJson.stringify(stateMap())})") }

    private fun pushMessages(id: String) {
        host.js("window.FCC&&FCC.messages(${MiniJson.stringify(id)}, ${MiniJson.stringify(msgsOf(id).map { it.toMap() })})")
    }

    private fun pushMsg(convId: String, m: Message) {
        val snapshot = synchronized(m) { MiniJson.stringify(m.toMap()) }
        host.js("window.FCC&&FCC.msg(${MiniJson.stringify(convId)}, $snapshot)")
    }

    fun pushAll() { pushState(); pushMessages(store.active().id) }

    companion object {
        private const val SYSTEM_HINT =
            "O usuário está em um aplicativo Android de chat. Os arquivos do projeto ficam na pasta de trabalho atual (/workspace). " +
            "Responda em português do Brasil, de forma clara e direta, e não mencione detalhes internos do ambiente Linux."
    }
}
