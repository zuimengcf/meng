/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * 文字转语音直发：把输入框文字用系统 TTS 合成音频文件，
 * 再经 commitContent 直接把音频提交给目标应用（如微信发语音）。
 *
 * 链路：readCurrentEditorTextForPlugin() → TextToSpeech.synthesizeToFile
 *       → FileProvider content URI → InputContentInfo → commitContent
 */
package org.fcitx.fcitx5.android.input

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.plugin.PluginHost
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object TtsAudioCommit {

    /** 合成超时：长文本由分段上限保护，超时兜底防 hang */
    private const val TIMEOUT_MS = 30_000L

    /** 单次合成文本上限（字）：超长文本截断，避免 TTS 引擎卡死或超时 */
    private const val MAX_CHARS = 800

    /** 语速默认值：可被 tts-voice 插件设置覆盖 */
    private const val DEFAULT_RATE = 0.7f

    /** 音调默认值：引擎默认 1.0，可被 tts-voice 插件设置覆盖 */
    private const val DEFAULT_PITCH = 1.0f

    /** 从 tts-voice 插件设置读语速/音调（插件未装或值非法时用默认） */
    private fun loadVoiceParams(): Pair<Float, Float> {
        val rate = PluginHost.settingOf("tts-voice", "rate", DEFAULT_RATE.toString())
            .toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: DEFAULT_RATE
        val pitch = PluginHost.settingOf("tts-voice", "pitch", DEFAULT_PITCH.toString())
            .toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: DEFAULT_PITCH
        return rate to pitch
    }

    /** 输出格式扩展名：tts-voice 插件设置 oggExt（"true"→ogg 伪装，默认 wav 原样） */
    private fun loadOutputExt(): String =
        if (PluginHost.settingOf("tts-voice", "oggExt", "false") == "true") "ogg" else "wav"

    private var tts: TextToSpeech? = null

    /** TTS 初始化状态：0=未开始 1=成功 -1=失败 */
    private val inited = AtomicInteger(0)

    @Volatile
    private var initListenerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 懒初始化系统 TTS 引擎（结果回调到 [onInited]，null 表示失败） */
    fun ensureTts(context: Context, onInited: ((Boolean) -> Unit)? = null) {
        if (tts != null || inited.get() == 1) {
            onInited?.invoke(true)
            return
        }
        // 防重复初始化：多按钮连点时只初始化一次
        if (!initListenerInstalled.compareAndSet(false, true)) {
            onInited?.invoke(inited.get() == 1)
            return
        }
        val t = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            android.util.Log.i("MengTts", "TTS init status=$status ok=$ok")
            inited.set(if (ok) 1 else -1)
            if (!ok) {
                tts?.shutdown()
                tts = null
            }
            onInited?.invoke(ok)
        }
        tts = t
    }

    /**
     * 把 [text] 合成为音频文件并直接提交给当前输入框。
     * 返回 false 表示目标编辑器拒绝音频（现场 commitContent 失败），由调用方提示。
     * 不做前置 mime 拦截：聊天文字框里合成照常进行，提交成败由目标现场决定。
     */
    fun commit(text: String, service: FcitxInputMethodService): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // 惰性初始化 TTS：首次点按需要等引擎就绪，就绪后再合成+提交
        ensureTts(service) { ok ->
            if (ok) {
                synthesizeThenCommit(trimmed.take(MAX_CHARS), service)
            } else {
                Toast.makeText(service, R.string.tts_init_failed, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    /** 合成到 cacheDir/tts_audio/（FileProvider 暴露路径，见 res/xml/filepaths.xml） */
    private fun synthesizeThenCommit(text: String, service: FcitxInputMethodService) {
        val dir = File(service.cacheDir, "tts_audio").apply { mkdirs() }
        val file = File(dir, "tts_${System.currentTimeMillis()}.wav")
        val t = tts ?: run {
            Toast.makeText(service, R.string.tts_init_failed, Toast.LENGTH_SHORT).show()
            return
        }
        t.language = java.util.Locale.CHINA
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UUID.randomUUID().toString())
        }
        // 超时兜底：合成不回调时放弃提交并清理临时文件
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeoutRunnable = Runnable {
            if (timedOut.compareAndSet(false, true)) {
                file.delete()
                Toast.makeText(service, R.string.tts_synth_timeout, Toast.LENGTH_SHORT).show()
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                android.util.Log.i("MengTts", "onStart")
            }
            override fun onDone(utteranceId: String?) {
                android.util.Log.i("MengTts", "onDone size=${file.length()}")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                if (timedOut.get()) { file.delete(); return }
                // onDone 在合成线程回调，提交回主线程
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    // 输出格式：tts-voice 插件设置 oggExt=true 时改扩展名 .wav → .ogg（内容仍为
                    // 引擎输出的 WAV 数据，仅扩展名/MIME 对齐接收方；插件关/未装则原样 wav）。
                    val ext = loadOutputExt()
                    val target: File
                    val mime: String
                    if (ext == "ogg") {
                        val oggFile = File(file.parentFile, file.nameWithoutExtension + ".ogg")
                        val renamed = runCatching { file.renameTo(oggFile) }.getOrDefault(false)
                        target = if (renamed && oggFile.exists()) oggFile else file
                        mime = if (target === oggFile) "audio/ogg" else "audio/wav"
                    } else {
                        target = file
                        mime = "audio/wav"
                    }
                    val ok = target.length() > 0 && service.commitAudio(target.absolutePath, mime)
                    android.util.Log.i("MengTts", "commitAudio ok=$ok size=${target.length()} mime=$mime")
                    if (!ok) {
                        // 兜底：当前输入框不支持直插音频 → 按 tts-voice 插件设置决定是否弹分享
                        val shareFallback = PluginHost.settingOf("tts-voice", "shareFallback", "true") == "true"
                        if (!shareFallback) {
                            target.delete()
                            Toast.makeText(service, R.string.tts_target_unsupported, Toast.LENGTH_SHORT).show()
                        } else {
                            shareAudio(service, target, mime)
                        }
                    } else {
                        Toast.makeText(service, R.string.tts_audio_sent, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                android.util.Log.w("MengTts", "onError(legacy)")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                file.delete()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(service, R.string.tts_synth_failed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                android.util.Log.w("MengTts", "onError code=$errorCode")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                file.delete()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(service, R.string.tts_synth_failed, Toast.LENGTH_SHORT).show()
                }
            }
        })
        val langResult = t.setLanguage(java.util.Locale.CHINA)
        android.util.Log.i("MengTts", "setLanguage(CHINA)=$langResult")
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            // 中文语音数据缺失：回退英文，至少能出声而不是静默失败
            val fallback = t.setLanguage(java.util.Locale.US)
            android.util.Log.i("MengTts", "setLanguage(US)=$fallback")
        }
        // 语速/音调：从 tts-voice 插件设置读取（未装插件用默认）
        val (rate, pitch) = loadVoiceParams()
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        android.util.Log.i("MengTts", "rate=$rate pitch=$pitch")
        val r = t.synthesizeToFile(text, params, file, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
        android.util.Log.i("MengTts", "synthesizeToFile r=$r file=${file.path}")
        if (r != TextToSpeech.SUCCESS) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            file.delete()
            Toast.makeText(service, R.string.tts_init_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 分享兜底：commitContent 直插失败时，用系统分享面板交出音频文件。
     * 用户可在分享面板里选目标应用（如微信）以文件形式发送。
     * 文件留给接收方读取，分享完成与否不再追踪（临时文件系统会回收）。
     */
    private fun shareAudio(service: FcitxInputMethodService, file: File, mime: String) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                service, "${service.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = android.content.Intent.createChooser(intent, file.name).apply {
                // IME 是 Service context：必须 NEW_TASK，否则 AndroidRuntimeException
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(chooser)
        } catch (e: Exception) {
            // 分享面板都拉不起（极端情况）：清理文件并退回原提示
            android.util.Log.e("MengTts", "shareAudio failed", e)
            file.delete()
            Toast.makeText(service, R.string.tts_target_unsupported, Toast.LENGTH_SHORT).show()
        }
    }
}
