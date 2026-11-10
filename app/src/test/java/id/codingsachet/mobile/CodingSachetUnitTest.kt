package id.codingsachet.mobile

import id.codingsachet.mobile.api.ModelStatus
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
import org.junit.Assert.assertNull
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

    // ---------- Providers: static metadata only (owner order T-ML26) ----------

    @Test
    fun providersCarryNoHardcodedModelSets() {
        // "mengecek actual bukan dari membaca hardcoded list": working/free
        // status keputusan RUNTIME (ModelStatus + sweep live). Providers.kt
        // hanya metadata statis — field bertipe Set = regresi daftar terbakar.
        Providers.ALL.forEach { p ->
            p.javaClass.declaredFields.forEach { f ->
                assertFalse(
                    "field ${p.id}.${f.name} bertipe Set — daftar model terbakar dilarang",
                    Set::class.java.isAssignableFrom(f.type),
                )
            }
        }
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

    // ---------- ModelStatus: dot & free dari hasil live (T-ML26) ----------

    @Test
    fun modelStatusDotsFollowLiveResults() {
        ModelStatus.reset()
        // Belum pernah dicek = UNKNOWN (abu-abu), bukan merah palsu.
        assertEquals(ModelStatus.State.UNKNOWN, ModelStatus.stateOf("nvidia", "m1"))
        ModelStatus.apply("nvidia", "m1", ModelStatus.State.OK, true, now = 1000)
        ModelStatus.apply("nvidia", "m2", ModelStatus.State.DEAD, false, now = 1000)
        assertEquals(ModelStatus.State.OK, ModelStatus.stateOf("nvidia", "m1"))
        assertEquals(ModelStatus.State.DEAD, ModelStatus.stateOf("nvidia", "m2"))
        // Provider lain tidak ikut-ikutan
        assertEquals(ModelStatus.State.UNKNOWN, ModelStatus.stateOf("apinex", "m1"))
    }

    @Test
    fun modelStatusFreeCombinesPrefixAndLive() {
        ModelStatus.reset()
        // Prefiks free/ APInex intrinsik — tidak butuh sweep.
        assertTrue(ModelStatus.isFree(Providers.APINEX, "free/apa-saja"))
        // Tanpa hasil live, model non-prefix tidak berbadge free.
        assertFalse(ModelStatus.isFree(Providers.APINEX, "claude-opus-5.5"))
        assertFalse(ModelStatus.isFree(Providers.NVIDIA, "nv-embedqa-e5-v5"))
        // Katalog live (allowFree) dan NIM menjawab = free.
        ModelStatus.apply("apinex", "claude-opus-5.5", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("nvidia", "z-ai/glm-5.3-flash", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("nvidia", "mati/model", ModelStatus.State.DEAD, false, now = 1)
        assertTrue(ModelStatus.isFree(Providers.APINEX, "claude-opus-5.5"))
        assertTrue(ModelStatus.isFree(Providers.NVIDIA, "z-ai/glm-5.3-flash"))
        assertFalse(ModelStatus.isFree(Providers.NVIDIA, "mati/model"))
    }

    @Test
    fun modelStatusStaleness() {
        ModelStatus.reset()
        assertFalse("belum pernah dicek = tidak fresh", ModelStatus.isFresh("nvidia", now = 10_000))
        ModelStatus.apply("nvidia", "m", ModelStatus.State.OK, true, now = 0)
        assertEquals(0L, ModelStatus.lastCheckedAt("nvidia"))
        assertTrue(ModelStatus.isFresh("nvidia", now = ModelStatus.STALE_MS - 1))
        assertFalse("lebih dari 12 jam = basi, sweep ulang", ModelStatus.isFresh("nvidia", now = ModelStatus.STALE_MS + 1))
    }

    @Test
    fun modelStatusOrderWarmStarts() {
        ModelStatus.reset()
        ModelStatus.apply("p", "a", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("p", "c", ModelStatus.State.DEAD, false, now = 1)
        ModelStatus.apply("p", "d", ModelStatus.State.OK, true, now = 1)
        val ordered = ModelStatus.orderForSweep(listOf("a", "b", "c", "d", "e"), "p", current = "b")
        // Model aktif dulu, lalu yang terakhir hijau (urutan katalog asli),
        // lalu sisanya.
        assertEquals(listOf("b", "a", "d", "c", "e"), ordered)
    }

    @Test
    fun modelStatusJsonRoundTrip() {
        ModelStatus.reset()
        ModelStatus.apply("x", "m1", ModelStatus.State.OK, true, now = 123)
        ModelStatus.apply("x", "m2", ModelStatus.State.DEAD, null, now = 456)
        ModelStatus.apply("x", "m3", ModelStatus.State.UNKNOWN, false, now = 789)
        val json = ModelStatus.toJson("x")
        ModelStatus.reset()
        ModelStatus.fromJson("x", json)
        assertEquals(ModelStatus.State.OK, ModelStatus.stateOf("x", "m1"))
        assertEquals(ModelStatus.State.DEAD, ModelStatus.stateOf("x", "m2"))
        assertEquals(ModelStatus.State.UNKNOWN, ModelStatus.stateOf("x", "m3"))
        assertEquals(true, ModelStatus.entry("x", "m1")?.free)
        assertNull(ModelStatus.entry("x", "m2")?.free)
        assertEquals(false, ModelStatus.entry("x", "m3")?.free)
        assertEquals(123L, ModelStatus.entry("x", "m1")?.at)
        // Payload rusak tidak boleh melempar — toko dibiarkan kosong.
        ModelStatus.reset()
        ModelStatus.fromJson("x", "bukan json {")
        assertNull(ModelStatus.entry("x", "m1"))
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

    // ---------- Logcat dump (T-ML25 → T-ML26: filter Coding Sachet saja) ----------

    @Test
    fun logcatDumpCommandFiltersToOwnUid() {
        // Utama: --uid — semua proses milik aplikasi ini (app + anak
        // proot/node + crash native-nya), proses lain difilter.
        val cmd = DumpFormat.commandForUid(10123)
        assertTrue(cmd.startsWith("logcat -d "))
        assertTrue("harus pakai format threadtime", cmd.contains("threadtime"))
        assertTrue("harus menyertakan tahun", cmd.contains("year"))
        assertTrue("filter UID wajib di ujung perintah", cmd.endsWith("--uid=10123"))
        assertFalse("filter tag -s tidak boleh ada", cmd.contains(" -s"))
    }

    @Test
    fun logcatFallbackChainScopesProgressively() {
        // OEM tanpa --uid: mundur ke --pid (proses app saja), lalu tanpa
        // filter — semua varian berbagi format threadtime,year.
        assertTrue(DumpFormat.commandForPid(4242).endsWith("--pid=4242"))
        assertEquals("logcat -d -v threadtime,year", DumpFormat.COMMAND_UNFILTERED)
        listOf(DumpFormat.commandForUid(1), DumpFormat.commandForPid(2), DumpFormat.COMMAND_UNFILTERED).forEach { c ->
            assertTrue("$c harus threadtime", c.contains("threadtime,year"))
        }
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
