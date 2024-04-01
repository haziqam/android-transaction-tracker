package com.example.bondoman.fragments

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.bondoman.R
import com.example.bondoman.database.TransactionDatabase
import com.example.bondoman.entities.Transaction
import com.example.bondoman.repositories.ScanRepository
import com.example.bondoman.repositories.TransactionRepository
import com.example.bondoman.viewModels.TransactionViewModelFactory
import com.example.bondoman.viewModels.TransactionsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanFragment : Fragment() {
    private var imageCapture : ImageCapture? = null
    private val wordViewModel: TransactionsViewModel by viewModels {
        TransactionViewModelFactory(
            TransactionRepository(
                TransactionDatabase.getInstance(requireContext(), CoroutineScope(
                    SupervisorJob()
                )
                ).transactionDao())
        )
    }
    private var loadingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view =  inflater.inflate(R.layout.fragment_scan, container, false)
        val photoButton = view.findViewById<Button>(R.id.captureButton)
        photoButton.setOnClickListener{takePhoto()}
        return view

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if(allPermissionsGranted()){
            startCamera()
        }else{
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

    }
    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("Error", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    super.onCaptureSuccess(image)
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
                    // Now save the bitmap to a file
                    try {
                        val imageFile = createImageFile()
                        saveBitmapToFile(bitmap, imageFile)
                        // Now you can upload the file
                        lifecycleScope.launch {
                            uploadPhoto(imageFile)
                        }
                        image.close()
                    } catch (e: IOException) {
                        Log.e("Error", "Failed to create image file: ${e.message}", e)
                    }
                }
            }
        )
    }
    private suspend fun uploadPhoto(file: File) {
        val scanRepository = ScanRepository(requireContext())
        showLoadingDialog()
        Log.d("Debugger","Uploading photo...")
        try {
            val response = scanRepository.uploadPhoto(file)
            val itemsArray = response.items.items.map { item ->
                " Item name : ${item.name}\n Qty :  ${item.qty} \n Price : ${item.price}\n"
            }.toTypedArray()

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(requireContext()).apply {
                    setTitle("Transactions's Items")
                    setItems(itemsArray) { dialog, which ->

                    }
                    setPositiveButton("Save") { dialog, which ->
                        response.items.items.map { item ->
                            val newTransaction = Transaction(
                                id = 0,
                                title = item.name,
                                category = "scan",
                                amount = item.qty * item.price,
                                location = "123,144",
                                date = Date(),
                                userEmail = "testing"
                            )
                            Log.e("Insert Transaction", newTransaction.title)
                            wordViewModel.insert(newTransaction)
                        }
                    }
                    setNegativeButton("Close") { dialog, which ->
                    }
                    show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
        }finally {
            hideLoadingDialog()
        }
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        if (storageDir == null) {
            throw IOException("Storage directory is null")
        }

        val newFile = File.createTempFile(
            "IMAGE_${timeStamp}_",
            ".jpg",
            storageDir
        )

        // If the file creation is successful, return the file
        return newFile ?: throw IOException("New file could not be created")
    }


    private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText( requireContext(),
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }
    private fun startCamera() {
        val viewFinder = view?.findViewById<PreviewView>(R.id.viewFinder)
        val cameraProviderFuture = ProcessCameraProvider.getInstance( requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder?.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }
    private fun showLoadingDialog() {
        loadingDialog = Dialog(requireContext()).apply {
            setContentView(R.layout.loading_upload)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            show()
        }
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
    }
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
        private const val TAG = "CameraXApp"

    }
}