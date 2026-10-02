package com.scl.livesubko

import android.os.Handler
import android.os.Looper

/**
 * 자막 텍스트 흐름을 문장 단위로 쪼개고, 번역 타이밍을 조율합니다. (메인 스레드 전용)
 *
 * 동시 번역 전략
 *  1. 말하는 중인 '부분 문장'도 단어가 바뀔 때마다 ML Kit 으로 즉시 번역해 보여줌
 *  2. 말이 잠깐 멈추면(약 0.45초) 부분 문장을 LLM 에 미리 보냄 (추측 번역)
 *  3. 문장이 끝나면(. ? ! 또는 큐 교체) LLM 번역을 확정 → 미리 보낸 결과가 같으면 캐시로 즉시 교체
 */
class SubtitlePipeline(
    private val translator: HybridTranslator,
    private val overlay: OverlayController,
    @Volatile var settings: Settings,
) {
    private class Line(var src: String) {
        var ko: String = ""
        var koSrc: String = ""
        var fromLlm: Boolean = false
    }

    private val main = Handler(Looper.getMainLooper())

    private val finished = ArrayDeque<Line>()     // 확정된 최근 문장 (최대 2개 표시)
    private var partial: Line? = null             // 말하는 중인 문장
    private val recentKeys = ArrayDeque<String>() // 중복 확정 방지
    private val history = ArrayDeque<Pair<String, String>>() // LLM 문맥용 (EN, KO)
    private var lastRaw = ""

    private var quickBusy = false
    private var quickNext: Line? = null

    private val stableRunnable = Runnable { onPartialStable() }
    private val hideRunnable = Runnable {
        overlay.hide()
        finished.clear()
        partial = null
        lastRaw = ""
    }

    fun onCaption(c: Caption) {
        val text = normalize(c.text)
        if (text.isEmpty() || text == lastRaw) return
        if (!CaptionExtractor.looksEnglish(text)) return
        lastRaw = text
        when (c.kind) {
            CaptionKind.CUE -> onCue(text)
            CaptionKind.ROLLING -> onRolling(text)
        }
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, settings.hideDelayMs)
    }

    fun reset() {
        main.removeCallbacks(stableRunnable)
        main.removeCallbacks(hideRunnable)
        finished.clear(); partial = null; history.clear(); recentKeys.clear(); lastRaw = ""
        overlay.hide()
    }

    // ---------------- 큐(장면) 자막 ----------------
    private fun onCue(text: String) {
        // 큐 자막은 한 번에 한 장면만 보여줌
        finished.clear()
        partial = null
        // 두 줄짜리 큐가 한 문장 이어지는 경우가 많으므로 통째로 한 문장 취급
        val line = Line(text)
        finished.addLast(line)
        finalizeLine(line)
        render()
    }

    // ---------------- 실시간(롤링) 자막 ----------------
    private fun onRolling(text: String) {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return
        val lastDone = endsSentence(sentences.last())
        val done = if (lastDone) sentences else sentences.dropLast(1)
        val open = if (lastDone) null else sentences.last()

        for (s in done) {
            if (isAlreadyFinished(s)) continue
            // 부분 문장이 완성된 것이면 같은 Line 을 이어서 사용 (깜빡임 방지)
            val p = partial
            val line = if (p != null && similarPrefix(p.src, s)) { p.src = s; partial = null; p } else Line(s)
            finished.addLast(line)
            while (finished.size > 2) finished.removeFirst()
            rememberKey(s)
            finalizeLine(line)
        }

        if (open != null && !isAlreadyFinished(open)) {
            val line = partial ?: Line(open).also { partial = it }
            if (line.src != open || line.ko.isEmpty()) {
                line.src = open
                requestQuick(line)
                main.removeCallbacks(stableRunnable)
                main.postDelayed(stableRunnable, STABLE_MS)
            }
        } else if (open == null) {
            partial = null
        }
        render()
    }

    private fun onPartialStable() {
        val line = partial ?: return
        val src = line.src
        if (wordCount(src) < 3) return
        translator.refine(src, context(), priority = false) { ko -> apply(line, src, ko, true) }
    }

    private fun finalizeLine(line: Line) {
        val src = line.src
        // 미리 받아둔 LLM 결과가 있으면 즉시 사용
        translator.cachedLlm(src)?.let { apply(line, src, it, true) }
        if (!(line.fromLlm && line.koSrc == src)) {
            if (line.koSrc != src) requestQuick(line)
            translator.refine(src, context(), priority = true) { ko ->
                apply(line, src, ko, true)
            }
        }
    }

    private fun requestQuick(line: Line) {
        if (!settings.mlkitUsable) return
        if (quickBusy) { quickNext = line; return }
        quickBusy = true
        val src = line.src
        translator.quick(src) { ko ->
            quickBusy = false
            if (ko != null) apply(line, src, ko, false)
            quickNext?.let { next -> quickNext = null; requestQuick(next) }
        }
    }

    private fun apply(line: Line, src: String, ko: String, fromLlm: Boolean) {
        // 원문이 이미 더 길어졌는데 늦게 도착한 결과
        if (line.src != src) {
            // 부분 문장에 대한 이전 결과라도, 아직 아무 번역도 없으면 임시로 사용
            if (line.ko.isNotEmpty() || !line.src.startsWith(src)) return
        }
        // 같은 원문에 대해 LLM 결과가 있으면 ML Kit 결과로 덮지 않음
        if (!fromLlm && line.fromLlm && line.koSrc == src) return
        line.ko = ko
        line.koSrc = src
        line.fromLlm = fromLlm
        if (fromLlm && line.src == src && finished.contains(line)) pushHistory(src, ko)
        render()
    }

    private fun render() {
        val shown = ArrayList<Line>(3)
        shown.addAll(finished)
        partial?.let { shown.add(it) }
        val visible = shown.filter { it.ko.isNotEmpty() }.takeLast(2)
        if (visible.isEmpty()) return
        overlay.show(
            visible.map { it.ko },
            if (settings.showOriginal) visible.joinToString(" ") { it.src } else null,
        )
    }

    // ---------------- 문맥/중복 관리 ----------------
    private fun context(): List<Pair<String, String>> = history.toList()

    private fun pushHistory(en: String, ko: String) {
        if (history.any { it.first == en }) return
        history.addLast(en to ko)
        while (history.size > CONTEXT_LINES) history.removeFirst()
    }

    private fun rememberKey(s: String) {
        recentKeys.addLast(key(s))
        while (recentKeys.size > 40) recentKeys.removeFirst()
    }

    private fun isAlreadyFinished(s: String): Boolean {
        val k = key(s)
        if (k.isEmpty()) return true
        return recentKeys.any { it == k || it.endsWith(k) || similarity(it, k) > 0.8 }
    }

    companion object {
        private const val STABLE_MS = 450L
        private const val CONTEXT_LINES = 4

        private val WS = Regex("\\s+")
        private val SENT_SPLIT = Regex("(?<=[.!?…♪])\\s+")

        fun normalize(s: String) = s.replace(WS, " ").trim()

        fun splitSentences(text: String): List<String> =
            text.split(SENT_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

        fun endsSentence(s: String): Boolean {
            val t = s.trimEnd('"', '\'', ')', ']', '”', '’')
            return t.endsWith(".") || t.endsWith("?") || t.endsWith("!") || t.endsWith("…") || t.endsWith("♪")
        }

        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(WS, " ").trim()

        fun wordCount(s: String) = s.split(' ').count { it.isNotBlank() }

        /** 부분 문장 → 완성 문장 관계인지 (실시간 자막은 앞 단어를 고쳐 쓰기도 해서 느슨하게 비교) */
        fun similarPrefix(partial: String, full: String): Boolean {
            val a = key(partial); val b = key(full)
            if (b.startsWith(a)) return true
            val aw = a.split(' ').take(3); val bw = b.split(' ').take(3)
            return aw.isNotEmpty() && aw == bw
        }

        /** 단어 집합 자카드 유사도 */
        fun similarity(a: String, b: String): Double {
            val sa = a.split(' ').toSet(); val sb = b.split(' ').toSet()
            if (sa.isEmpty() || sb.isEmpty()) return 0.0
            val inter = sa.intersect(sb).size.toDouble()
            return inter / (sa.size + sb.size - inter)
        }
    }
}
