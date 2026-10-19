/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.layout.KeyboardMode
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val keyboardHeightPercentBase = enumList(
            R.string.keyboard_height_percent_base,
            "keyboard_height_percent_base",
            KeyboardHeightPercentBase.DisplayMetrics
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", false)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) { showVoiceInputButton.getValue() }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Down
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.None
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                49,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                300,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        val keyboardMode = enumList(
            R.string.keyboard_position_mode,
            "keyboard_position_mode",
            KeyboardMode.Standard
        )
        val oneHandWidthPercent = int(
            R.string.one_hand_width_percent,
            "one_hand_width_percent",
            DEFAULT_ONE_HAND_WIDTH_PERCENT,
            55,
            95,
            "%"
        )
        val oneHandEdgeMargin = int(
            R.string.one_hand_edge_margin,
            "one_hand_edge_margin",
            0,
            0,
            80,
            "dp"
        )
        val oneHandBottomMargin = int(
            R.string.one_hand_bottom_margin,
            "one_hand_bottom_margin",
            0,
            0,
            100,
            "dp"
        )
        val floatingWidthPercent = int(
            R.string.floating_width_percent,
            "floating_width_percent",
            78,
            55,
            95,
            "%"
        )
        val floatingXPercent = int(
            R.string.floating_x_percent,
            "floating_x_percent",
            50,
            0,
            100,
            "%"
        )
        val floatingYPercent = int(
            R.string.floating_y_percent,
            "floating_y_percent",
            85,
            0,
            100,
            "%"
        )

        // Keep the existing keys for portrait so upgrades preserve saved positions.
        // Landscape has an independent travel-space position.
        val floatingXPercentLandscape = int(
            R.string.floating_x_percent_landscape, "floating_x_percent_landscape", 50, 0, 100, "%"
        )
        val floatingYPercentLandscape = int(
            R.string.floating_y_percent_landscape, "floating_y_percent_landscape", 85, 0, 100, "%"
        )

        val oneHandCornerRadius = int(
            R.string.one_hand_corner_radius, "one_hand_corner_radius",
            DEFAULT_COMPACT_CORNER_RADIUS_DP, 0, 40, "dp"
        )
        val floatingCornerRadius = int(
            R.string.floating_corner_radius, "floating_corner_radius",
            DEFAULT_COMPACT_CORNER_RADIUS_DP, 0, 40, "dp"
        )

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                8,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            999999,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            -1,
            Int.MAX_VALUE,
            "s"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    /**
     * 「梦」智能输入：分为「智能统计 / 智能联想 / 智能纠错」三组。
     * 独立于 fcitx 兼容层，为「梦」自有扩展。
     */
    inner class Smart : ManagedPreferenceCategory(R.string.smart_input, sharedPreferences) {

        /** 智能统计：输入统计开关 + 查看/清空入口。 */
        inner class Stats : ManagedPreferenceCategory(R.string.smart_group_stats, sharedPreferences) {
            /** 输入统计总开关。 */
            val statsEnabled = switch(
                R.string.smart_stats_enabled,
                "smart_stats_enabled",
                true,
                R.string.smart_stats_enabled_hint
            )
        }

        /** 智能联想：上屏后预测下一个字词。 */
        inner class Suggestion : ManagedPreferenceCategory(R.string.smart_group_suggest, sharedPreferences) {
            /** 智能联想总开关。 */
            val suggestEnabled = switch(
                R.string.smart_suggest_enabled,
                "smart_suggest_enabled",
                true,
                R.string.smart_suggest_enabled_hint
            )
        }

        /** 智能纠错：拼音码纠正。 */
        inner class Correct : ManagedPreferenceCategory(R.string.smart_group_correct, sharedPreferences) {
            /** 拼音纠错总开关。 */
            val pinyinCorrectEnabled = switch(
                R.string.smart_pinyin_correct_enabled,
                "smart_pinyin_correct_enabled",
                false,
                R.string.smart_pinyin_correct_enabled_hint
            )

            /** 纠错策略 1：邻键错位。 */
            val correctNeighbor = switch(
                R.string.smart_correct_neighbor,
                "smart_correct_neighbor",
                true,
                R.string.smart_correct_neighbor_hint,
                enableUiOn = { pinyinCorrectEnabled.getValue() }
            )

            /** 纠错策略 2：模糊音。 */
            val correctFuzzy = switch(
                R.string.smart_correct_fuzzy,
                "smart_correct_fuzzy",
                true,
                R.string.smart_correct_fuzzy_hint,
                enableUiOn = { pinyinCorrectEnabled.getValue() }
            )

            /** 纠错策略 3：自学习错拼/习惯码表。 */
            val correctHabit = switch(
                R.string.smart_correct_habit,
                "smart_correct_habit",
                true,
                R.string.smart_correct_habit_hint,
                enableUiOn = { pinyinCorrectEnabled.getValue() }
            )
        }

        val stats = Stats()
        val suggestion = Suggestion()
        val correct = Correct()

        init {
            // AppPrefs dispatches changes through registered top-level providers only.
            // Expose child preferences here while retaining their separate UI sections.
            listOf(stats, suggestion, correct).forEach { group ->
                group.managedPreferences.values.forEach { it.register() }
                group.managedPreferencesUi.forEach { it.registerUi() }
            }
        }

        /** 「智能统计」分组内可追加的自定义入口（如查看/清空统计），由 SmartSettingsFragment 设置。 */
        var statsGroupExtra: ((androidx.preference.PreferenceCategory) -> Unit)? = null
        /** 「智能联想」分组内的API配置入口，由 SmartSettingsFragment 设置。 */
        var suggestionGroupExtra: ((androidx.preference.PreferenceCategory) -> Unit)? = null

        // 兼容既有引用（smart.statsEnabled / smart.pinyinCorrectEnabled / ...）
        val statsEnabled get() = stats.statsEnabled
        val suggestEnabled get() = suggestion.suggestEnabled
        val pinyinCorrectEnabled get() = correct.pinyinCorrectEnabled
        val correctNeighbor get() = correct.correctNeighbor
        val correctFuzzy get() = correct.correctFuzzy
        val correctHabit get() = correct.correctHabit

        /** 把三个子分组的 preference 分别包进 PreferenceCategory 渲染。 */
        override fun createUi(screen: PreferenceScreen) {
            fun addGroup(@StringRes title: Int, group: ManagedPreferenceProvider): androidx.preference.PreferenceCategory {
                val category = androidx.preference.PreferenceCategory(screen.context).apply {
                    setTitle(title)
                    isIconSpaceReserved = false
                }
                // 先挂到 screen，让 category 继承到非空的 preferenceManager，
                // 否则后续 category.addPreference() 内部 getNextId() 会因 manager 为 null 而 NPE。
                screen.addPreference(category)
                group.managedPreferencesUi.forEach {
                    category.addPreference(it.createUi(screen.context).apply {
                        isEnabled = it.isEnabled()
                    })
                }
                return category
            }
            screen.addPreference(androidx.preference.Preference(screen.context).apply {
                key = "smart_page_description"
                title = screen.context.getString(R.string.smart_input_summary)
                isSelectable = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                order = Int.MIN_VALUE
            })
            addGroup(R.string.smart_group_suggest, suggestion).let {
                it.key = "smart_suggestion_group"
                suggestionGroupExtra?.invoke(it)
            }
            addGroup(R.string.smart_group_correct, correct).apply {
                key = "smart_correct_group"
                initialExpandedChildrenCount = 1
            }
            addGroup(R.string.smart_group_stats, stats).let {
                it.key = "smart_stats_group"
                statsGroupExtra?.invoke(it)
            }
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val smart = Smart().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_ONE_HAND_WIDTH_PERCENT = 88
        const val DEFAULT_COMPACT_CORNER_RADIUS_DP = 18
        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
