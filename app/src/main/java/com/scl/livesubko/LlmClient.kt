package com.scl.livesubko

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Claude / Gemini 로 "자연스러운 영화 자막체" 번역을 요청합니다.
 * 블로킹 호출이므로 반드시 백그라운드 스레드에서 사용하세요.
 * (HttpURLConnection 은 keep-alive 로 연결을 재사용하므로 두 번째 요청부터 지연이 줄어듭니다.)
 */
object LlmClient {

    private fun systemPrompt(workInfo: String): String = buildString {
        append(
            """
            너는 넷플릭스급 영화·드라마 한국어 자막 번역가다. 영어 대사를 한국어 자막으로 옮긴다.
            규칙:
            - 직역하지 말고, 한국 배우가 실제로 말할 법한 자연스러운 구어체로 번역한다.
            - 자막이므로 짧고 간결하게. 원문보다 길어지지 않게 하고 군더더기 말은 뺀다.
            - 앞 대사의 흐름을 보고 반말/존댓말, 인물 관계, 말투(거친 말, 농담, 비꼼)를 일관되게 유지한다.
            - 욕설·감탄사는 수위를 살려 한국어 표현으로 바꾼다. (예: "Oh my God" → "세상에", "Damn it" → "젠장")
            - 관용구·속어는 뜻을 살려 의역한다. 인명·지명은 한글 음역한다.
            - 문장이 중간에 끊겨 있으면 끊긴 그대로 자연스럽게 번역한다. 뒷내용을 지어내지 않는다.
            - [음악], [웃음] 같은 효과음 표기는 한국어 대괄호 표기로 옮긴다.
            - 설명, 따옴표, "번역:" 같은 머리말 없이 번역된 자막 문장만 출력한다.
            """.trimIndent()
        )
        if (workInfo.isNotBlank()) {
            append("\n\n작품 정보(참고): ")
            append(workInfo)
        }
    }

    private fun userPrompt(text: String, context: List<Pair<String, String>>): String = buildString {
        if (context.isNotEmpty()) {
            append("[앞 대사 흐름]\n")
            for ((en, ko) in context) {
                append("EN: ").append(en).append('\n')
                append("KO: ").append(ko).append('\n')
            }
            append('\n')
        }
        append("[번역할 대사]\n")
        append(text)
    }

    @Throws(IOException::class)
    fun translate(s: Settings, text: String, context: List<Pair<String, String>>): String {
        val sys = systemPrompt(s.workInfo)
        val user = userPrompt(text, context)
        val raw = when (s.provider) {
            Provider.CLAUDE -> claude(s.claudeKey, s.claudeModel, sys, user)
            Provider.GEMINI -> gemini(s.geminiKey, s.geminiModel, sys, user)
        }
        return clean(raw)
    }

    private fun claude(key: String, model: String, sys: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 300)
            .put("temperature", 0.3)
            .put("system", sys)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", user)
                )
            )
        val res = post(
            "https://api.anthropic.com/v1/messages",
            mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
            ),
            body.toString(),
        )
        val content = JSONObject(res).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    private fun gemini(key: String, model: String, sys: String, user: String): String {
        val gen = JSONObject()
            .put("temperature", 0.3)
            .put("maxOutputTokens", 300)
        // 2.5 계열은 생각(thinking)을 끄면 훨씬 빨라집니다.
        if (model.contains("2.5")) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", gen)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent"
        val res = post(url, mapOf("x-goog-api-key" to key), body.toString())
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        // disconnect() 는 keep-alive 연결을 끊어버리므로 호출하지 않음 (스트림만 닫음)
        run {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json; charset=utf-8")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IOException("HTTP $code ${msg ?: text.take(200)}")
            }
            return text
        }
    }

    /** 모델이 가끔 붙이는 머리말·따옴표 제거 */
    fun clean(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("KO:").removePrefix("번역:").trim()
        if (t.length >= 2 && (t.first() == '"' && t.last() == '"' || t.first() == '“' && t.last() == '”')) {
            t = t.substring(1, t.length - 1).trim()
        }
        // 두 줄 이상이면 자막은 최대 2줄까지만
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("EN:") }
        return lines.take(2).joinToString("\n")
    }
}
