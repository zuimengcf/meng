/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.plugin

/**
 * 官方插件指纹表：id → 「id:版本 → 内容指纹」映射。
 *
 * 指纹由 scripts/meng_pack.py 打包时自动计算（dist/RELEASES.tsv 台账），
 * 发布版本时从台账取对应版本指纹填入本表。
 *
 * 校验语义：
 *   - 安装时重算包内容指纹，命中本表 → 标记「官方」，证明包未被修改；
 *   - 未命中（含官方 id 的其他版本、未知 id）→ 标记「第三方」，照常安装（免责：风险自负）。
 *
 * 指纹算法（与打包脚本一致）：zip 条目按名字典序，逐条 "name:sha256(content)\n"，
 * 拼接后整体 sha256。与压缩参数/时间戳无关，改任何一字节即失配。
 */
object OfficialFingerprints {

    /**
     * 指纹表：key = "id:version"（version 为 manifest 解析后的整数值，
     * 与 PluginManifest.version 一致），value = 内容指纹 hex。
     */
    private val Fingerprints: Map<String, String> = mapOf(
        // art-symbols v7
        "art-symbols:7" to "e0a5843b5c5caf26c3b33bb2fce7c9fefe8d23f4b8efefbe3b20c2d36e587837",
        // link-tools v2
        "link-tools:2" to "a99f6368e101328dba5aa56c818568e3f34d14d17a3c402c33092e3cb6962394",
        // repeater v1
        "repeater:1" to "d73a5e6127b8d6ebd220d84e8aa76244373fdbedb57668987a9a447720655daf",
        // smart-dict v1.1.113
        "smart-dict:1001113" to "c6ce6e3c9cd47de82eabde4be443839cca6aa88343d23705a479477318a120bd",
        // smart-phrases v13
        "smart-phrases:13" to "1da082c197b1014a676aac1dd79f29d91043f60410d89d175037137ead712f93",
        // stickers v2
        "stickers:2" to "67bb6b4f0c24b6a6e314fdbd914ae498caeac9b6d5521f7d3b6154ffeb6f5434",
        // stickers-tieba v3
        "stickers-tieba:3" to "acadb2d5e730cfebccdd14de5d9f8294bfe85bb6a1e12177f1fa1fd2ba8196a1",
        // text-to-file v1
        "text-to-file:1" to "49e3b12baf6b5ee3e4403e04a30b1ee7ae53b5222614abc36bfa4455670c9e6a",
        // text-tools v1.1.113
        "text-tools:1001113" to "133e9d6664bd93fa00a793ad0b787ef26e846f46731447c251d4ac5994510644",
        // tts-voice v2
        "tts-voice:2" to "ce6a2c7a320059d1be367627b8ffc1cf29da79bf8f6f85c952d235eb208e5be1",
    )

    /** 包指纹是否命中官方表（id + 版本 + 指纹三方一致） */
    fun isOfficial(pluginId: String, version: Int, fingerprint: String): Boolean =
        Fingerprints["$pluginId:$version"] == fingerprint

    /** 插件 id 是否官方（不看版本，仅 UI 徽标参考） */
    fun isOfficialId(pluginId: String): Boolean =
        Fingerprints.keys.any { it.startsWith("$pluginId:") }
}
