/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.backup

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element
import kotlinx.serialization.json.*

internal object BackupValidation {
    /** Packages produced by this app and its known upstream/debug identities only. */
    fun isCompatiblePackage(source: String, target: String): Boolean {
        val known = setOf("com.meng.ime", "com.meng.ime.debug", "org.fcitx.fcitx5.android", "org.fcitx.fcitx5.android.debug")
        return source in known && target in known
    }

    fun fullBackupComponents(root: File, formatVersion: Int, declared: List<String>?): List<String> {
        val legacy = listOf("shared_prefs", "databases", "external", "recently_used")
        val version2 = legacy + listOf("clipboard_images", "clipboard_oplog")
        val version3 = version2 + "manual_corrections"
        val known = when (formatVersion) { 2 -> version2; 3 -> version3; else -> legacy }
        require(formatVersion in 1..3) { "不支持的完整备份格式：$formatVersion" }
        val components = if (formatVersion == 1) {
            require(declared == null || declared.toSet() == legacy.toSet()) { "旧版完整备份组件声明不完整" }
            legacy.take(3).forEach { name -> require(File(root, name).isDirectory) { "完整备份缺少 $name；剪贴板专用备份请使用“导入剪切板”" } }
            File(root, "recently_used").apply { require(isDirectory || mkdirs()) { "无法准备最近使用记录目录" } }
            legacy
        } else {
            require(declared != null && declared.size == known.size && declared.toSet() == known.toSet()) { "新版完整备份必须包含全部设置、数据库、图片、同步历史和手动纠错规则组件" }
            declared
        }
        require(components.distinct().size == components.size) { "备份组件重复" }
        val allowed = components.toSet() + "metadata.json"
        root.listFiles().orEmpty().forEach { require(it.name in allowed) { "备份包含未声明的组件：${it.name}" } }
        components.forEach { name ->
            val file = File(root, name)
            require(if (name == "clipboard_oplog") file.isFile else file.isDirectory) { "备份组件缺失或类型错误：$name" }
        }
        return components
    }

    fun manualRulesFile(root: File): File {
        val directory = File(root, "manual_corrections")
        require(directory.isDirectory) { "完整备份缺少手动纠错规则目录" }
        val entries = directory.listFiles()?.toList() ?: error("无法读取手动纠错规则目录")
        require(entries.size == 1 && entries.single().isFile && entries.single().name == "manual-corrections.json") {
            "手动纠错规则目录包含未知文件"
        }
        return entries.single()
    }

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

    /** Pure path validation used by both archive restore and JVM tests. */
    fun clipboardImageName(path: String): String {
        val normalized = path.replace('\\', '/')
        val name = normalized.substringAfterLast('/')
        require('\u0000' !in normalized && normalized.split('/').none { it == ".." || it == "." } &&
            name.isNotBlank() && name != "." && name != ".." &&
            name.split('/').none { it == ".." || it == "." || it.isBlank() }) { "Invalid clipboard image path" }
        return name
    }

    fun remapClipboardPath(path: String, imageSource: File, finalImageDirectory: File): String =
        remapClipboardPath(path, listOf(imageSource), finalImageDirectory)

    fun remapClipboardPath(path: String, imageSources: List<File>, finalImageDirectory: File): String {
        val name = clipboardImageName(path)
        require(imageSources.any { source ->
            val root = source.canonicalFile
            val imported = File(root, name).canonicalFile
            imported.isFile && imported.parentFile == root
        }) { "Missing imported clipboard image: $name" }
        return File(finalImageDirectory, name).absolutePath
    }

    fun remapOplogImagePaths(file: File, imageSource: File, finalImageDirectory: File) =
        remapOplogImagePaths(file, listOf(imageSource), finalImageDirectory)

    fun coalesceClipboardImages(imageSources: List<File>, target: File) {
        if (imageSources.isEmpty()) return
        require(target.isDirectory || target.mkdirs()) { "Cannot stage clipboard images" }
        fun hash(file: File): String = file.inputStream().use { source ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = source.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        imageSources.forEach { source ->
            source.listFiles().orEmpty().forEach { file ->
                require(file.isFile) { "Nested clipboard image directories are unsupported" }
                val dest = File(target, file.name)
                if (file.canonicalFile != dest.canonicalFile) {
                    if (dest.exists()) require(dest.isFile && hash(file) == hash(dest)) { "Conflicting clipboard images: ${file.name}" }
                    else file.copyTo(dest)
                }
            }
        }
    }

    fun remapOplogImagePaths(file: File, imageSources: List<File>, finalImageDirectory: File) {
        if (!file.isFile) return
        val temporary = File(file.parentFile, file.name + ".remapping")
        try {
            temporary.bufferedWriter().use { out -> file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) out.newLine() else {
                        fun rewrite(element: JsonElement): JsonElement = when (element) {
                            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                                if (key == "imagePath" && value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
                                    val mapped = runCatching { remapClipboardPath(value.content, imageSources, finalImageDirectory) }
                                    if (mapped.isSuccess) JsonPrimitive(mapped.getOrThrow())
                                    else if ((element["imageData"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) JsonNull
                                    else throw mapped.exceptionOrNull()!!
                                } else rewrite(value)
                            })
                            is JsonArray -> JsonArray(element.map(::rewrite))
                            else -> element
                        }
                        out.write(Json.encodeToString(rewrite(Json.parseToJsonElement(line)))); out.newLine()
                    }
                }
            } }
            check(temporary.renameTo(file)) { "Cannot remap clipboard history" }
        } finally { temporary.delete() }
    }
}
