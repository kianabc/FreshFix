package com.kianabc.freshfix

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a raw capture into the final photo: burns the time, address and coordinates into
 * the image, writes the same fix into EXIF, and publishes it to Pictures/FreshFix.
 */
class PhotoSaver(private val context: Context) {

    // A full-resolution bitmap is tens of MB; stamp one at a time so rapid shots can't exhaust the heap.
    private val stampLock = Mutex()
    private val stamp = StampRenderer(android.text.format.DateFormat.is24HourFormat(context))

    suspend fun save(
        capture: File,
        fileName: String,
        shutterWallMs: Long,
        location: Location?,
        address: String?,
        status: FixStatus,
    ): Uri = stampLock.withLock { withContext(Dispatchers.Default) {
        val stamped = File(context.cacheDir, "stamped_$fileName")
        try {
            val bitmap = loadUpright(capture)
            stamp.draw(bitmap, shutterWallMs, location, address, status)
            stamped.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()

            writeExif(stamped, shutterWallMs, location, address)
            withContext(Dispatchers.IO) { publish(stamped, fileName) }
        } finally {
            stamped.delete()
            capture.delete()
        }
    } }

    /** Decodes the capture and applies its EXIF rotation so the stamp lands along the visual bottom. */
    private fun loadUpright(file: File): Bitmap {
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inMutable = true })
            ?: error("Could not decode capture")
        val degrees = ExifInterface(file.absolutePath).rotationDegrees
        if (degrees == 0) return decoded
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true
        )
        decoded.recycle()
        return if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true).also { rotated.recycle() }
    }

    private fun writeExif(file: File, shutterWallMs: Long, location: Location?, address: String?) {
        val exif = ExifInterface(file.absolutePath)
        val exifTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(shutterWallMs))
        val offset = SimpleDateFormat("XXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(shutterWallMs))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTime)
        exif.setAttribute(ExifInterface.TAG_DATETIME, exifTime)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FreshFix")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        if (location != null) {
            exif.setGpsInfo(location)
            exif.setAttribute(
                ExifInterface.TAG_GPS_H_POSITIONING_ERROR, "${(location.accuracy * 100).toLong()}/100"
            )
        }
        if (address != null) exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, address)
        exif.saveAttributes()
    }

    private fun publish(file: File, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    companion object {
        private const val JPEG_QUALITY = 95
        const val RELATIVE_PATH = "Pictures/FreshFix"
    }
}
