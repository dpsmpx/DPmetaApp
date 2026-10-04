package com.dpsmpx.dppass.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressTest {
    @Test
    fun ipv4() {
        assertEquals("192.0.2.1", IpAddress.parse("192.0.2.1").toString())
        assertNull(IpAddress.parse("256.1.1.1"))
        assertNull(IpAddress.parse("01.2.3.4")) // ведущий ноль — неоднозначность
        assertNull(IpAddress.parse("1.2.3"))
    }

    @Test
    fun ipv6() {
        assertEquals("2001:db8::1", IpAddress.parse("2001:0db8:0:0:0:0:0:1").toString())
        assertEquals("::", IpAddress.parse("::").toString())
        assertEquals("::ffff:c000:201", IpAddress.parse("::ffff:192.0.2.1").toString())
        assertEquals("2001:db8::1", IpAddress.parse("[2001:db8::1]").toString())
        assertNull(IpAddress.parse("1::2::3"))
        assertNull(IpAddress.parse("fe80::1%eth0"))
    }

    @Test
    fun domainNormalization() {
        assertEquals("example.com", Host.normalizeDomain("Example.COM."))
        assertNull(Host.normalizeDomain("bad_domain.example"))
        assertNull(Host.normalizeDomain("пример.рф")) // IDN — только punycode
        assertEquals("xn--e1afmkfd.xn--p1ai", Host.normalizeDomain("xn--e1afmkfd.xn--p1ai"))
        assertNull(Host.normalizeDomain("1.2.3"))
        assertTrue(Host.parse("203.0.113.9") is Host.Ip)
    }

    @Test
    fun endpointAndPorts() {
        assertEquals("[2001:db8::1]:443", Endpoint(Host.parse("2001:db8::1")!!, 443).toString())
        assertFailsWith<IllegalArgumentException> { Endpoint(Host.parse("example.com")!!, 0) }
        assertEquals(PortRange(1000, 2000), PortRange.parse("1000-2000"))
        assertNull(PortRange.parse("2000-1000"))
        assertNull(PortRange.parse("70000"))
    }
}

class CidrTest {
    @Test
    fun parseAndContain() {
        val p = Cidr.parse("10.1.2.3/8")!!
        assertTrue(p.hadHostBits)
        assertEquals("10.0.0.0/8", p.cidr.toString())
        assertTrue(IpAddress.parse("10.200.0.1")!! in p.cidr)
        assertFalse(IpAddress.parse("11.0.0.1")!! in p.cidr)
        assertFalse(IpAddress.parse("::1")!! in p.cidr)
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertEquals("2001:db8::/32", Cidr.parse("2001:db8::/32")!!.cidr.toString())
    }

    @Test
    fun complementCoversEverythingExceptExcluded() {
        val excluded = listOf(Cidr.parse("10.0.0.0/8")!!.cidr, Cidr.parse("192.168.0.0/16")!!.cidr)
        val routes = Cidr.complement(Cidr.ALL_V4, excluded)
        val probes = listOf("1.1.1.1", "10.1.1.1", "192.168.5.5", "192.169.0.1", "255.255.255.255", "0.0.0.0", "9.255.255.255")
        for (p in probes) {
            val ip = IpAddress.parse(p)!!
            val inExcluded = excluded.any { ip in it }
            val inRoutes = routes.any { ip in it }
            assertTrue(inExcluded != inRoutes, "адрес $p должен быть ровно в одном наборе")
        }
        assertTrue(routes.size < 40, "дополнение должно быть компактным: ${routes.size}")
    }

    @Test
    fun complementOfEverythingIsEmpty() {
        assertEquals(emptyList(), Cidr.complement(Cidr.ALL_V4, listOf(Cidr.ALL_V4)))
        assertEquals(listOf(Cidr.ALL_V6), Cidr.complement(Cidr.ALL_V6, listOf(Cidr.parse("10.0.0.0/8")!!.cidr)))
    }
}

class JsonTest {
    @Test
    fun roundTrip() {
        val text = """{"a":[1,-2.5e3,true,null,"x\n\"y\""],"b":{"c":"ю"}}"""
        val v = Json.parse(text)
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals(v, Json.parse(Json.write(v, pretty = false)))
    }

    @Test
    fun duplicateKeyIsAnError() {
        val e = assertFailsWith<JsonParseException> { Json.parse("""{"port":443,"port":80}""") }
        assertEquals("port", e.duplicateKey)
    }

    @Test
    fun depthIsBoundedWithoutStackOverflow() {
        val deep = "[".repeat(100_000)
        val e = assertFailsWith<JsonParseException> { Json.parse(deep, maxDepth = 32) }
        assertTrue(e.tooDeep)
    }

    @Test
    fun errorsCarryPositionNotInput() {
        val e = assertFailsWith<JsonParseException> { Json.parse("{\n  \"secret\": tru }") }
        assertEquals(2, e.line)
        assertFalse("secret" in (e.message ?: ""))
    }

    @Test
    fun rejectsMalformedNumbersAndTrailingData() {
        for (bad in listOf("01", "1.", "-", "1e", "{} {}", "[1,]", "{\"a\":1,}", "\"\u0001\"")) {
            assertFailsWith<JsonParseException>(bad) { Json.parse(bad) }
        }
        assertNotNull(Json.parse(" 0 "))
    }

    @Test
    fun checksumIsStable() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Checksums.sha256(ByteArray(0)))
    }
}
