package tech.csalliance.unstuck.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.csalliance.unstuck.core.time.ClockMode

/**
 * The register-push-token body as it goes over the wire (kotlinx with
 * encodeDefaults off, like supabase-kt's default serializer): `platform` and the
 * 12/24-hour `clock` (migration 083) are always present — a dropped `clock` would
 * leave the server writing "3:00 PM" into a 24-hour phone's reminders.
 */
class PushClientBodyTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun encode(clock: ClockMode, token: String? = "fcm-1") =
        json.parseToJsonElement(json.encodeToString(PushClient.body("dev-1", token, clock, "Europe/London"))).jsonObject

    @Test fun `a 24-hour phone sends clock 24h with every field`() {
        val body = encode(ClockMode.H24)
        assertEquals(setOf("deviceId", "fcmToken", "platform", "timezone", "clock"), body.keys)
        assertEquals("dev-1", body["deviceId"]!!.jsonPrimitive.content)
        assertEquals("fcm-1", body["fcmToken"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("Europe/London", body["timezone"]!!.jsonPrimitive.content)
        assertEquals("24h", body["clock"]!!.jsonPrimitive.content)
    }

    @Test fun `a 12-hour phone sends clock 12h`() {
        assertEquals("12h", encode(ClockMode.H12)["clock"]!!.jsonPrimitive.content)
    }

    @Test fun `clock is sent even without a token`() {
        val body = encode(ClockMode.H24, token = null)
        assertEquals(JsonNull, body["fcmToken"])
        assertEquals("24h", body["clock"]!!.jsonPrimitive.content)
    }

    @Test fun `wire values are exactly what the server's check accepts`() {
        assertEquals("24h", PushClient.clockWire(ClockMode.H24))
        assertEquals("12h", PushClient.clockWire(ClockMode.H12))
    }
}
