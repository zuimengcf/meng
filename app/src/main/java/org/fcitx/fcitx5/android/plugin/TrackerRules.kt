/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

/**
 * URL 追踪参数清理规则（数据驱动，与插件实现解耦）。
 *
 * 内置插件与外部 .meng 数据插件共用此解析与匹配逻辑。
 * JSON 解析手写以避免拉依赖。
 *
 * 三级规则：
 * 1. paramBlacklist 全局字面量（如 fbclid/gclid）
 * 2. paramPatterns 全局通配（如 utm_.*）
 * 3. domainRules 域名级字面量（如 x.com 专属 a=，避免单字母误伤其他站点）
 */
data class TrackerRules(
    val paramBlacklist: Set<String>,
    val paramPatterns: List<Regex>,
    val domainRules: Map<String, Set<String>> = emptyMap(),
) {
    fun clean(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val base = url.substring(0, qIdx)
        val query = url.substring(qIdx + 1)
        val fragIdx = query.indexOf('#')
        val frag = if (fragIdx >= 0) query.substring(fragIdx) else ""
        val params = (if (fragIdx >= 0) query.substring(0, fragIdx) else query)
            .split('&').filter { it.isNotEmpty() }
        val host = hostOf(base)
        val kept = params.filter { p ->
            val key = p.substringBefore('=')
            !isTracker(key) && !isDomainTracker(host, key)
        }
        if (kept.size == params.size) return url // 无命中，原样
        val q = kept.joinToString("&")
        return base + (if (q.isNotEmpty()) "?$q" else "") + frag
    }
    fun isTracker(key: String): Boolean {
        if (key in paramBlacklist) return true
        // 通配匹配：utm_* / share_* / pm_*_from
        return paramPatterns.any { it.matches(key) }
    }
    /** 域名级规则：host 或其子域名命中 domainRules 的 key 时，检查该域名专属参数 */
    private fun isDomainTracker(host: String?, key: String): Boolean {
        if (host == null) return false
        domainRules.forEach { (domain, params) ->
            if (params.contains(key) &&
                (host == domain || host.endsWith(".$domain"))
            ) return true
        }
        return false
    }
    companion object {
        /** 近似 RFC3986 的 URL 匹配（够用即可：识别 http/https 后到空白/中文标点为止） */
        val URL_REGEX = Regex("""https?://[^\s<>"'）】》]+""")
        val DEFAULT = TrackerRules(
            paramBlacklist = setOf(
                "spm", "from", "vd_source", "bbid", "buvid", "buvid4", "msToken",
                "scm", "chksm", "scene", "pos", "track_id", "xshare_timestamp",
                "share_medium", "share_source", "share_id", "share_count", "share_token",
                "launchid", "timestamp2", "req_id", "app_lang", "sys_region",
            ),
            paramPatterns = listOf(
                Regex("utm_.*"),
                Regex("share_.*"),
                Regex("pm_.*_from"),
                Regex("channel_id"),
                Regex("register"),
            ),
            domainRules = mapOf(
                "x.com" to setOf("a", "s", "t", "cn", "ref_url", "src", "ref_src"),
                "twitter.com" to setOf("a", "s", "t", "cn", "ref_url", "src", "ref_src"),
            ),
        )
        fun parse(json: String): TrackerRules? = runCatching {
            val obj = org.json.JSONObject(json)
            val blacklist = mutableSetOf<String>()
            obj.optJSONArray("paramBlacklist")?.let { arr ->
                for (i in 0 until arr.length()) blacklist.add(arr.getString(i))
            }
            val patterns = mutableListOf<Regex>()
            obj.optJSONArray("paramPatterns")?.let { arr ->
                for (i in 0 until arr.length()) patterns.add(Regex(arr.getString(i)))
            }
            val domains = mutableMapOf<String, Set<String>>()
            obj.optJSONObject("domainRules")?.let { dObj ->
                val keys = dObj.keys()
                while (keys.hasNext()) {
                    val domain = keys.next()
                    val arr = dObj.optJSONArray(domain) ?: continue
                    val params = mutableSetOf<String>()
                    for (i in 0 until arr.length()) params.add(arr.getString(i))
                    if (params.isNotEmpty()) domains[domain] = params
                }
            }
            TrackerRules(blacklist, patterns, domains)
        }.getOrNull()
        /** 从 URL 提取 host（含子域名），解析失败返回 null */
        private fun hostOf(base: String): String? =
            runCatching {
                val after = base.substringAfter("://", "")
                after.substringBefore('/').substringBefore('@').substringBefore(':').lowercase()
            }.getOrNull()
    }
}