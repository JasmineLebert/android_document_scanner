package com.octo.rdo.opencvroot

import android.Manifest.permission.*
import android.R.attr.x
import android.R.attr.y
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
import com.octo.rdo.opencvroot.documentscanner.helpers.ImageUtils
import com.octo.rdo.opencvroot.documentscanner.libraries.NativeClass
import com.octo.rdo.opencvroot.documentscanner.libraries.PerspectiveTransformation
import com.octo.rdo.opencvroot.documentscanner.libraries.PolygonView
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
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
        var tempBitmap = (imageView.drawable as BitmapDrawable).bitmap
        placePointOnPolygonView(tempBitmap, false)
    }

    private fun switchToImagePreview(savedUri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(savedUri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            findViewById<ImageView>(R.id.viewImageForBitmap).setImageBitmap(bitmap)
            findViewById<ImageView>(R.id.viewImageForBitmap).visibility = View.VISIBLE
            findViewById<PreviewView>(R.id.viewFinder).visibility = View.GONE
            initializeCropping(bitmap)
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
            getContourEdgePoints(tempBitmap) // contour de l'image view
        }

        Log.e("YOLO, YOLO", "CONTOUR : $pointFs")
        return orderedValidEdgePoints(polygonView, tempBitmap, pointFs)
    }

    private fun getContourOfBitmap(
        tempBitmap: Bitmap,
        polygonView: PolygonView
    ): Map<Int, PointF>? {
        val pointFs: List<PointF> = getContourEdgePoints(tempBitmap)
        Log.e("YOLO, YOLO", "CONTOUR : $pointFs")
        return orderedValidEdgePoints(polygonView, tempBitmap, pointFs)
    }

    private fun getDocumentContour(tempBitmap: Bitmap): List<PointF> {
        val rgba = Mat()
        Utils.bitmapToMat(tempBitmap, rgba)
        val edges = Mat(rgba.size(), CvType.CV_8UC1)
        Imgproc.cvtColor(rgba, edges, Imgproc.COLOR_RGB2GRAY, 4) // change color of image
        Imgproc.Canny(
            edges, // 8-bit source image
            edges,//output edge map; single channels 8-bit image, which has the same size as image
            80.0,
            100.0
        )

        val resultBitmap: Bitmap =
            Bitmap.createBitmap(edges.cols(), edges.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(edges, resultBitmap)
        //findViewById<ImageView>(R.id.viewImageForBitmap).setImageBitmap(resultBitmap) //TMP

        return OpenCVUtils.getContourEdgePoints(resultBitmap).map { // contour du document
            PointF(it.x.toFloat(), it.y.toFloat())
        }
    }

    private fun getContourEdgePoints(tempBitmap: Bitmap): List<PointF> {
        var point2f = NativeClass().getPoint(tempBitmap)
        if (point2f == null) point2f = MatOfPoint2f()
        val points = listOf(*point2f.toArray())
        val result: MutableList<PointF> = ArrayList()
        for (i in points.indices) {
            result.add(PointF(points[i].x.toFloat(), points[i].y.toFloat()))
        }
        return result
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
        val xRatio: Float = tempBitmap.width.toFloat() / imageView.width
        val yRatio: Float = 1f//tempBitmap.height.toFloat() / imageView.height
        val x1 = points[0]!!.x * xRatio
        val x2 = points[1]!!.x * xRatio
        val x3 = points[2]!!.x * xRatio
        val x4 = points[3]!!.x * xRatio
        val y1 = points[0]!!.y * yRatio
        val y2 = points[1]!!.y * yRatio
        val y3 = points[2]!!.y * yRatio
        val y4 = points[3]!!.y * yRatio
        val finalBitmap: Bitmap = tempBitmap.copy(tempBitmap.config, true)
        return getScannedBitmap(finalBitmap, x1, y1, x2, y2, x3, y3, x4, y4)
    }

    private fun getScannedBitmap( //TODO: Move to native class
        bitmap: Bitmap?,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        x4: Float,
        y4: Float
    ): Bitmap? {

        /*  val uncropped: Mat = ImageUtils.bitmapToMat(bitmap)
          val width = 20//x4 - x1
          val height = 20//y4 - y1
          val roi = org.opencv.core.Rect(x1.toInt(), y1.toInt(), width.toInt(), height.toInt())
          //val cropped = org.opencv.core.Mat(uncropped, org.opencv.core.Rect(0, 0, uncropped.cols(), uncropped.rows() / 2)); // NOTE: this will only give you a reference to the ROI of the original data
          Log.e("YOLO, YOLO", "CONTOUR POINT $x1, $y1, $x2, $y2, $x3, $y3, $x4, $y4")
          val cropped = org.opencv.core.Mat(uncropped, org.opencv.core.Rect(Point(x1.toDouble(),y1.toDouble()), Point(x4.toDouble(),y4.toDouble())))

          //val cropped : Mat = org.opencv.core.Mat(uncropped, roi)

          return ImageUtils.matToBitmap(cropped)*/
        val perspective = PerspectiveTransformation()
        val rectangle = MatOfPoint2f()
        rectangle.fromArray(
            Point(x1.toDouble(), y1.toDouble()), Point(
                x2.toDouble(), y2.toDouble()
            ), Point(x3.toDouble(), y3.toDouble()), Point(
                x4.toDouble(),
                y4.toDouble()
            )
        )
        val dstMat: Mat = perspective.transform(ImageUtils.bitmapToMat(bitmap), rectangle)
        return ImageUtils.matToBitmap(dstMat)
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