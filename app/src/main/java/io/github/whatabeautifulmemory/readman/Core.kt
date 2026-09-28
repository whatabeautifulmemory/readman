// Pure JVM core: provider table, request/response shapes, lenient JSON, template mapping.
// No Android imports on purpose — everything here is covered by app/src/test.
package io.github.whatabeautifulmemory.readman

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- schemas

/** Keys the LLM reads off the card. The default prompt defines them; templates reference them. */
val CARD_FIELDS = listOf(
    "name", "name_en", "organization", "department", "team", "title",
    "phone", "mobile", "fax", "email", "address", "website", "other",
)

/** Keys that map 1:1 onto ContactsContract rows. Both mapping modes end here. */
val CONTACT_FIELDS = listOf(
    "display_name", "company", "department", "job_title",
    "phone_work", "phone_mobile", "phone_fax", "email", "address", "website", "note",
)

val DEFAULT_TEMPLATES = mapOf(
    "display_name" to "{name}", "company" to "{organization}", "department" to "{department} {team}",
    "job_title" to "{title}", "phone_work" to "{phone}", "phone_mobile" to "{mobile}", "phone_fax" to "{fax}",
    "email" to "{email}", "address" to "{address}", "website" to "{website}", "note" to "{other}",
)

const val DEFAULT_SYSTEM_PROMPT = """당신은 명함 이미지를 읽어 구조화된 데이터로 변환하는 도우미입니다.
이미지 속 명함 한 장의 정보를 읽고, 아래 키를 가진 JSON 객체 하나만 출력하세요. 설명이나 코드 펜스 없이 JSON만 출력합니다.

{
  "name": "이름(명함에 인쇄된 표기 그대로)",
  "name_en": "영문 이름(있으면)",
  "organization": "소속 기관/회사",
  "department": "소속 부서(본부/실/국 등)",
  "team": "소속 팀/과",
  "title": "직위/직책",
  "phone": "유선 전화번호",
  "mobile": "휴대전화번호",
  "fax": "팩스번호",
  "email": "이메일",
  "address": "주소",
  "website": "웹사이트",
  "other": "그 외 정보(SNS, 자격, 슬로건 등 자유 텍스트)"
}

규칙:
- 없는 항목은 빈 문자열 "" 로 둡니다. 추측해서 채우지 마세요.
- 전화번호는 명함에 적힌 대로 옮기되 구분자는 하이픈(-)으로 통일합니다. (예: +82-2-1234-5678, 010-1234-5678)
- 이메일과 URL은 오타 없이 정확히 옮깁니다.
- 한국어와 영어가 함께 있으면 한국어를 우선하고 영문 이름은 name_en에 넣습니다.
- 번호가 여러 개면 대표번호를 phone에, 나머지는 other에 적습니다."""

/** Appended to the system prompt when the LLM, not the templates, decides the contact fields. */
const val LLM_MAPPING_SUFFIX = """

추가로, 같은 JSON 객체에 "contact" 키를 넣어 주소록에 저장할 최종 형태를 직접 결정하세요. contact 객체의 키는 정확히 다음과 같습니다:
display_name(주소록 표시 이름), company(회사/기관), department(부서), job_title(직함), phone_work(직장 전화), phone_mobile(휴대전화), phone_fax(팩스), email(이메일), address(주소), website(웹사이트), note(메모)
각 값은 주소록에 그대로 들어가므로 사람이 보기 좋은 최종 문자열로 작성하세요(예: 부서와 팀을 합쳐 "정보보호본부 침해대응팀", 직함이 여럿이면 "/"로 연결, 남는 정보는 note에). 해당 없는 키는 "" 로 둡니다."""

const val USER_PROMPT = "이 명함을 분석해 지시된 JSON만 출력하세요."

// ---------------------------------------------------------------- providers

enum class Transport { OPENAI, ANTHROPIC }
enum class ModelsSource { OPENAI, OLLAMA }

/**
 * Mirrors Vineyard's ProviderDef: identity is data, protocol is code. Two wire shapes exist
 * (OpenAI chat/completions, Anthropic messages); everything else is a row.
 */
data class Provider(
    val id: String,
    val label: String,
    val baseURL: String,
    val models: List<String> = emptyList(),
    val keyHint: String = "API key",
    val transport: Transport = Transport.OPENAI,
    /** Header carrying the key. "bearer" → `Authorization: Bearer …`; anything else is a header name. */
    val authHeader: String = "bearer",
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Local/self-hosted rows: the user may point them elsewhere (LAN Ollama, a remote gateway). */
    val endpointEditable: Boolean = false,
    val keyOptional: Boolean = false,
    val modelsSource: ModelsSource = ModelsSource.OPENAI,
)

val PROVIDERS = listOf(
    Provider("anthropic", "Anthropic (Claude)", "https://api.anthropic.com/v1",
        models = listOf("claude-sonnet-5", "claude-haiku-4-5", "claude-opus-5"),
        keyHint = "sk-ant-… (console.anthropic.com)", transport = Transport.ANTHROPIC,
        authHeader = "x-api-key", extraHeaders = mapOf("anthropic-version" to "2023-06-01")),
    Provider("openai", "OpenAI", "https://api.openai.com/v1",
        models = listOf("gpt-5.5", "gpt-5.4-mini"), keyHint = "sk-… (platform.openai.com)"),
    Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        models = listOf("gemini-3.8-flash", "gemini-3.5-flash"), keyHint = "AIza… (aistudio.google.com)"),
    Provider("deepseek", "DeepSeek", "https://api.deepseek.com",
        models = listOf("deepseek-flash"), keyHint = "sk-… (platform.deepseek.com)"),
    Provider("kimi", "Kimi (Moonshot)", "https://api.moonshot.ai/v1",
        models = listOf("kimi-k3", "kimi-k2.6"), keyHint = "sk-… (platform.moonshot.ai)"),
    Provider("xai", "xAI (Grok)", "https://api.x.ai/v1",
        models = listOf("grok-4.6", "grok-4.5"), keyHint = "xai-… (console.x.ai)"),
    Provider("mistral", "Mistral", "https://api.mistral.ai/v1",
        models = listOf("mistral-medium-latest", "mistral-large-latest"), keyHint = "console.mistral.ai"),
    Provider("groq", "Groq", "https://api.groq.com/openai/v1",
        // No seed: Groq retires vision models fast (llama-4-scout was shut down 2026-07); fetch the list.
        keyHint = "gsk_… (console.groq.com)"),
    Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
        models = listOf("anthropic/claude-sonnet-5", "google/gemini-3.8-flash", "qwen/qwen3.8-max"),
        keyHint = "sk-or-… (openrouter.ai)"),
    Provider("opencode-go", "OpenCode Go", "https://opencode.ai/zen/go/v1",
        models = listOf("kimi-k3", "glm-5.3-flash", "qwen3.8-max", "mimo-v2.5", "deepseek-v4-flash-vision-exp"),
        keyHint = "opencode.ai/auth (Go plan)"),
    Provider("opencode-zen", "OpenCode Zen", "https://opencode.ai/zen/v1",
        models = listOf("claude-sonnet-5", "gemini-3.8-flash", "gpt-5.6-luna"),
        keyHint = "opencode.ai/auth (Zen, pay-as-you-go)"),
    Provider("opencodex", "OpenCodex (gateway)", "http://127.0.0.1:10100/v1",
        keyHint = "ocx_…", authHeader = "x-opencodex-api-key", endpointEditable = true, keyOptional = true),
    Provider("openai-compatible", "OpenAI-compatible endpoint", "http://127.0.0.1:8000/v1",
        keyHint = "…", endpointEditable = true, keyOptional = true),
    Provider("ollama", "Ollama", "http://127.0.0.1:11434/v1",
        models = listOf("qwen3-vl", "gemma3", "llama3.2-vision", "minicpm-v"),
        keyHint = "…", endpointEditable = true, keyOptional = true, modelsSource = ModelsSource.OLLAMA),
)

fun providerById(id: String): Provider = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS.first()

// ---------------------------------------------------------------- templates

// NB: the closing brace MUST be escaped — Android's ICU regex rejects a bare "}" (OpenJDK tolerates it,
// so a JVM test cannot catch the resulting crash-on-launch). Character class, not \w, so Korean keys work.
private val PLACEHOLDER = Regex("\\{([^{}\\s]+)\\}")

/** `{key}` substitution; unknown or empty keys vanish. Result is trimmed. */
fun renderTemplate(template: String, card: Map<String, String>): String =
    PLACEHOLDER.replace(template) { card[it.groupValues[1]] ?: "" }
        .replace(Regex("[ \\t]{2,}"), " ").trim()

fun contactFromTemplates(card: Map<String, String>, templates: Map<String, String>): Map<String, String> =
    CONTACT_FIELDS.associateWith { f -> renderTemplate(templates[f] ?: DEFAULT_TEMPLATES[f] ?: "", card) }
        .filterValues { it.isNotBlank() }

// ---------------------------------------------------------------- lenient JSON

/** Finds the outermost `{…}` in an LLM reply, tolerating prose and ``` fences around it. */
fun extractJsonObject(text: String): JSONObject? {
    val s = text.indexOf('{'); val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return runCatching { JSONObject(text.substring(s, e + 1)) }.getOrNull()
}

/** Any JSON value → display string. null → "", arrays joined, nested objects flattened. */
fun jsonToText(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is JSONArray -> (0 until v.length()).map { jsonToText(v.opt(it)) }.filter { it.isNotBlank() }.joinToString(", ")
    is JSONObject -> v.keys().asSequence().map { jsonToText(v.opt(it)) }.filter { it.isNotBlank() }.joinToString(", ")
    else -> v.toString().trim()
}

/** Every top-level key stringified (so a user-edited prompt can add keys and templates can use them). */
fun flatten(o: JSONObject, skip: Set<String> = emptySet()): Map<String, String> =
    o.keys().asSequence().filter { it !in skip }.associateWith { jsonToText(o.opt(it)) }

// ---------------------------------------------------------------- HTTP

/**
 * Failure with a stable [code] the UI turns into a localized string, plus a free-form [arg].
 * Codes: no_model, no_key(arg=provider label), http(arg="status: body"), bad_response, no_json,
 * truncated, bad_key.
 */
class LlmError(val code: String, val arg: String = "") : Exception("$code ${arg.take(200)}".trim())

data class Analysis(val card: Map<String, String>, val contact: Map<String, String>, val raw: String,
                    /** True only when the contact map came from the LLM, not the template fallback. */
                    val llmDecided: Boolean)

class LlmClient(private val http: OkHttpClient = defaultHttp()) {

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).build()
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private fun headers(p: Provider, key: String): Map<String, String> {
        val k = key.trim()
        if (k.isEmpty()) return p.extraHeaders
        // OkHttp echoes a non-Authorization header's full value in its "Unexpected char" exception,
        // which would put the key on screen. Refuse before it gets there.
        if (k.any { it !in '!'..'~' }) throw LlmError("bad_key")
        val auth = if (p.authHeader == "bearer") mapOf("Authorization" to "Bearer $k") else mapOf(p.authHeader to k)
        return auth + p.extraHeaders
    }

    fun buildBody(p: Provider, model: String, system: String, jpegBase64: String): JSONObject = when (p.transport) {
        Transport.OPENAI -> JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")))
                .put(JSONObject().put("type", "text").put("text", USER_PROMPT)))))
        // max_tokens caps thinking + text on Claude 5 models; 2k was enough to truncate the JSON mid-object.
        Transport.ANTHROPIC -> JSONObject().put("model", model).put("max_tokens", 16000).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)))
                .put(JSONObject().put("type", "text").put("text", USER_PROMPT)))))
    }

    /** The assistant's text, whichever wire shape carried it. */
    fun replyText(p: Provider, body: String): String {
        val o = JSONObject(body)
        return when (p.transport) {
            Transport.OPENAI -> {
                val msg = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                when (val c = msg.opt("content")) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).joinToString("") { c.getJSONObject(it).optString("text") }
                    else -> ""
                }
            }
            Transport.ANTHROPIC -> {
                if (o.optString("stop_reason") == "max_tokens") throw LlmError("truncated")
                val parts = o.getJSONArray("content")
                (0 until parts.length()).map { parts.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
            }
        }
    }

    fun analyze(
        p: Provider, endpoint: String, key: String, model: String,
        systemPrompt: String, llmMapping: Boolean, templates: Map<String, String>, jpegBase64: String,
    ): Analysis {
        if (model.isBlank()) throw LlmError("no_model")
        if (key.isBlank() && !p.keyOptional) throw LlmError("no_key", p.label)
        val system = if (llmMapping) systemPrompt + LLM_MAPPING_SUFFIX else systemPrompt
        val url = endpoint.trimEnd('/') + if (p.transport == Transport.ANTHROPIC) "/messages" else "/chat/completions"
        val req = Request.Builder().url(url).post(buildBody(p, model, system, jpegBase64).toString().toRequestBody(JSON))
            .apply { headers(p, key).forEach { (k, v) -> header(k, v) } }.build()
        val text = http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw LlmError("http", "${r.code}: ${body.take(300)}")
            runCatching { replyText(p, body) }.getOrElse { if (it is LlmError) throw it; throw LlmError("bad_response", body.take(200)) }
        }
        val o = extractJsonObject(text) ?: throw LlmError("no_json", text.take(200))
        val card = flatten(o, skip = setOf("contact"))
        val llmContact = o.optJSONObject("contact")
        val contact = if (llmMapping && llmContact != null)
            CONTACT_FIELDS.associateWith { jsonToText(llmContact.opt(it)) }.filterValues { it.isNotBlank() }
        else contactFromTemplates(card, templates)
        return Analysis(card, contact, text, llmMapping && llmContact != null)
    }

    fun listModels(p: Provider, endpoint: String, key: String): List<String> {
        val base = endpoint.trimEnd('/')
        val url = when (p.modelsSource) {
            ModelsSource.OPENAI -> "$base/models"
            ModelsSource.OLLAMA -> base.removeSuffix("/v1") + "/api/tags"
        }
        val req = Request.Builder().url(url).get().apply { headers(p, key).forEach { (k, v) -> header(k, v) } }.build()
        val body = http.newCall(req).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw LlmError("http", "${r.code}: ${b.take(300)}")
            b
        }
        val o = JSONObject(body)
        val arr = when (p.modelsSource) {
            ModelsSource.OPENAI -> o.getJSONArray("data")
            ModelsSource.OLLAMA -> o.getJSONArray("models")
        }
        val field = if (p.modelsSource == ModelsSource.OLLAMA) "name" else "id"
        return (0 until arr.length()).map { arr.getJSONObject(it).optString(field) }.filter { it.isNotBlank() }.sorted()
    }
}
