package com.scl.livesubko

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

/** 설정 + 테스트 화면 (라이브러리 없이 코드로 구성) */
class MainActivity : Activity() {

    private val prefs by lazy { Settings.prefs(this) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var testResult: TextView
    private var testTranslator: HybridTranslator? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(title("실시간 자막 번역", 24))
        statusView = text("", 15).also { col.addView(it) }
        col.addView(button("① 접근성 설정에서 서비스 켜기") {
            startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("② 안드로이드 '실시간 자막(Live Caption)' 설정 열기") { openLiveCaption() })
        col.addView(note(
            "• 웹·앱 영상 어디서든 쓰려면 '실시간 자막'을 켜 두세요. 소리를 영어 자막으로 만들면 이 앱이 한국어로 바꿔 띄웁니다. " +
                "(볼륨 버튼 → 자막 아이콘으로도 켤 수 있음)\n" +
                "• 일부 영상 앱은 자막 텍스트를 직접 읽을 수도 있습니다.\n" +
                "• 직접 설치한 앱이라 접근성 켜기가 막히면: 설정 → 애플리케이션 → 실시간 자막 번역 → ⋮ → '제한된 설정 허용' 후 다시 시도하세요.\n" +
                "• 화면의 한국어 자막은 끌어서 위치를 옮기고, 탭하면 잠깐 숨겨집니다."
        ))

        col.addView(switch("번역 켜기", "enabled", true))

        // ---- 번역 엔진 ----
        col.addView(section("번역 엔진"))
        col.addView(radio(
            "mode",
            listOf(
                "HYBRID" to "하이브리드 (ML Kit 즉시 표시 → LLM 자연스러운 번역으로 교체) · 추천",
                "MLKIT" to "ML Kit만 (무료·오프라인·가장 빠름, 직역투)",
                "LLM" to "LLM만 (가장 자연스러움, 약간 늦게 표시)",
            ),
            "HYBRID",
        ))
        col.addView(button("ML Kit 영→한 모델 다운로드 (약 30MB, 최초 1회)") { downloadModel() })

        col.addView(section("LLM 설정"))
        col.addView(radio(
            "provider",
            listOf("CLAUDE" to "Claude (Anthropic)", "GEMINI" to "Gemini (Google)"),
            "CLAUDE",
        ))
        col.addView(edit("Claude API 키 (sk-ant-…)", "claude_key", password = true))
        col.addView(edit("Claude 모델 (기본 ${Settings.DEFAULT_CLAUDE_MODEL})", "claude_model"))
        col.addView(edit("Gemini API 키", "gemini_key", password = true))
        col.addView(edit("Gemini 모델 (기본 ${Settings.DEFAULT_GEMINI_MODEL})", "gemini_model"))
        col.addView(edit("작품 정보 (선택) 예: 미국 범죄 드라마, 주인공 형사 둘은 서로 반말", "work_info", multiline = true))

        // ---- 화면 ----
        col.addView(section("자막 모양"))
        col.addView(seek("글자 크기", "font_size", 14, 40, 22, "sp"))
        col.addView(seek("배경 진하기", "bg_alpha", 0, 100, 55, "%"))
        col.addView(seek("자막 사라지는 시간", "hide_delay_sec", 2, 10, 4, "초"))
        col.addView(switch("영어 원문도 작게 표시", "show_original", false))

        // ---- 광고 창 차단 ----
        col.addView(section("광고 창 차단"))
        col.addView(switch("영상 재생 시 열리는 광고 창 차단", "ad_block", true))
        col.addView(switch("자막이 나온 사이트를 자동으로 보호", "ad_auto", true))
        col.addView(edit("보호할 사이트 (쉼표/줄바꿈 구분) 예: myvideosite.com", "ad_sites", multiline = true))
        col.addView(edit("넘어가도 되는 사이트 (선택)", "ad_allow", multiline = true))
        col.addView(note(
            "• 보호 사이트에 있다가 갑자기 다른 사이트로 넘어가거나 새 탭이 열리면 자동으로 '뒤로 가기'를 눌러 닫습니다.\n" +
                "• 몇 초 안에 Play 스토어나 다른 브라우저가 열려도 막습니다.\n" +
                "• 주소창에 직접 입력하거나 탭 목록에서 고른 이동은 막지 않습니다.\n" +
                "• 보호 사이트에서 링크를 눌러 다른 사이트로 가는 것도 광고로 보고 막으니, 필요하면 '넘어가도 되는 사이트'에 추가하세요.\n" +
                "• 크롬: 설정 → 사이트 설정 → '팝업 및 리디렉션'을 차단으로 두면 함께 효과가 좋습니다."
        ))

        // ---- 테스트 ----
        col.addView(section("번역 테스트"))
        val input = EditText(this).apply {
            setText("Hey, are you coming or not? We're gonna miss the whole thing.")
            textSize = 15f
        }
        col.addView(input)
        col.addView(button("번역해 보기") { runTest(input.text.toString()) })
        testResult = text("", 15).also { col.addView(it) }
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        statusView.text = if (on) "● 서비스 실행 중 — 영상을 틀고 실시간 자막을 켜 보세요" else "○ 서비스가 꺼져 있어요 — ①을 눌러 켜 주세요"
        statusView.setTextColor(if (on) Color.parseColor("#4CAF50") else Color.parseColor("#FF7043"))
    }

    override fun onDestroy() {
        testTranslator?.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = SysSettings.Secure.getString(contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, CaptionService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openLiveCaption() {
        val intents = listOf(
            Intent("com.android.settings.action.live_caption"),
            Intent("android.settings.CAPTIONING_SETTINGS"),
            Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        for (i in intents) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
    }

    private fun translator(): HybridTranslator {
        val s = Settings.load(this)
        val t = testTranslator ?: HybridTranslator(s).also { testTranslator = it }
        t.settings = s
        return t
    }

    private fun downloadModel() {
        toast("모델 다운로드 중…")
        translator().prepareModel { ok, err ->
            toast(if (ok) "ML Kit 모델 준비 완료" else "다운로드 실패: $err")
        }
    }

    private fun runTest(src: String) {
        if (src.isBlank()) return
        val s = Settings.load(this)
        val t = translator()
        val sb = StringBuilder()
        testResult.text = "번역 중…"
        fun update() { testResult.text = sb.toString() }

        if (s.mode != EngineMode.LLM) {
            val t0 = SystemClock.elapsedRealtime()
            t.prepareModel { ok, err ->
                if (!ok) { sb.append("ML Kit: 모델 없음 ($err)\n\n"); update(); return@prepareModel }
                t.quick(src) { ko ->
                    sb.append("ML Kit (${SystemClock.elapsedRealtime() - t0}ms)\n${ko ?: "실패"}\n\n"); update()
                }
            }
        }
        if (s.mode != EngineMode.MLKIT) {
            if (s.apiKey.isBlank()) { sb.append("LLM: API 키를 입력하세요\n"); update(); return }
            io.execute {
                val t0 = SystemClock.elapsedRealtime()
                val r = runCatching { LlmClient.translate(s, src, emptyList()) }
                val dt = SystemClock.elapsedRealtime() - t0
                main.post {
                    sb.append("${s.provider.name} ${s.model} (${dt}ms)\n")
                    sb.append(r.getOrElse { "오류: ${it.message}" }).append("\n")
                    update()
                }
            }
        }
    }

    // ---------------- 간단한 UI 헬퍼 ----------------
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun lp(top: Int = 6) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun title(t: String, size: Int) = TextView(this).apply {
        text = t; textSize = size.toFloat(); typeface = Typeface.DEFAULT_BOLD; layoutParams = lp(0)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; layoutParams = lp(24)
    }

    private fun text(t: String, size: Int) = TextView(this).apply {
        text = t; textSize = size.toFloat(); layoutParams = lp(8); setTextIsSelectable(true)
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; alpha = 0.8f; layoutParams = lp(10)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; layoutParams = lp(8); setOnClickListener { onClick() }
    }

    private fun switch(t: String, key: String, def: Boolean) = Switch(this).apply {
        text = t; textSize = 15f; layoutParams = lp(12)
        isChecked = prefs.getBoolean(key, def)
        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
    }

    private fun radio(key: String, options: List<Pair<String, String>>, def: String) = RadioGroup(this).apply {
        layoutParams = lp(4)
        val cur = prefs.getString(key, def)
        options.forEachIndexed { i, (value, label) ->
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId(); text = label; tag = value; textSize = 14f
                isChecked = value == cur
            })
        }
        setOnCheckedChangeListener { g, id ->
            val v = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            prefs.edit().putString(key, v).apply()
        }
    }

    private fun edit(hintText: String, key: String, password: Boolean = false, multiline: Boolean = false) =
        EditText(this).apply {
            hint = hintText; textSize = 14f; layoutParams = lp(6)
            setText(prefs.getString(key, ""))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(key, s?.toString() ?: "").apply()
                }
            })
        }

    private fun seek(label: String, key: String, min: Int, max: Int, def: Int, unit: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = lp(10) }
        val tv = TextView(this).apply { textSize = 14f }
        val cur = prefs.getInt(key, def).coerceIn(min, max)
        tv.text = "$label: $cur$unit"
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$label: ${p + min}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt(key, (s?.progress ?: 0) + min).apply()
                }
            })
        }
        box.addView(tv); box.addView(sb)
        return box
    }
}
