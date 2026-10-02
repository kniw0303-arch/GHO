package com.scl.livesubko

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CaptionService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var settings: Settings? = null
    private var overlay: OverlayController? = null
    private var translator: HybridTranslator? = null
    private var pipeline: SubtitlePipeline? = null
    private var adGuard: AdGuard? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        val s = Settings.load(this)
        settings = s
        val ov = OverlayController(this).also { it.attach(s) }
        val tr = HybridTranslator(s)
        tr.onError = { msg -> ov.showNotice("⚠ 번역 API 오류: ${msg.take(80)}") }
        tr.prepareModel { ok, err ->
            if (!ok && s.mode == EngineMode.MLKIT) ov.showNotice("ML Kit 번역 모델 다운로드 실패: $err")
        }
        overlay = ov
        translator = tr
        pipeline = SubtitlePipeline(tr, ov, s)
        adGuard = AdGuard(this, s)
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        running = true
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val s = settings ?: return
        try {
            adGuard?.onEvent(event)
        } catch (t: Throwable) {
            Log.w(TAG, "adGuard", t)
        }
        if (!s.enabled) return
        val caption = try {
            CaptionExtractor.extract(event, packageName)
        } catch (t: Throwable) {
            // 대상 앱 창이 사라지는 등 노드 접근 중 예외는 무시
            null
        } ?: return
        adGuard?.onCaptionSeen()
        pipeline?.onCaption(caption)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        val s = Settings.load(this)
        val wasEnabled = settings?.enabled ?: true
        settings = s
        translator?.settings = s
        pipeline?.settings = s
        adGuard?.settings = s
        overlay?.applySettings(s)
        if (wasEnabled && !s.enabled) pipeline?.reset()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { overlay?.relayout() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        pipeline?.reset()
        overlay?.detach()
        translator?.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptionService"
        @Volatile var running = false
            private set
    }
}
