/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Meng Input Method Contributors
 */
package org.fcitx.fcitx5.android.market

import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig

/**
 * 方案安装后的「激活」桥，以 FcitxAPI 扩展函数的形式提供。
 *
 * 复用 fcitx5 现有的 addon subconfig 机制（与 AddonSubconfig.kt 里的
 * reloadPinyinDict 等同款）：
 *
 * - Rime 插件监听了 setSubConfig(path="deploy") 来触发重新部署，
 *   见 plugin/rime/src/main/cpp/fcitx5-rime/src/rimeengine.cpp `setSubConfig`。
 *
 * 在 FcitxAPI 作用域内调用 [deployRime] 即可让 fcitx5 重新扫描 rime 用户数据目录，
 * 使新下载的方案生效。
 */
suspend fun FcitxAPI.deployRime() {
    setAddonSubConfig("rime", "deploy", RawConfig())
}