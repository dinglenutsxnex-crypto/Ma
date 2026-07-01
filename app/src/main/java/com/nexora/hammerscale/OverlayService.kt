package com.nexora.hammerscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.ConnectionStatus
import com.nexora.hammerscale.model.LiveMessage

class OverlayService : android.app.Service() {

    companion object {
        const val ACTION_START = "com.nexora.hammerscale.OVERLAY_START"
        const val ACTION_STOP  = "com.nexora.hammerscale.OVERLAY_STOP"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "hammerscale_overlay"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var miniView: View? = null

    private val packets = mutableListOf<LiveMessage>()
    private lateinit var packetAdapter: BattlePacketAdapter

    private var savedX: Int = 0
    private var savedY: Int = 120

    // ── Observers ──────────────────────────────────────────────────────────

    private val battlePacketsObserver = Observer<List<LiveMessage>> { list ->
        val added = if (packets.size < list.size) list.drop(packets.size) else emptyList()
        if (added.isNotEmpty()) {
            val prev = packets.size
            packets.addAll(added)
            packetAdapter.notifyItemRangeInserted(prev, added.size)
            val rv = overlayView?.findViewById<RecyclerView>(R.id.rv_connections)
            if (rv != null && isAtBottom(rv)) rv.scrollToPosition(packets.size - 1)
        }

        overlayView?.findViewById<TextView>(R.id.tv_status_bar)?.text =
            "${packets.size} pkts  ·  ${countByType()}  ·  ${lastTimeStr()}"
    }

    private val gameServerObserver = Observer<ConnectionEntry?> { entry ->
        updateDetectionPanel(entry)
        updateMenuDetection(entry)
    }

    private fun isAtBottom(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        return lm.findLastVisibleItemPosition() >= packetAdapter.itemCount - 3
    }

    private fun countByType(): String {
        val actions = packets.count { it.commandName == "tick_action" }
        val pings   = packets.count { it.commandName == "ping" || it.commandName == "ping_ack" }
        return "⚡$actions act  ·  ♡$pings ping"
    }

    private fun lastTimeStr(): String {
        val ts = packets.lastOrNull()?.timestamp ?: return "—"
        return java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(ts))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        packetAdapter = BattlePacketAdapter(packets)
        setupOverlay()

        AppState.viewModel.battlePackets.observeForever(battlePacketsObserver)
        AppState.viewModel.gameServer.observeForever(gameServerObserver)
    }

    override fun onDestroy() {
        AppState.viewModel.battlePackets.removeObserver(battlePacketsObserver)
        AppState.viewModel.gameServer.removeObserver(gameServerObserver)
        removeOverlay()
        removeMini()
        super.onDestroy()
    }

    // ── Detection panel ────────────────────────────────────────────────────

    private fun updateDetectionPanel(entry: ConnectionEntry?) {
        val v = overlayView ?: return
        val statusTv = v.findViewById<TextView>(R.id.tv_server_status) ?: return
        val addrTv   = v.findViewById<TextView>(R.id.tv_server_addr)   ?: return
        val rowBytes = v.findViewById<View>(R.id.row_bytes)
        val bytesTx  = v.findViewById<TextView>(R.id.tv_bytes_tx)
        val bytesRx  = v.findViewById<TextView>(R.id.tv_bytes_rx)
        val pktCount = v.findViewById<TextView>(R.id.tv_pkt_count)
        val liveDot  = v.findViewById<TextView>(R.id.tv_live_dot)

        if (entry == null) {
            statusTv.text = "WAITING FOR GAME..."
            statusTv.setTextColor(Color.parseColor("#FF8B949E"))
            addrTv.visibility = View.GONE
            rowBytes?.visibility = View.GONE
            liveDot?.setTextColor(Color.parseColor("#FF8B949E"))
            return
        }

        val isActive = entry.status == ConnectionStatus.ACTIVE
        statusTv.text = if (isActive) "● GAME SERVER DETECTED" else "GAME SERVER  (idle)"
        statusTv.setTextColor(if (isActive) Color.parseColor("#FF3FB950") else Color.parseColor("#FFD29922"))
        liveDot?.setTextColor(if (isActive) Color.parseColor("#FF3FB950") else Color.parseColor("#FF8B949E"))

        addrTv.text = "${entry.dstIp}:${entry.dstPort}  [${entry.protocol.name}]"
        addrTv.visibility = View.VISIBLE

        bytesTx?.text = "↑ ${formatBytes(entry.bytesOut)}"
        bytesRx?.text = "↓ ${formatBytes(entry.bytesIn)}"
        pktCount?.text = "${packets.size} pkts"
        rowBytes?.visibility = View.VISIBLE
    }

    private fun updateMenuDetection(entry: ConnectionEntry?) {
        val v = overlayView ?: return
        if (entry == null) {
            v.findViewById<TextView>(R.id.menu_detection_server)?.text = "   Game Server:  —"
            v.findViewById<TextView>(R.id.menu_detection_proto)?.text  = "   Protocol:  —"
            v.findViewById<TextView>(R.id.menu_detection_status)?.apply {
                text = "   Status:  WAITING"
                setTextColor(Color.parseColor("#FF8B949E"))
            }
            v.findViewById<TextView>(R.id.menu_detection_bytes)?.text = "   Traffic:  —"
        } else {
            v.findViewById<TextView>(R.id.menu_detection_server)?.text =
                "   Game Server:  ${entry.dstIp}:${entry.dstPort}"
            v.findViewById<TextView>(R.id.menu_detection_proto)?.text =
                "   Protocol:  ${entry.protocol.name}"
            val statusStr   = entry.status.name
            val statusColor = if (entry.status == ConnectionStatus.ACTIVE)
                Color.parseColor("#FF3FB950") else Color.parseColor("#FF8B949E")
            v.findViewById<TextView>(R.id.menu_detection_status)?.apply {
                text = "   Status:  $statusStr"
                setTextColor(statusColor)
            }
            v.findViewById<TextView>(R.id.menu_detection_bytes)?.text =
                "   Traffic:  ↑${formatBytes(entry.bytesOut)}  ↓${formatBytes(entry.bytesIn)}  (${packets.size} pkts)"
        }
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024        -> "${b} B"
        b < 1048576     -> "${"%.1f".format(b / 1024.0)} KB"
        else            -> "${"%.2f".format(b / 1048576.0)} MB"
    }

    // ── WindowManager params ───────────────────────────────────────────────

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun makeParams(
        w: Int = dp(360f),
        h: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        x: Int = savedX,
        y: Int = savedY
    ) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.END; this.x = x; this.y = y }

    // ── Main overlay ───────────────────────────────────────────────────────

    private fun setupOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)
        view.clipToOutline = true
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        overlayView = view

        val rv = view.findViewById<RecyclerView>(R.id.rv_connections)
        rv?.apply {
            layoutManager = LinearLayoutManager(this@OverlayService).also { it.stackFromEnd = true }
            adapter = packetAdapter
        }

        val params = makeParams()
        attachDrag(view.findViewById(R.id.overlay_header), view, params)

        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        val menuPanel = view.findViewById<View>(R.id.panel_menu)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            val opening = menuPanel.visibility == View.GONE
            menuPanel.visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) updateMenuDetection(AppState.viewModel.gameServer.value)
        }

        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            val prev = packets.size
            packets.clear()
            packetAdapter.notifyItemRangeRemoved(0, prev)
            AppState.viewModel.clearAll()
            menuPanel.visibility = View.GONE
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
        }

        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val msgs = AppState.viewModel.getBattleMessages()
            LogDownloader.downloadAndShare(this, msgs)
        }

        updateDetectionPanel(AppState.viewModel.gameServer.value)

        windowManager.addView(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    // ── Mini badge ─────────────────────────────────────────────────────────

    private fun showMini() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay_mini, null)
        miniView = view
        view.findViewById<GifView>(R.id.gif_mini)?.setGifResource(R.raw.lexan_effect)
        view.findViewById<TextView>(R.id.tv_mini_count)?.text =
            if (packets.isEmpty()) "--" else "${packets.size}"

        val params = makeParams(w = dp(80f), h = dp(80f))
        var startX = 0; var startY = 0; var rawX = 0f; var rawY = 0f; var dragged = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = ev.rawX; rawY = ev.rawY; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - ev.rawX).toInt(); val dy = (ev.rawY - rawY).toInt()
                    if (!dragged && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x; savedY = params.y
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) { removeMini(); setupOverlay() }; true }
                else -> false
            }
        }
        windowManager.addView(view, params)
    }

    private fun removeMini() {
        miniView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        miniView = null
    }

    // ── Drag ───────────────────────────────────────────────────────────────

    private fun attachDrag(handle: View, root: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var rawX = 0f; var rawY = 0f; var dragging = false
        handle.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = ev.rawX; rawY = ev.rawY; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - ev.rawX).toInt(); val dy = (ev.rawY - rawY).toInt()
                    if (!dragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x; savedY = params.y
                        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> dragging
                else -> false
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "HammerScale Overlay",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the overlay active"; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HammerScale Active")
            .setContentText("Monitoring Mech Arena")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

// ── Battle packet adapter ──────────────────────────────────────────────────

class BattlePacketAdapter(private val items: List<LiveMessage>) :
    RecyclerView.Adapter<BattlePacketAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val colorBar = v.findViewById<View>(R.id.view_color_bar)
        val label    = v.findViewById<TextView>(R.id.tv_event_label)
        val time     = v.findViewById<TextView>(R.id.tv_event_time)
        val detail   = v.findViewById<TextView>(R.id.tv_event_detail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_event, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val msg = items[pos]
        val type = msg.commandName ?: "unknown"
        val isOut = msg.direction == LiveMessage.Direction.OUTBOUND

        // Color bar by packet type
        val barColor = when (type) {
            "tick_action"  -> Color.parseColor("#FF3FB950")  // green  — movement/shooting
            "tick_idle"    -> Color.parseColor("#FF30363D")  // dim    — idle ticks
            "ping",
            "ping_ack"     -> Color.parseColor("#FF444C56")  // grey   — heartbeat
            "clock_sync"   -> Color.parseColor("#FF58A6FF")  // blue   — clock
            "world_state"  -> Color.parseColor("#FF8957E5")  // purple — server world state
            else           -> Color.parseColor("#FFD29922")  // amber  — unknown
        }
        h.colorBar.setBackgroundColor(barColor)

        // Direction arrow + label
        val arrow = if (isOut) "▲" else "▼"
        val typeLabel = when (type) {
            "ping"        -> "PING"
            "ping_ack"    -> "PING ACK"
            "clock_sync"  -> "CLOCK SYNC"
            "tick_idle"   -> "TICK  idle"
            "tick_action" -> "TICK  ACTION"
            "world_state" -> "WORLD STATE"
            else          -> type.uppercase()
        }
        h.label.text = "$arrow $typeLabel"
        h.label.setTextColor(
            if (type == "tick_action") Color.parseColor("#FF3FB950")
            else if (type == "tick_idle" || type == "ping" || type == "ping_ack") Color.parseColor("#FF6E7681")
            else Color.parseColor("#FFE6EDF3")
        )

        // Time
        h.time.text = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(msg.timestamp))

        // Detail: byte size + first byte hex
        val hex0 = "0x%02X".format(msg.data[0].toInt() and 0xFF)
        h.detail.text = "$hex0  ${msg.data.size} B"
        h.detail.visibility = View.VISIBLE
    }
}
