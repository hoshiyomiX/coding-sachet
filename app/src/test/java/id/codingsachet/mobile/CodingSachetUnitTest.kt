package id.codingsachet.mobile

import id.codingsachet.mobile.api.ModelStatus
import id.codingsachet.mobile.api.Providers
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.data.ToolCall
import id.codingsachet.mobile.data.snapshotSession
import id.codingsachet.mobile.data.writeSessionFile
import id.codingsachet.mobile.runtime.DumpFormat
import id.codingsachet.mobile.runtime.GitSetup
import id.codingsachet.mobile.ui.ChatEngine
import id.codingsachet.mobile.ui.EnStrings
import id.codingsachet.mobile.ui.IdStrings
import id.codingsachet.mobile.ui.ProjectNames
import id.codingsachet.mobile.ui.SachetStrings
import id.codingsachet.mobile.ui.Schemes
import id.codingsachet.mobile.ui.canContinueFromHistory
import id.codingsachet.mobile.ui.components.toolLabel
import id.codingsachet.mobile.ui.formatTokenCount
import id.codingsachet.mobile.ui.projectDir
import id.codingsachet.mobile.ui.screens.ChatEntry
import id.codingsachet.mobile.ui.screens.buildChatEntries
import id.codingsachet.mobile.ui.stringsFor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertSame(Providers.SEEK, Providers.byId("seek"))
        assertSame(Providers.NGODEAI, Providers.byId("ngodeai"))
        assertSame(Providers.APINEX, Providers.byId("apinex"))
        assertSame(Providers.APINEX, Providers.byId(null))
        assertSame(Providers.APINEX, Providers.byId("bogus"))
    }

    @Test
    fun providersHaveDistinctIdsAndValidUrls() {
        assertEquals(4, Providers.ALL.size)
        assertEquals(4, Providers.ALL.map { it.id }.toSet().size)
        Providers.ALL.forEach { p ->
            assertTrue("baseUrl ${p.baseUrl} harus https", p.baseUrl.startsWith("https://"))
            assertTrue("docsUrl ${p.docsUrl} harus https", p.docsUrl.startsWith("https://"))
        }
    }

    @Test
    fun ngodeaiProviderCarriesWalletPricingSemantics() {
        // T-ML64: NgodeAI — gateway IDR prepaid. Free = pricing katalog 0
        // (BUKAN freeByProbe: model berbayar juga menjawab begitu dompet
        // terisi — jawaban live tidak boleh menandai gratis); free flag
        // datang dari sweep katalog terautentikasi sisi server. Chip kuota
        // = saldo dompet GET /v1/balance (mode "idr" → "Saldo: Rp …").
        val ng = Providers.NGODEAI
        assertEquals("ngodeai", ng.id)
        assertEquals("ngodeai_live_", ng.keyPrefix)
        assertEquals("ngodeai/glm-5.3-flash", ng.defaultModel)
        assertNull("ngodeai tanpa prefiks free — badge dari pricing katalog", ng.freeModelPrefix)
        assertFalse("validasi key ngodeai via /models, bukan chat", ng.validateViaChat)
        assertTrue("chip saldo aktif utk ngodeai", ng.supportsQuota)
    }

    @Test
    fun seekProviderCarriesWholeServiceFreeSemantics() {
        // T-ML52: 100milionseek — seluruh layanan gratis, jadi semanticsnya
        // NIM-like: prefix-free (badge free dari probe live), validasi key
        // via GET /models (murah — chat probe membakar ±630 token jatah),
        // dan chip kuota aktif (header x-quota-* via server).
        val seek = Providers.SEEK
        assertEquals("seek", seek.id)
        assertEquals("hms-", seek.keyPrefix)
        assertEquals("deepseek-v4.1-flash-free", seek.defaultModel)
        assertNull("seek tanpa prefiks free — badge dari probe live", seek.freeModelPrefix)
        assertFalse("validasi key seek via /models, bukan chat", seek.validateViaChat)
        assertTrue("chip sisa token aktif utk seek", seek.supportsQuota)
        // NIM tetap tanpa chip; APInex tetap dengan chip.
        assertFalse(Providers.NVIDIA.supportsQuota)
        assertTrue(Providers.APINEX.supportsQuota)
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
    fun modelStatusSeekFreeViaLiveProbe() {
        // T-ML52: provider seek (100milionseek) tanpa prefiks — model yang
        // menjawab probe = gratis (seluruh layanan free), model belum
        // diperiksa tetap tampil di filter "gratis saja", hanya DEAD yang
        // disembunyikan (pola T-ML50 utk NIM).
        ModelStatus.reset()
        assertTrue("seek belum diperiksa = tampil", ModelStatus.passesFreeFilter(Providers.SEEK, "deepseek-v4.1-flash-free"))
        ModelStatus.apply("seek", "deepseek-v4.1-flash-free", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("seek", "jev-1.13-free", ModelStatus.State.DEAD, null, now = 1)
        assertTrue(ModelStatus.isFree(Providers.SEEK, "deepseek-v4.1-flash-free"))
        assertTrue(ModelStatus.passesFreeFilter(Providers.SEEK, "deepseek-v4.1-flash-free"))
        assertFalse("seek mati = disembunyikan", ModelStatus.passesFreeFilter(Providers.SEEK, "jev-1.13-free"))
    }

    @Test
    fun modelStatusFreeCombinesPrefixAndLive() {
        ModelStatus.reset()
        // Prefiks free/ APInex intrinsik — tidak butuh sweep.
        assertTrue(ModelStatus.isFree(Providers.APINEX, "free/apa-saja"))
        // Tanpa hasil live, model non-prefix tidak berbadge free.
        assertFalse(ModelStatus.isFree(Providers.APINEX, "claude-opus-5.5"))
        assertFalse(ModelStatus.isFree(Providers.NVIDIA, "nv-embedqa-e5-v5"))
        // T-ML48: flag free sweep TIDAK dipercaya untuk provider yang punya
        // prefiks intrinsik (APInex) — katalog allowFree 2026-11-19 true di
        // semua 22 model premium; entri stale tersimpan pun tak bisa mengecat
        // model premium jadi free. NIM (tanpa prefiks) tetap percaya sweep.
        ModelStatus.apply("apinex", "claude-opus-5.5", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("apinex", "free/glm-5.3", ModelStatus.State.OK, false, now = 1)
        ModelStatus.apply("nvidia", "z-ai/glm-5.3-flash", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("nvidia", "mati/model", ModelStatus.State.DEAD, false, now = 1)
        assertFalse("APInex premium + sweep free=true TETAP tidak free (bug T-ML48)", ModelStatus.isFree(Providers.APINEX, "claude-opus-5.5"))
        assertTrue("APInex free/ tetap free via prefiks walau sweep bilang false", ModelStatus.isFree(Providers.APINEX, "free/glm-5.3"))
        assertTrue(ModelStatus.isFree(Providers.NVIDIA, "z-ai/glm-5.3-flash"))
        assertFalse(ModelStatus.isFree(Providers.NVIDIA, "mati/model"))
    }

    @Test
    fun modelStatusSubscriptionTierOverridesPrefix() {
        // T-ML49 (probe langsung 2026-11-20): 8 model free/ kini allowFree:false
        // dan ditolak 402 "subscription" — tier SUBSCRIPTION dari sweep harus
        // mengeluarkannya dari filter gratis + badge free, meski prefiksnya free/.
        ModelStatus.reset()
        ModelStatus.apply("apinex", "free/claude-opus-4.6", ModelStatus.State.OK, false, ModelStatus.Tier.SUBSCRIPTION, now = 1)
        ModelStatus.apply("apinex", "free/glm-5.3-flashx", ModelStatus.State.OK, true, ModelStatus.Tier.FREE, now = 1)
        ModelStatus.apply("apinex", "glm-5.3-flash", ModelStatus.State.OK, false, ModelStatus.Tier.TOPUP, now = 1)
        ModelStatus.apply("apinex", "free/belum-disweep", ModelStatus.State.UNKNOWN, null, now = 1)
        assertFalse("free/ + tier langganan = BUKAN free (bug T-ML49)", ModelStatus.isFree(Providers.APINEX, "free/claude-opus-4.6"))
        assertEquals(ModelStatus.Tier.SUBSCRIPTION, ModelStatus.tierOf("apinex", "free/claude-opus-4.6"))
        assertTrue("free/ + tier free = badge free", ModelStatus.isFree(Providers.APINEX, "free/glm-5.3-flashx"))
        assertEquals(ModelStatus.Tier.FREE, ModelStatus.tierOf("apinex", "free/glm-5.3-flashx"))
        assertFalse("premium tier topup = bukan free", ModelStatus.isFree(Providers.APINEX, "glm-5.3-flash"))
        assertEquals(ModelStatus.Tier.TOPUP, ModelStatus.tierOf("apinex", "glm-5.3-flash"))
        assertTrue("tanpa tier (belum sweep) = fallback prefiks lama", ModelStatus.isFree(Providers.APINEX, "free/belum-disweep"))
        assertNull(ModelStatus.tierOf("apinex", "free/belum-disweep"))
    }

    @Test
    fun modelStatusStaleness() {
        // T-ML50: fresh = sweep yang MENYELESAI (doneAt), bukan entri apa pun.
        // Entri parsial dari sweep yang terputus TIDAK boleh mengunci 12 jam.
        ModelStatus.reset()
        assertFalse("belum pernah dicek = tidak fresh", ModelStatus.isFresh("nvidia", now = 10_000))
        ModelStatus.apply("nvidia", "m", ModelStatus.State.OK, true, now = 0)
        assertFalse("entri parsial tanpa sweep selesai = TIDAK fresh (bug T-ML50: picker NIM menampilkan 1 model saja)", ModelStatus.isFresh("nvidia", now = 1000))
        assertEquals(0L, ModelStatus.lastCheckedAt("nvidia"))
        ModelStatus.markSwept("nvidia", now = 0)
        assertTrue(ModelStatus.isFresh("nvidia", now = ModelStatus.STALE_MS - 1))
        assertFalse("lebih dari 12 jam = basi, sweep ulang", ModelStatus.isFresh("nvidia", now = ModelStatus.STALE_MS + 1))
    }

    @Test
    fun modelStatusDoneAtSurvivesPersistReload() {
        // T-ML50: doneAt ikut ke JSON v3; payload v2 (1.0.52, tanpa doneAt)
        // dimuat entri-nya TAPI tidak dianggap pernah selesai di-sweep.
        ModelStatus.reset()
        ModelStatus.apply("nvidia", "m1", ModelStatus.State.OK, true, now = 500)
        ModelStatus.markSwept("nvidia", now = 900)
        val v3 = ModelStatus.toJson("nvidia")
        assertTrue(v3.contains("\"v\":3"))
        assertTrue(v3.contains("\"doneAt\":900"))
        ModelStatus.reset()
        ModelStatus.fromJson("nvidia", v3)
        assertEquals(ModelStatus.State.OK, ModelStatus.stateOf("nvidia", "m1"))
        assertEquals(900L, ModelStatus.sweptAt("nvidia"))
        assertTrue(ModelStatus.isFresh("nvidia", now = 901))
        // Payload v2 ala 1.0.52: entri baru-baru ini, tanpa doneAt — dari
        // sweep yang terpotong. Entri tetap dipakai (terukur sungguhan),
        // tapi isFresh harus false agar picker membuka sweep lanjutan.
        val v2 = """{"v":2,"models":{"m1":{"s":"OK","f":true,"t":null,"at":99999}}}"""
        ModelStatus.reset()
        ModelStatus.fromJson("nvidia", v2)
        assertEquals(ModelStatus.State.OK, ModelStatus.stateOf("nvidia", "m1"))
        assertNull(ModelStatus.sweptAt("nvidia"))
        assertFalse("v2 tanpa doneAt = sweep belum selesai (bug T-ML50)", ModelStatus.isFresh("nvidia", now = 100_000))
        // v1 tetap ditolak (kontrak T-ML49).
        ModelStatus.reset()
        ModelStatus.fromJson("nvidia", """{"v":1,"models":{"m1":{"s":"OK","f":true,"at":1}}}""")
        assertEquals(ModelStatus.State.UNKNOWN, ModelStatus.stateOf("nvidia", "m1"))
    }

    @Test
    fun modelStatusFreeFilterKeepsUncheckedForPrefixlessProviders() {
        // T-ML50: provider tanpa prefiks free (NIM) tidak tahu status gratis
        // sebuah model sebelum probe menjawab — model BELUM DIPERIKSA tetap
        // tampil di filter "gratis saja" (titik abu-abu sudah bilang "belum
        // diperiksa"); hanya yang terkonfirmasi MATI yang disembunyikan.
        // Bukti lapangan 2026-11-20: filter lama menyembunyikan semua model
        // NIM yang belum di-sweep, picker tampak "macet" dengan 1 baris.
        ModelStatus.reset()
        assertTrue("NIM belum diperiksa = tampil (bug T-ML50)", ModelStatus.passesFreeFilter(Providers.NVIDIA, "nvidia/nemotron-3.5-bolt-e4b"))
        assertTrue("NIM ok gratis = tampil", ModelStatus.passesFreeFilter(Providers.NVIDIA, "z-ai/glm-5.3-flash"))
        ModelStatus.apply("nvidia", "z-ai/glm-5.3-flash", ModelStatus.State.OK, true, now = 1)
        ModelStatus.apply("nvidia", "mati/model", ModelStatus.State.DEAD, null, now = 1)
        ModelStatus.apply("nvidia", "tak-pasti/model", ModelStatus.State.UNKNOWN, null, now = 1)
        assertTrue(ModelStatus.passesFreeFilter(Providers.NVIDIA, "z-ai/glm-5.3-flash"))
        assertTrue("NIM tak pasti (429/timeout) = tetap tampil", ModelStatus.passesFreeFilter(Providers.NVIDIA, "tak-pasti/model"))
        assertFalse("NIM mati = disembunyikan", ModelStatus.passesFreeFilter(Providers.NVIDIA, "mati/model"))
        // Provider berprefiks (APInex) tetap ketat: unchecked non-free tak tampil.
        assertFalse(ModelStatus.passesFreeFilter(Providers.APINEX, "claude-opus-5.5"))
        assertTrue(ModelStatus.passesFreeFilter(Providers.APINEX, "free/glm-5.3-flashx"))
        ModelStatus.apply("apinex", "free/claude-opus-4.6", ModelStatus.State.OK, false, ModelStatus.Tier.SUBSCRIPTION, now = 1)
        assertFalse("APInex tier langganan = disembunyikan", ModelStatus.passesFreeFilter(Providers.APINEX, "free/claude-opus-4.6"))
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

    @Test
    fun modelStatusJsonV2TierRoundTripAndV1Dropped() {
        // T-ML49: v2 membawa tier; payload v1 (tanpa tier) sengaja DITOLAK —
        // entri lama bisa meng-klaim model langganan sebagai gratis.
        ModelStatus.reset()
        ModelStatus.apply("x", "sub", ModelStatus.State.OK, false, ModelStatus.Tier.SUBSCRIPTION, now = 10)
        ModelStatus.apply("x", "top", ModelStatus.State.OK, false, ModelStatus.Tier.TOPUP, now = 20)
        val json = ModelStatus.toJson("x")
        assertTrue(json.contains("\"v\":3"))
        ModelStatus.reset()
        ModelStatus.fromJson("x", json)
        assertEquals(ModelStatus.Tier.SUBSCRIPTION, ModelStatus.entry("x", "sub")?.tier)
        assertEquals(ModelStatus.Tier.TOPUP, ModelStatus.entry("x", "top")?.tier)
        // v1 (tanpa field t) ditolak — store tetap kosong, sweep ulang terjadi.
        ModelStatus.reset()
        val v1 = """{"v":1,"models":{"sub":{"s":"OK","f":false,"at":10}}}"""
        ModelStatus.fromJson("x", v1)
        assertNull("payload v1 tanpa tier ditolak (T-ML49)", ModelStatus.entry("x", "sub"))
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

    // ---------- T-ML28: scheme templates, project folders, actual token count ----------

    @Test
    fun schemeTemplatesSurviveServerSanitization() {
        // Every ready-made template must pass the agent server's
        // sanitizeOptions unchanged: no reserved keys, values in range,
        // within the 4096-char options budget.
        val reserved = setOf("model", "messages", "stream", "tools", "tool_choice", "n")
        val efforts = setOf("minimal", "low", "medium", "high")
        val json = Json { ignoreUnknownKeys = true }
        Schemes.ALL.forEach { sc ->
            assertTrue("skema ${sc.id} tak punya id unik", Schemes.ALL.count { it.id == sc.id } == 1)
            assertTrue("skema ${sc.id}: label ID/EN harus terisi", sc.labelId.isNotBlank() && sc.labelEn.isNotBlank())
            if (sc.optionsJson.isEmpty()) return@forEach
            assertTrue("skema ${sc.id}: options > 4096 karakter", sc.optionsJson.length <= 4096)
            val o = json.parseToJsonElement(sc.optionsJson) as JsonObject
            assertTrue("skema ${sc.id}: options kosong padahal non-blank", o.isNotEmpty())
            o.forEach { (k, v) ->
                assertFalse("skema ${sc.id}: kunci reserved $k", k in reserved)
                val p = v as? kotlinx.serialization.json.JsonPrimitive
                    ?: throw AssertionError("skema ${sc.id}: nilai $k bukan primitif")
                when (k) {
                    "temperature" -> assertTrue("skema ${sc.id}: temperature di luar 0..2", p.content.toDouble() in 0.0..2.0)
                    "top_p" -> assertTrue("skema ${sc.id}: top_p di luar 0..1", p.content.toDouble() in 0.0..1.0)
                    "presence_penalty", "frequency_penalty" ->
                        assertTrue("skema ${sc.id}: $k di luar -2..2", p.content.toDouble() in -2.0..2.0)
                    "reasoning_effort" -> assertTrue("skema ${sc.id}: effort tak dikenal", p.content in efforts)
                    else -> throw AssertionError("skema ${sc.id}: kunci tak dikenal $k")
                }
            }
        }
    }

    @Test
    fun codingSchemeIsTheVendorDefaultBaseline() {
        val coding = Schemes.byId("coding")!!
        // T-ML35: the coding scheme pins the vendor-recommended sampling
        // baseline explicitly (z.ai GLM-5.3 / NVIDIA bolt-e4b both tune
        // reasoning models at temperature 1.0 + top_p 0.95) instead of the
        // old "" (relay-dependent defaults). Persona stays the default
        // coding-agent prompt.
        val o = json.parseToJsonElement(coding.optionsJson) as JsonObject
        assertEquals(2, o.size)
        assertEquals(1.0, (o["temperature"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(0.95, (o["top_p"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), 0.0)
        assertNull(coding.systemPrompt)        // default coding-agent prompt
        assertEquals("", Schemes.storedPrompt(coding))
        assertEquals("coding", Schemes.DEFAULT_ID)
        // Every other scheme pins a persona + at least one option.
        Schemes.ALL.filter { it.id != "coding" }.forEach {
            assertFalse("skema ${it.id} tanpa persona", it.systemPrompt.isNullOrBlank())
            assertTrue("skema ${it.id} tanpa opsi", it.optionsJson.isNotBlank())
        }
    }

    @Test
    fun projectNamesSanitizeToFolderSafeSegments() {
        assertEquals("My-App-2", ProjectNames.sanitize("My App 2!"))
        assertEquals("etc-passwd", ProjectNames.sanitize("../etc/passwd"))
        assertEquals("proyek-keren", ProjectNames.sanitize("  proyek   keren  "))
        assertEquals(ProjectNames.FALLBACK, ProjectNames.sanitize("!!!???"))
        assertEquals(ProjectNames.FALLBACK, ProjectNames.sanitize("   "))
        assertEquals(64, ProjectNames.sanitize("a".repeat(100)).length)
        assertEquals("a.b-c-d", ProjectNames.sanitize("a.b c/d"))
        assertFalse(
            "karakter non-aman lolos",
            Regex("[^A-Za-z0-9._-]").containsMatchIn(ProjectNames.sanitize("h\u00e9llo w\u00f6rld")),
        )
    }

    @Test
    fun projectDirJoinsBaseAndSanitizedName() {
        assertEquals("/sdcard/coding-sachet/My-App", projectDir("/sdcard/coding-sachet", "My App"))
        assertEquals("/sdcard/coding-sachet/x", projectDir("/sdcard/coding-sachet/", "x"))
        // One project per folder: different names never share a folder.
        assertNotEquals(projectDir("/b", "satu"), projectDir("/b", "dua"))
    }

    @Test
    fun tokenCountShowsTheActualNumber() {
        // T-ML28 #2: actual number with id-ID grouping, no abbreviations.
        assertEquals("19.300.000", formatTokenCount(19_300_000L))
        assertEquals("45.000", formatTokenCount(45_000L))
        assertEquals("123", formatTokenCount(123L))
        assertFalse(formatTokenCount(19_300_000L).contains("jt"))
        assertFalse(formatTokenCount(45_000L).contains("rb"))
    }

    @Test
    fun deadRunIsContinuableOnlyWhenHistoryEndsInAToolRound() {
        // T-ML37 P1-3: the "Lanjutkan" button may only appear when the model
        // still owes a response — the history ends in a tool result, or in an
        // assistant round whose results never arrived (an interrupted round
        // the server heals). Everything else is a plain error.
        val call = ToolCall(id = "c1", name = "bash")
        assertTrue(
            "tool result terakhir = run bisa dilanjutkan",
            canContinueFromHistory(
                listOf(
                    Msg(role = "user", content = "kerja"),
                    Msg(role = "assistant", content = "", toolCalls = listOf(call)),
                    Msg(role = "tool", content = "exit=0", toolCallId = "c1", name = "bash"),
                )
            )
        )
        assertTrue(
            "ronde terputus tanpa hasil = run bisa dilanjutkan",
            canContinueFromHistory(
                listOf(
                    Msg(role = "user", content = "kerja"),
                    Msg(role = "assistant", content = "Aku cek dulu—", toolCalls = listOf(call)),
                )
            )
        )
        assertFalse(
            "jawaban final sudah tersampaikan = tidak ada yang dilanjutkan",
            canContinueFromHistory(
                listOf(
                    Msg(role = "user", content = "hai"),
                    Msg(role = "assistant", content = "Sudah selesai."),
                )
            )
        )
        assertFalse(
            "pesan user terakhir = run belum mulai",
            canContinueFromHistory(listOf(Msg(role = "user", content = "hai")))
        )
        assertFalse("history kosong", canContinueFromHistory(emptyList()))
    }

    @Test
    fun defaultCodingPromptPinsWorkflowContract() {
        // T-ML39 (owner order sesi 27 — hasil coding "aimless, tanpa
        // workflow/protocol/harness"): the default coding prompt must carry
        // the TODO-file protocol (RENCANA.md), the FULL tool roster
        // (read_lines was never mentioned before — AUDIT-TOKEN §6.2#2,
        // 40/40 field reads used bash cat/sed), the syntax-gate contract,
        // and the definition-of-done answer format. The server side
        // (agent.js todo-nudge + tools.js syntaxGate) enforces what this
        // text promises.
        // T-ML40 (owner order sesi 28 — analisa log 149.739 tok): the
        // roster grew to 6 (todo = the sanctioned RENCANA.md editor), the
        // environment contract must state python is NOT bundled (3 dead
        // turns tried python3), batching must be taught (exploration
        // one-by-one = spam), and MEMORI.md carries the cross-session
        // memory the server injects.
        val p = ChatEngine.SYSTEM_PROMPT
        assertTrue("read_lines harus disebut (audit R2a)", p.contains("read_lines"))
        listOf("bash", "read_file", "write_file", "list_dir", "todo").forEach {
            assertTrue("tool $it harus disebut di prompt default", p.contains(it))
        }
        assertTrue("protokol RENCANA.md harus ada", p.contains("RENCANA.md"))
        assertTrue("daftar centang harus diajarkan", p.contains("- [ ]") && p.contains("- [x]"))
        assertTrue("kontrak gerbang sintaks harus ada", p.contains("gerbang sintaks"))
        assertTrue("definition-of-done harus ada", p.contains("JAWABAN AKHIR"))
        // T-ML40: kontrak lingkungan — python TIDAK tersedia bawaan.
        assertTrue("kontrak lingkungan python harus ada", p.contains("python TIDAK tersedia"))
        // T-ML40: disiplin batching (anti-spam tool call satu-per-satu).
        assertTrue("disiplin batching harus diajarkan", p.contains("SATU"))
        // T-ML40: memori lintas sesi.
        assertTrue("protokol MEMORI.md harus ada", p.contains("MEMORI.md"))
        // Persona schemes list the full tool roster too (T-ML39 sweep,
        // T-ML40: roster 6 tool).
        Schemes.ALL.filter { it.systemPrompt != null }.forEach {
            assertTrue(
                "skema ${it.id} tak menyebut read_lines",
                it.systemPrompt!!.contains("read_lines")
            )
            assertTrue(
                "skema ${it.id} tak menyebut todo",
                it.systemPrompt!!.contains("todo")
            )
        }
    }

    @Test
    fun promptPinsTml41Contracts() {
        // T-ML41 (owner order sesi 28 "optimisasi lanjutan"): (1) MEMORI.md
        // memakai format grep-first — satu fakta per baris ber-anchor [tag]
        // dengan tag baku, agar sesi berikutnya bisa grep satu tag alih-alih
        // membaca ulang seluruh file; (2) bahasa jawaban dinamis mengikuti
        // bahasa pesan pengguna terakhir, dan pesan harness berbahasa
        // Indonesia dinyatakan BUKAN sinyal bahasa.
        val p = ChatEngine.SYSTEM_PROMPT
        assertTrue(
            "format MEMORI.md grep-first harus diajarkan",
            p.contains("SATU fakta per baris") && p.contains("grep")
        )
        listOf("[struktur]", "[keputusan]", "[uji]", "[jebakan]", "[fakta]", "[status]").forEach {
            assertTrue("anchor tag $it harus ada di kontrak MEMORI", p.contains(it))
        }
        assertTrue(
            "kontrak bahasa dinamis harus ada",
            p.contains("BAHASA JAWABAN (dinamis)")
        )
        assertTrue(
            "harness bukan sinyal bahasa harus dinyatakan",
            p.contains("BUKAN sinyal bahasa")
        )
        Schemes.ALL.filter { it.systemPrompt != null }.forEach {
            assertTrue(
                "skema ${it.id} tak mematok bahasa dinamis",
                it.systemPrompt!!.contains("BAHASA JAWABAN (dinamis)")
            )
        }
    }

    // ---------- T-ML58 #3: persistensi sesi atomik ----------

    @Test
    fun sessionSnapshotIsDetachedCopy() {
        // Snapshot dipakai thread IO saat thread utama masih menambah pesan
        // (SSE) — salinannya harus terlepas dari list hidup.
        val s = Session(id = "snap1", title = "t", created = 1L, model = "m")
        s.messages += Msg(role = "user", content = "halo")
        val snap = snapshotSession(s)
        s.messages += Msg(role = "assistant", content = "hai")
        assertEquals("snapshot tidak boleh ikut berubah bersama sesi hidup", 1L, snap.messages.size.toLong())
        assertEquals(2L, s.messages.size.toLong())
        assertEquals(0L, snap.tokensUsed)
    }

    @Test
    fun sessionFileWritesAreAtomicAndLeaveNoTemp() {
        val dir = java.nio.file.Files.createTempDirectory("sachet-store").toFile()
        try {
            val json = Json { ignoreUnknownKeys = true }
            val s = Session(
                id = "atom1", title = "t", created = 1L, model = "m",
                messages = mutableListOf(Msg(role = "user", content = "x")),
            )
            writeSessionFile(dir, s, json)
            // Tulis ulang — penggantian file lama juga harus mulus.
            writeSessionFile(dir, s.copy(tokensUsed = 5), json)
            val dst = java.io.File(dir, "atom1.json")
            assertEquals(1, dir.listFiles { f -> f.name == "atom1.json" }?.size)
            val reloaded = json.decodeFromString<Session>(dst.readText())
            assertEquals(5L, reloaded.tokensUsed)
            assertEquals(1, reloaded.messages.size)
            assertTrue(
                "file .tmp harus sudah ter-rename (tidak ada sisa)",
                dir.listFiles { f -> f.name.endsWith(".tmp") }.isNullOrEmpty(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------- T-ML58 #2: peringkasan kartu tool di history ----------

    @Test
    fun toolLabelsCoverPiNativeToolNames() {
        // Engine Pi memancarkan nama tool ASLINYA (read/write/edit/grep/glob)
        // — tanpa pemetaan, kartu menampilkan nama mentah bahasa Inggris
        // (bukti keluhan owner: campuran "terminal, read, menulis, membaca").
        for (s in listOf(IdStrings, EnStrings)) {
            for (name in listOf("bash", "read", "write", "edit", "grep", "glob")) {
                val label = toolLabel(name, s)
                assertTrue(
                    "$name harus terpetakan ke label lokal (dapat: \"$label\")",
                    label.isNotBlank() && label != name,
                )
            }
        }
    }

    @Test
    fun chatEntriesGroupConsecutiveToolOnlyRounds() {
        fun assistantTool(id: String, vararg calls: Pair<String, String>) = Msg(
            role = "assistant", content = "",
            toolCalls = calls.map { ToolCall(id = it.first, name = it.second) },
        )
        fun toolResult(id: String) = Msg(role = "tool", content = "exit=0", toolCallId = id)

        val msgs = listOf(
            Msg(role = "user", content = "buat file"),
            assistantTool("a1", "a1" to "bash"), toolResult("a1"),
            assistantTool("a2", "a2" to "read"), toolResult("a2"),
            assistantTool("a3", "a3" to "write"), toolResult("a3"),
            Msg(role = "assistant", content = "Selesai — ringkasan pekerjaan."),
        )
        val toolResults = listOf("a1", "a2", "a3").associate { it to toolResult(it) }
        val entries = buildChatEntries(msgs, toolResults)
        // 1) user, 2) GRUP 3 putaran tool, 3) jawaban final bertext.
        assertEquals(3L, entries.size.toLong())
        val group = entries[1] as ChatEntry.Group
        // Grup menampung assistant + hasil tool-nya (dirender dalam kartu).
        assertEquals(6L, group.msgs.size.toLong())
        assertEquals(3L, group.cards.size.toLong())
        assertEquals(listOf("bash", "read", "write"), group.cards.map { it.name })
        // Hasil tool tersambung ke kartu masing-masing (bukan "terputus").
        assertTrue(group.cards.all { it.ok == true && it.output == "exit=0" })
    }

    @Test
    fun chatEntriesKeepTextRoundsAndSingleToolsIndividual() {
        // Putaran bertext (narasi model) dan putaran tool tunggal tetap
        // tampil apa adanya — grup hanya untuk BARISAN putaran tool.
        val msgs = listOf(
            Msg(role = "user", content = "cek"),
            Msg(
                role = "assistant", content = "Aku cek dulu—",
                toolCalls = listOf(ToolCall(id = "b1", name = "bash")),
            ),
            Msg(role = "tool", content = "exit=0", toolCallId = "b1"),
            Msg(role = "assistant", content = "Hasilnya oke."),
        )
        val entries = buildChatEntries(msgs)
        assertEquals(4, entries.size)
        assertTrue(entries.all { it is ChatEntry.Single })
    }

    // ---------- T-ML59: baseline git (dubious ownership di /sdcard) ----------

    @Test
    fun gitSetupWritesBaselineIntoEmptyConfig() {
        val text = GitSetup.merge(null)
        assertTrue("safe.directory wajib ada", text.contains("[safe]"))
        assertTrue("safe.directory harus * (folder proyek dinamis)", text.contains("directory = *"))
        assertTrue("fileMode=false wajib (FUSE 0777)", text.contains("fileMode = false"))
        assertTrue(GitSetup.isConfigured(text))
        // Baris konfigurasi git tidak boleh berindentasi spasi (harus TAB).
        text.lines().filter { it.trim().startsWith("directory") || it.trim().startsWith("fileMode") }
            .forEach { assertTrue("indentasi harus TAB: $it", it.startsWith("\t")) }
    }

    @Test
    fun gitSetupKeepsUserConfigAndIsIdempotent() {
        val user = "[user]\n\tname = Sachet\n\temail = s@example.com\n"
        val merged = GitSetup.merge(user)
        assertTrue("config pengguna harus utuh", merged.contains("name = Sachet"))
        assertTrue(GitSetup.isConfigured(merged))
        // Boot berikutnya tidak boleh menduplikasi blok.
        assertEquals(merged, GitSetup.merge(merged))
        assertEquals(1, Regex(Regex.escape(GitSetup.MARKER)).findAll(merged).count())
        // Teks kosong/spasi juga diperlakukan sebagai config belum ada.
        assertEquals(GitSetup.block(), GitSetup.merge("  \n"))
    }
}
