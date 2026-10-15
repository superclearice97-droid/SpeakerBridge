package com.speakerbridge.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** JVM unit tests for the mDNS wire format. Run: ./gradlew test */
class MdnsWireTest {

    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write((v shr 8) and 0xFF); o.write(v and 0xFF) }

    private fun castResponse(): ByteArray {
        val o = ByteArrayOutputStream()
        u16(o, 0); u16(o, 0x8400); u16(o, 0); u16(o, 1); u16(o, 0); u16(o, 2)
        val ptrName = MdnsWire.encodeName("_googlecast._tcp.local")
        o.write(ptrName)
        u16(o, MdnsWire.T_PTR); u16(o, 0x8001); u16(o, 0); u16(o, 120)
        val label = "Bar-1".toByteArray()
        val rdata = byteArrayOf(label.size.toByte()) + label + byteArrayOf(0xC0.toByte(), 12)
        u16(o, rdata.size); o.write(rdata)
        val instOffset = 12 + ptrName.size + 10
        // TXT for the instance, name compressed to the PTR rdata
        o.write(byteArrayOf((0xC0 or (instOffset shr 8)).toByte(), (instOffset and 0xFF).toByte()))
        u16(o, MdnsWire.T_TXT); u16(o, 0x8001); u16(o, 0); u16(o, 120)
        val txt = listOf("id=s2", "md=HT-A3000", "fn=TV Soundbar").flatMap { s ->
            val b = s.toByteArray(); listOf(b.size.toByte()) + b.toList()
        }.toByteArray()
        u16(o, txt.size); o.write(txt)
        return o.toByteArray()
    }

    @Test fun parsesCompressedCastAnswer() {
        val recs = MdnsWire.parsePacket(castResponse())
        val ptr = recs.first { it.type == MdnsWire.T_PTR }
        assertEquals("Bar-1._googlecast._tcp.local", ptr.value)
        @Suppress("UNCHECKED_CAST")
        val txt = recs.first { it.type == MdnsWire.T_TXT }.value as Map<String, String>
        assertEquals("HT-A3000", txt["md"])
        assertEquals("Bar-1._googlecast._tcp.local", recs.first { it.type == MdnsWire.T_TXT }.name)
    }

    @Test fun collectorJoinsRecords() {
        val col = MdnsCollector(listOf("_googlecast._tcp"))
        col.feed(MdnsWire.parsePacket(castResponse()), "192.168.1.21")
        val inst = col.resolve().single()
        assertEquals("Bar-1", inst.label)
        assertEquals("192.168.1.21", inst.ipv4)
        assertEquals("TV Soundbar", inst.txt["fn"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCompressionLoop() {
        val bad = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12)
        MdnsWire.parsePacket(bad)
    }

    @Test fun airPlay2Bit() {
        assertTrue(Scanner.isAirPlay2("0x4A7FDFD5,0xBC157FDE"))
        assertFalse(Scanner.isAirPlay2("0x5A7FFFF7,0x1E"))
        assertFalse(Scanner.isAirPlay2("garbage"))
    }
}
