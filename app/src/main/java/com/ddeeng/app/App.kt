package com.ddeeng.app

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.google.android.gms.location.*
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

/* ==========================================================
   색상
   ========================================================== */
object C {
    const val BG      = 0xFF0A0E12.toInt()
    const val PANEL   = 0xFF131A21.toInt()
    const val LINE    = 0xFF22303B.toInt()
    const val INK     = 0xFFE8EEF3.toInt()
    const val MUTE    = 0xFF7A8B99.toInt()
    const val WARN    = 0xFFFFB020.toInt()
    const val OK      = 0xFF3FD69A.toInt()
    const val HOT     = 0xFFFF5A4E.toInt()
    const val ALERTBG = 0xFF1A0B08.toInt()
}

/* ==========================================================
   설정 저장
   ========================================================== */
object Prefs {
    private lateinit var sp: android.content.SharedPreferences
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("ddeeng", Context.MODE_PRIVATE)
    }
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var radiusM: Int get() = i("radius", 500);  set(v) = put("radius", v)
    var coneDeg: Int get() = i("cone", 90);     set(v) = put("cone", v)
    var instrument: String get() = s("inst", "bell"); set(v) = put("inst", v)
    var freq1: Int   get() = i("f1", 1568);     set(v) = put("f1", v)
    var freq2: Int   get() = i("f2", 1376);     set(v) = put("f2", v)
    var gapMs: Int   get() = i("gap", 135);     set(v) = put("gap", v)
    var decayPct: Int get() = i("decay", 100);  set(v) = put("decay", v)
    var noteCount: Int get() = i("count", 2);   set(v) = put("count", v)
    var btAddress: String get() = s("bt_addr", ""); set(v) = put("bt_addr", v)
}

/* ==========================================================
   알림음 — 배음 합성
   ========================================================== */
object SoundEngine {
    private const val SR = 44100

    class Inst(
        val label: String,
        val partials: Array<DoubleArray>,   // [배수, 세기, 감쇠배수]
        val attack: Double,
        val decay: Double,
        val inharmonic: Double = 0.0,
        val noise: Double = 0.0,
        val soft: Boolean = false
    )

    val INSTRUMENTS: LinkedHashMap<String, Inst> = linkedMapOf(
        "bell" to Inst("종",
            arrayOf(doubleArrayOf(0.5,0.5,1.2), doubleArrayOf(1.0,1.0,1.0),
                doubleArrayOf(2.76,0.4,0.75), doubleArrayOf(5.4,0.2,0.5),
                doubleArrayOf(8.93,0.1,0.35)), 0.002, 1.6, noise = 0.03),
        "marimba" to Inst("마림바",
            arrayOf(doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(4.0,0.28,0.5),
                doubleArrayOf(9.2,0.1,0.3)), 0.002, 0.55, noise = 0.05),
        "glocken" to Inst("글로켄",
            arrayOf(doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(3.0,0.5,0.7),
                doubleArrayOf(6.1,0.22,0.45), doubleArrayOf(10.5,0.1,0.3)),
            0.001, 0.9, noise = 0.04),
        "piano" to Inst("피아노",
            arrayOf(doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(2.0,0.5,0.85),
                doubleArrayOf(3.0,0.28,0.7), doubleArrayOf(4.0,0.18,0.6),
                doubleArrayOf(5.0,0.1,0.5), doubleArrayOf(6.0,0.06,0.4)),
            0.003, 1.0, inharmonic = 0.0004, noise = 0.12),
        "harp" to Inst("하프",
            arrayOf(doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(2.0,0.55,0.8),
                doubleArrayOf(3.0,0.32,0.65), doubleArrayOf(4.0,0.2,0.55),
                doubleArrayOf(5.0,0.12,0.45), doubleArrayOf(6.0,0.08,0.35),
                doubleArrayOf(8.0,0.05,0.28)), 0.004, 1.15, noise = 0.08),
        "musicbox" to Inst("뮤직박스",
            arrayOf(doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(2.05,0.42,0.7),
                doubleArrayOf(3.2,0.24,0.5), doubleArrayOf(4.6,0.12,0.35),
                doubleArrayOf(6.3,0.07,0.25)), 0.001, 0.75, noise = 0.06),
        "flute" to Inst("플루트",
            arrayOf(doubleArrayOf(1.0,1.0,1.0), doubleArrayOf(2.0,0.12,0.9),
                doubleArrayOf(3.0,0.05,0.8)), 0.055, 0.5, noise = 0.18, soft = true),
        "sine" to Inst("전자음",
            arrayOf(doubleArrayOf(1.0,1.0,1.0)), 0.006, 0.6)
    )

    private var cache: ShortArray? = null
    private var cacheKey = ""

    private fun key() = "${Prefs.instrument}|${Prefs.freq1}|${Prefs.freq2}|" +
            "${Prefs.gapMs}|${Prefs.decayPct}|${Prefs.noteCount}"

    fun invalidate() { cache = null; cacheKey = "" }

    private fun build(): ShortArray {
        val inst = INSTRUMENTS[Prefs.instrument] ?: INSTRUMENTS["bell"]!!
        val decay = inst.decay * (Prefs.decayPct / 100.0)
        val gap = Prefs.gapMs / 1000.0

        val notes = ArrayList<Pair<Double, Double>>()
        notes.add(0.0 to Prefs.freq1.toDouble())
        if (Prefs.noteCount >= 2) notes.add(gap to Prefs.freq2.toDouble())
        if (Prefs.noteCount >= 3) notes.add(gap * 2 to Prefs.freq2 * 1.5)

        val maxDecay = decay * inst.partials.maxOf { it[2] }
        val totalSec = min(6.0, notes.last().first + maxDecay + 0.08)
        val n = (totalSec * SR).toInt()
        val buf = DoubleArray(n)
        val ampSum = inst.partials.sumOf { it[1] }

        for ((startSec, freq) in notes) {
            val s0 = (startSec * SR).toInt()
            val base = 0.5 / ampSum

            for (p in inst.partials) {
                val mult = p[0]; val amp = p[1]; val dscale = p[2]
                var f = freq * mult
                if (inst.inharmonic > 0) f = freq * mult * sqrt(1 + inst.inharmonic * mult * mult)
                if (f > 15000 || f < 25) continue

                val d = max(0.06, decay * dscale)
                val dn = (d * SR).toInt()
                val kDec = 6.9 / d
                val atk = max(1, (inst.attack * SR).toInt())
                val hold = if (inst.soft) (d * 0.55 * SR).toInt() else 0
                val peak = base * amp
                val w = 2.0 * PI * f / SR

                var i = 0
                while (i < dn && s0 + i < n) {
                    val env = when {
                        i < atk -> peak * i / atk
                        inst.soft && i < hold -> peak
                        else -> peak * exp(-kDec * (i - hold).toDouble() / SR)
                    }
                    buf[s0 + i] += env * sin(w * i)
                    i++
                }
            }

            // 타격/숨 소리
            if (inst.noise > 0) {
                val len = if (inst.soft) 0.25 else 0.05
                val nn = (len * SR).toInt()
                val fc = min(if (inst.soft) freq * 1.5 else freq * 2.2, SR / 2.2)
                val q = if (inst.soft) 0.8 else 1.6
                val g = tan(PI * fc / SR)
                val k = 1.0 / q
                val a1 = 1.0 / (1.0 + g * (g + k))
                val a2 = g * a1
                var ic1 = 0.0; var ic2 = 0.0
                val peak = base * inst.noise * 2.2
                val kDec = 6.9 / len

                var i = 0
                while (i < nn && s0 + i < n) {
                    val x = Random.nextDouble(-1.0, 1.0)
                    val v1 = a1 * ic1 + a2 * (x - ic2)
                    val v2 = ic2 + g * v1
                    ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
                    val env = if (i < 180) peak * i / 180.0
                              else peak * exp(-kDec * (i - 180.0) / SR)
                    buf[s0 + i] += v1 * env
                    i++
                }
            }
        }

        var pk = 0.0
        for (v in buf) if (abs(v) > pk) pk = abs(v)
        val scale = if (pk > 0.92) 0.92 / pk else 1.0

        val out = ShortArray(n)
        for (i in 0 until n)
            out[i] = (buf[i] * scale * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
        return out
    }

    private fun samples(): ShortArray {
        val k = key()
        if (cache == null || cacheKey != k) { cache = build(); cacheKey = k }
        return cache!!
    }

    /** 내비 안내용으로 출력 — 블루투스 연결 시 차량 스피커로 나간다. */
    fun play() {
        try {
            val pcm = samples()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        try { t?.release() } catch (_: Exception) {}
                    }
                    override fun onPeriodicNotification(t: AudioTrack?) {}
                })
            track.play()
        } catch (_: Exception) {}
    }
}

/* ==========================================================
   카메라 데이터 (앱에 내장된 CSV)
   ========================================================== */
data class Cam(val lat: Double, val lng: Double, val limit: Int, val kind: String)

object CameraRepo {
    private const val CELL = 0.01
    private var cams: List<Cam> = emptyList()
    private val grid = HashMap<Long, MutableList<Int>>()

    @Volatile var ready = false; private set
    @Volatile var error: String? = null; private set
    val size: Int get() = cams.size

    private fun cellKey(la: Double, ln: Double): Long =
        (Math.round(la / CELL) shl 22) xor (Math.round(ln / CELL) and 0x3FFFFFL)

    fun load(ctx: Context) {
        if (ready) return
        try {
            parse(readSmart(ctx.assets.open("cameras.csv").use { it.readBytes() }))
            error = null
        } catch (e: Exception) {
            error = e.message ?: "데이터를 읽지 못했습니다"
        }
        ready = true
    }

    fun nearby(la: Double, ln: Double, radiusM: Double): List<Cam> {
        if (cams.isEmpty()) return emptyList()
        val r = ceil(radiusM / 1000.0 / 1.1).toInt() + 1
        val ba = Math.round(la / CELL); val bo = Math.round(ln / CELL)
        val out = ArrayList<Cam>()
        for (x in -r..r) for (y in -r..r)
            grid[((ba + x) shl 22) xor ((bo + y) and 0x3FFFFFL)]?.forEach { out.add(cams[it]) }
        return out
    }

    private fun readSmart(b: ByteArray): String {
        val euc = try { String(b, charset("EUC-KR")) } catch (e: Exception) { "" }
        return if (euc.isEmpty() || euc.take(400).contains('\uFFFD'))
            String(b, Charsets.UTF_8) else euc
    }

    private fun splitLine(l: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false
        var i = 0
        while (i < l.length) {
            val c = l[i]
            when {
                c == '"' -> if (q && i + 1 < l.length && l[i + 1] == '"') { cur.append('"'); i++ } else q = !q
                c == ',' && !q -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString()); return out
    }

    private fun findCol(h: List<String>, keys: List<String>): Int {
        for (i in h.indices) {
            val s = h[i].replace(Regex("[\\s\"']"), "")
            if (keys.any { s.contains(it) }) return i
        }
        return -1
    }

    private fun parse(text: String) {
        val lines = text.split(Regex("\\r?\\n")).filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "빈 파일입니다." }
        val hdr = splitLine(lines[0])
        val iLat = findCol(hdr, listOf("위도", "latitude", "lat"))
        val iLng = findCol(hdr, listOf("경도", "longitude", "lng", "lon"))
        require(iLat >= 0 && iLng >= 0) { "위도·경도 열이 없습니다." }
        val iLim = findCol(hdr, listOf("제한속도", "속도"))
        val iKind = findCol(hdr, listOf("단속구분", "단속종류", "시설종류"))

        val list = ArrayList<Cam>(lines.size)
        for (k in 1 until lines.size) {
            val c = splitLine(lines[k])
            val la = c.getOrNull(iLat)?.trim()?.toDoubleOrNull() ?: continue
            val ln = c.getOrNull(iLng)?.trim()?.toDoubleOrNull() ?: continue
            if (la < 32.0 || la > 39.8 || ln < 124.0 || ln > 132.2) continue
            val kind = if (iKind >= 0) c.getOrElse(iKind) { "" }.replace("\"", "") else ""
            if (Regex("주정차|주차").containsMatchIn(kind)) continue
            list.add(Cam(la, ln,
                if (iLim >= 0) c.getOrNull(iLim)?.trim()?.toIntOrNull() ?: 0 else 0, kind))
        }
        require(list.isNotEmpty()) { "유효한 좌표가 없습니다." }
        cams = list
        grid.clear()
        cams.forEachIndexed { i, cam -> grid.getOrPut(cellKey(cam.lat, cam.lng)) { ArrayList() }.add(i) }
    }

    fun distanceM(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val R = 6371000.0
        val dLa = Math.toRadians(a2 - a1); val dLo = Math.toRadians(o2 - o1)
        val h = sin(dLa / 2).pow(2) +
                cos(Math.toRadians(a1)) * cos(Math.toRadians(a2)) * sin(dLo / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }

    fun bearing(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val y = sin(Math.toRadians(o2 - o1)) * cos(Math.toRadians(a2))
        val x = cos(Math.toRadians(a1)) * sin(Math.toRadians(a2)) -
                sin(Math.toRadians(a1)) * cos(Math.toRadians(a2)) * cos(Math.toRadians(o2 - o1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

/* ==========================================================
   위치 서비스
   ========================================================== */
class LocationService : Service() {

    companion object {
        const val CH = "ddeeng_ongoing"
        const val NOTI = 1001
        const val ACTION_STOP = "STOP"

        @Volatile var running = false; private set
        @Volatile var speedKmh = -1; private set
        @Volatile var statusText = "위치 신호를 찾는 중"; private set
        @Volatile var nearAlert = false; private set

        /** 화면 갱신 콜백 (같은 프로세스라 직접 연결) */
        @Volatile var onUpdate: (() -> Unit)? = null
        @Volatile var onFinish: (() -> Unit)? = null
    }

    private lateinit var fused: FusedLocationProviderClient
    private val cooldown = HashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())

    private val cb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { onLoc(it) } }
    }

    private val btRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
            val target = Prefs.btAddress
            if (target.isBlank()) return
            val dev: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (dev?.address == target) {
                main.post { onFinish?.invoke() }
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "감시 상태", NotificationManager.IMPORTANCE_LOW))
        registerReceiver(btRx, IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED))
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val n = noti("위치 신호를 찾는 중")
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTI, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTI, n)

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try { fused.requestLocationUpdates(req, cb, Looper.getMainLooper()) }
        catch (_: SecurityException) { statusText = "위치 권한이 없습니다" }

        running = true
        return START_STICKY
    }

    private fun onLoc(loc: Location) {
        val la = loc.latitude; val ln = loc.longitude
        speedKmh = if (loc.hasSpeed() && loc.speed >= 0) (loc.speed * 3.6).toInt() else -1
        val now = System.currentTimeMillis()
        val radius = Prefs.radiusM.toDouble()
        val cone = Prefs.coneDeg

        var best: Cam? = null; var bestD = Double.MAX_VALUE
        for (c in CameraRepo.nearby(la, ln, radius)) {
            val d = CameraRepo.distanceM(la, ln, c.lat, c.lng)
            if (d > radius) continue
            if (cone < 360 && loc.hasBearing() && speedKmh > 15) {
                val br = CameraRepo.bearing(la, ln, c.lat, c.lng)
                if (CameraRepo.angleDiff(br, loc.bearing.toDouble()) > cone / 2.0) continue
            }
            if (d < bestD) { bestD = d; best = c }

            val key = "%.5f,%.5f".format(c.lat, c.lng)
            if (now - (cooldown[key] ?: 0L) > 90_000) {
                cooldown[key] = now
                SoundEngine.play()
            }
        }
        if (cooldown.size > 400) cooldown.entries.removeAll { now - it.value > 300_000 }

        nearAlert = best != null
        statusText = best?.let {
            val lim = if (it.limit > 0) "${it.limit}km/h · " else ""
            "${it.kind.ifBlank { "단속" }} · $lim${bestD.toInt()}m"
        } ?: "주행 중"

        getSystemService(NotificationManager::class.java).notify(NOTI, noti(statusText))
        main.post { onUpdate?.invoke() }
    }

    private fun noti(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("띵 — 단속카메라 감시")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "종료", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false; speedKmh = -1; statusText = "정지됨"; nearAlert = false
        try { fused.removeLocationUpdates(cb) } catch (_: Exception) {}
        try { unregisterReceiver(btRx) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

/* ==========================================================
   공통 UI 도우미
   ========================================================== */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun roundRect(fill: Int, stroke: Int, radiusPx: Int): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        setStroke(max(1, radiusPx / 14), stroke)
        cornerRadius = radiusPx.toFloat()
    }

fun circle(color: Int): GradientDrawable =
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

fun ringDrawable(color: Int, w: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(w, color)
    }

/* ==========================================================
   메인 화면 — 속도계가 초기 화면, 자동 시작
   ========================================================== */
class MainActivity : Activity() {

    private lateinit var speedView: TextView
    private lateinit var statusView: TextView
    private lateinit var dotView: View
    private lateinit var ringView: View
    private lateinit var blackView: View
    private var started = false
    private val REQ = 100

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        LocationService.onUpdate = { render() }
        LocationService.onFinish = {
            Toast.makeText(this, "블루투스 연결이 끊겨 종료합니다", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }

        if (!CameraRepo.ready) {
            statusView.text = "데이터 읽는 중…"
            thread {
                CameraRepo.load(this)
                runOnUiThread { dataReady() }
            }
        } else dataReady()
    }

    private fun dataReady() {
        val e = CameraRepo.error
        if (e != null) { statusView.text = "데이터 오류: $e"; return }
        ensurePerms()
    }

    override fun onResume() { super.onResume(); render() }

    // ---------- 화면 구성 ----------
    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // 머리말
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        dotView = View(this).apply { background = circle(0xFF3A4A57.toInt()) }
        head.addView(dotView, LinearLayout.LayoutParams(dp(8), dp(8)))
        head.addView(TextView(this).apply {
            text = "띵"; setTextColor(C.INK); textSize = 15f
            letterSpacing = 0.24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        })
        col.addView(head, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        // 속도판
        val panel = FrameLayout(this).apply { setBackgroundColor(C.PANEL) }
        ringView = View(this).apply {
            background = ringDrawable(C.WARN, dp(1)); alpha = 0.2f
        }
        panel.addView(ringView, FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        }
        speedView = TextView(this).apply {
            text = "--"; setTextColor(C.INK); textSize = 88f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        center.addView(speedView)
        center.addView(TextView(this).apply {
            text = "KM / H"; setTextColor(C.MUTE); textSize = 13f
            letterSpacing = 0.3f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        statusView = TextView(this).apply {
            text = "시작하는 중…"; setTextColor(C.MUTE); textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), 0)
        }
        center.addView(statusView)
        panel.addView(center, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        col.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 버튼
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val dimBtn = Button(this).apply {
            text = "화면 끄기"
            setOnClickListener { blackView.visibility = View.VISIBLE }
        }
        val setBtn = Button(this).apply {
            text = "설정"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        row.addView(dimBtn, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(setBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            leftMargin = dp(8)
        })
        col.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 블랙아웃
        blackView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(blackView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        return root
    }

    // ---------- 권한 → 자동 시작 ----------
    private fun ensurePerms() {
        val need = ArrayList<String>()
        if (!ok(Manifest.permission.ACCESS_FINE_LOCATION))
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 && !ok(Manifest.permission.POST_NOTIFICATIONS))
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31 && !ok(Manifest.permission.BLUETOOTH_CONNECT))
            need.add(Manifest.permission.BLUETOOTH_CONNECT)

        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), REQ); return }
        askBackground()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (ok(Manifest.permission.ACCESS_FINE_LOCATION)) askBackground()
        else statusView.text = "위치 권한이 필요합니다"
    }

    private fun askBackground() {
        if (Build.VERSION.SDK_INT >= 29 && !ok(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            AlertDialog.Builder(this)
                .setTitle("‘항상 허용’이 필요합니다")
                .setMessage("화면이 꺼지거나 폰이 잠겨도 알림을 받으려면 위치 권한을 ‘항상 허용’으로 설정하세요.")
                .setPositiveButton("설정하기") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ + 1)
                }
                .setNegativeButton("이대로 시작") { _, _ -> startWatch() }
                .setCancelable(false)
                .show()
            return
        }
        startWatch()
    }

    private fun ok(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startWatch() {
        if (started || LocationService.running) { render(); return }
        started = true
        startForegroundService(Intent(this, LocationService::class.java))
        SoundEngine.play()
        render()
    }

    private fun render() {
        val on = LocationService.running
        dotView.background = circle(if (on) C.OK else 0xFF3A4A57.toInt())
        val k = LocationService.speedKmh
        speedView.text = if (k >= 0) k.toString() else "--"
        statusView.text = if (on) LocationService.statusText else "시작하는 중…"

        val near = LocationService.nearAlert
        ringView.alpha = if (near) 1f else 0.2f
        blackView.setBackgroundColor(if (near) C.ALERTBG else Color.BLACK)
        statusView.setTextColor(if (near) C.WARN else C.MUTE)
    }

    override fun onDestroy() {
        LocationService.onUpdate = null
        LocationService.onFinish = null
        super.onDestroy()
    }
}

/* ==========================================================
   설정 화면
   ========================================================== */
class SettingsActivity : Activity() {

    private val radiusValues = listOf(300, 500, 800)
    private val coneValues = listOf(360, 90, 60)
    private val countValues = listOf(1, 2, 3)
    private var btAddrs: List<String> = listOf("")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        // 데이터 상태
        body.addView(sec("단속카메라 데이터"))
        body.addView(TextView(this).apply {
            text = CameraRepo.error?.let { "데이터 오류: $it" }
                ?: "내장 데이터 · 카메라 ${CameraRepo.size}곳"
            setTextColor(C.MUTE); textSize = 13f
        })

        // 알림 거리
        body.addView(sec("알림 거리"))
        body.addView(spinner(radiusValues.map { "$it m" },
            radiusValues.indexOf(Prefs.radiusM).coerceAtLeast(0)) {
            Prefs.radiusM = radiusValues[it]
        })

        // 진행 방향
        body.addView(sec("진행 방향 판정"))
        body.addView(spinner(listOf("전부 알림", "앞쪽만 (±45°)", "엄격 (±30°)"),
            coneValues.indexOf(Prefs.coneDeg).coerceAtLeast(0)) {
            Prefs.coneDeg = coneValues[it]
        })
        body.addView(hint("반대편 도로의 카메라를 걸러냅니다 (시속 15km 이상일 때)"))

        // 악기
        val instKeys = SoundEngine.INSTRUMENTS.keys.toList()
        body.addView(sec("악기"))
        body.addView(spinner(SoundEngine.INSTRUMENTS.values.map { it.label },
            instKeys.indexOf(Prefs.instrument).coerceAtLeast(0)) {
            Prefs.instrument = instKeys[it]; SoundEngine.invalidate(); SoundEngine.play()
        })

        // 음 개수
        body.addView(sec("음 개수"))
        body.addView(spinner(listOf("한 음", "두 음", "세 음"),
            countValues.indexOf(Prefs.noteCount).coerceAtLeast(0)) {
            Prefs.noteCount = countValues[it]; SoundEngine.invalidate(); SoundEngine.play()
        })

        // 슬라이더
        addSlider(body, "첫 번째 음", 330, 2600, Prefs.freq1, " Hz") { Prefs.freq1 = it }
        addSlider(body, "두 번째 음", 330, 2600, Prefs.freq2, " Hz") { Prefs.freq2 = it }
        addSlider(body, "두 음 사이 간격", 0, 400, Prefs.gapMs, " ms") { Prefs.gapMs = it }
        addSlider(body, "여운 길이", 30, 250, Prefs.decayPct, " %") { Prefs.decayPct = it }

        body.addView(Button(this).apply {
            text = "알림음 들어보기"
            setOnClickListener { SoundEngine.play() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(16)
        })

        // 블루투스
        body.addView(sec("블루투스 연결이 끊기면 종료"))
        body.addView(bluetoothSpinner())
        body.addView(hint("차량 블루투스를 고르면, 시동을 끄고 연결이 끊길 때 앱이 자동 종료됩니다."))

        val scroll = ScrollView(this).apply { addView(body) }
        outer.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        outer.addView(Button(this).apply {
            text = "완료"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            setMargins(dp(14), dp(6), dp(14), dp(14))
        })

        return outer
    }

    // ---------- 조각 ----------
    private fun sec(t: String) = TextView(this).apply {
        text = t; setTextColor(C.MUTE); textSize = 11f; letterSpacing = 0.14f
        setPadding(0, dp(22), 0, dp(7))
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF5C6F7D.toInt()); textSize = 11f
        setPadding(0, dp(5), 0, 0)
    }

    private fun spinner(items: List<String>, sel: Int, onPick: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(sel)
            var first = true
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (first) { first = false; return }
                    onPick(pos)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

    private fun addSlider(
        parent: LinearLayout, label: String, min: Int, max: Int,
        cur: Int, unit: String, save: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = label; setTextColor(C.INK); textSize = 13f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valView = TextView(this).apply {
            text = "$cur$unit"; setTextColor(C.WARN); textSize = 12f
        }
        row.addView(valView)
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (cur - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p
                    valView.text = "$v$unit"
                    save(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    SoundEngine.invalidate(); SoundEngine.play()
                }
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun bluetoothSpinner(): Spinner {
        val labels = arrayListOf("사용 안 함")
        val addrs = arrayListOf("")

        val allowed = Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        if (allowed) {
            try {
                val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                mgr.adapter?.bondedDevices?.forEach { d ->
                    labels.add(d.name ?: d.address); addrs.add(d.address)
                }
            } catch (_: SecurityException) {}
        } else {
            labels.add("블루투스 권한이 없습니다"); addrs.add("")
        }

        btAddrs = addrs
        return spinner(labels, addrs.indexOf(Prefs.btAddress).coerceAtLeast(0)) { pos ->
            Prefs.btAddress = btAddrs.getOrElse(pos) { "" }
        }
    }
}
