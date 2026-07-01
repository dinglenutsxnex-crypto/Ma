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
import com.nexora.hammerscale.model.Protocol

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

    private val connections = mutableListOf<ConnectionEntry>()
    private lateinit var connectionAdapter: ConnectionAdapter

    private var savedX: Int = 0
    private var savedY: Int = 120

    // ── Observers ─────────────────────────────────────────────────────────

    private val connectionsObserver = Observer<List<ConnectionEntry>> { list ->
        connections.clear()
        connections.addAll(list)
        connectionAdapter.notifyDataSetChanged()

        val v = overlayView ?: return@Observer
        val count = list.size
        val active = list.count { it.isLive }
        v.findViewById<TextView>(R.id.tv_status_bar)?.text =
            "$count conns  ·  $active active  ·  ${lastTimeStr()}"
    }

    private val gameServerObserver = Observer<ConnectionEntry?> { entry ->
        updateDetectionPanel(entry)
        updateMenuDetection(entry)
    }

    private fun lastTimeStr(): String {
        if (connections.isEmpty()) return "—"
        val ts = connections.maxOf { it.lastActivityTime }
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

        connectionAdapter = ConnectionAdapter(connections)
        setupOverlay()

        AppState.viewModel.connections.observeForever(connectionsObserver)
        AppState.viewModel.gameServer.observeForever(gameServerObserver)
    }

    override fun onDestroy() {
        AppState.viewModel.connections.removeObserver(connectionsObserver)
        AppState.viewModel.gameServer.removeObserver(gameServerObserver)
        removeOverlay()
        removeMini()
        super.onDestroy()
    }

    // ── Detection panel update ─────────────────────────────────────────────

    private fun updateDetectionPanel(entry: ConnectionEntry?) {
        val v = overlayView ?: return
        val statusTv  = v.findViewById<TextView>(R.id.tv_server_status) ?: return
        val addrTv    = v.findViewById<TextView>(R.id.tv_server_addr)   ?: return
        val rowBytes  = v.findViewById<View>(R.id.row_bytes)
        val bytesTx   = v.findViewById<TextView>(R.id.tv_bytes_tx)
        val bytesRx   = v.findViewById<TextView>(R.id.tv_bytes_rx)
        val pktCount  = v.findViewById<TextView>(R.id.tv_pkt_count)
        val liveDot   = v.findViewById<TextView>(R.id.tv_live_dot)

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
        statusTv.setTextColor(
            if (isActive) Color.parseColor("#FF3FB950") else Color.parseColor("#FFD29922")
        )
        liveDot?.setTextColor(
            if (isActive) Color.parseColor("#FF3FB950") else Color.parseColor("#FF8B949E")
        )

        addrTv.text = "${entry.dstIp}:${entry.dstPort}  [${entry.protocol.name}]"
        addrTv.visibility = View.VISIBLE

        // Calculate packet count from message count
        val msgCount = synchronized(entry.messages) { entry.messages.size }
        bytesTx?.text = "↑ ${formatBytes(entry.bytesOut)}"
        bytesRx?.text = "↓ ${formatBytes(entry.bytesIn)}"
        pktCount?.text = "$msgCount pkts"
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
            val statusStr = when (entry.status) {
                ConnectionStatus.ACTIVE     -> "ACTIVE"
                ConnectionStatus.CONNECTING -> "CONNECTING"
                ConnectionStatus.CLOSING    -> "CLOSING"
                ConnectionStatus.CLOSED     -> "CLOSED"
            }
            val statusColor = if (entry.status == ConnectionStatus.ACTIVE)
                Color.parseColor("#FF3FB950") else Color.parseColor("#FF8B949E")
            v.findViewById<TextView>(R.id.menu_detection_status)?.apply {
                text = "   Status:  $statusStr"
                setTextColor(statusColor)
            }
            val msgCount = synchronized(entry.messages) { entry.messages.size }
            v.findViewById<TextView>(R.id.menu_detection_bytes)?.text =
                "   Traffic:  ↑${formatBytes(entry.bytesOut)}  ↓${formatBytes(entry.bytesIn)}  ($msgCount pkts)"
        }
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024          -> "${b} B"
        b < 1024 * 1024   -> "${"%.1f".format(b / 1024.0)} KB"
        else              -> "${"%.2f".format(b / (1024.0 * 1024.0))} MB"
    }

    // ── WindowManager params ──────────────────────────────────────────────

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun makeParams(
        w: Int = dp(360f),
        h: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        x: Int = savedX,
        y: Int = savedY
    ) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        this.x = x; this.y = y
    }

    // ── Main overlay ──────────────────────────────────────────────────────

    private fun setupOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)
        view.clipToOutline = true
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        overlayView = view

        val rv = view.findViewById<RecyclerView>(R.id.rv_connections)
        rv?.apply {
            layoutManager = LinearLayoutManager(this@OverlayService)
            adapter = connectionAdapter
        }

        val params = makeParams()
        attachDrag(view.findViewById(R.id.overlay_header), view, params)

        // Minimize
        view.findViewById<TextView>(R.id.btn_minimize).setOnClickListener {
            removeOverlay(); showMini()
        }

        // Menu toggle
        val menuPanel = view.findViewById<View>(R.id.panel_menu)
        view.findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            menuPanel.visibility =
                if (menuPanel.visibility == View.GONE) View.VISIBLE else View.GONE
            // Refresh detection info when opening menu
            if (menuPanel.visibility == View.VISIBLE) {
                updateMenuDetection(AppState.viewModel.gameServer.value)
            }
        }

        // Clear
        view.findViewById<TextView>(R.id.menu_clear).setOnClickListener {
            AppState.viewModel.clearAll()
            menuPanel.visibility = View.GONE
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
        }

        // Download logs
        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            val msgs = AppState.viewModel.getAllMessages()
            LogDownloader.downloadAndShare(this, msgs)
        }

        // Seed current state
        updateDetectionPanel(AppState.viewModel.gameServer.value)

        windowManager.addView(view, params)
    }

    private fun removeOverlay() {
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    // ── Mini badge ────────────────────────────────────────────────────────

    private fun showMini() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_overlay_mini, null)
        miniView = view

        view.findViewById<GifView>(R.id.gif_mini)?.setGifResource(R.raw.lexan_effect)

        val miniCountTv = view.findViewById<TextView>(R.id.tv_mini_count)
        miniCountTv?.text = if (connections.isEmpty()) "--" else "${connections.count { it.isLive }}"

        val params = makeParams(w = dp(80f), h = dp(80f))

        var startX = 0; var startY = 0
        var rawX = 0f; var rawY = 0f
        var dragged = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = ev.rawX; rawY = ev.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - ev.rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (!dragged && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        savedX = params.x; savedY = params.y
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) { removeMini(); setupOverlay() }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
    }

    private fun removeMini() {
        miniView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        miniView = null
    }

    // ── Drag ──────────────────────────────────────────────────────────────

    private fun attachDrag(handle: View, root: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0
        var rawX = 0f; var rawY = 0f
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    rawX = event.rawX; rawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (rawX - event.rawX).toInt()
                    val dy = (event.rawY - rawY).toInt()
                    if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) dragging = true
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

    // ── Notification ──────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "HammerScale Overlay", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the overlay active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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

// ── Connection list adapter ────────────────────────────────────────────────

class ConnectionAdapter(private val items: List<ConnectionEntry>) :
    RecyclerView.Adapter<ConnectionAdapter.VH>() {

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
        val entry = items[pos]

        // Color bar by protocol / status
        val barColor = when {
            entry.protocol == Protocol.UDP && entry.dstPort == 7015 ->
                Color.parseColor("#FF00E5FF")  // cyan = game server
            entry.protocol == Protocol.UDP    -> Color.parseColor("#FF58A6FF")
            entry.protocol == Protocol.DNS    -> Color.parseColor("#FF8957E5")
            entry.isWebSocket                 -> Color.parseColor("#FFD29922")
            entry.status == ConnectionStatus.ACTIVE -> Color.parseColor("#FF3FB950")
            else                              -> Color.parseColor("#FF444C56")
        }
        h.colorBar.setBackgroundColor(barColor)

        // Label: proto  host:port  status
        val proto = when {
            entry.protocol == Protocol.DNS -> "DNS"
            entry.protocol == Protocol.UDP -> "UDP"
            entry.isWebSocket              -> "WS "
            else                           -> "TCP"
        }
        val host = if (entry.dstHost != entry.dstIp) entry.dstHost else entry.dstIp
        val statusMark = when (entry.status) {
            ConnectionStatus.ACTIVE     -> "●"
            ConnectionStatus.CONNECTING -> "○"
            ConnectionStatus.CLOSING    -> "◌"
            ConnectionStatus.CLOSED     -> "·"
        }
        h.label.text = "$statusMark $proto  $host:${entry.dstPort}"
        h.label.setTextColor(
            if (entry.isLive) Color.parseColor("#FFE6EDF3") else Color.parseColor("#FF6E7681")
        )

        // Time
        h.time.text = entry.startTimeStr

        // Detail: traffic
        val tx = formatBytes(entry.bytesOut)
        val rx = formatBytes(entry.bytesIn)
        if (entry.bytesIn > 0 || entry.bytesOut > 0) {
            h.detail.text = "↑$tx  ↓$rx"
            h.detail.visibility = View.VISIBLE
        } else {
            h.detail.visibility = View.GONE
        }
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024        -> "${b}B"
        b < 1024 * 1024 -> "${"%.1f".format(b / 1024.0)}KB"
        else            -> "${"%.2f".format(b / (1024.0 * 1024.0))}MB"
    }
}
