/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import java.net.URI

/** Shared validation for room forms. Never persist an unvalidated address or password. */
object ClipboardRoomForm {
    const val DEFAULT_PORT = 18523

    fun port(text: String): Int {
        val value = text.trim().toIntOrNull()
        require(value != null && value in 1024..65535) { "端口需为 1024–65535 的整数" }
        return value
    }

    fun password(text: String): String {
        require(text.length >= 8 && text.isNotBlank()) { "密码至少 8 个字符，且不能全部为空白" }
        require(!text.contains('\u001f')) { "密码不能包含控制分隔符" }
        return text
    }

    fun address(text: String): String {
        val raw = text.trim()
        require(raw.isNotEmpty()) { "请输入房间地址" }
        val uri = try { URI(if (raw.contains("://")) raw else "http://$raw") }
        catch (_: Exception) { throw IllegalArgumentException("地址格式不正确，请输入 IP:端口或主机名:端口") }
        require(uri.scheme == "http" || uri.scheme == "https") { "地址仅支持 http 或 https" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) { "只填写房间地址，不要包含密码、路径或参数" }
        require(uri.rawAuthority?.endsWith(":") != true) { "冒号后需填写端口" }
        val port = if (uri.port == -1) DEFAULT_PORT else uri.port
        require(port in 1..65535) { "地址中的端口需为 1–65535" }
        return URI(uri.scheme, null, uri.host.lowercase(), port, null, null, null).toASCIIString()
    }
}
