package com.octo.rdo.opencvroot.documentscanner.views

import android.Manifest.permission.*
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.octo.rdo.opencvroot.R
import com.octo.rdo.opencvroot.documentscanner.libraries.OpenCVUtils
import org.opencv.android.OpenCVLoader
import org.opencv.core.Point
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request camera permissions
        //if (allPermissionsGranted()) {
        startCamera()
//        } else {
        //          ActivityCompat.requestPermissions(
        //            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        //  }

        // Set up the listeners for take photo and video capture buttons
        findViewById<Button>(R.id.image_capture_button).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.crop_button).setOnClickListener { crop() }


        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    if (!OpenCVLoader.initDebug()) {
                        Toast.makeText(
                            baseContext,
                            "OpenCVLoader.initDebug(), not working.",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e("OpenCV not work.", "")
                    } else {
                        Toast.makeText(
                            baseContext,
                            "OpenCVLoader.initDebug(), working.",
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e("OpenCV working.", "")
                    }
                    Log.d(TAG, msg)
                    output.savedUri?.let {
                        switchToImagePreview(it)
                    }
                    cameraExecutor = Executors.newSingleThreadExecutor()

                }
            }
        )
    }

    private fun crop() {
        val croppedBitmap = getCroppedImage()
        val imageView = findViewById<ImageView>(R.id.viewImageForBitmap)
        imageView.setImageBitmap(croppedBitmap)

        //Reset polygon view
        val tempBitmap = (imageView.drawable as BitmapDrawable).bitmap
        placePointOnPolygonView(tempBitmap, false)
    }

    private fun switchToImagePreview(savedUri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(savedUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            findViewById<ImageView>(R.id.viewImageForBitmap).setImageBitmap(bitmap)
            findViewById<ImageView>(R.id.viewImageForBitmap).visibility = View.VISIBLE
            findViewById<PreviewView>(R.id.viewFinder).visibility = View.GONE
            val rotateBitmap = OpenCVUtils.rotate(bitmap, 90)
            initializeCropping(rotateBitmap)
        } catch (e: Throwable) {
            Log.e("YOLO YOLO", null, e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        Log.e("YOLO, YOLO", "JE suis sensé avoir démarré la caméra")
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
                }
            imageCapture = ImageCapture.Builder().build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initializeCropping(selectedImageBitmap: Bitmap) {
        placePointOnPolygonView(selectedImageBitmap, true)
    }

    private fun placePointOnPolygonView(selectedImageBitmap: Bitmap, isCropping: Boolean) {
        val polygonView = findViewById<PolygonView>(R.id.polygon_view)
        val imageView = findViewById<ImageView>(R.id.viewImageForBitmap)
        val scaledBitmap: Bitmap = scaledBitmap(
            selectedImageBitmap,
            imageView.width,
            imageView.height
        )
        imageView.setImageBitmap(scaledBitmap)
        val tempBitmap = (imageView.drawable as BitmapDrawable).bitmap
        val pointFs = getEdgePointsOfBitmap(tempBitmap, polygonView, isCropping)
        polygonView.points = pointFs
        polygonView.visibility = View.VISIBLE
        val padding = resources.getDimension(R.dimen.scanPadding).toInt() * 2
        val layoutParams =
            FrameLayout.LayoutParams(tempBitmap.width + padding, tempBitmap.height + padding)

        layoutParams.gravity = Gravity.CENTER
        polygonView.layoutParams = layoutParams
        polygonView.setPointColor(Color.BLUE)
    }

    private fun scaledBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val m = Matrix()
        m.setRectToRect(
            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), RectF(
                0f, 0f,
                width.toFloat(),
                height.toFloat()
            ), Matrix.ScaleToFit.CENTER
        )
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun getEdgePointsOfBitmap(
        tempBitmap: Bitmap,
        polygonView: PolygonView,
        isCropping: Boolean,
    ): Map<Int, PointF>? {
        val pointFs: List<PointF> = if (isCropping) {
            getDocumentContour(tempBitmap)
        } else {
            ArrayList() // contour de l'image view
        }

        Log.e("YOLO, YOLO", "CONTOUR : $pointFs")
        return orderedValidEdgePoints(polygonView, tempBitmap, pointFs)
    }

    private fun getDocumentContour(tempBitmap: Bitmap): List<PointF> {
        return OpenCVUtils.getContourEdgePoints(tempBitmap).map { // contour du document
            PointF(it.x.toFloat(), it.y.toFloat())
        }
    }

    private fun orderedValidEdgePoints(
        polygonView: PolygonView,
        tempBitmap: Bitmap,
        pointFs: List<PointF>
    ): Map<Int, PointF>? {
        var orderedPoints: Map<Int, PointF>? = polygonView.getOrderedPoints(pointFs)
        if (!polygonView.isValidShape(orderedPoints)) {
            orderedPoints = getOutlinePoints(tempBitmap)
        }
        return orderedPoints
    }

    private fun getOutlinePoints(tempBitmap: Bitmap): Map<Int, PointF> {
        val outlinePoints: MutableMap<Int, PointF> = HashMap()
        outlinePoints[0] = PointF(0f, 0f)
        outlinePoints[1] = PointF(tempBitmap.width.toFloat(), 0f)
        outlinePoints[2] = PointF(0f, tempBitmap.height.toFloat())
        outlinePoints[3] = PointF(tempBitmap.width.toFloat(), tempBitmap.height.toFloat())
        return outlinePoints
    }

    private fun getCroppedImage(): Bitmap? {
        val polygonView = findViewById<PolygonView>(R.id.polygon_view)
        val imageView = findViewById<ImageView>(R.id.viewImageForBitmap)
        val tempBitmap = (imageView.drawable as BitmapDrawable).bitmap

        val points: Map<Int, PointF> = polygonView.points
        val point1 = Point(points[0]!!.x.toDouble(), points[0]!!.y.toDouble())
        val point2 = Point(points[1]!!.x.toDouble(), points[1]!!.y.toDouble())
        // les points 3 et 4 sont inversés pour le moment car openCV pas dans le même sens que polygonView
        val point3 = Point(points[3]!!.x.toDouble(), points[3]!!.y.toDouble())
        val point4 = Point(points[2]!!.x.toDouble(), points[2]!!.y.toDouble())
        val finalBitmap: Bitmap = tempBitmap.copy(tempBitmap.config, true)
        return OpenCVUtils.getScannedBitmap(finalBitmap, point1, point2, point3, point4)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }


    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                CAMERA,
                RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}