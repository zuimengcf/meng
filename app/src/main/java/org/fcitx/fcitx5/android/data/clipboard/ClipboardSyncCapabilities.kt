/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

/**
 * 能力协商（借自 localsync 的 hello/protocol 握手，但不改协议版本号）。
 *
 * - 客户端在心跳/同步请求的 `capabilities` 里声明自己支持的能力；
 * - 主机只在客户端声明过时才在响应里回带自己的能力（旧客户端收到的响应逐字节不变）；
 * - 双方都支持的能力才启用，任一方是旧版就走原来的快照路径。
 *
 * 旧版两端的 Json 都开了 ignoreUnknownKeys，未知字段被安全忽略。
 */
object ClipboardSyncCapabilities {
    /** 图片按 SHA-256 内容寻址：快照里只带哈希，缺的图片用 blob_get 逐张拉取。 */
    const val BLOB_V1 = "blob/1"

    /**
     * 以 localsync core 为核心的增量同步（HLC + 版本向量 + 操作日志分页 + 对端确认）。
     * 依赖 [BLOB_V1]：引擎操作里的图片只有哈希。双方都声明这两项才走引擎，否则沿用旧快照协议。
     */
    const val ENGINE_V1 = "engine/1"

    /**
     * 主机侧「立即同步」请求：主机 UI 触发后，在线客户端下次心跳会看到这个回带标志，
     * 并立刻执行一次完整内容同步。不参与 negotiated 交集，客户端直接检查原始列表。
     */
    const val KICK_V1 = "kick/1"

    /** 长轮询：主机内容一变就返回，不依赖 UDP NOTIFY（路由器隔离组播/广播、熄屏过滤时依然及时）。 */
    const val WAIT_V1 = "wait/1"

    /** 图片 1 MiB 分块传输（blob_get / blob_put 带 blobOffset/blobTotal），大图内存峰值从数倍图片大小降到约 1 MiB/块。 */
    const val BLOB_V2 = "blob/2"

    /**
     * Rime 用户词库同步：交换 sync/<installation_id> 下的 *.userdb.txt 快照，由 Rime 自己的 sync_user_data 合并。
     * 复用加密信封与 1 MiB 分块；任一端是旧版或未开启词库同步就不启用，剪切板同步完全不受影响。
     */
    const val LEXICON_V1 = "lexicon/1"

    val LOCAL: List<String> = listOf(BLOB_V1, ENGINE_V1, WAIT_V1, BLOB_V2, LEXICON_V1)

    fun negotiated(peer: Collection<String>?): Set<String> =
        if (peer.isNullOrEmpty()) emptySet() else LOCAL.toSet().intersect(peer.toSet())

    fun blobMode(peer: Collection<String>?): Boolean = BLOB_V1 in negotiated(peer)

    fun engineMode(peer: Collection<String>?): Boolean = negotiated(peer).containsAll(listOf(BLOB_V1, ENGINE_V1))
}
