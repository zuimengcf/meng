/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.backup

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element
import kotlinx.serialization.json.*

internal object BackupValidation {
    /** 凭据通过 UserDataManager 的可移植表示导出，不再从设置备份中排除。 */
    fun protectedPrefs(name: String): Boolean = false

    fun preferencesXml(text: String) {
        require('\u0000' !in text && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            "Backup preference XML must not contain DOCTYPE, ENTITY or NUL"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            // Android parsers differ in supported feature names. The precheck and
            // StringReader above are mandatory even where these hardening flags exist.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
        require(root.tagName == "map") { "Preference XML root must be map" }
        val keys = HashSet<String>()
        for (i in 0 until root.childNodes.length) {
            val e = root.childNodes.item(i) as? Element ?: continue
            require(e.hasAttribute("name") && keys.add(e.getAttribute("name"))) { "Missing or duplicate preference name" }
            when (e.tagName) {
                "string" -> Unit
                "int" -> require(e.getAttribute("value").toIntOrNull() != null)
                "long" -> require(e.getAttribute("value").toLongOrNull() != null)
                "float" -> require(e.getAttribute("value").toFloatOrNull()?.isFinite() == true)
                "boolean" -> require(e.getAttribute("value") in setOf("true", "false"))
                "set" -> for (j in 0 until e.childNodes.length) {
                    val member = e.childNodes.item(j) as? Element ?: continue
                    require(member.tagName == "string") { "Invalid preference set member" }
                }
                else -> error("Unsupported preference type: ${e.tagName}")
            }
        }
    }

    fun mapDefaultPreferences(dir: File, sourcePackage: String, targetPackage: String) {
        val own = File(dir, "${targetPackage}_preferences.xml")
        if (own.isFile) return
        val foreign = File(dir, "${sourcePackage}_preferences.xml")
        if (foreign.isFile) foreign.copyTo(own, overwrite = true)
    }

    fun remapThemePaths(themeSource: File, finalThemeDirectory: File) {
        if (!themeSource.isDirectory) return
        val json = Json
        themeSource.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: error("Invalid theme JSON: ${file.name}")
            val image = root["backgroundImage"]
            if (image == null || image is JsonNull) return@forEach
            require(image is JsonObject) { "Invalid backgroundImage: ${file.name}" }
            val updated = image.toMutableMap()
            listOf("croppedFilePath", "srcFilePath").forEach { key ->
                val value = image[key] as? JsonPrimitive ?: error("Missing theme image path: $key")
                require(value.isString && value.content.isNotBlank())
                val normalized = value.content.replace('\\', '/')
                val marker = "/theme/"
                val rel = if (marker in normalized) normalized.substringAfterLast(marker) else normalized.substringAfterLast('/')
                require(rel.isNotBlank() && rel.split('/').none { it == ".." || it == "." || it.isBlank() })
                val stagedImage = File(themeSource, rel).canonicalFile
                require(stagedImage.path.startsWith(themeSource.canonicalPath + File.separator) && stagedImage.isFile) {
                    "Missing imported theme image: $rel"
                }
                // Never persist a temporary staging path which will disappear after restore.
                updated[key] = JsonPrimitive(File(finalThemeDirectory, rel).absolutePath)
            }
            file.writeText(json.encodeToString(JsonObject(root + ("backgroundImage" to JsonObject(updated)))))
        }
    }
}
