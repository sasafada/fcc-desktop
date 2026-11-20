package app.fcc.claude

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet

/**
 * Lado "remoto" da sincronização: a pasta escolhida pelo usuário no Android (SAF) em produção,
 * ou uma pasta comum nos testes. Os caminhos são sempre relativos, com "/" como separador.
 */
interface RemoteTree {
    data class Entry(val path: String, val isDir: Boolean, val size: Long, val mtime: Long)

    /** Lista tudo (arquivos e pastas), exceto o que [ignore] rejeitar. */
    fun scan(ignore: (String) -> Boolean): Map<String, Entry>
    /** Baixa o arquivo para [dest] (sobrescreve). */
    fun readTo(path: String, dest: File)
    /** Cria/sobrescreve o arquivo (criando as pastas necessárias) e devolve o estado resultante. */
    fun writeFrom(path: String, src: File): Entry
    fun mkdir(path: String)
    fun delete(path: String)
    /** Renomeia/move um arquivo. Pode lançar exceção se o provedor não suportar. */
    fun move(from: String, to: String): Entry
}

class SyncReport {
    val uploaded = ArrayList<String>()
    val downloaded = ArrayList<String>()
    val renamed = ArrayList<String>()
    val deletedLocal = ArrayList<String>()
    val deletedRemote = ArrayList<String>()
    val conflicts = ArrayList<String>()
    val errors = ArrayList<String>()
    val skipped = ArrayList<String>()
    val deferred = ArrayList<String>()

    fun isClean() = errors.isEmpty() && conflicts.isEmpty()
    fun changes() = uploaded.size + downloaded.size + renamed.size + deletedLocal.size + deletedRemote.size

    fun summary(): String {
        val parts = ArrayList<String>()
        if (uploaded.isNotEmpty()) parts.add("↑${uploaded.size}")
        if (downloaded.isNotEmpty()) parts.add("↓${downloaded.size}")
        if (renamed.isNotEmpty()) parts.add("renomeados ${renamed.size}")
        if (deletedLocal.size + deletedRemote.size > 0) parts.add("excluídos ${deletedLocal.size + deletedRemote.size}")
        if (conflicts.isNotEmpty()) parts.add("conflitos ${conflicts.size}")
        if (errors.isNotEmpty()) parts.add("erros ${errors.size}")
        return if (parts.isEmpty()) "tudo em dia" else parts.joinToString("  ")
    }

    fun details(): String {
        val sb = StringBuilder()
        fun sec(title: String, l: List<String>) { if (l.isNotEmpty()) { sb.append(title).append(" (").append(l.size).append("):\n"); l.take(60).forEach { sb.append("  • ").append(it).append('\n') }; if (l.size > 60) sb.append("  … e mais ${l.size - 60}\n") } }
        sec("Enviados para a pasta do Android", uploaded)
        sec("Baixados da pasta do Android", downloaded)
        sec("Renomeados/movidos", renamed)
        sec("Excluídos no app (propagação)", deletedLocal)
        sec("Excluídos na pasta do Android (propagação)", deletedRemote)
        sec("CONFLITOS — as duas versões foram preservadas", conflicts)
        sec("Erros", errors)
        sec("Ignorados", skipped)
        sec("Adiados (arquivo ainda sendo gravado)", deferred)
        return if (sb.isEmpty()) "Nada a sincronizar." else sb.toString()
    }
}

/**
 * Sincronização bidirecional de 3 vias entre [local] (cópia privada) e [remote].
 *
 * Princípios:
 *  - nunca sobrescreve em silêncio: quando os dois lados mudam, a versão mais nova fica no caminho
 *    original e a outra é preservada como "nome (conflito AAAAMMDD-HHMMSS).ext" nos DOIS lados;
 *  - toda exclusão propagada guarda antes uma cópia em [trashDir];
 *  - alteração vence exclusão (o arquivo alterado é restaurado);
 *  - renomeações são detectadas por (tamanho + data) e feitas sem reenviar o conteúdo;
 *  - não é em tempo real: roda quando chamada.
 */
class SyncEngine(
    private val local: File,
    private val remote: RemoteTree,
    private val stateFile: File,
    private val trashDir: File,
    private val settleMillis: Long = 1500L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Loc(val isDir: Boolean, val size: Long, val mtime: Long)
    private data class St(val isDir: Boolean, val lSize: Long, val lMtime: Long, val rSize: Long, val rMtime: Long)

    private val report = SyncReport()
    private lateinit var l: MutableMap<String, Loc>
    private lateinit var r: MutableMap<String, RemoteTree.Entry>
    private lateinit var st: MutableMap<String, St>
    private val trashStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))

    companion object {
        private val IGNORED_DIRS = setOf("node_modules", "__pycache__", ".venv", "venv", ".cache", ".gradle")
        fun ignored(path: String): Boolean {
            val parts = path.split('/')
            if (parts.any { it in IGNORED_DIRS }) return true
            val name = parts.last()
            return name.endsWith(".pyc") || name.endsWith(".fcc-tmp")
        }
    }

    fun sync(progress: (String) -> Unit = {}): SyncReport {
        local.mkdirs()
        st = loadState()
        progress("Lendo arquivos do app…")
        l = scanLocal()
        progress("Lendo a pasta do Android…")
        r = LinkedHashMap(remote.scan(::ignored))
        detectLocalRenames()
        detectRemoteRenames()

        val paths = TreeSet<String>()
        paths += l.keys; paths += r.keys; paths += st.keys
        var n = 0
        val dirsGoneRemote = ArrayList<String>()
        val dirsGoneLocal = ArrayList<String>()
        for (p in paths) {
            n++
            if (n % 20 == 0) progress("Sincronizando… $n/${paths.size}")
            try {
                process(p, dirsGoneRemote, dirsGoneLocal)
            } catch (e: Exception) {
                report.errors.add("$p: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // pastas excluídas de um lado: só removemos se ficaram vazias (mais profundas primeiro)
        for (d in dirsGoneRemote.sortedByDescending { it.length }) {
            val f = File(local, d)
            if (f.isDirectory && (f.list()?.isEmpty() == true)) { f.delete(); st.remove(d) }
        }
        for (d in dirsGoneLocal.sortedByDescending { it.length }) {
            val hasChildren = r.keys.any { it.startsWith("$d/") }
            if (!hasChildren) { try { remote.delete(d); st.remove(d) } catch (e: Exception) { report.errors.add("$d: ${e.message}") } }
        }
        saveState()
        return report
    }

    // ---------------------------------------------------------------- varredura
    private fun scanLocal(): MutableMap<String, Loc> {
        val out = LinkedHashMap<String, Loc>()
        fun walk(dir: File, prefix: String) {
            val list = dir.listFiles() ?: return
            for (f in list.sortedBy { it.name }) {
                val path = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (ignored(path)) continue
                if (Files.isSymbolicLink(f.toPath())) { report.skipped.add("$path (link simbólico — não sincroniza)"); continue }
                if (f.isDirectory) { out[path] = Loc(true, 0, 0); walk(f, path) }
                else if (f.isFile) out[path] = Loc(false, f.length(), f.lastModified())
            }
        }
        walk(local, "")
        return out
    }

    // ---------------------------------------------------------------- renomeações
    private fun localSame(p: String, s: St) = l[p]?.let { !it.isDir && it.size == s.lSize && it.mtime == s.lMtime } == true
    private fun remoteSame(p: String, s: St) = r[p]?.let { !it.isDir && it.size == s.rSize && it.mtime == s.rMtime } == true

    private fun detectLocalRenames() {
        val gone = st.filter { (p, s) -> !s.isDir && l[p] == null && remoteSame(p, s) && s.lSize > 0 }
        if (gone.isEmpty()) return
        val fresh = l.filter { (q, v) -> !v.isDir && st[q] == null && r[q] == null && v.size > 0 }.toMutableMap()
        for ((p, s) in gone) {
            val q = fresh.entries.firstOrNull { it.value.size == s.lSize && it.value.mtime == s.lMtime }?.key ?: continue
            try {
                val e = remote.move(p, q)
                r.remove(p); r[q] = e
                st.remove(p); st[q] = St(false, s.lSize, s.lMtime, e.size, e.mtime)
                fresh.remove(q)
                report.renamed.add("$p → $q (na pasta do Android)")
            } catch (e: Exception) { /* sem suporte: cai no caminho normal (enviar novo + excluir antigo) */ }
        }
    }

    private fun detectRemoteRenames() {
        val gone = st.filter { (p, s) -> !s.isDir && r[p] == null && localSame(p, s) && s.rSize > 0 }
        if (gone.isEmpty()) return
        val fresh = r.filter { (q, v) -> !v.isDir && st[q] == null && l[q] == null && v.size > 0 }.toMutableMap()
        for ((p, s) in gone) {
            val q = fresh.entries.firstOrNull { it.value.size == s.rSize && it.value.mtime == s.rMtime }?.key ?: continue
            try {
                val dst = File(local, q)
                dst.parentFile?.mkdirs()
                Files.move(File(local, p).toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                l.remove(p)
                val nl = Loc(false, dst.length(), dst.lastModified())
                l[q] = nl
                st.remove(p); st[q] = St(false, nl.size, nl.mtime, s.rSize, s.rMtime)
                fresh.remove(q)
                report.renamed.add("$p → $q (no app)")
            } catch (e: Exception) { report.errors.add("renomear $p: ${e.message}") }
        }
    }

    // ---------------------------------------------------------------- decisão por caminho
    private fun process(p: String, goneRemote: MutableList<String>, goneLocal: MutableList<String>) {
        val lo = l[p]; val re = r[p]; val s = st[p]

        if (lo != null && re != null && lo.isDir != re.isDir) {
            report.errors.add("$p: é pasta de um lado e arquivo do outro — ignorado"); return
        }
        if (lo?.isDir == true || re?.isDir == true) { processDir(p, lo, re, s, goneRemote, goneLocal); return }

        val localChanged = lo != null && (s == null || lo.size != s.lSize || lo.mtime != s.lMtime)
        val remoteChanged = re != null && (s == null || re.size != s.rSize || re.mtime != s.rMtime)

        when {
            lo == null && re == null -> st.remove(p)

            lo != null && re == null -> {
                if (s == null || localChanged) {
                    if (s != null) report.conflicts.add("$p: foi excluído na pasta do Android mas alterado no app — restaurado")
                    upload(p, lo)
                } else {
                    // remoto excluído e local intacto → propaga a exclusão (com cópia na lixeira)
                    moveToTrash(p)
                    l.remove(p); st.remove(p); report.deletedLocal.add(p)
                }
            }

            lo == null && re != null -> {
                if (s == null || remoteChanged) {
                    if (s != null) report.conflicts.add("$p: foi excluído no app mas alterado na pasta do Android — restaurado")
                    download(p, re)
                } else {
                    // local excluído e remoto intacto → propaga a exclusão (antes guarda cópia na lixeira)
                    val tmp = trashFile(p)
                    tmp.parentFile?.mkdirs()
                    remote.readTo(p, tmp)
                    remote.delete(p)
                    r.remove(p); st.remove(p); report.deletedRemote.add(p)
                }
            }

            else -> { // existe dos dois lados
                lo!!; re!!
                if (s != null && !localChanged && !remoteChanged) return
                if (s != null && localChanged && !remoteChanged) { upload(p, lo); return }
                if (s != null && !localChanged && remoteChanged) { download(p, re); return }
                // ambos mudaram (ou nunca sincronizados): iguais de fato?
                if (lo.size == re.size && sameContent(p)) {
                    st[p] = St(false, lo.size, lo.mtime, re.size, re.mtime); return
                }
                resolveConflict(p, lo, re)
            }
        }
    }

    private fun processDir(p: String, lo: Loc?, re: RemoteTree.Entry?, s: St?, goneRemote: MutableList<String>, goneLocal: MutableList<String>) {
        when {
            lo != null && re != null -> st[p] = St(true, 0, 0, 0, 0)
            lo != null && re == null -> {
                if (s == null) { remote.mkdir(p); r[p] = RemoteTree.Entry(p, true, 0, 0); st[p] = St(true, 0, 0, 0, 0) }
                else goneRemote.add(p)
            }
            lo == null && re != null -> {
                if (s == null) { File(local, p).mkdirs(); l[p] = Loc(true, 0, 0); st[p] = St(true, 0, 0, 0, 0) }
                else goneLocal.add(p)
            }
            else -> st.remove(p)
        }
    }

    // ---------------------------------------------------------------- operações
    private fun upload(p: String, lo: Loc) {
        val f = File(local, p)
        if (settleMillis > 0 && clock() - lo.mtime < settleMillis) { report.deferred.add(p); return }
        val e = remote.writeFrom(p, f)
        r[p] = e
        st[p] = St(false, lo.size, lo.mtime, e.size, e.mtime)   // instantâneo de ANTES do envio
        report.uploaded.add(p)
    }

    private fun download(p: String, re: RemoteTree.Entry) {
        val dst = File(local, p)
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, ".${dst.name}.fcc-tmp")
        try {
            remote.readTo(p, tmp)
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { if (tmp.exists()) tmp.delete() }
        if (re.mtime > 0) dst.setLastModified(re.mtime)
        val nl = Loc(false, dst.length(), dst.lastModified())
        l[p] = nl
        st[p] = St(false, nl.size, nl.mtime, re.size, re.mtime)
        report.downloaded.add(p)
    }

    private fun sameContent(p: String): Boolean {
        val tmp = File(local, ".${File(p).name}.cmp.fcc-tmp")
        return try {
            remote.readTo(p, tmp)
            sha256(tmp) == sha256(File(local, p))
        } finally { tmp.delete() }
    }

    private fun resolveConflict(p: String, lo: Loc, re: RemoteTree.Entry) {
        val localWins = lo.mtime >= re.mtime
        val conflictPath = uniqueConflictPath(p)
        val localFile = File(local, p)
        val cFile = File(local, conflictPath)
        cFile.parentFile?.mkdirs()
        if (localWins) {
            remote.readTo(p, cFile)                 // versão do Android → arquivo de conflito
            if (re.mtime > 0) cFile.setLastModified(re.mtime)
            val ce = remote.writeFrom(conflictPath, cFile)
            r[conflictPath] = ce
            val e = remote.writeFrom(p, localFile)  // versão do app permanece no caminho original
            r[p] = e
            st[p] = St(false, lo.size, lo.mtime, e.size, e.mtime)
        } else {
            Files.copy(localFile.toPath(), cFile.toPath(), StandardCopyOption.REPLACE_EXISTING)  // versão do app → conflito
            cFile.setLastModified(lo.mtime)
            val ce = remote.writeFrom(conflictPath, cFile)
            r[conflictPath] = ce
            download(p, re)                         // versão do Android no caminho original
            report.downloaded.remove(p)
        }
        val cl = Loc(false, cFile.length(), cFile.lastModified())
        l[conflictPath] = cl
        st[conflictPath] = St(false, cl.size, cl.mtime, r[conflictPath]!!.size, r[conflictPath]!!.mtime)
        report.conflicts.add("$p → versão preservada em: $conflictPath")
    }

    private fun uniqueConflictPath(p: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(clock()))
        val slash = p.lastIndexOf('/')
        val dir = if (slash >= 0) p.substring(0, slash + 1) else ""
        val name = p.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 0
        while (true) {
            val suffix = if (i == 0) "" else " $i"
            val cand = "$dir$base (conflito $stamp$suffix)$ext"
            if (!File(local, cand).exists() && r[cand] == null) return cand
            i++
        }
    }

    // ---------------------------------------------------------------- lixeira
    private fun trashFile(p: String) = File(File(trashDir, trashStamp), p)

    private fun moveToTrash(p: String) {
        val src = File(local, p)
        val dst = trashFile(p)
        dst.parentFile?.mkdirs()
        try { Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        catch (e: Exception) { Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING); src.delete() }
    }

    // ---------------------------------------------------------------- estado
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }; i += 2 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun loadState(): MutableMap<String, St> {
        val m = LinkedHashMap<String, St>()
        if (!stateFile.exists()) return m
        stateFile.readLines().forEach { line ->
            val f = line.split('\t')
            if (f.size == 6) m[unesc(f[0])] = St(f[1] == "d", f[2].toLong(), f[3].toLong(), f[4].toLong(), f[5].toLong())
        }
        return m
    }

    private fun saveState() {
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(st.entries.joinToString("\n") { (p, s) ->
            "${esc(p)}\t${if (s.isDir) "d" else "f"}\t${s.lSize}\t${s.lMtime}\t${s.rSize}\t${s.rMtime}"
        })
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
