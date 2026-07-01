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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexora.hammerscale.model.ConnectionEntry
import com.nexora.hammerscale.model.ConnectionStatus
import com.nexora.hammerscale.model.ConnectionViewModel
import com.nexora.hammerscale.model.LiveMessage
import com.nexora.hammerscale.net.MechStateDecoder
import com.nexora.hammerscale.net.TickState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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

    // FLOW tab — only non-idle packets shown
    private val flowPackets = mutableListOf<LiveMessage>()
    private lateinit var packetAdapter: BattlePacketAdapter

    // All packets (for counts/stats source)
    private var allPackets = listOf<LiveMessage>()

    // Active tab: 0=FLOW 1=TICK 2=STATS
    private var currentTab = 0

    private var savedX: Int = 0
    private var savedY: Int = 120

    // ── Observers ──────────────────────────────────────────────────────────

    private val battlePacketsObserver = Observer<List<LiveMessage>> { list ->
        allPackets = list

        // FLOW: only non-idle packets (tick_idle is too noisy to show)
        val filtered = list.filter { it.commandName != "tick_idle" }
        val prev = flowPackets.size
        val added = if (flowPackets.size < filtered.size) filtered.drop(flowPackets.size) else emptyList()
        if (added.isNotEmpty()) {
            flowPackets.addAll(added)
            packetAdapter.notifyItemRangeInserted(prev, added.size)
            val rv = overlayView?.findViewById<RecyclerView>(R.id.rv_connections)
            if (rv != null && isAtBottom(rv)) rv.scrollToPosition(flowPackets.size - 1)
        }

        updateStatusBar()
    }

    private val gameServerObserver = Observer<ConnectionEntry?> { entry ->
        updateDetectionPanel(entry)
        updateMenuDetection(entry)
    }

    private val tickStateObserver = Observer<TickState?> { state ->
        if (currentTab == 1) updateTickPanel(state)
    }

    private val battleStatsObserver = Observer<ConnectionViewModel.BattleStats> { stats ->
        if (currentTab == 2) updateStatsPanel(stats)
    }

    private fun isAtBottom(rv: RecyclerView): Boolean {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        return lm.findLastVisibleItemPosition() >= packetAdapter.itemCount - 3
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

        packetAdapter = BattlePacketAdapter(flowPackets)
        setupOverlay()

        AppState.viewModel.battlePackets.observeForever(battlePacketsObserver)
        AppState.viewModel.gameServer.observeForever(gameServerObserver)
        AppState.viewModel.lastTickState.observeForever(tickStateObserver)
        AppState.viewModel.battleStats.observeForever(battleStatsObserver)
    }

    override fun onDestroy() {
        AppState.viewModel.battlePackets.removeObserver(battlePacketsObserver)
        AppState.viewModel.gameServer.removeObserver(gameServerObserver)
        AppState.viewModel.lastTickState.removeObserver(tickStateObserver)
        AppState.viewModel.battleStats.removeObserver(battleStatsObserver)
        removeOverlay()
        removeMini()
        super.onDestroy()
    }

    // ── Tab switching ──────────────────────────────────────────────────────

    private fun switchTab(tab: Int) {
        currentTab = tab
        val v = overlayView ?: return

        val tabFlow  = v.findViewById<TextView>(R.id.tab_flow)
        val tabTick  = v.findViewById<TextView>(R.id.tab_tick)
        val tabStats = v.findViewById<TextView>(R.id.tab_stats)

        val rv      = v.findViewById<View>(R.id.rv_connections)
        val panelTick  = v.findViewById<View>(R.id.panel_tick)
        val panelStats = v.findViewById<View>(R.id.panel_stats)

        val activeColor   = Color.parseColor("#FF00E5FF")
        val inactiveColor = Color.parseColor("#FF6E7681")
        val activeBg      = Color.parseColor("#FF161B22")
        val inactiveBg    = Color.parseColor("#FF0D1117")

        tabFlow.setTextColor(if (tab == 0) activeColor else inactiveColor)
        tabFlow.setBackgroundColor(if (tab == 0) activeBg else inactiveBg)
        tabTick.setTextColor(if (tab == 1) activeColor else inactiveColor)
        tabTick.setBackgroundColor(if (tab == 1) activeBg else inactiveBg)
        tabStats.setTextColor(if (tab == 2) activeColor else inactiveColor)
        tabStats.setBackgroundColor(if (tab == 2) activeBg else inactiveBg)

        rv?.visibility        = if (tab == 0) View.VISIBLE else View.GONE
        panelTick?.visibility  = if (tab == 1) View.VISIBLE else View.GONE
        panelStats?.visibility = if (tab == 2) View.VISIBLE else View.GONE

        when (tab) {
            1 -> updateTickPanel(AppState.viewModel.lastTickState.value)
            2 -> updateStatsPanel(AppState.viewModel.battleStats.value ?: ConnectionViewModel.BattleStats())
        }
    }

    // ── TICK panel ─────────────────────────────────────────────────────────

    private fun updateTickPanel(state: TickState?) {
        val v = overlayView ?: return

        if (state == null) {
            v.findViewById<TextView>(R.id.tick_seq)?.text = "—  (waiting for 0x01 packet)"
            v.findViewById<TextView>(R.id.tick_load_val)?.text = "—"
            v.findViewById<View>(R.id.tick_load_bar)?.layoutParams?.let {
                (it as FrameLayout.LayoutParams).width = 0; v.findViewById<View>(R.id.tick_load_bar)?.requestLayout()
            }
            v.findViewById<TextView>(R.id.tick_state_label)?.apply { text = "—"; setTextColor(Color.parseColor("#FF6E7681")) }
            v.findViewById<TextView>(R.id.tick_est_x)?.text = "—"
            v.findViewById<TextView>(R.id.tick_est_z)?.text = "—"
            v.findViewById<TextView>(R.id.tick_est_yaw)?.text = "—"
            v.findViewById<TextView>(R.id.tick_est_pitch)?.text = "—"
            v.findViewById<TextView>(R.id.tick_raw)?.text = "—"
            return
        }

        v.findViewById<TextView>(R.id.tick_seq)?.text =
            "${state.seq}  (tick #${state.tick})"

        v.findViewById<TextView>(R.id.tick_load_val)?.text = state.load.toString()

        // Update load bar width — post so container has measured width
        val barContainer = v.findViewById<FrameLayout>(R.id.tick_load_container)
        val barView      = v.findViewById<View>(R.id.tick_load_bar)
        if (barContainer != null && barView != null) {
            barContainer.post {
                val fill = (MechStateDecoder.loadFraction(state.load) * barContainer.width).toInt()
                barView.layoutParams = FrameLayout.LayoutParams(fill, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = android.view.Gravity.START
                }
                barView.setBackgroundColor(MechStateDecoder.barColor(state))
                barView.requestLayout()
            }
        }

        v.findViewById<TextView>(R.id.tick_state_label)?.apply {
            text = MechStateDecoder.stateLabel(state)
            setTextColor(MechStateDecoder.stateColor(state))
        }

        val fmt = "%.3f"
        v.findViewById<TextView>(R.id.tick_est_x)?.text =
            state.estF0?.let { "${fmt.format(it)} (bytes[5:9])" } ?: "— (not a valid float)"
        v.findViewById<TextView>(R.id.tick_est_z)?.text =
            state.estF1?.let { "${fmt.format(it)} (bytes[9:13])" } ?: "— (not a valid float)"
        v.findViewById<TextView>(R.id.tick_est_yaw)?.text =
            state.estF2?.let { fmtAngle(it) + "  (bytes[13:17])" } ?: "— (not a valid float)"
        v.findViewById<TextView>(R.id.tick_est_pitch)?.text =
            state.estF3?.let { fmtAngle(it) + "  (bytes[17:21])" } ?: "— (not a valid float)"

        v.findViewById<TextView>(R.id.tick_raw)?.text =
            "${state.payloadSize} B total  |  payload ${state.payloadSize - 5} B (bytes[5+])"
    }

    private fun fmtAngle(f: Float): String {
        val deg = (Math.toDegrees(f.toDouble())).toFloat()
        return if (abs(deg) < 720f) "${"%.1f".format(f)} rad → ${"%.1f".format(deg)}°"
        else "${"%.3f".format(f)}"
    }

    // ── STATS panel ────────────────────────────────────────────────────────

    private fun updateStatsPanel(stats: ConnectionViewModel.BattleStats) {
        val v = overlayView ?: return

        val elapsed = if (stats.firstTs > 0 && stats.lastTs > stats.firstTs)
            ((stats.lastTs - stats.firstTs) / 1000f).let { s ->
                val m = (s / 60).toInt(); val sec = (s % 60).toInt()
                if (m > 0) "${m}m ${sec}s" else "${sec}s"
            } else "—"

        v.findViewById<TextView>(R.id.stat_session)?.text  = elapsed
        v.findViewById<TextView>(R.id.stat_tickrate)?.text =
            "${"%.1f".format(stats.recentTickRateHz)} ticks/s  (5-s window)"
        v.findViewById<TextView>(R.id.stat_action)?.text  = stats.actionTicks.toString()
        v.findViewById<TextView>(R.id.stat_idle)?.text    = stats.idleTicks.toString()
        v.findViewById<TextView>(R.id.stat_world)?.text   = stats.worldStates.toString()
        v.findViewById<TextView>(R.id.stat_pings)?.text   = stats.pings.toString()
        v.findViewById<TextView>(R.id.stat_clocks)?.text  = stats.clockSyncs.toString()
        v.findViewById<TextView>(R.id.stat_tx)?.text      = formatBytes(stats.txBytes)
        v.findViewById<TextView>(R.id.stat_rx)?.text      = formatBytes(stats.rxBytes)
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
        pktCount?.text = "${allPackets.size} pkts"
        rowBytes?.visibility = View.VISIBLE
    }

    private fun updateMenuDetection(entry: ConnectionEntry?) {
        val v = overlayView ?: return
        if (entry == null) {
            v.findViewById<TextView>(R.id.menu_detection_server)?.text = "   Game Server:  —"
            v.findViewById<TextView>(R.id.menu_detection_proto)?.text  = "   Protocol:  —"
            v.findViewById<TextView>(R.id.menu_detection_status)?.apply {
                text = "   Status:  WAITING"; setTextColor(Color.parseColor("#FF8B949E"))
            }
            v.findViewById<TextView>(R.id.menu_detection_bytes)?.text = "   Traffic:  —"
        } else {
            v.findViewById<TextView>(R.id.menu_detection_server)?.text =
                "   Game Server:  ${entry.dstIp}:${entry.dstPort}"
            v.findViewById<TextView>(R.id.menu_detection_proto)?.text =
                "   Protocol:  ${entry.protocol.name}"
            val ok = entry.status == ConnectionStatus.ACTIVE
            v.findViewById<TextView>(R.id.menu_detection_status)?.apply {
                text = "   Status:  ${entry.status.name}"
                setTextColor(if (ok) Color.parseColor("#FF3FB950") else Color.parseColor("#FF8B949E"))
            }
            v.findViewById<TextView>(R.id.menu_detection_bytes)?.text =
                "   Traffic:  ↑${formatBytes(entry.bytesOut)}  ↓${formatBytes(entry.bytesIn)}  (${allPackets.size} pkts)"
        }
    }

    private fun updateStatusBar() {
        val n = allPackets.size
        val actions = allPackets.count { it.commandName == "tick_action" }
        val pings   = allPackets.count { it.commandName == "ping" || it.commandName == "ping_ack" }
        overlayView?.findViewById<TextView>(R.id.tv_status_bar)?.text =
            "${n} pkts  ·  ⚡${actions} act  ·  ♡${pings} ping"
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024    -> "${b} B"
        b < 1048576 -> "${"%.1f".format(b / 1024.0)} KB"
        else        -> "${"%.2f".format(b / 1048576.0)} MB"
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

        // RecyclerView (FLOW tab)
        val rv = view.findViewById<RecyclerView>(R.id.rv_connections)
        rv?.apply {
            layoutManager = LinearLayoutManager(this@OverlayService).also { it.stackFromEnd = true }
            adapter = packetAdapter
        }

        val params = makeParams()
        attachDrag(view.findViewById(R.id.overlay_header), view, params)

        // Tab buttons
        view.findViewById<TextView>(R.id.tab_flow)?.setOnClickListener  { switchTab(0) }
        view.findViewById<TextView>(R.id.tab_tick)?.setOnClickListener  { switchTab(1) }
        view.findViewById<TextView>(R.id.tab_stats)?.setOnClickListener { switchTab(2) }

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
            flowPackets.clear()
            packetAdapter.notifyDataSetChanged()
            AppState.viewModel.clearAll()
            allPackets = emptyList()
            menuPanel.visibility = View.GONE
            view.findViewById<TextView>(R.id.tv_status_bar)?.text = "cleared"
        }

        view.findViewById<TextView>(R.id.menu_download).setOnClickListener {
            menuPanel.visibility = View.GONE
            LogDownloader.downloadAndShare(this, AppState.viewModel.getBattleMessages())
        }

        updateDetectionPanel(AppState.viewModel.gameServer.value)
        switchTab(0)

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
            if (allPackets.isEmpty()) "--" else "${allPackets.size}"

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
                    if (!dragged && (abs(dx) > 8 || abs(dy) > 8)) dragged = true
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

    // ── Drag handle ────────────────────────────────────────────────────────

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
                    if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) dragging = true
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

// ── Battle packet adapter (FLOW tab) ──────────────────────────────────────

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
        val msg  = items[pos]
        val type = msg.commandName ?: "unknown"
        val isOut = msg.direction == LiveMessage.Direction.OUTBOUND

        val barColor = when (type) {
            "tick_action"  -> Color.parseColor("#FF3FB950")
            "ping",
            "ping_ack"     -> Color.parseColor("#FF444C56")
            "clock_sync"   -> Color.parseColor("#FF58A6FF")
            "world_state"  -> Color.parseColor("#FF8957E5")
            else           -> Color.parseColor("#FFD29922")
        }
        h.colorBar.setBackgroundColor(barColor)

        val arrow = if (isOut) "▲" else "▼"
        val label = when (type) {
            "ping"        -> "PING"
            "ping_ack"    -> "PING ACK"
            "clock_sync"  -> "CLOCK SYNC"
            "tick_action" -> "TICK  ACTION"
            "world_state" -> "WORLD STATE"
            else          -> type.uppercase()
        }
        h.label.text = "$arrow $label"
        h.label.setTextColor(when (type) {
            "tick_action"                         -> Color.parseColor("#FF3FB950")
            "ping", "ping_ack", "clock_sync"      -> Color.parseColor("#FF6E7681")
            "world_state"                         -> Color.parseColor("#FF8957E5")
            else                                  -> Color.parseColor("#FFE6EDF3")
        })

        h.time.text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(msg.timestamp))

        val hex0 = "0x%02X".format(msg.data[0].toInt() and 0xFF)
        h.detail.text = "$hex0  ${msg.data.size} B"
        h.detail.visibility = View.VISIBLE
    }
}
