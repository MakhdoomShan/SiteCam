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
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import kotlin.math.atan2

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var preview: PreviewView
    private lateinit var info: TextView
    private lateinit var siteId: EditText
    private lateinit var btnMode: Button
    private lateinit var btnOrient: Button
    private var landscape = false

    private var imageCapture: ImageCapture? = null
    private var front = false
    private var inverse = true            // true = ANTENNA (+180), false = PANORAMA (normal)
    private var sinF = 0.0
    private var cosF = 1.0
    private var smoothInit = false
    private var camAzimuth = 0f           // true-north direction the camera points
    private var location: Location? = null

    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private val bg = Executors.newSingleThreadExecutor()
    private val rot = FloatArray(9)
    private val rot2 = FloatArray(9)
    private val ori = FloatArray(3)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startAll() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landscape = savedInstanceState?.getBoolean("landscape") ?: false
        front = savedInstanceState?.getBoolean("front") ?: false
        inverse = savedInstanceState?.getBoolean("inverse") ?: true
        applyOrientation()
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        siteId = findViewById(R.id.siteId)
        btnMode = findViewById(R.id.btnMode)
        btnOrient = findViewById(R.id.btnOrient)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        updateModeButton()
        btnOrient.setOnClickListener { landscape = !landscape; applyOrientation() }
        btnMode.setOnClickListener { inverse = !inverse; updateModeButton(); updateInfo() }
        findViewById<Button>(R.id.btnFlip).setOnClickListener { front = !front; bindCamera() }
        findViewById<Button>(R.id.btnCapture).setOnClickListener { capture() }

        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("landscape", landscape)
        out.putBoolean("front", front)
        out.putBoolean("inverse", inverse)
    }

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

    private fun startSensors() {
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run { info.text = "No compass/rotation sensor on this device" }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        lm.removeUpdates(this)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(p)) {
                lm.requestLocationUpdates(p, 1000L, 0f, this)
                lm.getLastKnownLocation(p)?.let { if (location == null) location = it }
            }
        }
    }

    // ---- Location ----
    override fun onLocationChanged(loc: Location) { location = loc; updateInfo() }
    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    // ---- Compass ----
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Phone held upright, camera pointing at the horizon
        // Remap so the result is the direction the camera points, for each screen rotation
        when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, rot2)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_Z, SensorManager.AXIS_X, rot2)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Z, rot2)
            else -> SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rot2)
        }
        SensorManager.getOrientation(rot2, ori)
        var az = Math.toDegrees(ori[0].toDouble())
        location?.let {
            az += GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(),
                it.altitude.toFloat(), System.currentTimeMillis()).declination
        }
        if (front) az += 180.0
        val r = Math.toRadians(az)
        if (!smoothInit) { sinF = Math.sin(r); cosF = Math.cos(r); smoothInit = true }
        else { sinF += 0.15 * (Math.sin(r) - sinF); cosF += 0.15 * (Math.cos(r) - cosF) }
        camAzimuth = ((Math.toDegrees(atan2(sinF, cosF)) + 360) % 360).toFloat()
        updateInfo()
    }

    private fun shownAzimuth() = (camAzimuth + if (inverse) 180f else 0f) % 360f
    private fun modeName() = if (inverse) "ANTENNA (inverse +180)" else "PANORAMA (normal)"
    private fun updateModeButton() {
        btnMode.text = "Azimuth: " + modeName()
        btnOrient.text = "Orientation: " + if (landscape) "LANDSCAPE" else "PORTRAIT"
    }

    private fun infoLines(): List<String> {
        val l = location
        val ll = if (l != null) "Lat: %.6f  Lon: %.6f".format(Locale.US, l.latitude, l.longitude)
                 else "Lat/Lon: waiting for GPS..."
        val id = siteId.text.toString().trim()
        return listOf(
            "Site ID: " + if (id.isEmpty()) "-" else id,
            ll,
            "Azimuth: %.0f°  [%s] %s".format(Locale.US, shownAzimuth(), modeName(), if (landscape) "LANDSCAPE" else "PORTRAIT")
        )
    }

    private fun updateInfo() { info.text = infoLines().joinToString("\n") }

    // ---- Camera ----
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = displayRotation()
            val pv = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setTargetRotation(rotation).build()
            val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, sel, pv, imageCapture)
                smoothInit = false
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        ic.targetRotation = displayRotation()
        val lines = infoLines() + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val id = siteId.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "")
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

    private fun stampAndSave(file: File, lines: List<String>, id: String): Boolean {
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
        val size = minOf(bmp.width, bmp.height) / 22f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size }
        val boxPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        val lh = size * 1.3f
        val top = bmp.height - lh * lines.size - size * 0.6f
        canvas.drawRect(0f, top, bmp.width.toFloat(), bmp.height.toFloat(), boxPaint)
        lines.forEachIndexed { i, t -> canvas.drawText(t, size * 0.5f, top + lh * (i + 1) - size * 0.1f, paint) }

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
