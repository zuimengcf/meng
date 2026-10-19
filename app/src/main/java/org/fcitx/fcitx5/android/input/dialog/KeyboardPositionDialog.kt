/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.dialog

import android.app.AlertDialog
import android.content.Context
import org.fcitx.fcitx5.android.R

/**
 * The IME view uses a framework DeviceDefault context rather than an Activity
 * AppCompat context. Keep the dialog and all its children on the framework
 * dialog context, including the day/night resource variant. Theme.DialogTheme
 * must inherit Dialog.Alert so AlertController's list layouts are non-zero.
 */
fun keyboardPositionDialogBuilder(context: Context): AlertDialog.Builder =
    AlertDialog.Builder(context, R.style.Theme_DialogTheme)
