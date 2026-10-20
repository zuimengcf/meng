/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.provider

import java.io.File
import java.io.FileNotFoundException

/** Fail closed on traversal and symlinks, including nested recursive copy/delete entries. */
internal object DocumentPathPolicy {
    fun resolve(root: File, prefix: File, documentId: String): File {
        if (documentId.startsWith('/') || documentId.contains('\\') ||
            documentId.split('/').any { it == ".." || it == "." } || documentId.contains('\u0000')) {
            throw FileNotFoundException("Invalid document id")
        }
        val candidate = File(prefix, documentId).absoluteFile
        validate(root, candidate)
        return candidate
    }
    fun validate(root: File, candidate: File) {
        val base = root.canonicalFile
        val canonical = candidate.canonicalFile
        if (canonical != candidate.absoluteFile ||
            (canonical != base && !canonical.path.startsWith(base.path + File.separator))) {
            throw FileNotFoundException("Document is outside root or contains a symbolic link")
        }
    }
    fun validateTree(root: File, source: File) {
        source.walkTopDown().onEnter { validate(root, it); true }.forEach { validate(root, it) }
    }
}
