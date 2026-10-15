package com.speakerbridge.discovery

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Dependency-free mDNS / DNS-SD browser, a port of linux/speakerbridge/mdns.py.
 * Sends one-shot queries from an ephemeral port; responders reply by unicast,
 * so we never compete with Android's own mDNS service for port 5353.
 */
object MdnsWire {
    const val T_A = 1
    const val T_PTR = 12
    const val T_TXT = 16
    const val T_AAAA = 28
    const val T_SRV = 33
    const val T_ANY = 255

    data class Record(val name: String, val type: Int, val ttl: Long, val value: Any)
    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String)

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            val raw = label.toByteArray(Charsets.UTF_8)
            require(raw.size <= 63) { "label too long: $label" }
            out.write(raw.size)
            out.write(raw)
        }
        out.write(0)
        return out.toByteArray()
    }

    fun buildQuery(names: List<String>, qtype: Int = T_PTR, qid: Int = Random.nextInt(1, 0xFFFF)): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        u16(qid); u16(0); u16(names.size); u16(0); u16(0); u16(0)
        for (n in names) {
            out.write(encodeName(n))
            u16(qtype); u16(1)
        }
        return out.toByteArray()
    }

    private fun u8(d: ByteArray, i: Int): Int {
        if (i !in d.indices) throw IllegalArgumentException("read past end of packet")
        return d[i].toInt() and 0xFF
    }
    private fun u16(d: ByteArray, i: Int) = (u8(d, i) shl 8) or u8(d, i + 1)
    private fun u32(d: ByteArray, i: Int): Long =
        (u16(d, i).toLong() shl 16) or u16(d, i + 2).toLong()

    /** Decode a possibly-compressed name. Returns (name, offset after it). */
    fun readName(d: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var off = start
        var end = -1
        var hops = 0
        while (true) {
            val len = u8(d, off)
            if (len and 0xC0 == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or u8(d, off + 1)
                if (end < 0) end = off + 2
                off = ptr
                if (++hops > 64) throw IllegalArgumentException("compression loop")
                continue
            }
            if (len == 0) { off += 1; break }
            off += 1
            if (off + len > d.size) throw IllegalArgumentException("label past end")
            labels += String(d, off, len, Charsets.UTF_8)
            off += len
        }
        return labels.joinToString(".") to (if (end >= 0) end else off)
    }

    fun parseTxt(d: ByteArray, start: Int, len: Int): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = start
        val stop = start + len
        while (i < stop) {
            val n = u8(d, i); i += 1
            if (n == 0) continue
            val item = String(d, i, minOf(n, stop - i), Charsets.UTF_8)
            i += n
            val eq = item.indexOf('=')
            val k = (if (eq >= 0) item.substring(0, eq) else item).lowercase()
            val v = if (eq >= 0) item.substring(eq + 1) else ""
            out.putIfAbsent(k, v)
        }
        return out
    }

    fun parsePacket(d: ByteArray, length: Int = d.size): List<Record> {
        val data = if (length == d.size) d else d.copyOf(length)
        if (data.size < 12) return emptyList()
        val flags = u16(data, 2)
        if (flags and 0x8000 == 0) return emptyList()  // a query, not a response
        val qd = u16(data, 4)
        val total = u16(data, 6) + u16(data, 8) + u16(data, 10)
        var off = 12
        repeat(qd) { off = readName(data, off).second + 4 }
        val out = mutableListOf<Record>()
        repeat(total) {
            val (name, after) = readName(data, off)
            off = after
            if (off + 10 > data.size) return out
            val type = u16(data, off)
            val ttl = u32(data, off + 4)
            val rdlen = u16(data, off + 8)
            off += 10
            val rd = off
            off += rdlen
            if (off > data.size) return out
            val value: Any? = when (type) {
                T_PTR -> readName(data, rd).first
                T_SRV -> if (rdlen >= 7) Srv(u16(data, rd), u16(data, rd + 2), u16(data, rd + 4), readName(data, rd + 6).first) else null
                T_TXT -> parseTxt(data, rd, rdlen)
                T_A -> if (rdlen == 4) InetAddress.getByAddress(data.copyOfRange(rd, rd + 4)).hostAddress else null
                T_AAAA -> if (rdlen == 16) InetAddress.getByAddress(data.copyOfRange(rd, rd + 16)).hostAddress else null
                else -> null
            }
            if (value != null) out += Record(name, type, ttl, value)
        }
        return out
    }
}

data class ServiceInstance(
    val instance: String,
    val serviceType: String,
    var host: String = "",
    var port: Int = 0,
    val txt: MutableMap<String, String> = mutableMapOf(),
    val addresses: MutableSet<String> = mutableSetOf(),
    var senderIp: String = "",
) {
    val label: String
        get() = instance.removeSuffix(".$serviceType.local")

    val ipv4: String
        get() = addresses.filter { ':' !in it }.minOrNull() ?: senderIp
}

/** Accumulates records from many packets and joins PTR → SRV/TXT → A. */
class MdnsCollector(serviceTypes: List<String>) {
    private val types = serviceTypes.associateBy { it.lowercase() }
    private val instances = linkedMapOf<String, ServiceInstance>()
    private val hosts = mutableMapOf<String, MutableSet<String>>()
    private val srv = mutableMapOf<String, MdnsWire.Srv>()
    private val txt = mutableMapOf<String, MutableMap<String, String>>()

    fun feed(records: List<MdnsWire.Record>, sender: String) {
        for (r in records) {
            val lname = r.name.lowercase()
            when (r.type) {
                MdnsWire.T_PTR -> {
                    val stype = lname.removeSuffix(".local")
                    val t = types[stype] ?: continue
                    val instanceName = r.value as String
                    val inst = instances.getOrPut(instanceName.lowercase()) {
                        ServiceInstance(instance = instanceName, serviceType = t)
                    }
                    if (inst.senderIp.isEmpty()) inst.senderIp = sender
                }
                MdnsWire.T_SRV -> srv[lname] = r.value as MdnsWire.Srv
                MdnsWire.T_TXT -> {
                    @Suppress("UNCHECKED_CAST")
                    txt.getOrPut(lname) { mutableMapOf() }.putAll(r.value as Map<String, String>)
                }
                MdnsWire.T_A, MdnsWire.T_AAAA ->
                    hosts.getOrPut(lname) { mutableSetOf() }.add(r.value as String)
            }
        }
    }

    fun unresolved(): List<String> =
        instances.filter { (k, _) -> k !in srv || k !in txt }.map { it.value.instance }

    fun resolve(): List<ServiceInstance> {
        for ((k, inst) in instances) {
            srv[k]?.let { s ->
                inst.port = s.port
                inst.host = s.target
                hosts[s.target.lowercase()]?.let { inst.addresses += it }
            }
            txt[k]?.let { inst.txt.putAll(it) }
        }
        return instances.values.toList()
    }
}

object MdnsBrowser {
    private val GROUP = InetSocketAddress("224.0.0.251", 5353)

    /** Blocking; call from Dispatchers.IO. */
    fun browse(
        serviceTypes: List<String>,
        timeoutMs: Long = 4000,
        iface: NetworkInterface? = null,
        target: InetSocketAddress = GROUP,
    ): List<ServiceInstance> {
        val col = MdnsCollector(serviceTypes)
        val query = MdnsWire.buildQuery(serviceTypes.map { "$it.local" })
        MulticastSocket(0).use { sock ->
            sock.timeToLive = 255
            iface?.let { runCatching { sock.networkInterface = it } }
            sock.soTimeout = 250
            val buf = ByteArray(9000)
            val start = System.currentTimeMillis()
            val sendAt = ArrayDeque(listOf(0L, 600L, 1500L))
            var followedUp = false
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= timeoutMs) break
                while (sendAt.isNotEmpty() && elapsed >= sendAt.first()) {
                    sendAt.removeFirst()
                    runCatching { sock.send(DatagramPacket(query, query.size, target)) }
                }
                if (!followedUp && elapsed > timeoutMs / 2) {
                    followedUp = true
                    col.unresolved().chunked(8).forEach { batch ->
                        val q = MdnsWire.buildQuery(batch, MdnsWire.T_ANY)
                        runCatching { sock.send(DatagramPacket(q, q.size, target)) }
                    }
                }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                runCatching {
                    col.feed(MdnsWire.parsePacket(pkt.data, pkt.length), pkt.address.hostAddress ?: "")
                }
            }
        }
        return col.resolve()
    }
}
