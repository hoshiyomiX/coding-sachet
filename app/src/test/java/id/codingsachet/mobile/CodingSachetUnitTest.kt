package id.codingsachet.mobile

import id.codingsachet.mobile.api.Providers
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.data.ToolCall
import id.codingsachet.mobile.runtime.DumpFormat
import id.codingsachet.mobile.ui.EnStrings
import id.codingsachet.mobile.ui.IdStrings
import id.codingsachet.mobile.ui.SachetStrings
import id.codingsachet.mobile.ui.stringsFor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the app's non-Android logic (run by CI before any
 * APK is assembled — see .github/workflows/release.yml "Kotlin unit tests").
 * No Context / no Compose UI: data classes, provider gating, wire format,
 * localization parity.
 */
class CodingSachetUnitTest {

    // ---------- Providers: free-model gating + lookup fallbacks ----------

    @Test
    fun nimVerifiedModelsAreFree() {
        val nim = Providers.NVIDIA
        assertTrue("katalog NIM verified kosong", nim.verifiedModels.isNotEmpty())
        nim.verifiedModels.forEach { id ->
            assertTrue("model verified $id seharusnya free", nim.isFreeModel(id))
        }
    }

    @Test
    fun nimDefaultModelIsVerifiedFree() {
        val nim = Providers.NVIDIA
        assertTrue(
            "default model ${nim.defaultModel} harus ada di verifiedModels",
            nim.defaultModel in nim.verifiedModels,
        )
        assertTrue(nim.isFreeModel(nim.defaultModel))
    }

    @Test
    fun nimUnverifiedCatalogueModelIsNotFree() {
        // Entri katalog NIM yang tidak menjawab chat utk key biasa
        // (embedding/rerank/404) tidak boleh berbadge free.
        assertFalse(Providers.NVIDIA.isFreeModel("nv-embedqa-e5-v5"))
        assertFalse(Providers.NVIDIA.isFreeModel("model/ngasal"))
    }

    @Test
    fun apinexFreePrefixGating() {
        val apx = Providers.APINEX
        assertTrue(apx.isFreeModel("free/glm-5.3"))
        assertFalse(apx.isFreeModel("gpt-4o"))
        assertTrue("APInex harus mengandalkan prefiks free/, bukan verifiedModels", apx.verifiedModels.isEmpty())
        // Free tier broader than the prefix (allowFree catalogue, probed
        // 2026-11-09): premium models on the free daily allowance.
        assertTrue("model allowFree seharusnya free", apx.isFreeModel("claude-opus-5.5"))
        assertTrue(apx.isFreeModel("gpt-6.1-terra"))
    }

    @Test
    fun workingModelDots() {
        // GREEN dot = verified working; RED = unverified (owner order).
        val nim = Providers.NVIDIA
        assertTrue(nim.isWorkingModel(nim.defaultModel))
        assertFalse("model tak terverifikasi NIM harus dot merah", nim.isWorkingModel("nv-embedqa-e5-v5"))
        // APInex: whole catalogue was healthy at audit time → all green.
        val apx = Providers.APINEX
        assertTrue(apx.isWorkingModel("free/glm-5.3"))
        assertTrue(apx.isWorkingModel("model-baru-belum-terdaftar"))
    }

    @Test
    fun providerLookupFallsBackToApinex() {
        assertSame(Providers.NVIDIA, Providers.byId("nvidia"))
        assertSame(Providers.APINEX, Providers.byId("apinex"))
        assertSame(Providers.APINEX, Providers.byId(null))
        assertSame(Providers.APINEX, Providers.byId("bogus"))
    }

    @Test
    fun providersHaveDistinctIdsAndValidUrls() {
        assertEquals(2, Providers.ALL.size)
        assertEquals(2, Providers.ALL.map { it.id }.toSet().size)
        Providers.ALL.forEach { p ->
            assertTrue("baseUrl ${p.baseUrl} harus https", p.baseUrl.startsWith("https://"))
            assertTrue("docsUrl ${p.docsUrl} harus https", p.docsUrl.startsWith("https://"))
        }
    }

    // ---------- Chat persistence wire format ----------

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionRoundTripsThroughJson() {
        val s = Session(
            id = "abc123",
            title = "sesi uji",
            created = 42L,
            model = "free/glm-5.3",
            messages = mutableListOf(
                Msg(role = "user", content = "halo"),
                Msg(
                    role = "assistant",
                    content = "sebentar",
                    thinking = "pikiran internal",
                    toolCalls = listOf(
                        ToolCall(
                            id = "t1",
                            name = "bash",
                            args = Json.parseToJsonElement("""{"cmd":"ls"}"""),
                        ),
                    ),
                ),
                Msg(role = "tool", content = "keluaran", toolCallId = "t1", name = "bash"),
            ),
        )
        val back = json.decodeFromString<Session>(json.encodeToString(s))
        assertEquals(s.id, back.id)
        assertEquals(s.title, back.title)
        assertEquals(s.created, back.created)
        assertEquals(s.model, back.model)
        assertEquals(s.messages.size, back.messages.size)
        assertEquals(s.messages[1].thinking, back.messages[1].thinking)
        assertEquals(s.messages[1].toolCalls?.size, back.messages[1].toolCalls?.size)
        assertEquals(s.messages[1].toolCalls?.get(0)?.name, back.messages[1].toolCalls?.get(0)?.name)
        assertEquals(s.messages[2].toolCallId, back.messages[2].toolCallId)
    }

    @Test
    fun sessionDecodingIgnoresUnknownFields() {
        // Wire format harus tahan terhadap field baru dari versi server/app lanjutan.
        val text = """{"id":"x","title":"t","created":1,"model":"m","futureField":true}"""
        val back = json.decodeFromString<Session>(text)
        assertEquals("x", back.id)
        assertEquals("m", back.model)
    }

    @Test
    fun toolCallDefaultArgsIsEmptyObject() {
        val tc = ToolCall(id = "i", name = "write")
        assertTrue(tc.args is JsonObject)
        assertTrue((tc.args as JsonObject).isEmpty())
    }

    @Test
    fun wireHistoryReturnsSnapshot() {
        val s = Session(id = "i", title = "t", created = 1, model = "m")
        s.messages.add(Msg(role = "user", content = "a"))
        val snapshot = s.wireHistory()
        s.messages.add(Msg(role = "user", content = "b"))
        assertEquals(1, snapshot.size)
        assertEquals(2, s.messages.size)
    }

    // ---------- Localization parity (ID vs EN) ----------

    @Test
    fun localizationNoBlankFieldsEitherLanguage() {
        // Compose compiler menambahkan field statis sintetis ($stable) pada
        // class stabil — hanya field instance (semuanya String) yang diperiksa.
        val fields = SachetStrings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        for (inst in listOf(IdStrings, EnStrings)) {
            val label = if (inst === IdStrings) "ID" else "EN"
            for (f in fields) {
                f.isAccessible = true
                val v = f.get(inst) as String
                assertTrue("string $label.${f.name} kosong/blank", v.isNotBlank())
            }
        }
    }

    @Test
    fun localizationCoversLargeVocabulary() {
        // Guard regresi: layout lokalasi keduanya sama lebar (data class
        // menjamin tipe sama; jumlah field instance dikunci di sini —
        // field statis sintetis compose dikecualikan).
        val n = SachetStrings::class.java.declaredFields
            .count { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue("kolom strings $n terlalu sedikit — ada yang hilang?", n >= 100)
    }

    @Test
    fun stringsForLanguageFallbackIsIndonesian() {
        assertSame(EnStrings, stringsFor("en"))
        assertSame(IdStrings, stringsFor("id"))
        assertSame(IdStrings, stringsFor("xyz"))
        assertSame(IdStrings, stringsFor(""))
    }

    // ---------- Logcat dump v2 (T-ML25: "selengkap logcat") ----------

    @Test
    fun logcatDumpCommandCapturesAllOwnUidTags() {
        // No tag whitelist (-s ...) anymore — the dump keeps EVERY line the
        // UID may read; threadtime adds PID+TID, year adds the full date.
        assertTrue(DumpFormat.COMMAND.startsWith("logcat -d "))
        assertTrue("harus pakai format threadtime", DumpFormat.COMMAND.contains("threadtime"))
        assertTrue("harus menyertakan tahun", DumpFormat.COMMAND.contains("year"))
        assertFalse("filter tag -s tidak boleh ada lagi", DumpFormat.COMMAND.contains("-s"))
    }

    @Test
    fun logcatTailTrimSnapsToLineStart() {
        val lines = (1..500).joinToString("") { "line-$it-0123456789abcdef\n" }
        val bytes = lines.toByteArray()

        // Fits -> no trim.
        assertEquals(0, DumpFormat.tailStart(bytes, bytes.size + 10))

        // Overflow -> keep the newest maxBytes, snapped to a line boundary.
        val start = DumpFormat.tailStart(bytes, 100)
        assertTrue(start > 0)
        val kept = String(bytes, start, bytes.size - start, Charsets.UTF_8)
        assertTrue("ekor harus <= batas", kept.toByteArray().size <= 100)
        assertTrue("harus mulai di awal baris", kept.startsWith("line-"))
        assertTrue(kept.endsWith("\n"))
    }
}
