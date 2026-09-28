package io.github.whatabeautifulmemory.readman

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val card = mapOf("name" to "홍길동", "organization" to "금융보안원", "department" to "보안연구부", "team" to "", "title" to "선임")

    @Test fun templateSubstitutesAndDropsBlank() {
        assertEquals("보안연구부", renderTemplate("{department} {team}", card))
        assertEquals("홍길동 / 선임", renderTemplate("{name} / {title}", card))
        assertEquals("", renderTemplate("{nope}", card))
        assertEquals("금융보안원 X", renderTemplate("{소속기관} {org-name}", mapOf("소속기관" to "금융보안원", "org-name" to "X")))
        assertEquals("{ } {}", renderTemplate("{ } {}", card))
        val c = contactFromTemplates(card, DEFAULT_TEMPLATES)
        assertEquals("홍길동", c["display_name"]); assertEquals("금융보안원", c["company"])
        assertTrue("blank fields must not be present", "phone_work" !in c)
    }

    @Test fun lenientJsonSurvivesFencesAndProse() {
        val o = extractJsonObject("Sure! ```json\n{\"name\": \"A\", \"phone\": null, \"email\": [\"a@b.c\", \"\"], \"nested\": {\"x\": 1}}\n```")!!
        val f = flatten(o)
        assertEquals("A", f["name"]); assertEquals("", f["phone"]); assertEquals("a@b.c", f["email"]); assertEquals("1", f["nested"])
        assertNull(extractJsonObject("no json here"))
        assertNull(extractJsonObject("{broken"))
    }

    @Test fun openAiWireShape() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"name\":\"김철수\",\"organization\":\"ACME\",\"mobile\":\"010-1111-2222\"}"}}]}"""))
        val p = providerById("opencode-go")
        val a = LlmClient().analyze(p, srv.url("/zen/go/v1").toString(), "k", "kimi-k3", "SYS", false, DEFAULT_TEMPLATES, "AAAA")
        val req = srv.takeRequest()
        assertEquals("/zen/go/v1/chat/completions", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("kimi-k3", body.getString("model"))
        val msgs = body.getJSONArray("messages")
        assertEquals("SYS", msgs.getJSONObject(0).getString("content"))
        val user = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("data:image/jpeg;base64,AAAA", user.getJSONObject(0).getJSONObject("image_url").getString("url"))
        assertEquals("text", user.getJSONObject(1).getString("type"))
        assertEquals("김철수", a.contact["display_name"]); assertEquals("010-1111-2222", a.contact["phone_mobile"])
        assertEquals("ACME", a.contact["company"])
    }

    @Test fun anthropicWireShapeAndLlmMapping() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"{\"name\":\"이영희\",\"contact\":{\"display_name\":\"이영희 팀장\",\"company\":\"X\",\"note\":null}}"}]}"""))
        val p = providerById("anthropic")
        val a = LlmClient().analyze(p, srv.url("/v1").toString(), "sk-ant", "claude-sonnet-5", "SYS", true, DEFAULT_TEMPLATES, "BBBB")
        val req = srv.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant", req.getHeader("x-api-key")); assertNull(req.getHeader("Authorization"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val body = JSONObject(req.body.readUtf8())
        assertTrue(body.getString("system").endsWith(LLM_MAPPING_SUFFIX))
        assertEquals(16000, body.getInt("max_tokens"))
        val img = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("image", img.getString("type")); assertEquals("BBBB", img.getJSONObject("source").getString("data"))
        assertEquals("이영희 팀장", a.contact["display_name"]); assertEquals("X", a.contact["company"])
        assertTrue("null note must be dropped", "note" !in a.contact)
        assertTrue("contact must not leak into card", "contact" !in a.card)
    }

    @Test fun llmMappingFallsBackToTemplatesWhenContactMissing() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"name\":\"N\"}"}}]}"""))
        val a = LlmClient().analyze(providerById("openai"), srv.url("/v1").toString(), "k", "m", "S", true, DEFAULT_TEMPLATES, "x")
        assertEquals("N", a.contact["display_name"]); assertTrue("fallback is not an LLM decision", !a.llmDecided)
    }

    @Test fun anthropicTruncationIsNamed() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setBody("""{"stop_reason":"max_tokens","content":[{"type":"text","text":"{\"name\":\"trunc"}]}"""))
        val err = runCatching { LlmClient().analyze(providerById("anthropic"), srv.url("/v1").toString(), "k", "m", "S", false, DEFAULT_TEMPLATES, "x") }.exceptionOrNull()
        assertTrue(err is LlmError && err.code == "truncated")
    }

    @Test fun customHeaderAndOptionalKey() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}"""))
        LlmClient().analyze(providerById("opencodex"), srv.url("/v1").toString(), "ocx_1", "m", "S", false, DEFAULT_TEMPLATES, "x")
        assertEquals("ocx_1", srv.takeRequest().getHeader("x-opencodex-api-key"))
        srv.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}"""))
        LlmClient().analyze(providerById("ollama"), srv.url("/v1").toString(), "", "m", "S", false, DEFAULT_TEMPLATES, "x")
        assertNull("keyless must send no auth header", srv.takeRequest().getHeader("Authorization"))
        val err = runCatching { LlmClient().analyze(providerById("openai"), srv.url("/v1").toString(), "", "m", "S", false, DEFAULT_TEMPLATES, "x") }.exceptionOrNull()
        assertTrue(err is LlmError && err.code == "no_key" && err.arg == "OpenAI")
        // A key with a stray control char must be refused before OkHttp can echo it in an exception.
        val bad = runCatching { LlmClient().analyze(providerById("anthropic"), srv.url("/v1").toString(), "sk-ant\u0007x", "m", "S", false, DEFAULT_TEMPLATES, "x") }.exceptionOrNull()
        assertTrue(bad is LlmError && bad.code == "bad_key" && !bad.message!!.contains("sk-ant"))
        assertEquals("trailing whitespace is trimmed, not rejected", 0, srv.requestCount - 2)
    }

    @Test fun httpErrorSurfacesStatusAndBody() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        val err = runCatching { LlmClient().analyze(providerById("openai"), srv.url("/v1").toString(), "k", "m", "S", false, DEFAULT_TEMPLATES, "x") }.exceptionOrNull()
        assertTrue(err is LlmError && err.code == "http" && err.arg.startsWith("401") && err.arg.contains("bad key"))
    }

    @Test fun modelLists() = MockWebServer().use { srv ->
        srv.enqueue(MockResponse().setBody("""{"data":[{"id":"b"},{"id":"a"}]}"""))
        assertEquals(listOf("a", "b"), LlmClient().listModels(providerById("openai"), srv.url("/v1").toString(), "k"))
        assertEquals("/v1/models", srv.takeRequest().path)
        srv.enqueue(MockResponse().setBody("""{"models":[{"name":"qwen3-vl:8b"}]}"""))
        assertEquals(listOf("qwen3-vl:8b"), LlmClient().listModels(providerById("ollama"), srv.url("/v1").toString(), ""))
        assertEquals("/api/tags", srv.takeRequest().path)
    }

    @Test fun providerTableSanity() {
        for (id in listOf("opencode-go", "opencodex", "anthropic", "deepseek", "openai-compatible", "ollama")) assertEquals(id, providerById(id).id)
        assertEquals(PROVIDERS.size, PROVIDERS.map { it.id }.toSet().size)
        assertTrue(DEFAULT_TEMPLATES.keys == CONTACT_FIELDS.toSet())
    }
}
