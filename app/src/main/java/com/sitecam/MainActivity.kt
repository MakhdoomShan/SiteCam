package com.sitecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    companion object { const val NOT_IN_LIST = "Site is Not in list" }

    private data class Line(val main: String, val sub: String = "")

    private lateinit var preview: PreviewView
    private lateinit var cross: CrosshairView
    private lateinit var info: TextView
    private lateinit var siteId: EditText
    private lateinit var btnPrint: Button
    private lateinit var btnMode: Button
    private lateinit var btnOrient: Button
    private lateinit var btnAuto: Button
    private lateinit var zoomText: TextView
    private var camera: Camera? = null

    private var imageCapture: ImageCapture? = null
    private var front = false
    private var inverse = true            // true = Antenna (+180), false = Panorama (normal)
    private var landscape = false
    private var autoId = true
    private var programmatic = false

    // settings (saved)
    private var printAz = true
    private var trueNorth = true
    private var compassSrc = 0            // 0 = fused (gyro + compass), 1 = compass only
    private var limitM = 200

    // sensor state
    private var sinF = 0.0
    private var cosF = 1.0
    private var smoothInit = false
    private var camAzimuth = 0f           // direction the camera points
    private var tiltUp = 0f               // + = camera pointing up
    private var rollDeg = 0f
    private var magUt = 0f
    private var magAcc = -1
    private var location: Location? = null

    // site list
    private var sites: List<Site> = emptyList()
    private var nearestText = ""
    private var nearestDist = ""
    private var inList = false

    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private val bg = Executors.newSingleThreadExecutor()
    private val rot = FloatArray(9)
    private val rot2 = FloatArray(9)
    private val ori = FloatArray(3)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startAll() }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSites(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = prefs()
        printAz = sp.getBoolean("printAz", true)
        trueNorth = sp.getBoolean("trueNorth", true)
        compassSrc = sp.getInt("compassSrc", 0)
        limitM = sp.getInt("limitM", 200)
        landscape = savedInstanceState?.getBoolean("landscape") ?: false
        front = savedInstanceState?.getBoolean("front") ?: false
        inverse = savedInstanceState?.getBoolean("inverse") ?: true
        autoId = savedInstanceState?.getBoolean("autoId") ?: true
        applyOrientation()
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        cross = findViewById(R.id.cross)
        info = findViewById(R.id.info)
        siteId = findViewById(R.id.siteId)
        btnPrint = findViewById(R.id.btnPrint)
        btnMode = findViewById(R.id.btnMode)
        btnOrient = findViewById(R.id.btnOrient)
        btnAuto = findViewById(R.id.btnAuto)
        zoomText = findViewById(R.id.zoomText)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        sites = try { SiteDb.load(File(filesDir, "sites.csv")) } catch (e: Exception) { emptyList() }

        updateButtons()
        btnOrient.setOnClickListener { landscape = !landscape; applyOrientation() }
        btnMode.setOnClickListener { inverse = !inverse; updateButtons(); updateInfo() }
        btnPrint.setOnClickListener {
            printAz = !printAz
            prefs().edit().putBoolean("printAz", printAz).apply()
            updateButtons()
        }
        btnAuto.setOnClickListener {
            autoId = !autoId
            updateButtons()
            if (autoId) refreshNearest()
            updateInfo()
        }
        findViewById<Button>(R.id.btnFlip).setOnClickListener { front = !front; bindCamera() }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { capture() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        siteId.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                // Typing by hand switches auto-fill off
                if (!programmatic && siteId.hasFocus() && autoId) {
                    autoId = false
                    updateButtons()
                }
            }
        })
        siteId.setOnFocusChangeListener { _, has ->
            if (has && siteId.text.toString() == NOT_IN_LIST) setIdText("")
        }

        // Zoom: on-screen + / - and two-finger pinch on the camera view
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { stepZoom(1.25f) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { stepZoom(0.8f) }
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { stepZoom(d.scaleFactor); return true }
        })
        preview.setOnTouchListener { _, ev -> pinch.onTouchEvent(ev); true }

        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("landscape", landscape)
        out.putBoolean("front", front)
        out.putBoolean("inverse", inverse)
        out.putBoolean("autoId", autoId)
    }

    private fun prefs() = getSharedPreferences("sitecam", Context.MODE_PRIVATE)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun applyOrientation() {
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                               else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    @Suppress("DEPRECATION")
    private fun displayRotation() = windowManager.defaultDisplay.rotation

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startAll() {
        if (has(Manifest.permission.CAMERA)) bindCamera()
        startSensors(); startLocation()
    }

    override fun onResume() { super.onResume(); startSensors(); startLocation() }
    override fun onPause() { super.onPause(); sm.unregisterListener(this); lm.removeUpdates(this) }

    // ---------------- Settings ----------------
    private fun showSettings() {
        val items = arrayOf(
            "Load site list  (${sites.size} loaded)",
            "Print azimuth on photo: " + if (printAz) "ON" else "OFF",
            "North: " + if (trueNorth) "TRUE (declination applied)" else "MAGNETIC",
            "Compass source: " + if (compassSrc == 0) "Fused (gyro + compass)" else "Compass only",
            "Site distance limit: $limitM m"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, i ->
                val e = prefs().edit()
                when (i) {
                    0 -> pickFile.launch(arrayOf("*/*"))
                    1 -> { printAz = !printAz; e.putBoolean("printAz", printAz).apply(); updateButtons(); showSettings() }
                    2 -> { trueNorth = !trueNorth; e.putBoolean("trueNorth", trueNorth).apply(); showSettings() }
                    3 -> { compassSrc = 1 - compassSrc; e.putInt("compassSrc", compassSrc).apply(); startSensors(); showSettings() }
                    4 -> {
                        limitM = when (limitM) { 100 -> 200; 200 -> 300; else -> 100 }
                        e.putInt("limitM", limitM).apply(); refreshNearest(); updateInfo(); showSettings()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateButtons() {
        btnMode.text = if (inverse) "ANTENNA" else "PANORAMA"
        btnPrint.text = if (printAz) "PRINT AZ ON" else "PRINT AZ OFF"
        btnAuto.text = if (autoId) "ID AUTO" else "ID MANUAL"
    }

    // ---------------- Sensors ----------------
    private fun startSensors() {
        sm.unregisterListener(this)
        val type = if (compassSrc == 1) Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR else Sensor.TYPE_ROTATION_VECTOR
        val s = sm.getDefaultSensor(type) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) { info.text = "No compass sensor on this device"; return }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        smoothInit = false
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {
        if (s?.type == Sensor.TYPE_MAGNETIC_FIELD) magAcc = a
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            magUt = sqrt(x * x + y * y + z * z)
            magAcc = e.accuracy
            return
        }
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Remap so the result is the direction the camera points, for each screen rotation
        when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, rot2)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, rot2)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, rot2)
            else -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rot2)
        }
        SensorManager.getOrientation(rot2, ori)
        var az = Math.toDegrees(ori[0].toDouble())
        if (trueNorth) {
            location?.let {
                az += GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(),
                    it.altitude.toFloat(), System.currentTimeMillis()).declination
            }
        }
        if (front) az += 180.0
        val r = Math.toRadians(az)
        var up = -Math.toDegrees(ori[1].toDouble()).toFloat()
        if (front) up = -up
        val rl = Math.toDegrees(ori[2].toDouble()).toFloat()
        if (!smoothInit) {
            sinF = Math.sin(r); cosF = Math.cos(r); tiltUp = up; rollDeg = rl; smoothInit = true
        } else {
            sinF += 0.15 * (Math.sin(r) - sinF)
            cosF += 0.15 * (Math.cos(r) - cosF)
            tiltUp += 0.2f * (up - tiltUp)
            rollDeg += 0.2f * (rl - rollDeg)
        }
        camAzimuth = ((Math.toDegrees(atan2(sinF, cosF)) + 360) % 360).toFloat()
        cross.level = abs(tiltUp) < 3f && abs(rollDeg) < 3f
        updateInfo()
    }

    // ---------------- Location ----------------
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        lm.removeUpdates(this)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(p)) {
                lm.requestLocationUpdates(p, 1000L, 0f, this)
                lm.getLastKnownLocation(p)?.let { if (location == null) { location = it; refreshNearest() } }
            }
        }
    }

    override fun onLocationChanged(loc: Location) { location = loc; refreshNearest(); updateInfo() }
    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---------------- Site list ----------------
    private fun fmt(m: Float) = if (m < 1000) "%.0f m".format(Locale.US, m) else "%.1f km".format(Locale.US, m / 1000)

    private fun setIdText(t: String) {
        if (siteId.text.toString() != t) {
            programmatic = true
            siteId.setText(t)
            programmatic = false
        }
    }

    private fun refreshNearest() {
        val l = location
        if (l == null || sites.isEmpty()) { nearestText = ""; nearestDist = ""; inList = false; return }
        val res = FloatArray(1)
        var best: Site? = null
        var bestD = Float.MAX_VALUE
        for (st in sites) {
            Location.distanceBetween(l.latitude, l.longitude, st.lat, st.lon, res)
            if (res[0] < bestD) { bestD = res[0]; best = st }
        }
        val n = best ?: return
        nearestDist = fmt(bestD)
        inList = bestD <= limitM
        nearestText = if (inList) "Nearest site: ${n.id} ($nearestDist)"
                      else "Site is Not in list (nearest: ${n.id}, $nearestDist)"
        if (autoId) setIdText(if (inList) n.id else NOT_IN_LIST)
    }

    private fun loadSites(uri: Uri) {
        bg.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val list = SiteDb.parse(bytes)
                if (list.isEmpty()) throw Exception("no valid rows found")
                SiteDb.save(File(filesDir, "sites.csv"), list)
                prefs().edit().putLong("listDate", System.currentTimeMillis()).apply()
                runOnUiThread {
                    sites = list
                    refreshNearest()
                    updateInfo()
                    toast("Loaded ${list.size} sites")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Could not read site list: ${e.message}") }
            }
        }
    }

    private fun listStatus(): String {
        if (sites.isEmpty()) return "Site list: none (MENU > Load site list)"
        val d = prefs().getLong("listDate", 0L)
        return "Site list: ${sites.size} sites" +
            if (d > 0) ", loaded " + SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date(d)) else ""
    }

    // ---------------- Text ----------------
    private fun shownAzimuth() = (camAzimuth + if (inverse) 180f else 0f) % 360f
    private fun azMain() = "Azimuth: %.0f°".format(Locale.US, shownAzimuth())
    private fun azSub() = "  " + (if (inverse) "Antenna (inverse +180°)" else "Panorama (normal)") +
        " · " + if (landscape) "Landscape" else "Portrait"

    private fun latLonLine(): String {
        val l = location ?: return "Lat/Lon: waiting for GPS..."
        val acc = if (l.hasAccuracy()) "  (±%.0f m)".format(Locale.US, l.accuracy) else ""
        return "Lat: %.6f  Lon: %.6f%s".format(Locale.US, l.latitude, l.longitude, acc)
    }

    private fun tiltLine(): String =
        "Tilt: %.0f° %s  Roll: %.0f°".format(Locale.US, abs(tiltUp), if (tiltUp >= 0) "up" else "down", abs(rollDeg))

    private fun compassLine(): String {
        val acc = when (magAcc) { 3 -> "High"; 2 -> "Medium"; 1 -> "Low"; 0 -> "Unreliable"; else -> "..." }
        val mag = if (magUt > 0) " · %.0f µT".format(Locale.US, magUt) else ""
        val north = if (trueNorth) "True N" else "Magnetic N"
        var warn = ""
        if (magAcc in 0..1) warn += "  [calibrate: wave phone in figure-8]"
        if (magUt > 0 && (magUt < 20 || magUt > 70)) warn += "  [interference?]"
        return "Compass: $acc$mag · $north$warn"
    }

    private fun updateInfo() {
        val sb = SpannableStringBuilder()
        sb.append(latLonLine()).append("\n").append(azMain())
        val s = sb.length
        sb.append(azSub())
        sb.setSpan(RelativeSizeSpan(0.75f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n").append(tiltLine()).append("\n").append(compassLine())
        if (nearestText.isNotEmpty()) sb.append("\n").append(nearestText)
        sb.append("\n").append(listStatus())
        info.text = sb
    }

    private fun stampLines(): List<Line> {
        val raw = siteId.text.toString().trim()
        val dist = if (autoId && inList && nearestDist.isNotEmpty()) "  ($nearestDist)" else ""
        val out = ArrayList<Line>()
        out.add(Line("Site ID: " + (if (raw.isEmpty()) "-" else raw) + dist))
        out.add(Line(latLonLine()))
        if (printAz) {
            out.add(Line(azMain(), azSub()))
            out.add(Line(tiltLine()))
        }
        out.add(Line(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())))
        return out
    }

    // ---------------- Camera ----------------
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = displayRotation()
            val pv = Preview.Builder().setTargetRotation(rotation).build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setTargetRotation(rotation).build()
            val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, sel, pv, imageCapture)
                camera = cam
                cam.cameraInfo.zoomState.observe(this) { z ->
                    zoomText.text = "%.1fx".format(Locale.US, z.zoomRatio)
                }
                smoothInit = false
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stepZoom(factor: Float) {
        val c = camera ?: return
        val z = c.cameraInfo.zoomState.value ?: return
        val r = (z.zoomRatio * factor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
        c.cameraControl.setZoomRatio(r)
    }

    private fun capture() {
        val ic = imageCapture ?: return
        ic.targetRotation = displayRotation()
        val lines = stampLines()
        val raw = siteId.text.toString().trim()
        val id = if (raw == NOT_IN_LIST) "NOSITE" else raw.replace(Regex("[^A-Za-z0-9_-]"), "")
        val tmp = File(cacheDir, "tmp_${System.currentTimeMillis()}.jpg")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), bg,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    val ok = try { stampAndSave(tmp, lines, id) } catch (e: Exception) { false }
                    tmp.delete()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            if (ok) "Saved to Pictures/SiteCam" else "Save failed", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show() }
                }
            })
    }

    private fun stampAndSave(file: File, lines: List<Line>, id: String): Boolean {
        val deg = when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        var bmp = if (deg != 0f) Bitmap.createBitmap(src, 0, 0, src.width, src.height,
            Matrix().apply { postRotate(deg) }, true) else src
        if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)

        val canvas = Canvas(bmp)
        val size = minOf(bmp.width, bmp.height) / 24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size * 0.75f }
        val boxPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        val lh = size * 1.3f
        val top = bmp.height - lh * lines.size - size * 0.6f
        canvas.drawRect(0f, top, bmp.width.toFloat(), bmp.height.toFloat(), boxPaint)
        lines.forEachIndexed { i, l ->
            val x = size * 0.5f
            val y = top + lh * (i + 1) - size * 0.1f
            canvas.drawText(l.main, x, y, paint)
            if (l.sub.isNotEmpty()) canvas.drawText(l.sub, x + paint.measureText(l.main), y, subPaint)
        }

        val name = (if (id.isEmpty()) "IMG" else id) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SiteCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return true
    }
}
