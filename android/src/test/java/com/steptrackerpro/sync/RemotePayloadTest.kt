package com.steptrackerpro.sync

import com.steptrackerpro.core.DayTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two contracts: a retried batch carries the same key and a changed one does
 * not, and the default body is the one every endpoint written against 1.3
 * already parses.
 */
class RemotePayloadTest {

    private val pkg = "com.example.app"

    private fun day(date: String, steps: Int, recovered: Int = 0) =
        DayTotals(date, steps, steps * 0.7, steps * 0.03, recoveredSteps = recovered)

    private val batch = listOf(day("2026-09-12", 8_000), day("2026-09-13", 11_204, recovered = 200))

    @Test
    fun `the same records produce the same key`() {
        assertEquals(RemotePayload.idempotencyKey(pkg, batch), RemotePayload.idempotencyKey(pkg, batch))
        // A SHA-256 hex digest: fixed width, lower case.
        assertTrue(RemotePayload.idempotencyKey(pkg, batch).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `one step changed produces a different key`() {
        val changed = listOf(day("2026-09-12", 8_000), day("2026-09-13", 11_205))
        assertNotEquals(RemotePayload.idempotencyKey(pkg, batch), RemotePayload.idempotencyKey(pkg, changed))
    }

    @Test
    fun `the order rows came out of the database does not matter`() {
        assertEquals(
            RemotePayload.idempotencyKey(pkg, batch),
            RemotePayload.idempotencyKey(pkg, batch.reversed())
        )
    }

    @Test
    fun `distance, calories and the recovered share are not part of the key`() {
        // They are derived from, or a split of, the same step count; a
        // stride change must not make an unchanged day look like new content.
        val restrided = batch.map { it.copy(distance = it.distance * 1.1, calories = 0.0, recoveredSteps = 0) }
        assertEquals(RemotePayload.idempotencyKey(pkg, batch), RemotePayload.idempotencyKey(pkg, restrided))
    }

    @Test
    fun `a different package or a different day is a different key`() {
        assertNotEquals(RemotePayload.idempotencyKey(pkg, batch), RemotePayload.idempotencyKey("com.other", batch))
        assertNotEquals(
            RemotePayload.idempotencyKey(pkg, batch),
            RemotePayload.idempotencyKey(pkg, batch + day("2026-09-14", 1))
        )
    }

    @Test
    fun `the default body is the 1_3_0 shape, nothing added`() {
        val body = RemotePayload.body(batch, RemotePayload.Shape.TOTALS, sentAt = 1_757_800_000_000L)
        assertEquals(setOf("source", "sentAt", "records"), body.keySet())
        assertEquals("react-native-step-tracker-pro", body.getString("source"))
        assertEquals(1_757_800_000_000L, body.getLong("sentAt"))
        val records = body.getJSONArray("records")
        assertEquals(2, records.length())
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            assertEquals(setOf("date", "steps", "distance", "calories"), record.keySet())
            assertEquals(batch[i].date, record.getString("date"))
            assertEquals(batch[i].steps, record.getInt("steps"))
            assertEquals(batch[i].distance, record.getDouble("distance"), 0.0)
            assertEquals(batch[i].calories, record.getDouble("calories"), 0.0)
        }
        // Detail handed in is ignored under the default shape.
        val withDetail = RemotePayload.body(
            batch, RemotePayload.Shape.TOTALS, 1L,
            mapOf("2026-09-13" to RemotePayload.DayDetail(mapOf("steps" to 1), emptyList()))
        )
        assertEquals(setOf("date", "steps", "distance", "calories"), withDetail.getJSONArray("records").getJSONObject(1).keySet())
        assertEquals(RemotePayload.Shape.TOTALS, RemotePayload.Shape.from(null))
        assertEquals(RemotePayload.Shape.TOTALS, RemotePayload.Shape.from("nonsense"))
    }

    @Test
    fun `the full body adds the device count, the recovered share, the resolution and the origins`() {
        val detail = RemotePayload.DayDetail(
            stepSource = mapOf(
                "date" to "2026-09-13", "steps" to 12_000, "kind" to "watch",
                "packageName" to "com.fitbit.FitbitMobile", "usedExternal" to true, "manualStepsExcluded" to 0
            ),
            sources = listOf(
                mapOf("packageName" to "com.fitbit.FitbitMobile", "steps" to 12_000, "manualSteps" to 0, "trustedWearable" to true),
                mapOf("packageName" to "com.example.app", "steps" to 11_204, "isSelf" to true)
            )
        )
        val body = RemotePayload.body(
            batch, RemotePayload.Shape.FULL, 1L, mapOf("2026-09-13" to detail)
        )
        val records = body.getJSONArray("records")

        val second = records.getJSONObject(1)
        assertEquals(
            setOf("date", "steps", "distance", "calories", "deviceSteps", "recoveredSteps", "stepSource", "sources"),
            second.keySet()
        )
        // The stored row is this device's count, whatever the policy showed.
        assertEquals(11_204, second.getInt("steps"))
        assertEquals(11_204, second.getInt("deviceSteps"))
        assertEquals(200, second.getInt("recoveredSteps"))
        assertEquals("com.fitbit.FitbitMobile", second.getJSONObject("stepSource").getString("packageName"))
        assertEquals(2, second.getJSONArray("sources").length())
        assertTrue(second.getJSONArray("sources").getJSONObject(1).getBoolean("isSelf"))

        // A day with no detail - Health Connect not readable - is this
        // device's own, with no origins, rather than missing fields. So is
        // one whose resolution came back empty because the read failed.
        val failed = RemotePayload.body(
            batch, RemotePayload.Shape.FULL, 1L,
            mapOf("2026-09-12" to RemotePayload.DayDetail(emptyMap(), emptyList()))
        ).getJSONArray("records").getJSONObject(0)
        assertEquals("self", failed.getJSONObject("stepSource").getString("kind"))
        assertEquals(8_000, failed.getJSONObject("stepSource").getInt("steps"))

        val first = records.getJSONObject(0)
        assertEquals(0, first.getJSONArray("sources").length())
        assertEquals("self", first.getJSONObject("stepSource").getString("kind"))
        assertFalse(first.getJSONObject("stepSource").getBoolean("usedExternal"))
        assertEquals(8_000, first.getJSONObject("stepSource").getInt("deviceSteps"))
        assertEquals(0, first.getInt("recoveredSteps"))
    }

    @Test
    fun `a full record gains suspectSteps and integrity only when a report is attached`() {
        val report = mapOf(
            "enabled" to true,
            "suspectSteps" to 900,
            "flags" to listOf(mapOf("type" to "steady_cadence", "severity" to "strong", "steps" to 900))
        )
        val body = RemotePayload.body(
            batch, RemotePayload.Shape.FULL, 1L,
            mapOf("2026-09-13" to RemotePayload.DayDetail(emptyMap(), emptyList(), report)),
            suspects = mapOf("2026-09-13" to 900)
        )
        val records = body.getJSONArray("records")
        // No report, no new keys: the 1.4 full shape exactly.
        assertFalse(records.getJSONObject(0).has("integrity"))
        assertFalse(records.getJSONObject(0).has("suspectSteps"))
        val second = records.getJSONObject(1)
        assertEquals(900, second.getInt("suspectSteps"))
        assertEquals(
            "steady_cadence",
            second.getJSONObject("integrity").getJSONArray("flags").getJSONObject(0).getString("type")
        )
        // Flag mode: the count is sent as counted.
        assertEquals(11_204, second.getInt("steps"))
        assertEquals(11_204, second.getInt("deviceSteps"))
    }

    @Test
    fun `exclude mode sends the count less suspect steps, scaled distance, raw deviceSteps`() {
        val suspects = mapOf("2026-09-13" to 1_204)
        val totals = RemotePayload.body(batch, RemotePayload.Shape.TOTALS, 1L, suspects = suspects, excludeSuspect = true)
        val record = totals.getJSONArray("records").getJSONObject(1)
        assertEquals(setOf("date", "steps", "distance", "calories"), record.keySet())
        assertEquals(10_000, record.getInt("steps"))
        assertEquals(10_000 * 0.7, record.getDouble("distance"), 1e-6)
        assertEquals(10_000 * 0.03, record.getDouble("calories"), 1e-6)
        // A day with nothing suspect is untouched.
        assertEquals(8_000, totals.getJSONArray("records").getJSONObject(0).getInt("steps"))

        val full = RemotePayload.body(batch, RemotePayload.Shape.FULL, 1L, suspects = suspects, excludeSuspect = true)
            .getJSONArray("records").getJSONObject(1)
        assertEquals(10_000, full.getInt("steps"))
        assertEquals(11_204, full.getInt("deviceSteps"))

        // The key follows what is sent, so a growing exclusion is new content.
        val sent = batch.map { it.copy(steps = RemotePayload.sentSteps(it, suspects, true)) }
        assertNotEquals(RemotePayload.idempotencyKey(pkg, batch), RemotePayload.idempotencyKey(pkg, sent))
        // More suspect than steps can never send a negative count.
        assertEquals(0, RemotePayload.sentSteps(day("2026-09-14", 50), mapOf("2026-09-14" to 80), true))
        assertEquals(50, RemotePayload.sentSteps(day("2026-09-14", 50), mapOf("2026-09-14" to 80), false))
    }

    @Test
    fun `the signature header names the key and the algorithm`() {
        assertEquals(
            "keyId=abc;alg=SHA256withECDSA;sig=MEUCIQ==",
            RemotePayload.signatureHeader("abc", "SHA256withECDSA", "MEUCIQ==")
        )
    }

    @Test
    fun `a refused credential is not retried, anything else short of success is`() {
        assertEquals(RemotePayload.Outcome.UPLOADED, RemotePayload.outcomeOf(200))
        assertEquals(RemotePayload.Outcome.UPLOADED, RemotePayload.outcomeOf(204))
        assertEquals(RemotePayload.Outcome.AUTH_REFUSED, RemotePayload.outcomeOf(401))
        assertEquals(RemotePayload.Outcome.AUTH_REFUSED, RemotePayload.outcomeOf(403))
        assertEquals(RemotePayload.Outcome.RETRY, RemotePayload.outcomeOf(500))
        assertEquals(RemotePayload.Outcome.RETRY, RemotePayload.outcomeOf(429))
        assertEquals(RemotePayload.Outcome.RETRY, RemotePayload.outcomeOf(-1))
    }
}
