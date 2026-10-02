package com.scl.livesubko

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 영상 위에 한국어 자막을 띄우는 오버레이.
 * 접근성 서비스 전용 창(TYPE_ACCESSIBILITY_OVERLAY)이라 '다른 앱 위에 표시' 권한이 필요 없습니다.
 * 자막을 손가락으로 끌어서 위아래 위치를 옮길 수 있습니다.
 */
class OverlayController(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private lateinit var koView: TextView
    private lateinit var enView: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var settings: Settings = Settings.load(service)

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics,
    )

    @SuppressLint("ClickableViewAccessibility")
    fun attach(s: Settings) {
        if (root != null) return
        settings = s
        val pad = dp(10f).toInt()

        koView = TextView(service).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
            setLineSpacing(0f, 1.1f)
        }
        enView = TextView(service).apply {
            setTextColor(Color.parseColor("#CCDDDDDD"))
            gravity = Gravity.CENTER
            setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
            visibility = View.GONE
        }
        root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad * 2, pad, pad * 2, pad)
            addView(koView)
            addView(enView)
            visibility = View.GONE
            setOnTouchListener(DragListener())
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        applySettings(s)
        wm.addView(root, params)
    }

    fun applySettings(s: Settings) {
        settings = s
        val r = root ?: return
        koView.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp.toFloat())
        enView.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp * 0.6f)
        val maxW = (service.resources.displayMetrics.widthPixels * 0.92f).toInt()
        koView.maxWidth = maxW
        enView.maxWidth = maxW
        r.background = GradientDrawable().apply {
            cornerRadius = dp(10f)
            setColor(Color.argb((s.bgAlpha.coerceIn(0, 100) * 2.55f).toInt(), 0, 0, 0))
        }
        relayout()
    }

    /** 화면 회전 등으로 크기가 바뀌었을 때 비율 위치를 다시 계산 */
    fun relayout() {
        val r = root ?: return
        val h = service.resources.displayMetrics.heightPixels
        params.y = (h * settings.overlayYFraction.coerceIn(0.02f, 0.95f)).toInt()
        val maxW = (service.resources.displayMetrics.widthPixels * 0.92f).toInt()
        koView.maxWidth = maxW
        enView.maxWidth = maxW
        if (r.isAttachedToWindow) wm.updateViewLayout(r, params)
    }

    /**
     * @param lines 한국어 자막 줄들 (마지막이 최신). 이전 줄은 흐리게 표시.
     */
    fun show(lines: List<String>, original: String?) {
        val r = root ?: return
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            val start = sb.length
            sb.append(line)
            if (i < lines.size - 1) {
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#B3FFFFFF")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.88f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        koView.text = sb
        if (original.isNullOrBlank()) {
            enView.visibility = View.GONE
        } else {
            enView.text = original
            enView.visibility = View.VISIBLE
        }
        r.visibility = View.VISIBLE
    }

    fun showNotice(msg: String) = show(listOf(msg), null)

    fun hide() {
        root?.visibility = View.GONE
    }

    fun detach() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private inner class DragListener : View.OnTouchListener {
        private var downY = 0f
        private var startY = 0
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > dp(4f)) moved = true
                    params.y = (startY + dy).toInt().coerceAtLeast(0)
                    wm.updateViewLayout(v, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val h = service.resources.displayMetrics.heightPixels.toFloat()
                        val frac = (params.y / h).coerceIn(0.02f, 0.95f)
                        Settings.prefs(service).edit().putFloat("overlay_y", frac).apply()
                    } else {
                        hide() // 탭하면 잠시 숨기기 (다음 자막이 오면 다시 표시)
                    }
                }
            }
            return true
        }
    }
}
