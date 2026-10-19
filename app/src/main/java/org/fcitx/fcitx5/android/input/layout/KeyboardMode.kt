/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class KeyboardMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Standard(R.string.keyboard_mode_standard),
    OneHandLeft(R.string.keyboard_mode_left),
    OneHandRight(R.string.keyboard_mode_right),
    Floating(R.string.keyboard_mode_floating),
}
