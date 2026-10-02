package com.scl.livesubko

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

enum class CaptionKind {
    /** 실시간 자막(Live Caption)처럼 글자가 계속 이어 붙는 형태 */
    ROLLING,
    /** 영상 앱 자막처럼 한 장면(큐) 단위로 통째로 바뀌는 형태 */
    CUE,
}

data class Caption(val text: String, val kind: CaptionKind, val pkg: String)

/**
 * 접근성 이벤트에서 자막 텍스트만 골라냅니다.
 *
 * 1) 안드로이드 '실시간 자막(Live Caption)' 창 → 어떤 앱/웹 영상이든 소리를 영어 자막으로 만들어 주므로 가장 범용적
 * 2) 영상 앱/웹 페이지 안에서 id·클래스 이름에 subtitle/caption 이 들어간 뷰
 */
object CaptionExtractor {

    /** 실시간 자막을 그리는 시스템 패키지들 (기기/버전별) */
    private val ROLLING_PKGS = setOf(
        "com.google.android.as",                 // Android System Intelligence (Pixel, 대부분의 기기)
        "com.google.android.as.oss",
        "com.google.audio.hearing.visualization.accessibility.scribe", // 실시간 텍스트 변환(Live Transcribe)
    )

    private val IGNORE_PKG_PARTS = listOf(
        "inputmethod", "honeyboard", "keyboard", "com.android.systemui", "launcher",
    )

    private val ID_HINTS = listOf("subtitle", "caption", "timedtext", "cue_", "_cue", "closed_cc")
    private val CLASS_HINTS = listOf("SubtitleView", "CaptionView", "CaptionWindow", "SubtitleWindow")

    private val LIVE_CAPTION_UI_WORDS = setOf(
        "live caption", "실시간 자막", "captions", "자막", "close", "닫기", "settings", "설정",
    )

    fun extract(event: AccessibilityEvent, ownPkg: String): Caption? {
        val pkg = event.packageName?.toString() ?: return null
        if (pkg == ownPkg || IGNORE_PKG_PARTS.any { pkg.contains(it) }) return null
        val src = event.source ?: return null

        if (pkg in ROLLING_PKGS) {
            val root = src.window?.root ?: src
            val texts = ArrayList<String>()
            collectTexts(root, texts, 0, intArrayOf(200), includeDesc = false)
            val best = texts
                .filter { it.lowercase() !in LIVE_CAPTION_UI_WORDS }
                .maxByOrNull { it.length } ?: return null
            return Caption(best, CaptionKind.ROLLING, pkg)
        }

        // 일반 앱: 바뀐 노드에서 위로 올라가며 자막 컨테이너를 찾음
        var node: AccessibilityNodeInfo? = src
        var level = 0
        while (node != null && level < 5) {
            if (isCaptionNode(node)) return captionFrom(node, pkg)
            node = node.parent
            level++
        }
        // 컨테이너 전체가 갱신된 경우: 아래쪽에서 자막 노드 탐색 (탐색량 제한)
        val found = findCaptionDescendant(src, 0, intArrayOf(80)) ?: return null
        return captionFrom(found, pkg)
    }

    private fun captionFrom(node: AccessibilityNodeInfo, pkg: String): Caption? {
        val texts = ArrayList<String>()
        collectTexts(node, texts, 0, intArrayOf(40), includeDesc = true)
        val joined = texts.distinct().joinToString(" ").trim()
        if (joined.isEmpty() || !looksEnglish(joined)) return null
        return Caption(joined, CaptionKind.CUE, pkg)
    }

    private fun isCaptionNode(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.lowercase()
        if (id != null && ID_HINTS.any { id.contains(it) }) return true
        val cls = n.className?.toString() ?: return false
        return CLASS_HINTS.any { cls.contains(it) }
    }

    private fun findCaptionDescendant(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): AccessibilityNodeInfo? {
        if (depth > 10 || budget[0]-- <= 0) return null
        if (isCaptionNode(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findCaptionDescendant(c, depth + 1, budget)?.let { return it }
        }
        return null
    }

    private fun collectTexts(
        n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int, budget: IntArray, includeDesc: Boolean,
    ) {
        if (depth > 12 || budget[0]-- <= 0) return
        if (!n.isClickable) {
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t)
            else if (includeDesc) {
                val d = n.contentDescription?.toString()?.trim()
                if (!d.isNullOrEmpty()) out.add(d)
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collectTexts(c, out, depth + 1, budget, includeDesc)
        }
    }

    /** 알파벳 비율로 영어 자막인지 대략 판단 (이미 한국어 자막인 경우 제외) */
    fun looksEnglish(s: String): Boolean {
        var latin = 0
        var hangul = 0
        for (ch in s) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') latin++
            else if (ch in '가'..'힣') hangul++
        }
        return latin >= 2 && latin > hangul * 2
    }
}
