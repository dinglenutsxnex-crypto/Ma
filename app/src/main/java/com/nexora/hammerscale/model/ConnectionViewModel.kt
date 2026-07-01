package com.nexora.hammerscale.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nexora.hammerscale.net.MechStateDecoder
import com.nexora.hammerscale.net.TickState
import java.util.concurrent.ConcurrentHashMap

class ConnectionViewModel : ViewModel() {

    private val connectionMap = ConcurrentHashMap<String, ConnectionEntry>()

    private val _connections = MutableLiveData<List<ConnectionEntry>>(emptyList())
    val connections: LiveData<List<ConnectionEntry>> = _connections

    private val _vpnRunning = MutableLiveData(false)
    val vpnRunning: LiveData<Boolean> = _vpnRunning

    private val _stats = MutableLiveData(Stats())
    val stats: LiveData<Stats> = _stats

    private val _gameServer = MutableLiveData<ConnectionEntry?>(null)
    val gameServer: LiveData<ConnectionEntry?> = _gameServer

    // Battle flow: all packets from game server (UDP 7015)
    private val battlePacketList = mutableListOf<LiveMessage>()
    private val _battlePackets = MutableLiveData<List<LiveMessage>>(emptyList())
    val battlePackets: LiveData<List<LiveMessage>> = _battlePackets

    // Latest decoded tick state (from most recent outbound 0x01 packet)
    private val _lastTickState = MutableLiveData<TickState?>(null)
    val lastTickState: LiveData<TickState?> = _lastTickState

    // Aggregate battle stats
    private val _battleStats = MutableLiveData(BattleStats())
    val battleStats: LiveData<BattleStats> = _battleStats

    data class Stats(
        val totalConnections: Int = 0,
        val activeConnections: Int = 0,
        val dnsQueries: Int = 0,
        val udpActive: Int = 0
    )

    data class BattleStats(
        val actionTicks: Int  = 0,
        val idleTicks: Int    = 0,
        val worldStates: Int  = 0,
        val pings: Int        = 0,
        val clockSyncs: Int   = 0,
        val txBytes: Long     = 0L,
        val rxBytes: Long     = 0L,
        val firstTs: Long     = 0L,
        val lastTs: Long      = 0L,
        val recentTickRateHz: Float = 0f   // rolling 5-s window
    )

    fun setVpnRunning(running: Boolean) {
        _vpnRunning.postValue(running)
        if (!running) {
            connectionMap.values.forEach { if (it.isLive) it.status = ConnectionStatus.CLOSED }
            _gameServer.postValue(null)
            publishUpdate()
        }
    }

    fun addOrUpdateConnection(entry: ConnectionEntry) {
        connectionMap[entry.id] = entry
        if (entry.protocol == Protocol.UDP && entry.dstPort == 7015) _gameServer.postValue(entry)
        publishUpdate()
    }

    fun updateConnectionStatus(id: String, status: ConnectionStatus) {
        connectionMap[id]?.let {
            it.status = status
            it.lastActivityTime = System.currentTimeMillis()
            if (it.protocol == Protocol.UDP && it.dstPort == 7015) _gameServer.postValue(it)
            publishUpdate()
        }
    }

    fun markAsWebSocket(id: String) {
        connectionMap[id]?.let {
            it.isWebSocket = true
            it.lastActivityTime = System.currentTimeMillis()
            publishUpdate()
        }
    }

    fun addMessage(id: String, message: LiveMessage) {
        connectionMap[id]?.let { conn ->
            synchronized(conn.messages) {
                conn.messages.add(message)
                if (conn.messages.size > 500) conn.messages.removeAt(0)
            }
            if (message.direction == LiveMessage.Direction.INBOUND) conn.bytesIn += message.data.size
            else conn.bytesOut += message.data.size
            conn.lastActivityTime = System.currentTimeMillis()

            if (conn.protocol == Protocol.UDP && conn.dstPort == 7015) {
                _gameServer.postValue(conn)

                synchronized(battlePacketList) {
                    battlePacketList.add(message)
                    if (battlePacketList.size > 2000) battlePacketList.removeAt(0)
                }
                _battlePackets.postValue(battlePacketList.toList())

                // Decode tick state from outbound 0x01 packets
                if (message.direction == LiveMessage.Direction.OUTBOUND &&
                    message.data.isNotEmpty() && (message.data[0].toInt() and 0xFF) == 0x01) {
                    MechStateDecoder.decode(message.data)?.let { _lastTickState.postValue(it) }
                }

                _battleStats.postValue(computeBattleStats())
            }

            publishUpdate()
        }
    }

    fun resolvedHost(id: String, host: String) {
        connectionMap[id]?.let { it.dstHost = host; publishUpdate() }
    }

    fun clearAll() {
        connectionMap.clear()
        synchronized(battlePacketList) { battlePacketList.clear() }
        _battlePackets.postValue(emptyList())
        _gameServer.postValue(null)
        _lastTickState.postValue(null)
        _battleStats.postValue(BattleStats())
        publishUpdate()
    }

    fun getConnection(id: String): ConnectionEntry? = connectionMap[id]

    fun getMessages(id: String): List<LiveMessage> {
        val conn = connectionMap[id] ?: return emptyList()
        return synchronized(conn.messages) { conn.messages.toList() }
    }

    fun getBattleMessages(): List<LiveMessage> =
        synchronized(battlePacketList) { battlePacketList.toList() }

    private fun computeBattleStats(): BattleStats {
        val list = synchronized(battlePacketList) { battlePacketList.toList() }
        if (list.isEmpty()) return BattleStats()

        var action = 0; var idle = 0; var world = 0; var pings = 0; var clocks = 0
        var tx = 0L; var rx = 0L

        for (msg in list) {
            when (msg.commandName) {
                "tick_action"          -> action++
                "tick_idle"            -> idle++
                "world_state"          -> world++
                "ping", "ping_ack"     -> pings++
                "clock_sync"           -> clocks++
            }
            if (msg.direction == LiveMessage.Direction.OUTBOUND) tx += msg.data.size
            else rx += msg.data.size
        }

        // Rolling 5-second tick rate (outbound 0x01 packets)
        val now = System.currentTimeMillis()
        val windowMs = 5_000L
        val recentTicks = list.count {
            it.direction == LiveMessage.Direction.OUTBOUND &&
            (it.commandName == "tick_action" || it.commandName == "tick_idle") &&
            (now - it.timestamp) <= windowMs
        }
        val tickRate = recentTicks / (windowMs / 1000f)

        return BattleStats(
            actionTicks = action, idleTicks = idle, worldStates = world,
            pings = pings, clockSyncs = clocks,
            txBytes = tx, rxBytes = rx,
            firstTs = list.first().timestamp, lastTs = list.last().timestamp,
            recentTickRateHz = tickRate
        )
    }

    private fun publishUpdate() {
        val list = connectionMap.values.sortedByDescending { it.lastActivityTime }
        _connections.postValue(list)
        val active = list.count { it.isLive }
        val dns    = list.count { it.protocol == Protocol.DNS }
        val udp    = list.count { it.protocol == Protocol.UDP && it.isLive }
        _stats.postValue(Stats(list.size, active, dns, udp))
    }
}
