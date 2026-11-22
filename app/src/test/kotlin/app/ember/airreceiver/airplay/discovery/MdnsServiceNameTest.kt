// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MdnsServiceNameTest — Unit tests for the mDNS service name sanitizer.
 *
 * WHY: A name the platform's mDNS daemon rejects makes [android.net.nsd.NsdManager]
 * throw inside the registration path, which takes the AirPlay receiver down and hides the
 * device from every AirPlay picker. The sanitizer is the guard against that, so it needs
 * to handle hostile input (control characters, over-long names, names that are nothing
 * but whitespace).
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class MdnsServiceNameTest {

    /**
     * Test: A normal name is kept unchanged.
     */
    @Test
    fun `normal name is unchanged`() {
        assertEquals(
            "Living Room TV",
            MdnsServiceName.sanitize("Living Room TV", "Ember AirReceiver")
        )
    }

    /**
     * Test: Surrounding whitespace is removed.
     */
    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "Living Room TV",
            MdnsServiceName.sanitize("  Living Room TV  ", "Ember AirReceiver")
        )
    }

    /**
     * Test: Control characters are stripped.
     *
     * WHY: A newline or a null byte inside an mDNS instance name is rejected by the
     * daemon and — worse — would end up in log output and in the TXT records.
     */
    @Test
    fun `control characters are removed`() {
        val withControls = "Living" + Char(0) + "Room" + Char(31)

        assertEquals("LivingRoom", MdnsServiceName.sanitize(withControls, "Ember AirReceiver"))
    }

    /**
     * Test: An over-long name is capped at the DNS-SD limit.
     */
    @Test
    fun `long name is truncated to the limit`() {
        val long = "A".repeat(200)

        val result = MdnsServiceName.sanitize(long, "Ember AirReceiver")

        assertEquals(MdnsServiceName.MAX_LENGTH, result.length)
    }

    /**
     * Test: A name that is only whitespace falls back.
     */
    @Test
    fun `blank name falls back`() {
        assertEquals("Ember AirReceiver", MdnsServiceName.sanitize("   ", "Ember AirReceiver"))
    }

    /**
     * Test: An empty name falls back.
     */
    @Test
    fun `empty name falls back`() {
        assertEquals("Ember AirReceiver", MdnsServiceName.sanitize("", "Ember AirReceiver"))
    }

    /**
     * Test: The fallback is used when the name contains only control characters.
     */
    @Test
    fun `name with only control characters falls back`() {
        val onlyControls = Char(0).toString() + Char(7).toString()

        assertEquals("Ember AirReceiver", MdnsServiceName.sanitize(onlyControls, "Ember AirReceiver"))
    }

    /**
     * Test: The fallback itself is capped, so it can never break the registration either.
     */
    @Test
    fun `over-long fallback is truncated too`() {
        val result = MdnsServiceName.sanitize("", "B".repeat(100))

        assertEquals(MdnsServiceName.MAX_LENGTH, result.length)
    }

    /**
     * Test: The limit is the documented 63 characters.
     */
    @Test
    fun `maximum length is 63`() {
        assertEquals(63, MdnsServiceName.MAX_LENGTH)
    }
}
