/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Rime 用户词库同步（lexicon/1）。
 *
 * Rime 原生的同步模型是「共享目录」：每台设备把自己的 userdb 导出到 sync/<自己的 installation_id>/，
 * 调 sync_user_data 时把 sync/ 下所有其他设备的快照合并进本机 userdb（按词频和时间戳，不会互相覆盖），再导出自己的快照。
 * 这里只做一件事：让房间里每台设备的 sync/ 目录内容一致（主机做中转），合并完全交给 Rime。
 *
 * 安全约束：
 * - 只搬运 *.userdb.txt；Rime 同时备份到 sync/<id>/ 的 yaml/txt 配置不传。
 * - 永远不写本机自己的 sync/<本机 id>/（那是本机 Rime 的导出，被覆盖会丢词）。
 * - 主机上一个 installation_id 只能属于一台设备（deviceId），防止某台设备冒名覆盖别人的快照。
 * - 收到的文件先写临时文件、校验 SHA-256 与文件头，再原子改名。
 * - 合并只在键盘没有显示时触发：fcitx5-rime 的 sync 会结束所有输入会话，正在输入时触发会吞掉未上屏的内容。
 */
object RimeLexiconSync {
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_FILES = 200
    const val CHUNK = 1024 * 1024
    private const val PREFS = "rime_lexicon_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_EXPORT = "last_export_at"
    private const val KEY_OWNER_PREFIX = "owner:"
    private const val KEY_LAST_AUTO_ATTEMPT = "last_auto_attempt_at"
    private const val KEY_AUTO_INTERVAL = "auto_interval_min"
    /** 客户端两次自动词库同步的最小间隔（分钟）；手动「立即同步词库」不受限。用户可在设置里改。 */
    const val DEFAULT_AUTO_INTERVAL_MIN = 60
    const val MIN_AUTO_INTERVAL_MIN = 5
    const val MAX_AUTO_INTERVAL_MIN = 720
    /** 主机自己的快照超过这个时间没导出，就在有客户端来拉清单时顺手导出一次。 */
    private const val HOST_EXPORT_STALE_MS = 15 * 60_000L
    private const val EXPORT_WAIT_MS = 90_000L
    private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,64}")
    private val SAFE_NAME = Regex("[A-Za-z0-9_.+-]{1,96}\\.userdb\\.txt")
    private const val HEADER = "# Rime user dictionary"
    /** 跨设备同步的配置词典：Rime 通用「自定义短语」源文件（非 userdb，静态词典）。
     * 万象/官方方案的 custom_phrase 都从这里编译；随词库快照一起经 lexicon 通道传递。 */
    private const val CUSTOM_PHRASE_CONFIG = "custom_phrase.dict.yaml"

    /** 自动词库同步间隔（毫秒）：默认 60 分钟（与剪贴板长轮询周期一致），用户可自定义。 */
    val AUTO_INTERVAL_MS: Long get() = autoIntervalMin() * 60_000L

    /** 自动词库同步间隔（分钟），夹在 [MIN_AUTO_INTERVAL_MIN]..[MAX_AUTO_INTERVAL_MIN]。 */
    fun autoIntervalMin(): Int {
        val stored = prefs().getInt(KEY_AUTO_INTERVAL, DEFAULT_AUTO_INTERVAL_MIN)
        return stored.coerceIn(MIN_AUTO_INTERVAL_MIN, MAX_AUTO_INTERVAL_MIN)
    }

    /** 设置自动词库同步间隔（分钟）；改小立即生效，改大不会打断已在途的等待。 */
    fun setAutoIntervalMin(minutes: Int) {
        prefs().edit().putInt(KEY_AUTO_INTERVAL, minutes.coerceIn(MIN_AUTO_INTERVAL_MIN, MAX_AUTO_INTERVAL_MIN)).apply()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileLock = Mutex()
    private val shaCache = HashMap<String, Triple<Long, Long, String>>()

    /** 键盘是否正在显示（由 FcitxInputMethodService 维护）。 */
    @Volatile var inputViewActive = false
    /** 收到了新快照但还没能合并（当时正在输入）：键盘收起时补做。 */
    @Volatile private var mergePending = false
    @Volatile private var hostMergeJob: Job? = null

    private fun prefs(context: Context = appContext) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs().getBoolean(KEY_ENABLED, false)
        set(value) { prefs().edit().putBoolean(KEY_ENABLED, value).apply() }
    val lastSyncAt: Long get() = prefs().getLong(KEY_LAST_SYNC, 0L)
    val lastError: String? get() = prefs().getString(KEY_LAST_ERROR, null)

    /** 距上次自动词库同步的毫秒数；跨进程重启也有效（持久化）。 */
    fun msSinceLastAutoAttempt(): Long {
        val last = prefs().getLong(KEY_LAST_AUTO_ATTEMPT, 0L)
        // 从未自动同步过（含刚开启开关）视为「已到期限」，让下一次周期到来时立即同步一次
        return if (last == 0L) AUTO_INTERVAL_MS + 1 else System.currentTimeMillis() - last
    }

    /** 标记一次自动词库同步尝试（成功失败都记，避免进程重启后立即重来）。 */
    fun markAutoAttempt() {
        prefs().edit().putLong(KEY_LAST_AUTO_ATTEMPT, System.currentTimeMillis()).apply()
    }

    /** 重置自动限频（开启开关后调用：让下一次自动同步立即生效）。 */
    fun resetAutoAttempt() {
        prefs().edit().remove(KEY_LAST_AUTO_ATTEMPT).apply()
    }

    fun recordResult(error: String?) {
        prefs().edit().apply {
            if (error == null) { putLong(KEY_LAST_SYNC, System.currentTimeMillis()); remove(KEY_LAST_ERROR) }
            else putString(KEY_LAST_ERROR, error.take(200))
        }.apply()
    }

    // ---------------- 目录与身份 ----------------

    fun rimeDir(context: Context = appContext): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "data"), "rime")

    private fun installationYaml(): File = File(rimeDir(), "installation.yaml")

    private fun yamlValue(key: String): String? = runCatching {
        installationYaml().takeIf { it.isFile && it.length() < 64 * 1024 }?.readLines()
            ?.firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 本机 Rime installation_id；installation.yaml 缺失或损坏时为 null（部署一次 Rime 会重新生成）。 */
    fun installationId(): String? = yamlValue("installation_id")?.takeIf { SAFE_ID.matches(it) }

    fun syncDir(): File {
        val custom = yamlValue("sync_dir")?.let { File(it) }
        return if (custom != null && custom.isAbsolute) custom else File(rimeDir(), "sync")
    }

    /** 点开头是 Rime 自己的隐藏临时目录（如 .temp），不能当设备 id，也不能被下载落盘。 */
    fun isSafeId(id: String) = SAFE_ID.matches(id) && !id.startsWith(".")
    fun isSafeName(name: String) = SAFE_NAME.matches(name) || name == CUSTOM_PHRASE_CONFIG
    private fun isCustomPhraseConfig(name: String) = name == CUSTOM_PHRASE_CONFIG

    /**
     * 同步前的自检：installation.yaml 缺失则触发一次部署让 Rime 生成；
     * 别的设备目录里不是 Rime 快照格式的 *.userdb.txt（历史残留、半截文件）移到 sync_quarantine/，不删除，
     * 否则 Rime 合并时会报错并中断整个同步。
     */
    suspend fun prepare(): String? {
        val me = installationId()
        if (me == null) {
            runRime("deploy")
            return "Rime 尚未生成 installation.yaml，已触发一次部署，稍后会自动重试"
        }
        val dir = syncDir()
        if (!dir.isDirectory && !dir.mkdirs()) return "无法创建 Rime 同步目录"
        fileLock.withLock {
            // Rime 原生 sync 每次都会用 .temp.userdb 做快照恢复验证，但结束后不清理；
            // 残留的 .temp 会被 Rime 当成一个真实词库遍历，打开只读失败报「failed synchronizing N/M」。
            cleanupRimeTemp()
            val stamp = System.currentTimeMillis()
            dir.listFiles().orEmpty().filter { it.isDirectory && it.name != me && !it.name.startsWith(".") }.forEach { sub ->
                sub.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".userdb.txt") }.forEach { f ->
                    if (!hasRimeHeader(f) || !isSafeId(sub.name) || !isSafeName(f.name)) {
                        val q = File(File(File(rimeDir(), "sync_quarantine"), stamp.toString()), sub.name)
                        q.mkdirs()
                        val moved = f.renameTo(File(q, f.name))
                        Timber.w("lexicon: quarantined invalid snapshot %s/%s (moved=%s)", sub.name, f.name, moved)
                    }
                }
            }
            File(dir, ".lexicon-incoming").deleteRecursively()
        }
        return null
    }

    /** 删除 Rime 同步自身产生的临时库残留（librime 会把它当真实词库打开并报错）。 */
    private fun cleanupRimeTemp() {
        runCatching {
            File(rimeDir(), ".temp.userdb").deleteRecursively()
            File(syncDir(), ".temp").deleteRecursively()
        }
    }

    private fun hasRimeHeader(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(64); val n = input.read(buf)
            n > 0 && String(buf, 0, n, Charsets.UTF_8).trimStart('\uFEFF').startsWith(HEADER)
        }
    }.getOrDefault(false)

    /** 静态词典（custom_phrase 等配置源文件）的文件头：`# Rime dictionary`。 */
    private fun hasRimeDictHeader(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(64); val n = input.read(buf)
            n > 0 && String(buf, 0, n, Charsets.UTF_8).trimStart('\uFEFF').startsWith("# Rime dictionary")
        }
    }.getOrDefault(false)

    private fun configAcceptable(name: String, f: File): Boolean =
        if (isCustomPhraseConfig(name)) hasRimeDictHeader(f) else hasRimeHeader(f)

    fun sha256(f: File): String = synchronized(shaCache) {
        val key = f.absolutePath
        shaCache[key]?.let { (len, mtime, sha) -> if (len == f.length() && mtime == f.lastModified()) return sha }
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        shaCache[key] = Triple(f.length(), f.lastModified(), sha)
        sha
    }

    /** sync/ 下所有设备的词库快照（含本机），只列合法路径。 */
    fun listLocal(): List<LexiconFileInfo> {
        val dir = syncDir()
        val out = mutableListOf<LexiconFileInfo>()
        dir.listFiles().orEmpty().filter { it.isDirectory && isSafeId(it.name) }.sortedBy { it.name }.forEach { sub ->
            sub.listFiles().orEmpty()
                .filter { it.isFile && isSafeName(it.name) && it.length() in 1..MAX_FILE_BYTES }
                .sortedBy { it.name }
                .forEach { f -> if (out.size < MAX_FILES) out += LexiconFileInfo(sub.name, f.name, f.length(), sha256(f)) }
        }
        return out
    }

    fun fileFor(owner: String, name: String): File {
        require(isSafeId(owner) && isSafeName(name)) { "非法的词库快照路径" }
        return File(File(syncDir(), owner), name)
    }

    fun parsePath(path: String?): Pair<String, String> {
        val p = path.orEmpty()
        val owner = p.substringBefore('/', ""); val name = p.substringAfter('/', "")
        require(isSafeId(owner) && isSafeName(name) && !name.contains('/')) { "非法的词库快照路径" }
        return owner to name
    }

    fun readChunk(f: File, offset: Long): ByteArray = RandomAccessFile(f, "r").use { raf ->
        require(offset in 0..raf.length()) { "分块偏移越界" }
        val n = minOf(CHUNK.toLong(), raf.length() - offset).toInt()
        ByteArray(n).also { raf.seek(offset); raf.readFully(it) }
    }

    /**
     * 写入一个收到的分块；最后一块写完后校验整份 SHA-256 与 Rime 文件头，再原子替换 sync/<owner>/<name>。
     * 返回 true 表示整份文件已落地。[owner] 必须不是本机 id（调用方已检查）。
     */
    suspend fun acceptChunk(owner: String, name: String, offset: Long, total: Long, sha: String, data: ByteArray, tag: String): Boolean =
        fileLock.withLock {
            require(owner != installationId()) { "不能覆盖本机自己的词库快照" }
            require(total in 1..MAX_FILE_BYTES) { "词库快照大小异常" }
            require(sha.matches(Regex("[0-9a-f]{64}"))) { "词库快照校验值格式错误" }
            val incoming = File(syncDir(), ".lexicon-incoming").apply { mkdirs() }
            val part = File(incoming, "${tag.filter { it.isLetterOrDigit() }.take(40)}-$owner-$name-${sha.take(16)}.part")
            if (offset == 0L) part.delete()
            require(part.length() == offset) { "分块顺序错乱（期望偏移 ${part.length()}），将从头重传" }
            require(offset + data.size <= total) { "分块超出声明大小" }
            java.io.FileOutputStream(part, true).use { it.write(data); it.fd.sync() }
            if (part.length() < total) return@withLock false
            val actual = sha256(part)
            if (actual != sha) { part.delete(); throw IllegalArgumentException("词库快照校验失败（SHA-256 不一致）") }
            if (!configAcceptable(name, part)) { part.delete(); throw IllegalArgumentException("不是有效的 Rime 词典/快照文件") }
            val target = fileFor(owner, name)
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) { part.delete(); throw IllegalStateException("无法写入词库快照") }
            }
            true
        }

    // ---------------- 主机侧 ----------------

    /** 主机上 installation_id 与客户端 deviceId 一一绑定：首次上传即绑定，之后别的设备不能用同一个 id。 */
    fun claimOwner(installId: String, clientId: String): Boolean {
        val key = KEY_OWNER_PREFIX + installId
        val bound = prefs().getString(key, null)
        if (bound == null) { prefs().edit().putString(key, clientId).apply(); return true }
        return bound == clientId
    }

    /** 主机处理 lexicon_* 请求。lexicon_list / lexicon_get 只读不进同步锁；lexicon_put 由调用方在同步锁内调用。 */
    suspend fun handle(req: ClipboardSyncWireRequest, requestId: String): ClipboardSyncWireResponse {
        fun fail(msg: String) = ClipboardSyncWireResponse(false, requestId, req.kind, error = msg)
        if (ClipboardSyncCapabilities.LEXICON_V1 !in ClipboardSyncCapabilities.negotiated(req.capabilities)) return fail("主机未协商 lexicon/1")
        if (!enabled) return fail("主机未开启用户词库同步")
        val me = installationId() ?: run { prepare(); return fail("主机 Rime 尚未初始化，请稍后重试") }
        return when (req.kind) {
            "lexicon_list" -> {
                maybeExportOnHost()
                ClipboardSyncWireResponse(true, requestId, req.kind, lexiconFiles = listLocal(), lexiconOwner = me)
            }
            "lexicon_get" -> {
                val (owner, name) = parsePath(req.lexiconPath)
                val f = fileFor(owner, name)
                if (!f.isFile) return fail("主机上没有这份词库快照")
                val chunk = readChunk(f, req.blobOffset)
                ClipboardSyncWireResponse(true, requestId, req.kind, statusHash = sha256(f), blobTotal = f.length(),
                    blobData = java.util.Base64.getEncoder().encodeToString(chunk))
            }
            "lexicon_put" -> {
                val (owner, name) = parsePath(req.lexiconPath)
                if (owner != req.lexiconOwner) return fail("只能上传本机自己的词库快照")
                if (owner == me) return fail("installation_id 与主机相同，请在其中一台设备上删除 installation.yaml 后重新部署")
                if (!claimOwner(owner, req.clientId)) return fail("该 Rime 设备 id 已绑定到另一台设备，已拒绝")
                val data = java.util.Base64.getDecoder().decode(req.blobData.orEmpty())
                val done = acceptChunk(owner, name, req.blobOffset, req.blobTotal, req.blobHash.orEmpty(), data, req.clientId)
                if (done) scheduleHostMerge()
                ClipboardSyncWireResponse(true, requestId, req.kind)
            }
            else -> fail("未知的词库请求")
        }
    }

    private fun maybeExportOnHost() {
        val last = prefs().getLong(KEY_LAST_EXPORT, 0L)
        if (System.currentTimeMillis() - last < HOST_EXPORT_STALE_MS || inputViewActive) return
        prefs().edit().putLong(KEY_LAST_EXPORT, System.currentTimeMillis()).apply()
        scope.launch { runCatching { mergeAndExport() } }
    }

    /** 主机收到新快照：10 秒内的多份合并成一次，键盘空闲时再交给 Rime。 */
    private fun scheduleHostMerge() {
        if (hostMergeJob?.isActive == true) return
        hostMergeJob = scope.launch {
            delay(10_000)
            if (inputViewActive) { mergePending = true; return@launch }
            runCatching { mergeAndExport() }.onFailure { Timber.w(it, "lexicon host merge failed") }
        }
    }

    // ---------------- 与 Rime 交互 ----------------

    private suspend fun runRime(path: String): Boolean {
        val conn = FcitxDaemon.getFirstConnectionOrNull() ?: return false
        return runCatching { conn.runOnReady { setAddonSubConfig("rime", path) }; true }
            .onFailure { Timber.w(it, "lexicon: rime %s failed", path) }.getOrDefault(false)
    }

    /**
     * 让 Rime 合并 sync/ 下其他设备的快照并导出本机快照，等待导出完成（看本机快照目录的修改时间）。
     * 正在输入时不触发，记下待合并，键盘收起后补做。返回 false 表示这次没有做。
     */
    suspend fun mergeAndExport(): Boolean {
        if (inputViewActive) { mergePending = true; return false }
        val me = installationId() ?: return false
        val mine = File(syncDir(), me)
        val before = newestMtime(mine)
        // 上次 sync 残留的 .temp.userdb 会被 librime 当真实词库遍历并报错，sync 前先清掉
        cleanupRimeTemp()
        // 先用流式拷贝把配置备份写到位：内容一致时 librime 判 up-to-date 跳过 copy_file（绕开 0B 问题）
        me.let { preBackupConfigFiles(rimeDir(), File(syncDir(), it)) }
        if (!runRime("sync")) { mergePending = true; return false }
        mergePending = false
        // custom_phrase 是静态词典（enable_user_dict=false），Rime sync 只合并 userdb 不会动它：
        // 把其他设备 sync/<id>/ 传来的 custom_phrase.dict.yaml 合并进本机 rime 根目录，变化时重新编译。
        val customChanged = mergeCustomPhraseConfig()
        if (customChanged) runRime("deploy")
        prefs().edit().putLong(KEY_LAST_EXPORT, System.currentTimeMillis()).apply()
        val deadline = SystemClock.elapsedRealtime() + EXPORT_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(1_000)
            // 导出完成后再多等 2 秒，等 Rime 写完同一批里的其他词典
            if (newestMtime(mine) > before) { delay(2_000); return true }
        }
        Timber.w("lexicon: rime sync did not update %s within %d ms", mine, EXPORT_WAIT_MS)
        return true
    }

    private fun newestMtime(dir: File): Long =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".userdb.txt") }.maxOfOrNull { it.lastModified() } ?: 0L

    /**
     * 在 Rime sync 之前把配置流式拷到 sync/<id>/，绕开部分内核上 copy_file 留 0B 的问题。
     * 内容一致时 Rime 会判为 up-to-date 并跳过自己的拷贝。
     */
    private fun preBackupConfigFiles(rimeDir: File, backupDir: File) {
        if (!backupDir.isDirectory && !backupDir.mkdirs()) return
        rimeDir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".yaml") || it.name.endsWith(".txt")) }
            .forEach { src ->
                val dst = File(backupDir, src.name)
                if (dst.isFile && dst.length() == src.length() && dst.lastModified() == src.lastModified()) return@forEach
                val tmp = File(backupDir, ".${src.name}.meng-tmp")
                runCatching {
                    src.inputStream().use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.fd.sync()
                        }
                    }
                    if (tmp.length() != src.length()) error("拷贝长度不一致")
                    tmp.setLastModified(src.lastModified())
                    if (!tmp.renameTo(dst)) {
                        dst.delete()
                        if (!tmp.renameTo(dst)) error("改名失败")
                    }
                }.onFailure {
                    tmp.delete()
                    Timber.w(it, "pre-backup ${src.name} failed")
                }
            }
    }

    /**
     * 把其他设备 sync/<id>/ 传来的 custom_phrase.dict.yaml 合并进本机 rime 根目录。
     * custom_phrase 是静态词典（enable_user_dict=false），Rime sync 原生只合并 userdb 不碰它，
     * 这里只能应用层合并：以本机现有文件为基础（保留原有词条与顺序），把各设备文件里
     * 「本机还没有的 词+编码」词条追加进去（按词+编码去重），变化时返回 true（调用方随后 deploy 重新编译）。
     * 本机自己的 sync/<me>/ 是上游导出，不参与合并。
     */
    private fun mergeCustomPhraseConfig(): Boolean {
        val me = installationId() ?: return false
        val target = File(rimeDir(), CUSTOM_PHRASE_CONFIG)
        if (!target.isFile) return false
        val baseLines = target.readLines()
        // 词条只统计 YAML 文档分隔符（---...---）之后、且含 Tab 编码的行；头注释保持原样。
        val bodyStart = baseLines.indexOfFirst { it.trim() == "---" }
        val bodyFrom = (bodyStart + 1).coerceAtLeast(0)
        fun isEntry(line: String) = line.isNotBlank() && !line.trimStart().startsWith('#') && line.split('\t').size >= 2

        // 以本机现有词条为基线集合
        val seen = LinkedHashSet<String>()
        baseLines.drop(bodyFrom).filter { isEntry(it) }.forEach { seen += it.trim() }
        val appended = mutableListOf<String>()
        syncDir().listFiles().orEmpty()
            .filter { it.isDirectory && isSafeId(it.name) && it.name != me }
            .forEach { sub ->
                val src = File(sub, CUSTOM_PHRASE_CONFIG)
                if (!src.isFile || !hasRimeDictHeader(src)) return@forEach
                val sBody = src.readLines().drop(
                    src.readLines().indexOfFirst { it.trim() == "---" }.let { if (it < 0) 0 else it + 1 })
                sBody.forEach { l ->
                    if (!isEntry(l)) return@forEach
                    val t = l.trim()
                    if (seen.add(t)) appended += t // 新词，追加
                }
            }
        if (appended.isEmpty()) return false

        val finalLines = baseLines.toMutableList().apply {
            if (lastOrNull()?.isNotBlank() == true) add("")
            addAll(appended)
        }
        val tmp = File(rimeDir(), ".${CUSTOM_PHRASE_CONFIG}.meng-sync-tmp")
        var ok = false
        runCatching {
            tmp.writeText(finalLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("改名失败")
            }
            ok = true
        }.onFailure {
            Timber.w(it, "lexicon: merge custom_phrase config failed")
        }.also { tmp.delete() }
        if (ok) Timber.i("lexicon: merged custom_phrase, appended %d entries from other devices", appended.size)
        return ok
    }

    /** 键盘收起：有待合并的快照就补做（延迟 3 秒，避免切换输入框时的瞬时收起）。 */
    fun onInputIdle() {
        if (!mergePending || !enabled) return
        scope.launch {
            delay(3_000)
            if (!inputViewActive && mergePending) runCatching { mergeAndExport() }
        }
    }

    fun requestMergeWhenIdle() { mergePending = true; if (!inputViewActive) onInputIdle() }
}
