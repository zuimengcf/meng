/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Path
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import org.fcitx.fcitx5.android.input.dialog.keyboardPositionDialogBuilder
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.layout.KeyboardMode
import org.fcitx.fcitx5.android.input.layout.KeyboardPlacement
import org.fcitx.fcitx5.android.input.layout.CompactKeyboardSizing
import kotlin.math.abs
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

private class CompactPanelLayout(context: Context) : ConstraintLayout(context) {
    var cornerMode = KeyboardMode.Standard
    var requestedCornerRadius = 0f
    var clipHeightInset = 0
    var clippingEnabled = false

    override fun dispatchDraw(canvas: Canvas) {
        if (!clippingEnabled || width <= 0 || height <= clipHeightInset) {
            super.dispatchDraw(canvas)
            return
        }
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, width.toFloat(), (height - clipHeightInset).toFloat()),
                org.fcitx.fcitx5.android.input.layout.CompactPanelCorners.radii(
                    cornerMode, requestedCornerRadius, width, height - clipHeightInset
                ),
                Path.Direction.CW
            )
        }
        val saveCount = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saveCount)
    }
}

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
        keyboardPrefs.keyboardMode,
        keyboardPrefs.oneHandWidthPercent,
        keyboardPrefs.oneHandEdgeMargin,
        keyboardPrefs.oneHandBottomMargin,
        keyboardPrefs.floatingWidthPercent,
        keyboardPrefs.floatingXPercent,
        keyboardPrefs.floatingYPercent,
        keyboardPrefs.floatingXPercentLandscape,
        keyboardPrefs.floatingYPercentLandscape,
        keyboardPrefs.oneHandCornerRadius,
        keyboardPrefs.floatingCornerRadius,
    )

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val base = when (baseType) {
                DisplayMetrics -> resources.displayMetrics.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.y
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            if (key == keyboardPrefs.keyboardMode.key) {
                positionPointer = -1
                positionDragging = false
                dragXPercent = null
                dragYPercent = null
            }
            updateKeyboardSize()
        }
    }

    private var safeLeftInset = 0
    private var safeRightInset = 0
    private var safeTopInset = 0
    private var safeBottomInset = 0
    private var navBottomInset = 0
    private var dragXPercent: Int? = null
    private var dragYPercent: Int? = null
    private var positionPointer = -1
    private var dragDownX = 0f
    private var dragDownY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0
    private var positionDragging = false

    private val landscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val floatingXPreference
        get() = if (landscape) keyboardPrefs.floatingXPercentLandscape else keyboardPrefs.floatingXPercent
    private val floatingYPreference
        get() = if (landscape) keyboardPrefs.floatingYPercentLandscape else keyboardPrefs.floatingYPercent

    private val compactPreeditReserve: Int
        get() = maxOf(preedit.ui.root.measuredHeight, dp((48 * resources.configuration.fontScale).toInt()))
    private val floatingSafeLeft: Int get() = safeLeftInset + dp(8)
    private val floatingSafeRight: Int get() = safeRightInset + dp(8)
    private val floatingSafeTop: Int get() = safeTopInset + compactPreeditReserve + dp(8)
    private val floatingSafeBottom: Int get() = safeBottomInset + dp(8)

    val isFloatingKeyboard: Boolean
        get() = keyboardPrefs.keyboardMode.getValue() == KeyboardMode.Floating

    private val positionHandle = LinearLayout(themedContext).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(theme.barColor)
    }
    private val moveHandle = TextView(themedContext).apply {
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        textSize = 12f
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        contentDescription = context.getString(R.string.position_drag_handle)
        isClickable = true
        isFocusable = true
        setOnClickListener { showKeyboardPositionMenu() }
    }

    val keyboardView: View

    private var compactCornerRadiusPx = 0f
    private var panelNavigationInset = 0

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = CompactPanelLayout(themedContext).apply {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(positionHandle, lParams(matchParent, dp(44)) {
                topOfParent()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                below(positionHandle)
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        setupPositionHandle()
        updateKeyboardSize()

        add(preedit.ui.root, lParams(0, wrapContent) {
            above(keyboardView)
            startToStart = keyboardView.id
            endToEnd = keyboardView.id
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        preedit.ui.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && keyboardPrefs.keyboardMode.getValue() != KeyboardMode.Standard) {
                post { updateKeyboardSize() }
            }
        }
        updateKeyboardSize()
        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        val mode = keyboardPrefs.keyboardMode.getValue()
        val standard = mode == KeyboardMode.Standard
        val floating = mode == KeyboardMode.Floating
        // One-handed typing does not pay for a permanent control row. All modes
        // remain reachable through the normal tools menu (long press is a shortcut).
        positionHandle.visibility = if (floating) VISIBLE else GONE
        preedit.ui.setCompactLayout(!standard)
        val parentHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val parentWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val safeTop = if (standard) safeTopInset + dp(64)
            else safeTopInset + compactPreeditReserve + dp(8)
        val safeBottom = if (floating) floatingSafeBottom else 0
        val navPadding = if (floating) 0 else if (standard) navBottomInset else maxOf(navBottomInset, safeBottomInset)
        val barHeight = dp(KawaiiBarComponent.HEIGHT)
        val preferredHandle = if (floating) dp(maxOf(24f, 20f * resources.configuration.fontScale).toInt()) else 0
        val sizing = if (standard) null else CompactKeyboardSizing.calculate(
            availableHeight = (parentHeight - safeTop - safeBottom - navPadding).coerceAtLeast(0),
            desiredContentHeight = keyboardHeightPx,
            barHeight = barHeight,
            handleHeight = preferredHandle,
            desiredBottomPadding = if (floating) 0 else keyboardBottomPaddingPx,
            desiredBottomMargin = if (floating) 0 else dp(keyboardPrefs.oneHandBottomMargin.getValue().coerceIn(0, 100)),
            preferredMinimumContentHeight = dp(144)
        )
        // Keep standard mode's established dimensions and padding unchanged.
        val bottomPadding = sizing?.bottomPadding ?: keyboardBottomPaddingPx
        val handleHeight = sizing?.handleHeight ?: 0
        val contentHeight = sizing?.contentHeight ?: keyboardHeightPx.coerceIn(
            1, (parentHeight - safeTop - barHeight - bottomPadding - navPadding).coerceAtLeast(1))
        positionHandle.updateLayoutParams { height = handleHeight }
        moveHandle.updateLayoutParams { height = handleHeight }
        windowManager.view.updateLayoutParams { height = contentHeight }
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            height = bottomPadding
            bottomMargin = navPadding
        }
        val panelHeight = contentHeight + barHeight + handleHeight + bottomPadding + navPadding
        compactCornerRadiusPx = if (standard) 0f else dp(
            (if (floating) keyboardPrefs.floatingCornerRadius else keyboardPrefs.oneHandCornerRadius)
                .getValue().coerceIn(0, 40)
        ).toFloat()
        panelNavigationInset = navPadding
        // Clip only the visible keyboard body. Preedit and long-press popups are
        // siblings, so rounded corners never cut off the popup or its touch area.
        (keyboardView as CompactPanelLayout).apply {
            cornerMode = mode
            requestedCornerRadius = compactCornerRadiusPx
            clipHeightInset = panelNavigationInset
            clippingEnabled = !standard && compactCornerRadiusPx > 0f
            invalidate()
        }
        val placement = KeyboardPlacement.calculate(
            mode, parentWidth, parentHeight, panelHeight, safeTop, safeBottom, dp(240),
            if (floating) keyboardPrefs.floatingWidthPercent.getValue() else keyboardPrefs.oneHandWidthPercent.getValue(),
            dp(keyboardPrefs.oneHandEdgeMargin.getValue().coerceIn(0, 80)),
            sizing?.bottomMargin ?: 0,
            dragXPercent ?: floatingXPreference.getValue(),
            dragYPercent ?: floatingYPreference.getValue(),
            if (floating) floatingSafeLeft else safeLeftInset,
            if (floating) floatingSafeRight else safeRightInset
        )
        if (keyboardView.layoutParams != null) {
            keyboardView.updateLayoutParams<LayoutParams> {
                width = placement.width
                height = placement.height
                startToStart = unset
                endToEnd = unset
                topToTop = unset
                bottomToBottom = unset
                leftToLeft = LayoutParams.PARENT_ID
                rightToRight = unset
                leftMargin = placement.left
                rightMargin = 0
                if (floating) {
                    topToTop = LayoutParams.PARENT_ID
                    topMargin = placement.top
                    bottomMargin = 0
                } else {
                    bottomToBottom = LayoutParams.PARENT_ID
                    bottomMargin = (parentHeight - placement.top - placement.height).coerceAtLeast(0)
                    topMargin = 0
                }
            }
        }
        // Compact modes have one shared horizontal boundary: preedit, candidate
        // bar and every window (keys, tools, clipboard and emoji). Standard keeps
        // the user's old symmetric padding.
        val sidePadding = if (standard) keyboardSidePaddingPx.coerceIn(
            0, ((placement.width - dp(180)) / 2).coerceAtLeast(0)) else 0
        leftPaddingSpace.visibility = if (sidePadding == 0) GONE else VISIBLE
        rightPaddingSpace.visibility = if (sidePadding == 0) GONE else VISIBLE
        leftPaddingSpace.updateLayoutParams { width = sidePadding }
        rightPaddingSpace.updateLayoutParams { width = sidePadding }
        windowManager.view.updateLayoutParams<LayoutParams> {
            startToStart = unset
            endToEnd = unset
            startToEndOf(leftPaddingSpace)
            endToStartOf(rightPaddingSpace)
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        positionPointer = -1
        positionDragging = false
        dragXPercent = null
        dragYPercent = null
        updateKeyboardSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) updateKeyboardSize()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets)
        val safe = compat.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val gestures = compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        safeLeftInset = maxOf(safe.left, gestures.left)
        safeRightInset = maxOf(safe.right, gestures.right)
        safeTopInset = safe.top
        safeBottomInset = maxOf(safe.bottom, gestures.bottom)
        navBottomInset = getNavBarBottomInset(insets)
        updateKeyboardSize()
        return insets
    }

    /** Only the visible panel, preedit and actual popup children receive touches.
     * In floating mode the rest of the IME window passes through to the app. */
    fun computeTouchableRegion(region: Region) {
        region.setEmpty()
        fun include(view: View) {
            if (view.visibility != VISIBLE || view.width <= 0 || view.height <= 0) return
            val location = IntArray(2)
            view.getLocationInWindow(location)
            if (view === keyboardView && keyboardView.clipToOutline) {
                val visibleHeight = (view.height - panelNavigationInset).coerceAtLeast(0)
                val radius = org.fcitx.fcitx5.android.input.layout.CompactPanelCorners.radius(
                    compactCornerRadiusPx, view.width, visibleHeight)
                val path = Path().apply {
                    addRoundRect(location[0].toFloat(), location[1].toFloat(),
                        (location[0] + view.width).toFloat(), (location[1] + visibleHeight).toFloat(),
                        radius, radius, Path.Direction.CW)
                }
                val rounded = Region().apply {
                    setPath(path, Region(location[0], location[1], location[0] + view.width,
                        location[1] + visibleHeight))
                }
                region.op(rounded, Region.Op.UNION)
            } else {
                region.op(Rect(location[0], location[1], location[0] + view.width,
                    location[1] + view.height), Region.Op.UNION)
            }
        }
        include(keyboardView)
        include(preedit.ui.root)
        for (i in 0 until popup.root.childCount) include(popup.root.getChildAt(i))
    }

    private fun setupPositionHandle() {
        positionHandle.addView(moveHandle, LinearLayout.LayoutParams(matchParent, dp(24)))
        moveHandle.setText(R.string.position_drag_handle)
        moveHandle.setOnLongClickListener { showKeyboardPositionMenu(); true }
        moveHandle.setOnTouchListener { view, event ->
            if (!isFloatingKeyboard) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    positionPointer = event.getPointerId(0)
                    dragDownX = event.rawX
                    dragDownY = event.rawY
                    dragStartLeft = keyboardView.left
                    dragStartTop = keyboardView.top
                    positionDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (positionPointer < 0) return@setOnTouchListener false
                    val index = event.findPointerIndex(positionPointer)
                    if (index < 0) {
                        finishPositionDrag(view, false)
                        return@setOnTouchListener true
                    }
                    // rawX(index) is API 29+. Derive the tracked pointer's screen
                    // coordinate from pointer zero for Android 6+ compatibility.
                    // Only a screen-space delta is added to the parent-space origin,
                    // so non-zero IME window offsets do not introduce a position bias.
                    val dx = event.rawX + event.getX(index) - event.getX(0) - dragDownX
                    val dy = event.rawY + event.getY(index) - event.getY(0) - dragDownY
                    if (!positionDragging && (abs(dx) > ViewConfiguration.get(context).scaledTouchSlop ||
                                abs(dy) > ViewConfiguration.get(context).scaledTouchSlop)) positionDragging = true
                    if (positionDragging) {
                        val travelY = height - floatingSafeBottom - floatingSafeTop - keyboardView.height
                        dragXPercent = KeyboardPlacement.percentForOffset(
                            (dragStartLeft + dx).toInt() - floatingSafeLeft,
                            width - floatingSafeLeft - floatingSafeRight - keyboardView.width)
                        dragYPercent = KeyboardPlacement.percentForOffset(
                            (dragStartTop + dy).toInt() - floatingSafeTop, travelY)
                        updateKeyboardSize()
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == positionPointer) finishPositionDrag(view, false)
                    true
                }
                MotionEvent.ACTION_UP -> { finishPositionDrag(view, true); true }
                MotionEvent.ACTION_CANCEL -> { finishPositionDrag(view, false, commit = false); true }
                else -> true
            }
        }
    }

    private fun finishPositionDrag(view: View, allowClick: Boolean, commit: Boolean = true) {
        if (positionPointer < 0) return
        positionPointer = -1
        if (positionDragging && commit) {
            dragXPercent?.let { floatingXPreference.setValue(it) }
            dragYPercent?.let { floatingYPreference.setValue(it) }
        } else if (!positionDragging && allowClick) view.performClick()
        dragXPercent = null
        dragYPercent = null
        positionDragging = false
        updateKeyboardSize()
    }

    fun showKeyboardPositionMenu() {
        val modes = KeyboardMode.entries
        val labels = modes.map { context.getString(it.stringRes) }.toTypedArray()
        val builder = keyboardPositionDialogBuilder(context)
            .setTitle(R.string.keyboard_position_mode)
            .setSingleChoiceItems(labels, modes.indexOf(keyboardPrefs.keyboardMode.getValue())) { dialog, index ->
                keyboardPrefs.keyboardMode.setValue(modes[index])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (keyboardPrefs.keyboardMode.getValue() != KeyboardMode.Standard) {
            builder.setNeutralButton(R.string.position_adjust) { _, _ -> showPositionAdjustment() }
        }
        service.showDialog(builder.create())
    }

    private fun showPositionAdjustment() {
        val floating = isFloatingKeyboard
        // Capture the orientation's preference objects for the lifetime of this
        // dialog; rotating must not write portrait sliders into landscape keys.
        val xPreference = floatingXPreference
        val yPreference = floatingYPreference
        val orientationLabel = context.getString(if (landscape) R.string.landscape else R.string.portrait)
        val builder = keyboardPositionDialogBuilder(context)
        val dialogContext = builder.context
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val changes = mutableListOf<() -> Unit>()
        fun slider(title: Int, pref: org.fcitx.fcitx5.android.data.prefs.ManagedPreference.PInt,
                   min: Int, max: Int, unit: String) {
            val label = TextView(dialogContext)
            val bar = SeekBar(dialogContext).apply {
                this.max = max - min
                progress = pref.getValue().coerceIn(min, max) - min
            }
            fun labelValue(value: Int) { label.text = context.getString(title) + "：" + value + unit }
            labelValue(bar.progress + min)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { labelValue(progress + min) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            container.addView(label)
            container.addView(bar, LinearLayout.LayoutParams(matchParent, dp(44)))
            changes += { pref.setValue(bar.progress + min) }
        }
        if (floating) {
            slider(R.string.position_width, keyboardPrefs.floatingWidthPercent, 55, 95, "%")
            slider(R.string.position_horizontal, xPreference, 0, 100, "%")
            slider(R.string.position_vertical, yPreference, 0, 100, "%")
        } else {
            slider(R.string.position_width, keyboardPrefs.oneHandWidthPercent, 55, 95, "%")
            slider(R.string.position_edge_margin, keyboardPrefs.oneHandEdgeMargin, 0, 80, "dp")
            slider(R.string.position_bottom_margin, keyboardPrefs.oneHandBottomMargin, 0, 100, "dp")
        }
        slider(R.string.position_corner_radius,
            if (floating) keyboardPrefs.floatingCornerRadius else keyboardPrefs.oneHandCornerRadius,
            0, 40, "dp")
        val scroll = android.widget.ScrollView(dialogContext).apply { addView(container) }
        service.showDialog(builder
            .setTitle(if (floating) context.getString(R.string.position_floating_adjust) + " · " + orientationLabel
                else context.getString(R.string.position_one_hand_adjust))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ -> changes.forEach { it() } }
            .setNeutralButton(R.string.position_reset) { _, _ ->
                if (floating) {
                    keyboardPrefs.floatingWidthPercent.setValue(78)
                    xPreference.setValue(50)
                    yPreference.setValue(85)
                    keyboardPrefs.floatingCornerRadius.setValue(AppPrefs.DEFAULT_COMPACT_CORNER_RADIUS_DP)
                } else {
                    keyboardPrefs.oneHandWidthPercent.setValue(AppPrefs.DEFAULT_ONE_HAND_WIDTH_PERCENT)
                    keyboardPrefs.oneHandEdgeMargin.setValue(0)
                    keyboardPrefs.oneHandBottomMargin.setValue(0)
                    keyboardPrefs.oneHandCornerRadius.setValue(AppPrefs.DEFAULT_COMPACT_CORNER_RADIUS_DP)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    /**
     * 重新展示剪切板窗口（搜索结果/取消搜索后调用）。
     * 搜索输入窗口是独立 Activity，聚焦时会切回键盘窗口；搜索完成后需回到剪切板窗口展示过滤结果。
     */
    fun showClipboardWindow() {
        windowManager.attachWindow(ClipboardWindow())
    }

    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
