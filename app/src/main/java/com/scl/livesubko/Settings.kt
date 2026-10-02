package com.scl.livesubko

import android.content.Context
import android.content.SharedPreferences

enum class EngineMode { HYBRID, MLKIT, LLM }
enum class Provider { CLAUDE, GEMINI }

/** 앱 전체 설정. SharedPreferences 한 곳에서 읽고 씁니다. */
data class Settings(
    val enabled: Boolean,
    val mode: EngineMode,
    val provider: Provider,
    val claudeKey: String,
    val claudeModel: String,
    val geminiKey: String,
    val geminiModel: String,
    val fontSizeSp: Int,
    val bgAlpha: Int,          // 0~100 (%)
    val showOriginal: Boolean,
    val workInfo: String,      // 작품 제목/등장인물/말투 힌트
    val overlayYFraction: Float,
    val hideDelayMs: Long,
    // ---- 광고 창 차단 ----
    val adBlock: Boolean,
    val adAuto: Boolean,           // 자막이 나온 사이트를 자동으로 보호
    val adSites: List<String>,     // 직접 지정한 보호 사이트
    val adAllow: List<String>,     // 넘어가도 되는 사이트
) {
    val apiKey: String get() = if (provider == Provider.CLAUDE) claudeKey else geminiKey
    val model: String get() = if (provider == Provider.CLAUDE) claudeModel else geminiModel
    val llmUsable: Boolean get() = mode != EngineMode.MLKIT && apiKey.isNotBlank()
    val mlkitUsable: Boolean get() = mode != EngineMode.LLM

    companion object {
        const val PREFS = "settings"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-4-5"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"

        private fun hostList(raw: String): List<String> =
            raw.split(Regex("[,\\s]+")).mapNotNull { AdGuard.parseHost(it) }.distinct()

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(ctx: Context): Settings {
            val p = prefs(ctx)
            return Settings(
                enabled = p.getBoolean("enabled", true),
                mode = runCatching { EngineMode.valueOf(p.getString("mode", "HYBRID")!!) }
                    .getOrDefault(EngineMode.HYBRID),
                provider = runCatching { Provider.valueOf(p.getString("provider", "CLAUDE")!!) }
                    .getOrDefault(Provider.CLAUDE),
                claudeKey = p.getString("claude_key", "")!!.trim(),
                claudeModel = p.getString("claude_model", "")!!.trim().ifEmpty { DEFAULT_CLAUDE_MODEL },
                geminiKey = p.getString("gemini_key", "")!!.trim(),
                geminiModel = p.getString("gemini_model", "")!!.trim().ifEmpty { DEFAULT_GEMINI_MODEL },
                fontSizeSp = p.getInt("font_size", 22),
                bgAlpha = p.getInt("bg_alpha", 55),
                showOriginal = p.getBoolean("show_original", false),
                workInfo = p.getString("work_info", "")!!.trim(),
                overlayYFraction = p.getFloat("overlay_y", 0.72f),
                hideDelayMs = p.getInt("hide_delay_sec", 4).toLong() * 1000L,
                adBlock = p.getBoolean("ad_block", true),
                adAuto = p.getBoolean("ad_auto", true),
                adSites = hostList(p.getString("ad_sites", "")!!),
                adAllow = hostList(p.getString("ad_allow", "")!!),
            )
        }
    }
}
