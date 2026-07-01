package com.nexora.hammerscale.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.concurrent.ConcurrentHashMap

class ConnectionViewModel : ViewModel() {

    private val connectionMap = ConcurrentHashMap<String, ConnectionEntry>()

    private val _connections = MutableLiveData<List<ConnectionEntry>>(emptyList())
    val connections: LiveData<List<ConnectionEntry>> = _connections

    private val _vpnRunning = MutableLiveData(false)
    val vpnRunning: LiveData<Boolean> = _vpnRunning

    private val _stats = MutableLiveData(Stats())
    val stats: LiveData<Stats> = _stats

    // Detected Mech Arena game server (UDP to port 7015)
    private val _gameServer = MutableLiveData<ConnectionEntry?>(null)
    val gameServer: LiveData<ConnectionEntry?> = _gameServer

    data class Stats(
        val totalConnections: Int = 0,
        val activeConnections: Int = 0,
        val dnsQueries: Int = 0,
        val udpActive: Int = 0
    )

    fun setVpnRunning(running: Boolean) {
        _vpnRunning.postValue(running)
        if (!running) {
            connectionMap.values.forEach { conn ->
                if (conn.isLive) conn.status = ConnectionStatus.CLOSED
            }
            _gameServer.postValue(null)
            publishUpdate()
        }
    }

    fun addOrUpdateConnection(entry: ConnectionEntry) {
        connectionMap[entry.id] = entry
        // Track Mech Arena game server: UDP port 7015
        if (entry.protocol == Protocol.UDP && entry.dstPort == 7015) {
            _gameServer.postValue(entry)
        }
        publishUpdate()
    }

    fun updateConnectionStatus(id: String, status: ConnectionStatus) {
        connectionMap[id]?.let {
            it.status = status
            it.lastActivityTime = System.currentTimeMillis()
            // Refresh game server live data if it's this connection
            if (it.protocol == Protocol.UDP && it.dstPort == 7015) {
                _gameServer.postValue(it)
            }
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
            if (message.direction == LiveMessage.Direction.INBOUND) {
                conn.bytesIn += message.data.size
            } else {
                conn.bytesOut += message.data.size
            }
            conn.lastActivityTime = System.currentTimeMillis()
            if (conn.protocol == Protocol.UDP && conn.dstPort == 7015) {
                _gameServer.postValue(conn)
            }
            publishUpdate()
        }
    }

    fun resolvedHost(id: String, host: String) {
        connectionMap[id]?.let {
            it.dstHost = host
            publishUpdate()
        }
    }

    fun clearAll() {
        connectionMap.clear()
        _gameServer.postValue(null)
        publishUpdate()
    }

    fun getConnection(id: String): ConnectionEntry? = connectionMap[id]

    fun getMessages(id: String): List<LiveMessage> {
        val conn = connectionMap[id] ?: return emptyList()
        return synchronized(conn.messages) { conn.messages.toList() }
    }

    fun getAllMessages(): List<LiveMessage> {
        return connectionMap.values
            .flatMap { conn -> synchronized(conn.messages) { conn.messages.toList() } }
            .sortedBy { it.timestamp }
    }

    private fun publishUpdate() {
        val list = connectionMap.values.sortedByDescending { it.lastActivityTime }
        _connections.postValue(list)
        val active = list.count { it.isLive }
        val dns = list.count { it.protocol == Protocol.DNS }
        val udp = list.count { it.protocol == Protocol.UDP && it.isLive }
        _stats.postValue(Stats(list.size, active, dns, udp))
    }
}
