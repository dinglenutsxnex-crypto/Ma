package com.nexora.hammerscale.net

import android.net.VpnService
import com.nexora.hammerscale.model.*
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

// ── Mech Arena Lidgren UDP packet classifier ───────────────────────────────
//
// Protocol confirmed from pcap analysis (ma.pcapng):
//   0x81  9 bytes   client↔server  Heartbeat / keepalive         → "ping"
//   0x82  13 bytes  client↔server  Clock-sync (two LE float32)   → "clock_sync"
//   0x01  variable  client→server  Per-tick input batch           → "tick_idle" / "tick_action"
//   0x01  variable  server→client  Per-tick world-state           → "world_state"
//   0x02  rare      server→client  Unknown infrequent opcode      → "unknown"
//   other any       any            Uncharacterised                → "unknown"
//
// 0x01 client sub-classification uses the load field (bytes[3:5] LE uint16):
//   ≤ 272  → "tick_idle"    (baseline ~256, ±16 jitter, packet ~35–37 B)
//   > 272  → "tick_action"  (jumps to 264→392→448→592 during movement/shooting)

object MechArenaPacketClassifier {

    private const val GAME_PORT = 7015

    fun isGamePort(dstPort: Int): Boolean = dstPort == GAME_PORT

    fun classify(data: ByteArray, direction: LiveMessage.Direction): String {
        if (data.isEmpty()) return "unknown"
        val opcode = data[0].toInt() and 0xFF
        val isOutbound = direction == LiveMessage.Direction.OUTBOUND

        return when (opcode) {
            0x81 -> if (isOutbound) "ping" else "ping_ack"
            0x82 -> "clock_sync"
            0x01 -> {
                if (!isOutbound) {
                    "world_state"
                } else {
                    // Load field = bytes[3:5] LE uint16
                    if (data.size >= 5) {
                        val load = ByteBuffer.wrap(data, 3, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        if (load > 272) "tick_action" else "tick_idle"
                    } else {
                        "tick_idle"
                    }
                }
            }
            else -> "unknown_0x%02X".format(opcode)
        }
    }
}

class UdpHandler(
    private val vpnService: VpnService,
    private val vpnFd: FileDescriptor,
    private val onConnectionEvent: (ConnectionEntry) -> Unit,
    private val onMessage: (String, LiveMessage) -> Unit,
    private val onStatusChange: (String, ConnectionStatus) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val outStream = FileOutputStream(vpnFd)
    private val udpSessions = ConcurrentHashMap<String, DatagramSocket>()

    fun handlePacket(packet: ParsedPacket) {
        val udp = packet.udp ?: return
        val isDns = udp.dstPort == 53

        val connKey = "${packet.ip.srcAddr.hostAddress}:${udp.srcPort}->" +
                      "${packet.ip.dstAddr.hostAddress}:${udp.dstPort}"

        if (isDns) {
            handleDns(packet, udp, connKey)
        } else {
            handleGenericUdp(packet, udp, connKey)
        }
    }

    private fun handleDns(packet: ParsedPacket, udp: UDPHeader, connKey: String) {
        val dnsQuery = DnsParser.parse(packet.payload)
        val queryName = dnsQuery?.questions?.firstOrNull() ?: "?"

        val entry = ConnectionEntry(
            id = connKey,
            protocol = Protocol.DNS,
            srcPort = udp.srcPort,
            dstIp = packet.ip.dstAddr.hostAddress ?: "8.8.8.8",
            dstPort = udp.dstPort,
            dstHost = "DNS: $queryName",
            status = ConnectionStatus.CONNECTING,
            dnsQuery = queryName
        )
        onConnectionEvent(entry)

        val srcIp = packet.ip.srcAddr.address
        val dstIp = packet.ip.dstAddr.address
        val payload = packet.payload

        scope.launch {
            try {
                val sock = DatagramSocket()
                vpnService.protect(sock)
                sock.soTimeout = 3000

                val target = InetSocketAddress(packet.ip.dstAddr, udp.dstPort)
                sock.send(DatagramPacket(payload, payload.size, target))
                onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND, payload, commandName = "dns"))

                val recvBuf = ByteArray(4096)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                sock.receive(recvPkt)
                sock.close()

                val response = recvBuf.copyOf(recvPkt.length)
                onMessage(connKey, LiveMessage(LiveMessage.Direction.INBOUND, response, commandName = "dns"))

                val dnsResp = DnsParser.parse(response)
                val answers = dnsResp?.answers?.map { rec ->
                    when (rec.type) {
                        1 -> "A: ${rec.data}"
                        28 -> "AAAA: ${rec.data}"
                        5 -> "CNAME: ${rec.data}"
                        else -> rec.data
                    }
                } ?: emptyList()

                onConnectionEvent(entry.copy(
                    dnsAnswers = answers,
                    status = ConnectionStatus.CLOSED,
                    dstHost = "DNS: $queryName → ${answers.firstOrNull() ?: "NXDOMAIN"}"
                ))

                writeToVpn(PacketParser.buildIPv4UDPPacket(
                    srcIp = dstIp, dstIp = srcIp,
                    srcPort = udp.dstPort, dstPort = udp.srcPort,
                    payload = response
                ))
            } catch (e: Exception) {
                onStatusChange(connKey, ConnectionStatus.CLOSED)
            }
        }
    }

    private fun handleGenericUdp(packet: ParsedPacket, udp: UDPHeader, connKey: String) {
        val isGameServer = MechArenaPacketClassifier.isGamePort(udp.dstPort)

        val entry = ConnectionEntry(
            id = connKey,
            protocol = Protocol.UDP,
            srcPort = udp.srcPort,
            dstIp = packet.ip.dstAddr.hostAddress ?: "?",
            dstPort = udp.dstPort,
            status = ConnectionStatus.ACTIVE
        )
        onConnectionEvent(entry)

        val srcIp = packet.ip.srcAddr.address
        val dstIp = packet.ip.dstAddr.address
        val payload = packet.payload

        val outTag = if (isGameServer)
            MechArenaPacketClassifier.classify(payload, LiveMessage.Direction.OUTBOUND)
        else "udp"
        onMessage(connKey, LiveMessage(LiveMessage.Direction.OUTBOUND, payload, commandName = outTag))

        scope.launch {
            try {
                val sock = udpSessions.getOrPut(connKey) {
                    DatagramSocket().also { vpnService.protect(it) }
                }
                if (isGameServer) sock.soTimeout = 50 else sock.soTimeout = 5000

                val target = InetSocketAddress(packet.ip.dstAddr, udp.dstPort)
                sock.send(DatagramPacket(payload, payload.size, target))

                val recvBuf = ByteArray(65507)
                val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    sock.receive(recvPkt)
                    val response = recvBuf.copyOf(recvPkt.length)

                    val inTag = if (isGameServer)
                        MechArenaPacketClassifier.classify(response, LiveMessage.Direction.INBOUND)
                    else "udp"
                    onMessage(connKey, LiveMessage(LiveMessage.Direction.INBOUND, response, commandName = inTag))

                    writeToVpn(PacketParser.buildIPv4UDPPacket(
                        srcIp = dstIp, dstIp = srcIp,
                        srcPort = udp.dstPort, dstPort = udp.srcPort,
                        payload = response
                    ))
                } catch (_: Exception) {}
            } catch (e: Exception) {
                udpSessions.remove(connKey)
                onStatusChange(connKey, ConnectionStatus.CLOSED)
            }
        }
    }

    private fun writeToVpn(data: ByteArray) {
        try { outStream.write(data) } catch (_: Exception) {}
    }

    fun shutdown() {
        scope.cancel()
        udpSessions.values.forEach { try { it.close() } catch (_: Exception) {} }
        udpSessions.clear()
    }
}
