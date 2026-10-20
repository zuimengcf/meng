/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.provider

import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.fcitx.fcitx5.android.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class FcitxDataProvider : DocumentsProvider() {

    companion object {
        private const val MIME_TYPE_WILDCARD = "*/*"
        private const val MIME_TYPE_TEXT = "text/plain"
        private const val MIME_TYPE_BIN = "application/octet-stream"

        private val TEXT_EXTENSIONS = arrayOf(
            "conf",
            "mb",
            "lua",
            "yml",
            "yaml"
        )

        // path relative to baseDir that should be recognize as text files
        private val TEXT_FILES = arrayOf(
            "config/config",
            "config/profile",
            "data/punctuation/punc.mb.zh_CN",
            "data/punctuation/punc.mb.zh_HK",
            "data/punctuation/punc.mb.zh_TW"
        )

        // The default columns to return information about a root if no specific
        // columns are requested in a query.
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        // The default columns to return information about a document if no specific
        // columns are requested in a query.
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )

        private const val SEARCH_RESULTS_LIMIT = 50
    }

    private lateinit var baseDir: File
    private lateinit var docIdPrefix: String
    private lateinit var textFilePaths: Array<String>

    private val File.docId
        get() = absolutePath.removePrefix(docIdPrefix)

    /** Resolve a document id without allowing `..`, absolute paths, or symlinks out of the root. */
    @Throws(FileNotFoundException::class)
    private fun fileFromDocId(docId: String): File {
        return DocumentPathPolicy.resolve(baseDir, File(docIdPrefix), docId)
    }

    private fun File.isWithin(root: File): Boolean {
        val rootPath = root.canonicalPath
        val path = canonicalPath
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    override fun onCreate(): Boolean {
        baseDir = (context!!.getExternalFilesDir(null) ?: return false).canonicalFile
        docIdPrefix = "${baseDir.parent}${File.separator}"
        textFilePaths = Array(TEXT_FILES.size) { baseDir.resolve(TEXT_FILES[it]).absolutePath }
        return true
    }

    override fun queryRoots(projection: Array<String>?) =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, baseDir.docId)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(Root.COLUMN_ICON, R.mipmap.app_icon)
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_DOCUMENT_ID, baseDir.docId)
                add(Root.COLUMN_MIME_TYPES, MIME_TYPE_WILDCARD)
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?) =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            newRowFromFile(fileFromDocId(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ) = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        fileFromDocId(parentDocumentId).listFiles()?.forEach {
            newRowFromFile(it)
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            fileFromDocId(documentId),
            ParcelFileDescriptor.parseMode(mode)
        )
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = fileFromDocId(documentId)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val newFile = createAbstractFile(parentDocumentId, displayName)
        try {
            val ok = if (mimeType == Document.MIME_TYPE_DIR) {
                newFile.mkdir()
            } else {
                newFile.createNewFile()
            }
            if (!ok) {
                throw FileNotFoundException("createDocument id=${newFile.path} failed")
            }
        } catch (e: IOException) {
            throw FileNotFoundException("createDocument id=${newFile.path} failed: ${e.message}")
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        fileFromDocId(documentId).apply {
            if (canonicalFile == baseDir) throw FileNotFoundException("Cannot delete provider root")
            DocumentPathPolicy.validateTree(baseDir, this)
            val ok = if (isDirectory) {
                deleteRecursively()
            } else {
                delete()
            }
            if (!ok) {
                throw FileNotFoundException("deleteDocument id=$documentId failed")
            }
        }
    }

    override fun getDocumentType(documentId: String): String {
        return fileFromDocId(documentId).mimeType
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            val parent = fileFromDocId(parentDocumentId).canonicalFile
            val child = fileFromDocId(documentId).canonicalFile
            child != parent && child.isWithin(parent)
        } catch (_: FileNotFoundException) {
            false
        }
    }

    @Throws(FileNotFoundException::class)
    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val oldFile = fileFromDocId(sourceDocumentId)
        val newFile = createAbstractFile(targetParentDocumentId, oldFile.name)
        DocumentPathPolicy.validateTree(baseDir, oldFile)
        oldFile.apply {
            try {
                val ok = if (isDirectory) {
                    copyRecursively(newFile)
                } else {
                    copyTo(newFile).exists()
                }
                if (!ok) {
                    throw FileNotFoundException("copyDocument id=$sourceDocumentId to ${newFile.docId} failed")
                }
            } catch (e: Exception) {
                throw FileNotFoundException("copyDocument id=$sourceDocumentId to ${newFile.docId} failed: ${e.message}")
            }
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun renameDocument(documentId: String, displayName: String): String {
        val oldFile = fileFromDocId(documentId)
        if (oldFile.canonicalFile == baseDir) throw FileNotFoundException("Cannot rename provider root")
        requireSafeName(displayName)
        val newFile = oldFile.resolveSibling(displayName)
        if (newFile.exists() || !newFile.canonicalFile.isWithin(baseDir)) {
            throw FileNotFoundException("renameDocument id=$documentId to $displayName failed: invalid target")
        }
        if (!oldFile.renameTo(newFile)) {
            throw FileNotFoundException("renameDocument id=$documentId to $displayName failed")
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        val oldFile = fileFromDocId(sourceDocumentId)
        val newFile = createAbstractFile(targetParentDocumentId, oldFile.name)
        if (oldFile.canonicalFile == baseDir) throw FileNotFoundException("Cannot move provider root")
        if (!oldFile.renameTo(newFile)) {
            throw FileNotFoundException("moveDocument id=$sourceDocumentId failed")
        }
        return newFile.docId
    }

    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?
    ) = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        val q = query.lowercase()
        fileFromDocId(rootId).walkTopDown().onEnter {
            runCatching { DocumentPathPolicy.validate(baseDir, it) }.isSuccess
        }
            .filter { runCatching { DocumentPathPolicy.validate(baseDir, it) }.isSuccess }
            .filter { it.name.lowercase().contains(q) }
            .take(SEARCH_RESULTS_LIMIT)
            .forEach { newRowFromFile(it) }
    }

    private val File.mimeType: String
        get() = when {
            isDirectory -> Document.MIME_TYPE_DIR
            TEXT_EXTENSIONS.contains(extension) -> MIME_TYPE_TEXT
            textFilePaths.contains(absolutePath) -> MIME_TYPE_TEXT
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: MIME_TYPE_BIN
        }

    private fun createAbstractFile(parentDocumentId: String, displayName: String): File {
        requireSafeName(displayName)
        val parent = fileFromDocId(parentDocumentId)
        if (!parent.isDirectory || !parent.canonicalFile.isWithin(baseDir)) {
            throw FileNotFoundException("Invalid parent document: $parentDocumentId")
        }
        var newFile = parent.resolve(displayName)
        var noConflictId = 2
        while (newFile.exists()) {
            newFile = parent.resolve("$displayName ($noConflictId)")
            noConflictId += 1
        }
        if (!newFile.canonicalFile.isWithin(baseDir)) {
            throw FileNotFoundException("Invalid document name: $displayName")
        }
        return newFile
    }

    private fun requireSafeName(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
            throw FileNotFoundException("Invalid document name")
        }
    }

    @Throws(FileNotFoundException::class)
    private fun MatrixCursor.newRowFromFile(file: File) {
        if (!file.exists() || !file.canonicalFile.isWithin(baseDir)) {
            throw FileNotFoundException("File(path=${file.absolutePath}) not found")
        }

        val mimeType = file.mimeType
        var flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Document.FLAG_SUPPORTS_COPY else 0
        if (file.canWrite()) {
            flags = flags or if (file.isDirectory) {
                Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                Document.FLAG_SUPPORTS_WRITE
            }
        }
        if (file.parentFile?.canWrite() == true) {
            flags = flags or
                    Document.FLAG_SUPPORTS_DELETE or
                    Document.FLAG_SUPPORTS_RENAME
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags or Document.FLAG_SUPPORTS_MOVE
            }
        }
        if (mimeType.startsWith("image/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.docId)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, file.length())
        }
    }
}
