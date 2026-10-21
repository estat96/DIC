package com.dic.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Receives force readings from an external device (e.g. ESP32 + HX711 load cell).
 *
 * Transport: plain text over TCP (port 5000) or UDP (port 5000). One reading per line/datagram;
 * the first number found in the message is taken, so "123.4", "F=123.4", "123.4 N" all work.
 *
 * Network: either the phone creates a local-only Wi-Fi hotspot (no internet) and the device joins it,
 * or both join the same Wi-Fi / the device's own AP. The phone IPs are shown to configure the device.
 */
class ForceServer(private val ctx: Context) {
    @Volatile var raw = Float.NaN; private set
    @Volatile var lastMs = 0L; private set
    @Volatile var clients = 0; private set
    @Volatile var rate = 0f; private set
    @Volatile var running = false; private set
    var port = 5000
    var factor = 1f          // raw → force units
    var offset = 0f          // tare (in force units)
    var unit = "N"
    var onUpdate: (() -> Unit)? = null

    var ssid: String? = null; private set
    var pass: String? = null; private set
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private val socks = CopyOnWriteArrayList<Socket>()
    private val main = Handler(Looper.getMainLooper())
    private var cnt = 0; private var rateT = 0L

    /** Calibrated force, NaN if no fresh data (older than 2 s). */
    val force: Float get() = if (System.currentTimeMillis() - lastMs < 2000) raw * factor - offset else Float.NaN

    fun tare() { if (raw.isFinite()) offset = raw * factor }

    fun start() {
        if (running) return
        running = true
        thread(name = "force-tcp") {
            try {
                tcp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                while (running) {
                    val s = tcp!!.accept()
                    socks += s; clients = socks.size; post()
                    thread(name = "force-client") {
                        try { s.getInputStream().bufferedReader().forEachLine { parse(it) } } catch (_: Exception) {}
                        socks -= s; clients = socks.size; post(); runCatching { s.close() }
                    }
                }
            } catch (_: Exception) {}
        }
        thread(name = "force-udp") {
            try {
                udp = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
                val buf = ByteArray(512); val p = DatagramPacket(buf, buf.size)
                while (running) { udp!!.receive(p); String(buf, 0, p.length).lines().forEach { parse(it) } }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        runCatching { tcp?.close() }; runCatching { udp?.close() }
        socks.forEach { runCatching { it.close() } }; socks.clear(); clients = 0
        post()
    }

    private val num = Regex("[-+]?\\d*[.,]?\\d+(?:[eE][-+]?\\d+)?")
    private fun parse(line: String) {
        val m = num.find(line) ?: return
        val v = m.value.replace(',', '.').toFloatOrNull() ?: return
        raw = v; lastMs = System.currentTimeMillis()
        cnt++
        if (lastMs - rateT > 1000) { rate = cnt * 1000f / (lastMs - rateT).coerceAtLeast(1); cnt = 0; rateT = lastMs; post() }
    }

    private fun post() = main.post { onUpdate?.invoke() }

    // ---------------- hotspot ----------------
    val hotspotOn get() = reservation != null

    @SuppressLint("MissingPermission")
    fun startHotspot(done: (String?) -> Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") c.ssid
                        pass = c.passphrase
                    } else {
                        @Suppress("DEPRECATION") val c = r.wifiConfiguration
                        @Suppress("DEPRECATION") run { ssid = c?.SSID; pass = c?.preSharedKey }
                    }
                    done(null)
                }
                override fun onStopped() { reservation = null; ssid = null; pass = null; post() }
                override fun onFailed(reason: Int) {
                    done(when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "Tethering is disallowed on this device"
                        ERROR_INCOMPATIBLE_MODE -> "Turn off the regular hotspot / Wi-Fi Direct and retry"
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel"
                        else -> "Hotspot failed (code $reason)"
                    })
                }
            }, main)
        } catch (e: Exception) { done(e.message ?: "Hotspot failed (is Location turned on?)") }
    }

    fun stopHotspot() { reservation?.close(); reservation = null; ssid = null; pass = null }

    /** IPv4 addresses of the phone (to enter on the force device). */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress}  (${ni.name})" } }
    }.getOrDefault(emptyList())
}
