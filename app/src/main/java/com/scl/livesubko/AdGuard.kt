package com.scl.livesubko

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 영상 사이트에서 재생 버튼을 누를 때 튀어나오는 광고 탭/리디렉션/앱 이동을 막습니다.
 *
 * 원리 (접근성 서비스로 브라우저 주소창을 읽음)
 *  - '보호 사이트'를 보고 있다가 주소가 갑자기 다른 사이트로 바뀌면 → 뒤로 가기
 *    · 새 탭으로 열린 광고: 크롬/삼성인터넷에서 뒤로 가기 = 광고 탭 닫고 원래 탭으로 복귀
 *    · 같은 탭 리디렉션: 뒤로 가기 = 영상 페이지로 복귀 (광고가 연달아 넘기면 최대 4번까지 반복)
 *  - 보호 사이트를 보던 중 몇 초 안에 Play 스토어/다른 브라우저가 열리면 → 뒤로 가기
 *  - 주소창을 직접 눌러 입력하거나 탭 목록에서 탭을 고르면 사용자의 이동으로 보고 막지 않음
 *
 * 보호 사이트 = 설정에 적은 사이트 + (자동 모드) 자막이 나왔던 사이트
 */
class AdGuard(private val service: AccessibilityService, @Volatile var settings: Settings) {

    private val main = Handler(Looper.getMainLooper())

    /** 마지막으로 확인한 브라우저 주소 (모르면 null) */
    private var currentHost: String? = null
    private var currentBrowser: String? = null
    /** 돌아가야 할 보호 사이트 */
    private var anchorHost: String? = null
    private var lastProtectedSeenAt = 0L
    private var lastForegroundPkg: String? = null

    /** 이번 실행 동안 자막이 나온 사이트 (자동 보호) */
    private val autoHosts = LinkedHashSet<String>()

    private var blockTimes = ArrayDeque<Long>()
    private var lastBackAt = 0L
    var blockedCount = 0
        private set

    private var checkScheduled = false
    private val checkRunnable = Runnable { checkScheduled = false; checkBrowser() }

    fun onEvent(e: AccessibilityEvent) {
        if (!settings.adBlock) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in BROWSER_URL_IDS.keys || pkg.contains("browser")) {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastForegroundPkg = pkg
            // 이벤트가 몰아서 오므로 잠깐 모아서 한 번만 검사
            if (!checkScheduled) {
                checkScheduled = true
                main.postDelayed(checkRunnable, 120)
            }
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (IGNORE_PKG_PARTS.any { pkg.contains(it) } || pkg == service.packageName) return

        val fromBrowser = lastForegroundPkg?.let { it in BROWSER_URL_IDS.keys || it.contains("browser") } == true
        val recent = SystemClock.elapsedRealtime() - lastProtectedSeenAt < APP_JUMP_WINDOW_MS
        if (fromBrowser && recent && anchorHost != null && pkg in AD_TARGET_APPS) {
            block("앱 이동 차단 ($pkg)")
            return
        }
        lastForegroundPkg = pkg
        // 브라우저를 떠났으니 다음 주소 변화는 '직접 이동'이 아님을 보장할 수 없음 → 초기화
        currentHost = null
    }

    /** 자막이 감지될 때 호출: 지금 보고 있는 사이트를 자동 보호 목록에 추가 */
    fun onCaptionSeen() {
        if (!settings.adBlock || !settings.adAuto) return
        val host = currentHost ?: return
        if (lastForegroundPkg != currentBrowser) return
        if (autoHosts.add(site(host))) Log.i(TAG, "auto-protect $host")
        anchorHost = host
        lastProtectedSeenAt = SystemClock.elapsedRealtime()
    }

    private fun checkBrowser() {
        val root = service.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in BROWSER_URL_IDS.keys && !pkg.contains("browser")) return
        lastForegroundPkg = pkg

        val urlNode = findUrlNode(root, pkg)
        if (urlNode == null) {
            // 주소창이 없는 화면: 탭 목록이면 탭 전환일 수 있으니 '직접 이동' 판단 보류,
            // 전체화면 영상이면 보호 사이트를 계속 보고 있는 것으로 간주
            if (isTabSwitcher(root)) currentHost = null
            else if (currentHost?.let { isProtected(it) } == true) lastProtectedSeenAt = SystemClock.elapsedRealtime()
            return
        }
        if (urlNode.isFocused) {
            // 사용자가 주소를 직접 입력 중 → 이후 이동은 막지 않음
            currentHost = null
            anchorHost = null
            return
        }
        val host = parseHost(urlNode.text?.toString()) ?: return
        val prev = currentHost
        currentHost = host
        currentBrowser = pkg

        if (isProtected(host)) {
            anchorHost = host
            lastProtectedSeenAt = SystemClock.elapsedRealtime()
            return
        }
        val anchor = anchorHost ?: return
        if (isAllowed(host) || sameSite(host, anchor)) return
        // 보호 사이트 → 다른 사이트로 '바로' 바뀐 경우만 (prev 가 보호 사이트이거나, 차단 후 재확인 중)
        val justBlocked = SystemClock.elapsedRealtime() - lastBackAt < 2500
        if (prev != null && (isProtected(prev) || justBlocked)) {
            currentHost = anchor // 뒤로 간 뒤 비교 기준 유지
            block(host)
        }
    }

    private fun block(what: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < 500) return
        blockTimes.addLast(now)
        while (blockTimes.isNotEmpty() && now - blockTimes.first() > 8000) blockTimes.removeFirst()
        if (blockTimes.size > MAX_BACKS_IN_ROW) {
            // 계속 튕겨 나가면 사용자를 가두지 않도록 포기
            anchorHost = null
            currentHost = null
            blockTimes.clear()
            toast("광고 차단을 잠시 멈췄어요 (계속 다른 사이트로 이동됨)")
            return
        }
        lastBackAt = now
        blockedCount++
        Log.i(TAG, "block $what")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        toast("광고 창 차단: $what")
        // 광고가 연달아 리디렉션하는 경우를 위해 잠시 후 다시 확인
        main.postDelayed({ checkBrowser() }, 800)
    }

    private fun isProtected(host: String): Boolean {
        val s = site(host)
        return settings.adSites.any { site(it) == s } || (settings.adAuto && s in autoHosts)
    }

    private fun isAllowed(host: String): Boolean {
        val s = site(host)
        return settings.adAllow.any { site(it) == s } || SAFE_SITES.any { s == it }
    }

    private fun findUrlNode(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        BROWSER_URL_IDS[pkg]?.forEach { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull()?.let { return it }
        }
        return searchUrlNode(root, 0, intArrayOf(150))
    }

    private fun searchUrlNode(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): AccessibilityNodeInfo? {
        if (depth > 8 || budget[0]-- <= 0) return null
        val id = n.viewIdResourceName?.lowercase()
        if (id != null && (id.endsWith("url_bar") || id.contains("location_bar") || id.contains("url_field") ||
                id.contains("toolbar_url"))) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            searchUrlNode(c, depth + 1, budget)?.let { return it }
        }
        return null
    }

    private fun isTabSwitcher(root: AccessibilityNodeInfo): Boolean =
        listOf("tab_switcher_recycler_view", "tab_list_recycler_view", "tab_grid").any { id ->
            root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id")?.isNotEmpty() == true
        }

    private fun toast(msg: String) {
        main.post { Toast.makeText(service, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "AdGuard"
        private const val APP_JUMP_WINDOW_MS = 5000L
        private const val MAX_BACKS_IN_ROW = 4

        /** 브라우저 패키지 → 주소창 view id 후보 */
        val BROWSER_URL_IDS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.chrome.dev" to listOf("url_bar"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "url_bar"),
            "com.sec.android.app.sbrowser.beta" to listOf("location_bar_edit_text", "url_bar"),
            "com.naver.whale" to listOf("url_bar", "location_bar_edit_text"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.kiwibrowser.browser" to listOf("url_bar"),
            "com.opera.browser" to listOf("url_field"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view"),
        )

        /** 광고가 자주 여는 앱 (다른 브라우저 포함) */
        private val AD_TARGET_APPS = setOf(
            "com.android.vending", "com.sec.android.app.samsungapps",
        ) + BROWSER_URL_IDS.keys

        private val IGNORE_PKG_PARTS = listOf(
            "inputmethod", "honeyboard", "keyboard", "com.android.systemui", "launcher",
            "com.google.android.as", "permissioncontroller",
        )

        /** 로그인 등 막으면 곤란한 사이트 */
        private val SAFE_SITES = setOf("google.com", "accounts.google.com", "naver.com", "kakao.com", "apple.com")

        private val SECOND_LEVEL = setOf("co", "com", "net", "or", "go", "ac", "ne", "org", "re", "pe")

        /** "https://m.example.com/watch?x" / "example.com" → "m.example.com" */
        fun parseHost(raw: String?): String? {
            var t = raw?.trim()?.lowercase() ?: return null
            if (t.isEmpty() || t.contains(' ')) return null
            t = t.substringAfter("://")
            t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            t = t.removePrefix("www.")
            if (!t.contains('.') || t.startsWith('.') || t.endsWith('.')) return null
            if (!t.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            return t
        }

        /** 대표 도메인 (m.video.example.co.kr → example.co.kr) */
        fun site(host: String): String {
            val p = host.split('.')
            if (p.size <= 2) return host
            val n = if (p.last().length == 2 && p[p.size - 2] in SECOND_LEVEL) 3 else 2
            return p.takeLast(n).joinToString(".")
        }

        fun sameSite(a: String, b: String) = site(a) == site(b)
    }
}
