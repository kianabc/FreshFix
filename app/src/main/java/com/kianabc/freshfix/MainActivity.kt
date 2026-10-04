package com.kianabc.freshfix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var gps: GpsTracker
    private lateinit var log: PhotoLog
    private lateinit var saver: PhotoSaver
    private lateinit var addresses: AddressLookup

    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

    private val photoCount = MutableStateFlow(0)
    private val lastResult = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow(0)

    // The activity is locked to portrait, so follow the physical orientation by hand
    // to keep landscape shots of signs the right way up.
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (degrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gps = GpsTracker(this)
        log = PhotoLog(this)
        saver = PhotoSaver(this)
        addresses = AddressLookup(this)
        photoCount.value = log.count()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var granted by remember { mutableStateOf(hasPermissions()) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    granted = hasPermissions()
                    if (granted) gps.start()
                }
                LaunchedEffect(Unit) { if (!granted) launcher.launch(PERMISSIONS) }

                if (granted) {
                    CameraScreen()
                } else {
                    PermissionScreen { launcher.launch(PERMISSIONS) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) gps.start()
        orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        gps.stop()
        orientationListener.disable()
    }

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun takePhoto() {
        val shutterNanos = SystemClock.elapsedRealtimeNanos()
        val shutterWallMs = System.currentTimeMillis()
        val fileName = "FreshFix_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(shutterWallMs)) + ".jpg"
        val capture = File(cacheDir, "raw_$fileName")
        pending.value++

        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(capture).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch {
                        try {
                            finishPhoto(capture, fileName, shutterNanos, shutterWallMs)
                        } catch (e: Exception) {
                            lastResult.value = "Save failed: ${e.message}"
                        } finally {
                            pending.value--
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    pending.value--
                    lastResult.value = "Capture failed: ${exception.message}"
                }
            },
        )
    }

    private suspend fun finishPhoto(capture: File, fileName: String, shutterNanos: Long, shutterWallMs: Long) {
        val fix = gps.fixFor(shutterNanos)
        val satellites = gps.satellitesUsed.value
        val offsetMs = fix?.let { (it.elapsedRealtimeNanos - shutterNanos) / 1_000_000 }
        val address = fix?.let { addresses.lookup(it) }
        val status = when {
            fix == null -> FixStatus.NO_FIX
            abs(offsetMs!!) > GOOD_OFFSET_MS || fix.accuracy > GOOD_ACCURACY_M -> FixStatus.WEAK
            else -> FixStatus.OK
        }

        saver.save(capture, fileName, shutterWallMs, fix, address)
        log.append(
            PhotoRecord(
                fileName = fileName,
                takenAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(shutterWallMs)),
                latitude = fix?.latitude,
                longitude = fix?.longitude,
                accuracyM = fix?.accuracy,
                altitudeM = fix?.takeIf { it.hasAltitude() }?.altitude,
                fixOffsetMs = offsetMs,
                satellitesUsed = satellites,
                speedMps = fix?.takeIf { it.hasSpeed() }?.speed,
                bearingDeg = fix?.takeIf { it.hasBearing() }?.bearing,
                mock = fix?.let(::isMock) ?: false,
                status = status,
                address = address,
            )
        )
        photoCount.value = log.count()
        lastResult.value = when (status) {
            FixStatus.OK -> "Saved #${photoCount.value} · ±%.0f m".format(fix!!.accuracy)
            FixStatus.WEAK -> "Saved #${photoCount.value} · WEAK fix ±%.0f m, %.1f s off".format(
                fix!!.accuracy, abs(offsetMs!!) / 1000.0
            )
            FixStatus.NO_FIX -> "Saved #${photoCount.value} · NO GPS FIX"
        }
    }

    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
        else @Suppress("DEPRECATION") location.isFromMockProvider

    private fun shareLog() {
        if (!log.file.exists()) {
            Toast.makeText(this, "No photos logged yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", log.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Export photo log"))
    }

    @Composable
    private fun CameraScreen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPreview(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                GpsStatusBar()
                Spacer(Modifier.weight(1f))
                BottomControls()
            }
        }
    }

    @Composable
    private fun CameraPreview(modifier: Modifier) {
        val lifecycleOwner = LocalLifecycleOwner.current
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
    }

    @Composable
    private fun GpsStatusBar() {
        val fix by gps.latest.collectAsState()
        val satellites by gps.satellitesUsed.collectAsState()
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = SystemClock.elapsedRealtimeNanos()
                delay(250)
            }
        }

        val current = fix
        val ageSec = current?.let { (now - it.elapsedRealtimeNanos) / 1e9 }
        val (color, text) = when {
            current == null -> Color(0xFFC62828) to "Waiting for GPS…  ($satellites sats)"
            ageSec!! > STALE_FIX_SEC -> Color(0xFFC62828) to "GPS lost · last fix %.0f s ago".format(ageSec)
            current.accuracy > GOOD_ACCURACY_M -> Color(0xFFF9A825) to
                "GPS weak · ±%.0f m · %d sats".format(current.accuracy, satellites)
            else -> Color(0xFF2E7D32) to "GPS ±%.0f m · %.1f s old · %d sats".format(current.accuracy, ageSec, satellites)
        }
        Column(
            Modifier.fillMaxWidth().padding(12.dp)
                .background(color.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (current != null) {
                Text(
                    "%.6f, %.6f".format(current.latitude, current.longitude),
                    color = Color.White, fontSize = 13.sp,
                )
            }
        }
    }

    @Composable
    private fun BottomControls() {
        val count by photoCount.collectAsState()
        val result by lastResult.collectAsState()
        val inFlight by pending.collectAsState()

        Column(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                result ?: "Each photo is matched to a live GPS fix",
                color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$count photos" + if (inFlight > 0) "\nsaving $inFlight…" else "",
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.width(96.dp),
                )
                Box(
                    Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                        .background(Color.White, CircleShape).clickable { takePhoto() }
                )
                TextButton(onClick = ::shareLog, modifier = Modifier.width(96.dp)) {
                    Text("Export\nlog", color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }

    @Composable
    private fun PermissionScreen(onRequest: () -> Unit) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "FreshFix needs the camera and precise location to tag every photo with where it was taken.",
                color = Color.White, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = onRequest) { Text("Grant access") }
            val context = LocalContext.current
            TextButton(onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", context.packageName, null))
                )
            }) { Text("Open settings") }
        }
    }

    companion object {
        private val PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        private const val GOOD_ACCURACY_M = 15f
        private const val GOOD_OFFSET_MS = 2_000L
        private const val STALE_FIX_SEC = 5.0
    }
}
