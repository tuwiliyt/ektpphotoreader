package com.ektp.reader

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ektp.reader.databinding.ActivityMainBinding
import com.ektp.reader.nfc.EktpReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var readAgainIntentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null

    private val ektpReader = EktpReader()
    private var lastReadPhoto: Bitmap? = null
    private var pulseAnimator: ObjectAnimator? = null

    private val REQUEST_WRITE_STORAGE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupUI()
        setupNfcPendingIntent()
        startPulseAnimation()
    }

    override fun onResume() {
        super.onResume()
        checkNfcStatus()
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
        disableNfcReaderMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }

    private fun setupUI() {
        binding.btnSave.setOnClickListener {
            lastReadPhoto?.let { bitmap ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_WRITE_STORAGE
                    )
                } else {
                    savePhotoToGallery(bitmap)
                }
            }
        }

        binding.btnShare.setOnClickListener {
            lastReadPhoto?.let { bitmap ->
                sharePhoto(bitmap)
            }
        }

        binding.btnReadAgain.setOnClickListener {
            lastReadPhoto = null
            showState(UIState.IDLE)
        }

        binding.btnTryAgain.setOnClickListener {
            showState(UIState.IDLE)
        }

        binding.btnEnableNfc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }
    }

    private fun setupNfcPendingIntent() {
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )

        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)

        readAgainIntentFilters = arrayOf(ndefFilter, techFilter, tagFilter)
        techLists = arrayOf(
            arrayOf(IsoDep::class.java.name),
            arrayOf(NfcA::class.java.name)
        )
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofFloat(binding.ivNfcIcon, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun checkNfcStatus() {
        val adapter = nfcAdapter
        if (adapter == null) {
            showState(UIState.NO_NFC)
            binding.viewNfcDot.setBackgroundResource(R.drawable.card_background) // gray out
            binding.viewNfcDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_dot_inactive)
            binding.tvNfcStatus.text = getString(R.string.nfc_status_off)
        } else if (!adapter.isEnabled) {
            showState(UIState.NFC_DISABLED)
            binding.viewNfcDot.setBackgroundResource(R.drawable.card_background)
            binding.viewNfcDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_dot_inactive)
            binding.tvNfcStatus.text = getString(R.string.nfc_status_off)
        } else {
            // NFC is enabled and active
            binding.viewNfcDot.setBackgroundResource(R.drawable.card_background)
            binding.viewNfcDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_dot_active)
            binding.tvNfcStatus.text = getString(R.string.nfc_status_on)

            if (lastReadPhoto != null) {
                showState(UIState.SUCCESS)
            } else {
                showState(UIState.IDLE)
            }

            enableNfcReaderMode()
            enableNfcForegroundDispatch()
        }
    }

    private fun enableNfcReaderMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

            nfcAdapter?.enableReaderMode(this, { tag ->
                runOnUiThread {
                    onTagDiscovered(tag)
                }
            }, flags, null)
        }
    }

    private fun disableNfcReaderMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.disableReaderMode(this)
        }
    }

    private fun enableNfcForegroundDispatch() {
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, readAgainIntentFilters, techLists)
    }

    private fun disableNfcForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let { onTagDiscovered(it) }
        }
    }

    private fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            showErrorState("Kartu tidak mendukung komunikasi ISO-DEP (IsoDep)")
            return
        }

        vibrateDevice()
        showState(UIState.SCANNING)

        lifecycleScope.launch {
            val result = ektpReader.readPhoto(isoDep)
            handleReadResult(result)
        }
    }

    private fun handleReadResult(result: EktpReader.ReadResult) {
        when (result) {
            is EktpReader.ReadResult.Success -> {
                lastReadPhoto = result.photo
                binding.ivPhoto.setImageBitmap(result.photo)
                vibrateDeviceSuccess()
                showState(UIState.SUCCESS)
            }
            is EktpReader.ReadResult.Error -> {
                showErrorState(result.message)
            }
            is EktpReader.ReadResult.Reading -> {
                showState(UIState.SCANNING)
            }
        }
    }

    private fun showState(state: UIState) {
        binding.layoutIdle.visibility = if (state == UIState.IDLE) View.VISIBLE else View.GONE
        binding.layoutReading.visibility = if (state == UIState.SCANNING) View.VISIBLE else View.GONE
        binding.layoutSuccess.visibility = if (state == UIState.SUCCESS) View.VISIBLE else View.GONE
        binding.layoutError.visibility = if (state == UIState.ERROR) View.VISIBLE else View.GONE

        // NFC states are subsets of main card layouts
        binding.layoutNoNfc.visibility = if (state == UIState.NO_NFC || state == UIState.NFC_DISABLED) View.VISIBLE else View.GONE

        if (state == UIState.NO_NFC) {
            binding.tvNoNfcTitle.text = getString(R.string.nfc_not_available)
            binding.tvNoNfcDesc.text = getString(R.string.nfc_not_available_desc)
            binding.btnEnableNfc.visibility = View.GONE
        } else if (state == UIState.NFC_DISABLED) {
            binding.tvNoNfcTitle.text = getString(R.string.nfc_disabled)
            binding.tvNoNfcDesc.text = getString(R.string.nfc_disabled_desc)
            binding.btnEnableNfc.visibility = View.VISIBLE
        }

        // Action Buttons
        binding.layoutButtons.visibility = if (state == UIState.SUCCESS) View.VISIBLE else View.GONE
        binding.layoutSecondaryButtons.visibility = if (state == UIState.SUCCESS) View.VISIBLE else View.GONE
        binding.btnTryAgain.visibility = if (state == UIState.ERROR) View.VISIBLE else View.GONE
    }

    private fun showErrorState(message: String) {
        binding.tvErrorMessage.text = message
        showState(UIState.ERROR)
    }

    private fun vibrateDevice() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    private fun vibrateDeviceSuccess() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 100, 50, 100)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
        }
    }

    private fun savePhotoToGallery(bitmap: Bitmap) {
        val filename = "eKTP_Photo_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null

        try {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/eKTPReader")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(imagesDir, "eKTPReader")
                if (!appDir.exists()) appDir.mkdirs()
                val imageFile = File(appDir, filename)
                fos = FileOutputStream(imageFile)
                MediaScannerConnection.scanFile(this, arrayOf(imageFile.absolutePath), null, null)
                Uri.fromFile(imageFile)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                imageUri?.let { uri ->
                    fos = contentResolver.openOutputStream(uri)
                }
            }

            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                Toast.makeText(this, getString(R.string.photo_saved), Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, getString(R.string.photo_save_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "${getString(R.string.photo_save_failed)}: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePhoto(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cachePath = File(cacheDir, "images")
                if (!cachePath.exists()) cachePath.mkdirs()
                
                val file = File(cachePath, "shared_ektp_photo.jpg")
                val stream = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                stream.close()

                val contentUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "com.ektp.reader.fileprovider",
                    file
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Bagikan Foto e-KTP"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal membagikan foto: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                lastReadPhoto?.let { savePhotoToGallery(it) }
            } else {
                Toast.makeText(this, "Izin penyimpanan ditolak. Tidak bisa menyimpan foto.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private enum class UIState {
        IDLE,
        SCANNING,
        SUCCESS,
        ERROR,
        NO_NFC,
        NFC_DISABLED
    }
}
