package com.scl.livesubko

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executors

/**
 * 하이브리드 번역기
 *  - quick():  ML Kit 온디바이스 번역 (수십 ms) → 화면에 즉시 표시
 *  - refine(): LLM 번역 (0.5~1.5초) → 도착하면 자연스러운 문장으로 교체
 *
 * 모든 콜백은 메인 스레드에서 호출됩니다.
 */
class HybridTranslator(@Volatile var settings: Settings) {

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(MAX_ACTIVE)

    private val quickCache = LruCache<String, String>(400)
    private val llmCache = LruCache<String, String>(400)

    private val mlkit: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
    )
    @Volatile var mlkitReady = false
        private set

    /** LLM 오류 알림 (예: API 키 오류) */
    var onError: ((String) -> Unit)? = null

    // ---- LLM 요청 스케줄링 (메인 스레드에서만 접근) ----
    private class Job(val src: String, val ctx: List<Pair<String, String>>, val priority: Boolean)
    private val waiters = HashMap<String, MutableList<(String) -> Unit>>()
    private val pending = ArrayDeque<Job>()
    private var active = 0
    private var lastErrorAt = 0L

    fun prepareModel(onDone: ((Boolean, String?) -> Unit)? = null) {
        mlkit.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                mlkitReady = true
                onDone?.invoke(true, null)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ML Kit model download failed", e)
                onDone?.invoke(false, e.message)
            }
    }

    /** ML Kit 즉시 번역. 실패하거나 사용 불가하면 null 로 콜백 (항상 정확히 한 번 호출). */
    fun quick(src: String, cb: (String?) -> Unit) {
        if (!settings.mlkitUsable || !mlkitReady) { cb(quickCache.get(src)); return }
        quickCache.get(src)?.let { cb(it); return }
        mlkit.translate(src)
            .addOnSuccessListener { ko ->
                val fixed = polishMlKit(ko)
                quickCache.put(src, fixed)
                cb(fixed)
            }
            .addOnFailureListener {
                Log.w(TAG, "ML Kit translate failed", it)
                cb(null)
            }
    }

    fun cachedLlm(src: String): String? = llmCache.get(src)

    /**
     * LLM 번역. 같은 문장은 한 번만 요청하고, 밀려 있는 요청이 많으면
     * 오래된 '부분 문장' 요청부터 버려서 항상 최신 대사가 먼저 번역되게 합니다.
     */
    fun refine(src: String, context: List<Pair<String, String>>, priority: Boolean, cb: (String) -> Unit) {
        if (!settings.llmUsable) return
        llmCache.get(src)?.let { cb(it); return }
        val list = waiters[src]
        if (list != null) { list.add(cb); return }
        waiters[src] = mutableListOf(cb)
        pending.addLast(Job(src, context, priority))
        // 대기열이 길면 오래된 비우선 요청 제거
        while (pending.size > MAX_PENDING) {
            val victim = pending.firstOrNull { !it.priority } ?: pending.first()
            pending.remove(victim)
            waiters.remove(victim.src)
        }
        pump()
    }

    private fun pump() {
        while (active < MAX_ACTIVE && pending.isNotEmpty()) {
            // 최신 우선 (동시 번역감을 위해), 단 확정 문장 우선
            val job = pending.lastOrNull { it.priority } ?: pending.last()
            pending.remove(job)
            active++
            val s = settings
            net.execute {
                val t0 = SystemClock.elapsedRealtime()
                val result = runCatching { LlmClient.translate(s, job.src, job.ctx) }
                val dt = SystemClock.elapsedRealtime() - t0
                main.post {
                    active--
                    val cbs = waiters.remove(job.src)
                    result.onSuccess { ko ->
                        Log.d(TAG, "LLM ${dt}ms: ${job.src} -> $ko")
                        if (ko.isNotBlank()) {
                            llmCache.put(job.src, ko)
                            cbs?.forEach { it(ko) }
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "LLM failed", e)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastErrorAt > 15_000) {
                            lastErrorAt = now
                            onError?.invoke(e.message ?: e.javaClass.simpleName)
                        }
                    }
                    pump()
                }
            }
        }
    }

    fun close() {
        net.shutdownNow()
        mlkit.close()
    }

    /** ML Kit 직역투를 조금 다듬기 (가벼운 규칙 몇 개) */
    private fun polishMlKit(ko: String): String {
        var t = ko.trim()
        // 끝에 남는 마침표는 자막에서 보통 생략
        if (t.endsWith(".") && !t.endsWith("..")) t = t.dropLast(1)
        t = t.replace("당신은 ", "넌 ").replace("당신이 ", "네가 ").replace("당신을 ", "널 ")
            .replace("당신의 ", "네 ")
        return t
    }

    companion object {
        private const val TAG = "HybridTranslator"
        private const val MAX_ACTIVE = 3
        private const val MAX_PENDING = 4
    }
}
