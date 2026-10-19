/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Xml
import org.fcitx.fcitx5.android.utils.appContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.io.StringWriter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 加密保存同步配置；每个 prefs key 都作为 GCM AAD，防止密文被替换到另一配置项。 */
object ClipboardSyncSettings {
    private const val PREFS = "clipboard_sync_secure"
    private const val KEY_ALIAS = "clipboard-sync-password-v1"
    private const val SESSION = "session"
    private const val DEFAULT_PASSWORD = "default_password"
    private const val HOST_PASSWORD_PREFIX = "host_password:"
    private const val SEPARATOR = "\u001f"
    private val keyLock = Any()
    data class Session(val mode: String, val host: String, val port: Int, val password: String)
    private fun prefs(context: Context = appContext) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false).build())
            generator.generateKey()
        }
    }

    private fun encrypt(value: String, aad: String): String = synchronized(keyLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String, aad: String): String = synchronized(keyLock) {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "invalid encrypted clipboard sync setting" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
    private fun putEncrypted(prefKey: String, value: String): Boolean = prefs().edit().putString(prefKey, encrypt(value, prefKey)).commit()
    private fun readEncrypted(prefKey: String): String? = prefs().getString(prefKey, null)?.let { runCatching { decrypt(it, prefKey) }.getOrNull() }

    /** Keystore 密文绑定本机密钥，备份必须导出明文并在导入安装重新加密。 */
    fun exportPortablePreferences(): String = synchronized(keyLock) {
        val snapshot = prefs().all
        val values = snapshot.keys.sorted().map { name ->
            val encrypted = snapshot[name] as? String ?: error("Invalid room credential type: $name")
            name to decrypt(encrypted, name)
        }
        val writer = StringWriter()
        Xml.newSerializer().apply {
            setOutput(writer); startDocument("UTF-8", true)
            startTag(null, "map"); attribute(null, "portable", "1"); attribute(null, "encoding", "base64")
            values.forEach { (name, value) ->
                startTag(null, "string"); attribute(null, "name", name)
                // Session values contain U+001F, which is not legal XML text.
                text(Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
                endTag(null, "string")
            }
            endTag(null, "map"); endDocument()
        }
        writer.toString()
    }

    /** 将可移植凭据转换为当前安装可解密的 SharedPreferences XML。 */
    fun reencryptPortablePreferences(xml: String): String = synchronized(keyLock) {
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        require(parser.nextTag() == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "map") { "Invalid portable credentials" }
        require(parser.getAttributeValue(null, "portable") == "1") { "Credentials are not portable" }
        require(parser.getAttributeValue(null, "encoding") == "base64") { "Invalid credential encoding" }
        val values = linkedMapOf<String, String>()
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_TAG) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            require(parser.name == "string") { "Invalid portable credential type" }
            val name = parser.getAttributeValue(null, "name") ?: error("Missing credential name")
            val value = String(Base64.decode(parser.nextText(), Base64.NO_WRAP), StandardCharsets.UTF_8)
            require(values.put(name, value) == null) { "Duplicate credential name" }
        }
        val writer = StringWriter()
        Xml.newSerializer().apply {
            setOutput(writer); startDocument("UTF-8", true); startTag(null, "map")
            values.forEach { (name, value) -> startTag(null, "string"); attribute(null, "name", name); text(encrypt(value, name)); endTag(null, "string") }
            endTag(null, "map"); endDocument()
        }
        writer.toString()
    }

    fun saveDefaultPassword(password: String) {
        check(password.length >= 8) { "同步密码至少需要 8 个字符" }
        check(putEncrypted(DEFAULT_PASSWORD, password)) { "无法保存同步密码" }
    }
    fun getDefaultPassword(): String? = readEncrypted(DEFAULT_PASSWORD)

    fun saveHost(port: Int, password: String) {
        check(password.length >= 8) { "同步密码至少需要 8 个字符" }
        check(putEncrypted(SESSION, listOf("host", "", port.toString(), password).joinToString(SEPARATOR))) { "无法保存同步会话" }
    }
    fun saveClient(host: String, port: Int, password: String) {
        check(host.isNotBlank()) { "同步房间地址不能为空" }
        check(password.length >= 8) { "同步密码至少需要 8 个字符" }
        val hostKey = HOST_PASSWORD_PREFIX + host.trimEnd('/')
        val editor = prefs().edit().putString(SESSION, encrypt(listOf("client", host, port.toString(), password).joinToString(SEPARATOR), SESSION))
            .putString(hostKey, encrypt(password, hostKey))
        check(editor.commit()) { "无法保存同步会话" }
    }
    fun rememberedPassword(host: String): String? = readEncrypted(HOST_PASSWORD_PREFIX + host.trimEnd('/'))
    fun readSession(): Session? = readEncrypted(SESSION)?.split(SEPARATOR, limit = 4)?.let { parts ->
        if (parts.size == 4) Session(parts[0], parts[1], parts[2].toIntOrNull() ?: 0, parts[3]) else null
    }
    /** 退出只清理当前运行模式，保留默认密码和按主机记忆密码。 */
    fun clearMode() { check(prefs().edit().remove(SESSION).commit()) { "无法清理同步会话" } }
}
