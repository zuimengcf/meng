/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.input.keyboard.KeyAction

/** Resolve popup selections without falling back to the base key's click action. */
internal fun KeyAction?.pickerCommitText(): String? = when (this) {
    // PopupKeyboardUi returns CommitAction so symbols are committed verbatim.
    is KeyAction.CommitAction -> text
    // Keep compatibility with popup sources that still return engine key actions.
    is KeyAction.FcitxKeyAction -> act
    else -> null
}
