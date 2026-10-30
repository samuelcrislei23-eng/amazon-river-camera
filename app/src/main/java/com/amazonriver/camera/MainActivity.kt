package com.amazonriver.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CORREÇÃO DO PROBLEMA DO RELÓGIO: o Horizon Pro tem só UMA câmera física
 * (4 MP, no braço lateral que gira), sem sensor frontal dedicado. Apps que
 * tentam abrir uma "câmera frontal" que não existe no hardware travam ou
 * simplesmente não abrem. Este app nunca pede uma câmera frontal inexistente:
 * ele detecta quantas câmeras o hardware realmente tem e, quando só existe a
 * traseira rotativa, oferece um modo "FRENTE" virtual (espelha a prévia e a
 * foto, e mostra a dica para girar o braço) usando a MESMA câmera, sem jamais
 * tentar um bind que falharia. Se o aparelho realmente tiver 2 câmeras, o
 * app troca entre elas normalmente.
 */
@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlay
    private lateinit var tvFlash: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvMenu: TextView
    private lateinit var tvScene: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvLens: TextView
    private lateinit var tvPhoto: TextView
    private lateinit var tvVideo: TextView
    private lateinit var tvRotateHint: TextView
    private lateinit var tvPhoneHint: TextView
    private lateinit var chips: LinearLayout
    private lateinit var shutter: View
    private lateinit var shutterInner: View
    private lateinit var panel: View
    private lateinit var panelList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val mainExec by lazy { ContextCompat.getMainExecutor(this) }
    private var provider: ProcessCameraProvider? = null
    private var ext: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var infos: List<CameraInfo> = emptyList()
    private var lensIdx = 0
    private var isVideo = false
    private var extMode = ExtensionMode.NONE
    private var availableExt: List<Int> = listOf(ExtensionMode.NONE)
    private var flash = 0
    private var timerSec = 0
    private var counting = false
    private var pinched = false
    private var bindTries = 0
    private var startTries = 0

    // "FRENTE" virtual: mesma câmera física, prévia/foto espelhadas + dica para girar o braço.
    private var frontVirtual = false

    // Modo "pedido por outro app" (ex.: banco pedindo selfie para reconhecimento facial).
    // Ativado quando este app é aberto via android.media.action.IMAGE_CAPTURE.
    private var captureForCaller = false
    private var callerOutputUri: Uri? = null

    // Câmera do celular como "câmera externa": em vez de usar o sensor do relógio,
    // pede a foto pro app companion no celular, pela rede local.
    private var phoneMode = false
    private fun phoneIp() = prefs.getString("phone_ip", "") ?: ""
    private fun phoneAvailable() = on("use_phone_cam") && phoneIp().isNotBlank()

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f).build()
        )
    }
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private var aiBusy = false
    private var tick = 0
    private var labelScene = "AUTO"
    private var lastAutoSwitch = 0L
    private var lastSmile = 0L
    private var lastFocus = 0L
    private val aiLoop = object : Runnable {
        override fun run() { analyze(); handler.postDelayed(this, 700) }
    }

    private val prefs by lazy { getSharedPreferences("ar_cam", MODE_PRIVATE) }
    private val settings = listOf(
        Triple("ai_auto", "AI AUTO SCENE", true),
        Triple("ai_enhance", "AI ENHANCE (PHOTOS)", true),
        Triple("smile", "SMILE SHUTTER", false),
        Triple("face_focus", "FACE FOCUS", true),
        Triple("hq", "HQ IMAGE PIPELINE", true),
        Triple("use_phone_cam", "USE PHONE CAMERA", false),
        Triple("grid", "GRID", false),
        Triple("dark", "DARK THEME", false),
    )
    private fun on(k: String) = prefs.getBoolean(k, settings.first { it.first == k }.third)

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.CAMERA)) startCamera()
        else status("CAMERA PERMISSION NEEDED")
    }
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        captureForCaller = intent?.action == android.provider.MediaStore.ACTION_IMAGE_CAPTURE
        callerOutputUri = if (captureForCaller)
            @Suppress("DEPRECATION") intent?.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT) else null
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlay = findViewById(R.id.overlay)
        tvFlash = findViewById(R.id.tvFlash); tvTimer = findViewById(R.id.tvTimer)
        tvMenu = findViewById(R.id.tvMenu); tvScene = findViewById(R.id.tvScene)
        tvCount = findViewById(R.id.tvCount); tvLens = findViewById(R.id.tvLens)
        tvPhoto = findViewById(R.id.tvPhoto); tvVideo = findViewById(R.id.tvVideo)
        tvRotateHint = findViewById(R.id.tvRotateHint)
        tvPhoneHint = findViewById(R.id.tvPhoneHint)
        chips = findViewById(R.id.chips); shutter = findViewById(R.id.shutter)
        shutterInner = findViewById(R.id.shutterInner)
        panel = findViewById(R.id.panel); panelList = findViewById(R.id.panelList)

        tvFlash.setOnClickListener { flash = (flash + 1) % 3; applyFlash(); refreshUi() }
        tvTimer.setOnClickListener {
            timerSec = when (timerSec) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }; refreshUi()
        }
        tvMenu.setOnClickListener { openPanel(true) }
        tvLens.setOnClickListener { toggleLens() }
        tvPhoto.setOnClickListener { setVideo(false) }
        tvVideo.setOnClickListener { setVideo(true) }
        shutter.setOnClickListener { onShutter() }

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                cam.cameraControl.setZoomRatio(z * d.scaleFactor); pinched = true; return true
            }
        })
        previewView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            if (e.actionMasked == MotionEvent.ACTION_UP && !pinched && e.eventTime - e.downTime < 300) focusAt(e.x, e.y, true)
            true
        }

        overlay.grid = on("grid")
        buildPanel(); refreshUi()

        if (granted(Manifest.permission.CAMERA)) startCamera()
        else perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onResume() { super.onResume(); handler.postDelayed(aiLoop, 1200) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(aiLoop) }
    override fun onBackPressed() {
        if (panel.visibility == View.VISIBLE) openPanel(false)
        else { if (captureForCaller) setResult(RESULT_CANCELED); super.onBackPressed() }
    }

    private fun startCamera() {
        status("OPENING CAMERA...")
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get(); provider = p
                try {
                    val ef = ExtensionsManager.getInstanceAsync(this, p)
                    ef.addListener({
                        ext = try { ef.get() } catch (_: Throwable) { null }
                        initLenses(); bind()
                    }, mainExec)
                } catch (_: Throwable) { ext = null; initLenses(); bind() }
            } catch (t: Throwable) {
                retryStart()
            }
        }, mainExec)
    }

    private fun retryStart() {
        startTries++
        if (startTries <= 6) {
            status("CAMERA BUSY, RETRYING...")
            handler.postDelayed({ startCamera() }, 700)
        } else {
            status("CAMERA UNAVAILABLE")
        }
    }

    private fun facing(i: CameraInfo) =
        try { Camera2CameraInfo.from(i).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) }
        catch (_: Throwable) { null }

    private fun initLenses() {
        infos = try { provider?.availableCameraInfos ?: emptyList() } catch (_: Throwable) { emptyList() }
        lensIdx = infos.indexOfFirst { facing(it) == CameraCharacteristics.LENS_FACING_BACK }.coerceAtLeast(0)
        if (captureForCaller && phoneAvailable()) {
            // Outro app pediu a foto (ex. banco) e a câmera do celular está configurada
            // como padrão: usa ela direto, sem precisar nem abrir a câmera do relógio.
            enterPhoneMode()
        } else {
            // Quando outro app pede a foto, já abre direto voltado para o usuário.
            setFrontVirtual(captureForCaller)
        }
    }

    private fun realFrontIdx() = infos.indexOfFirst { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }

    private fun setFrontVirtual(v: Boolean) {
        phoneMode = false
        tvPhoneHint.visibility = View.GONE
        frontVirtual = v
        previewView.scaleX = if (v) -1f else 1f
        tvRotateHint.visibility = if (v) View.VISIBLE else View.GONE
        applyFlash()
    }

    /** Entra no modo "câmera do celular": desliga a câmera local do relógio e espera o toque no obturador. */
    private fun enterPhoneMode() {
        phoneMode = true
        tvRotateHint.visibility = View.GONE
        tvPhoneHint.visibility = View.VISIBLE
        try { provider?.unbindAll() } catch (_: Throwable) {}
        camera = null
        status("")
        refreshUi()
    }

    /** Estado atual do seletor de lente, só para saber o que vem a seguir ao tocar em LENS. */
    private fun currentMode(): Int = when {
        phoneMode -> 2
        frontVirtual || (realFrontIdx() >= 0 && lensIdx == realFrontIdx()) -> 1
        else -> 0
    }

    private fun toggleLens() {
        if (recording != null || captureForCaller) return
        val next = (currentMode() + 1).let { if (it == 2 && !phoneAvailable()) 0 else it % 3 }
        when (next) {
            0 -> {
                lensIdx = infos.indexOfFirst { facing(it) == CameraCharacteristics.LENS_FACING_BACK }.coerceAtLeast(0)
                setFrontVirtual(false); bind()
            }
            1 -> {
                val realFront = realFrontIdx()
                if (realFront >= 0 && infos.size > 1) { lensIdx = realFront; setFrontVirtual(false) }
                else setFrontVirtual(true)
                bind()
            }
            else -> enterPhoneMode()
        }
    }

    private fun selectorFor(i: CameraInfo): CameraSelector {
        val id = try { Camera2CameraInfo.from(i).cameraId } catch (_: Throwable) { return CameraSelector.DEFAULT_BACK_CAMERA }
        return CameraSelector.Builder()
            .addCameraFilter { l -> l.filter { try { Camera2CameraInfo.from(it).cameraId == id } catch (_: Throwable) { false } } }.build()
    }

    private fun <T> tune(b: ExtendableBuilder<T>, info: CameraInfo, video: Boolean) {
        if (!on("hq")) return
        val c = Camera2CameraInfo.from(info)
        val ex = Camera2Interop.Extender(b)
        fun pick(k: CameraCharacteristics.Key<IntArray>, r: CaptureRequest.Key<Int>, want: Int) {
            val a = try { c.getCameraCharacteristic(k) } catch (_: Throwable) { null } ?: return
            if (a.contains(want)) ex.setCaptureRequestOption(r, want)
        }
        pick(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (video) CaptureRequest.NOISE_REDUCTION_MODE_FAST else CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        pick(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE,
            if (video) CaptureRequest.EDGE_MODE_FAST else CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        pick(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CaptureRequest.HOT_PIXEL_MODE,
            CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        pick(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        pick(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CaptureRequest.TONEMAP_MODE,
            CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
        pick(CameraCharacteristics.SHADING_AVAILABLE_MODES, CaptureRequest.SHADING_MODE,
            CaptureRequest.SHADING_MODE_HIGH_QUALITY)
        if (video) pick(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        else pick(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    private fun bind() {
        if (phoneMode) { refreshUi(); return }
        val p = provider
        if (p == null) { retryStart(); return }
        if (infos.isEmpty()) { status("NO CAMERA FOUND"); return }
        val info = infos[lensIdx.coerceIn(0, infos.size - 1)]
        val base = selectorFor(info)
        val e = ext
        // No modo "pedido por outro app" mantemos STD (sem extensões), pra abrir rápido
        // e de forma previsível — importante numa verificação de identidade.
        availableExt = listOf(ExtensionMode.NONE) + if (!isVideo && !captureForCaller && e != null)
            listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.FACE_RETOUCH)
                .filter { try { e.isExtensionAvailable(base, it) } catch (_: Throwable) { false } }
        else emptyList()
        if (extMode !in availableExt) extMode = ExtensionMode.NONE
        val sel = if (extMode != ExtensionMode.NONE && e != null)
            try { e.getExtensionEnabledCameraSelector(base, extMode) } catch (_: Throwable) { extMode = ExtensionMode.NONE; base }
        else base

        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val previewB = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
        tune(previewB, info, isVideo)
        val preview = previewB.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        try {
            p.unbindAll()
            if (!isVideo) {
                val icB = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                tune(icB, info, false)
                imageCapture = icB.build(); videoCapture = null
                camera = p.bindToLifecycle(this, sel, preview, imageCapture)
            } else {
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.UHD, Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
                val vcB = VideoCapture.Builder(rec)
                tune(vcB, info, true)
                videoCapture = vcB.build(); imageCapture = null
                camera = p.bindToLifecycle(this, sel, preview, videoCapture)
            }
            bindTries = 0
            applyFlash()
            status("")
        } catch (t: Throwable) {
            when {
                extMode != ExtensionMode.NONE -> { extMode = ExtensionMode.NONE; bind() }
                bindTries < 3 -> { bindTries++; status("OPENING CAMERA..."); handler.postDelayed({ bind() }, 500) }
                else -> status("CAMERA ERROR")
            }
        }
        refreshUi()
    }

    private fun applyFlash() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        if (isVideo) cam.cameraControl.enableTorch(flash == 2 && !frontVirtual)
        else imageCapture?.flashMode = if (frontVirtual) ImageCapture.FLASH_MODE_OFF else when (flash) {
            1 -> ImageCapture.FLASH_MODE_AUTO; 2 -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun setVideo(v: Boolean) {
        if (recording != null || v == isVideo || (v && phoneMode)) return // câmera do celular é só foto
        isVideo = v; bind()
    }

    private fun focusAt(x: Float, y: Float, ring: Boolean) {
        val cam = camera ?: return
        val px = if (frontVirtual) previewView.width - x else x
        val pt = previewView.meteringPointFactory.createPoint(px, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(pt).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
        if (ring) { overlay.tap = PointF(x, y); handler.postDelayed({ overlay.tap = null }, 900) }
    }

    private fun onShutter() {
        if (isVideo) toggleRecord()
        else if (!counting) { if (timerSec > 0) countdown(timerSec) else takePhoto() }
    }

    private fun countdown(n: Int) {
        if (n <= 0) { tvCount.visibility = View.GONE; counting = false; takePhoto(); return }
        counting = true; tvCount.visibility = View.VISIBLE; tvCount.text = "$n"
        handler.postDelayed({ countdown(n - 1) }, 1000)
    }

    private fun takePhoto() {
        if (phoneMode) { status("CONNECTING TO PHONE..."); bg.execute { capturePhoneAndDeliver(allowFallback = captureForCaller) }; return }
        val ic = imageCapture ?: return
        val f = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        val realFront = facing(infos[lensIdx.coerceIn(0, infos.size - 1)]) == CameraCharacteristics.LENS_FACING_FRONT
        val mirror = realFront || frontVirtual
        val opts = ImageCapture.OutputFileOptions.Builder(f)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirror }).build()
        ic.takePicture(opts, mainExec, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                status(if (on("ai_enhance")) "ENHANCING..." else "SAVING...")
                bg.execute { deliverPhoto(f) }
            }
            override fun onError(e: ImageCaptureException) { status("CAPTURE ERROR") }
        })
    }

    /** Pede a foto ao celular pela rede local; se falhar/der timeout, cai de volta pra câmera do relógio. */
    private fun capturePhoneAndDeliver(allowFallback: Boolean) {
        val bytes = PhoneCamera.fetchCapture(phoneIp(), 7000)
        if (bytes != null) {
            val f = File(cacheDir, "phone_${System.currentTimeMillis()}.jpg")
            try { f.writeBytes(bytes) } catch (_: Throwable) { runOnUiThread { status("SAVE ERROR") }; return }
            deliverPhoto(f)
        } else {
            runOnUiThread { status("PHONE CAM UNAVAILABLE") }
            if (allowFallback) {
                runOnUiThread {
                    setFrontVirtual(true); bind()
                    handler.postDelayed({ takePhoto() }, 900)
                }
            }
        }
    }

    /**
     * Processa a foto e, dependendo de como o app foi aberto:
     *  - Uso normal: salva em Pictures/AmazonRiverCamera, como antes.
     *  - Pedido por outro app (ex. banco pedindo selfie no login): devolve a foto
     *    processada pro app que pediu, via o contrato padrão do Android para
     *    android.media.action.IMAGE_CAPTURE (grava no Uri que ele passou, ou retorna
     *    um Uri de conteúdo via FileProvider se ele não passou nenhum).
     */
    private fun deliverPhoto(src: File) {
        val processed = File(cacheDir, "out_${System.currentTimeMillis()}.jpg")
        try {
            java.io.FileOutputStream(processed).use { os ->
                var done = false
                if (on("ai_enhance")) { try { Enhancer.process(src, os); done = true } catch (_: Throwable) { } }
                if (!done) src.inputStream().use { it.copyTo(os) }
            }
        } catch (_: Throwable) {
            runOnUiThread { status("SAVE ERROR") }
            src.delete(); return
        }
        src.delete()

        if (captureForCaller) {
            try {
                val out = callerOutputUri
                if (out != null) {
                    contentResolver.openOutputStream(out)?.use { os -> processed.inputStream().use { it.copyTo(os) } }
                    processed.delete()
                    runOnUiThread { setResult(RESULT_OK); finish() }
                } else {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", processed)
                    runOnUiThread {
                        val result = Intent().apply {
                            data = uri
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        setResult(RESULT_OK, result); finish()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { setResult(RESULT_CANCELED); status("RETURN ERROR") }
            }
            return
        }

        try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AR_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AmazonRiverCamera")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(uri)!!.use { os -> processed.inputStream().use { it.copyTo(os) } }
            runOnUiThread { status("SAVED") }
        } catch (_: Throwable) {
            runOnUiThread { status("SAVE ERROR") }
        } finally { processed.delete() }
    }

    private fun toggleRecord() {
        val vc = videoCapture ?: return
        recording?.let { it.stop(); return }
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AR_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AmazonRiverCamera")
        }
        val o = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()
        var pending = vc.output.prepareRecording(this, o)
        if (granted(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(mainExec) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> refreshUi()
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000
                    tvScene.text = "REC %02d:%02d".format(s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null; refreshUi()
                    status(if (ev.hasError()) "VIDEO ERROR" else "SAVED")
                }
                else -> {}
            }
        }
        refreshUi()
    }

    private fun analyze() {
        if (aiBusy || panel.visibility == View.VISIBLE || recording != null || camera == null) return
        val full = previewView.bitmap ?: return
        if (full.width < 8) return
        val bmp = Bitmap.createScaledBitmap(full, 320, (320f * full.height / full.width).toInt().coerceAtLeast(8), true)
        var sum = 0L; var n = 0
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices step 8) { val p = px[i]; sum += ((p shr 16 and 255) * 77 + (p shr 8 and 255) * 150 + (p and 255) * 29) shr 8; n++ }
        val lowLight = n > 0 && sum / n < 45
        aiBusy = true; tick++
        val img = InputImage.fromBitmap(bmp, 0)
        faceDetector.process(img).addOnSuccessListener { faces ->
            val sx = overlay.width.toFloat() / bmp.width; val sy = overlay.height.toFloat() / bmp.height
            overlay.boxes = faces.map { val b = it.boundingBox; RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy) }
            val now = System.currentTimeMillis()
            val big = faces.maxByOrNull { it.boundingBox.width() }
            if (big != null && on("face_focus") && now - lastFocus > 3000 && !isVideo) {
                lastFocus = now
                focusAt(big.boundingBox.centerX() * sx, big.boundingBox.centerY() * sy, false)
            }
            if (big != null && on("smile") && !isVideo && !counting && !captureForCaller && now - lastSmile > 5000 &&
                (big.smilingProbability ?: 0f) > 0.85f) { lastSmile = now; takePhoto() }
            val scene = when {
                lowLight -> "LOW LIGHT"
                faces.isNotEmpty() -> "PORTRAIT"
                else -> labelScene
            }
            tvScene.text = if (frontVirtual) "AI · $scene · SELFIE" else "AI · $scene"
            autoMode(scene)
            if (tick % 4 == 0) {
                labeler.process(img).addOnSuccessListener { ls ->
                    val t = ls.map { it.text.lowercase() }
                    labelScene = when {
                        t.any { it in setOf("food", "dish", "cuisine", "meal", "drink") } -> "FOOD"
                        t.any { it in setOf("text", "document", "paper", "font", "handwriting") } -> "TEXT"
                        t.any { it in setOf("sky", "mountain", "landscape", "cloud", "tree", "sea", "beach", "plant") } -> "LANDSCAPE"
                        else -> "AUTO"
                    }
                }.addOnCompleteListener { aiBusy = false }
            } else aiBusy = false
        }.addOnFailureListener { aiBusy = false }
    }

    private fun autoMode(scene: String) {
        if (!on("ai_auto") || isVideo || counting || captureForCaller) return
        val want = when (scene) {
            "LOW LIGHT" -> ExtensionMode.NIGHT
            "PORTRAIT" -> ExtensionMode.FACE_RETOUCH
            "LANDSCAPE", "FOOD", "TEXT" -> ExtensionMode.HDR
            else -> ExtensionMode.AUTO
        }
        val target = if (want in availableExt) want else if (ExtensionMode.AUTO in availableExt) ExtensionMode.AUTO else ExtensionMode.NONE
        val now = System.currentTimeMillis()
        if (target != extMode && now - lastAutoSwitch > 5000) { lastAutoSwitch = now; extMode = target; bind() }
    }

    private fun modeName(m: Int) = when (m) {
        ExtensionMode.AUTO -> "AUTO"; ExtensionMode.HDR -> "HDR"; ExtensionMode.NIGHT -> "NIGHT"
        ExtensionMode.FACE_RETOUCH -> "RETOUCH"; else -> "STD"
    }

    private fun refreshUi() {
        tvFlash.text = "FLASH\n" + listOf("OFF", "AUTO", "ON")[flash]
        tvTimer.text = "TIMER\n" + if (timerSec == 0) "OFF" else "${timerSec}S"
        tvPhoto.alpha = if (isVideo) 0.5f else 1f
        tvVideo.alpha = if (isVideo) 1f else 0.5f
        tvLens.text = "LENS\n" + when { phoneMode -> "PHONE"; frontVirtual -> "FRONT"; else -> "BACK" }
        val rec = recording != null
        shutterInner.setBackgroundResource(if (rec) R.drawable.shutter_rec else R.drawable.shutter_dot)
        val sz = ((if (rec) 32 else 58) * resources.displayMetrics.density).toInt()
        shutterInner.layoutParams = (shutterInner.layoutParams as FrameLayout.LayoutParams).also { it.width = sz; it.height = sz }
        if (!rec && tvScene.text.startsWith("REC")) tvScene.text = ""
        if (captureForCaller) {
            tvLens.visibility = View.GONE
            tvPhoto.visibility = View.GONE
            tvVideo.visibility = View.GONE
        }
        chips.removeAllViews()
        if (!isVideo && !captureForCaller && !phoneMode) {
            addChip("AI", on("ai_auto")) { prefs.edit().putBoolean("ai_auto", !on("ai_auto")).apply(); refreshUi() }
            availableExt.forEach { m ->
                addChip(modeName(m), m == extMode) {
                    prefs.edit().putBoolean("ai_auto", false).apply(); extMode = m; bind()
                }
            }
        }
    }

    private fun addChip(t: String, sel: Boolean, click: () -> Unit) {
        val v = TextView(this)
        v.text = t; v.setTextColor(Color.WHITE); v.textSize = 13f; v.letterSpacing = 0.08f
        v.gravity = Gravity.CENTER; v.minimumHeight = (44 * resources.displayMetrics.density).toInt()
        v.minimumWidth = (56 * resources.displayMetrics.density).toInt()
        v.alpha = if (sel) 1f else 0.5f
        if (sel) v.paintFlags = v.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        v.setOnClickListener { click() }
        chips.addView(v)
    }

    private fun status(t: String) {
        tvScene.text = t
        if (t.isNotEmpty()) handler.postDelayed({ if (tvScene.text == t) tvScene.text = "" }, 2200)
    }

    private fun openPanel(open: Boolean) {
        panel.visibility = if (open) View.VISIBLE else View.GONE
        if (open) buildPanel()
    }

    private fun buildPanel() {
        val dark = on("dark")
        val fg = if (dark) Color.WHITE else Color.parseColor("#111111")
        val muted = Color.parseColor("#777777")
        panel.setBackgroundColor(if (dark) Color.parseColor("#111111") else Color.WHITE)
        panelList.removeAllViews()
        val d = resources.displayMetrics.density

        val head = LinearLayout(this); head.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this); logo.setImageResource(R.drawable.logo_river)
        if (dark) logo.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        head.addView(logo, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
        val title = TextView(this); title.text = "AMAZON RIVER\nCAMERA"; title.setTextColor(fg)
        title.textSize = 15f; title.letterSpacing = 0.12f
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this); close.text = "CLOSE"; close.setTextColor(fg); close.textSize = 13f
        close.letterSpacing = 0.08f; close.setPadding((12 * d).toInt(), (14 * d).toInt(), 0, (14 * d).toInt())
        close.setOnClickListener { openPanel(false) }
        head.addView(close)
        panelList.addView(head)

        val sub = TextView(this); sub.setTextColor(muted); sub.textSize = 11f; sub.letterSpacing = 0.08f
        sub.text = if (realFrontIdx() >= 0) "FRONT + ROTATING SIDE CAMERA · 4 MP"
            else "ROTATING SIDE CAMERA · 4 MP · VIRTUAL FRONT MODE"
        sub.setPadding(0, (8 * d).toInt(), 0, (16 * d).toInt())
        panelList.addView(sub)

        settings.forEach { (k, label, _) ->
            val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            val tv = TextView(this); tv.text = label; tv.setTextColor(fg); tv.textSize = 14f; tv.letterSpacing = 0.06f
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = SwitchCompat(this); sw.isChecked = on(k)
            sw.minimumHeight = (52 * d).toInt()
            sw.setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean(k, v).apply()
                when (k) {
                    "grid" -> overlay.grid = v
                    "hq" -> bind()
                    "dark" -> buildPanel()
                }
            }
            row.addView(sw)
            panelList.addView(row)
            val line = View(this); line.setBackgroundColor(if (dark) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5E5"))
            panelList.addView(line, LinearLayout.LayoutParams(-1, (1 * d).toInt().coerceAtLeast(1)))
        }

        // Endereço do celular (app companion), usado quando "USE PHONE CAMERA" está ligado.
        val ipRow = LinearLayout(this); ipRow.orientation = LinearLayout.VERTICAL
        ipRow.setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        val ipLabel = TextView(this); ipLabel.text = "PHONE IP"; ipLabel.setTextColor(muted); ipLabel.textSize = 11f; ipLabel.letterSpacing = 0.08f
        val ipValue = TextView(this)
        ipValue.text = phoneIp().ifBlank { "TAP TO SET (EX: 192.168.0.23:8765)" }
        ipValue.setTextColor(fg); ipValue.textSize = 14f; ipValue.setPadding(0, (4 * d).toInt(), 0, 0)
        ipRow.addView(ipLabel); ipRow.addView(ipValue)
        ipRow.setOnClickListener { editPhoneIp() }
        panelList.addView(ipRow)
    }

    private fun editPhoneIp() {
        val input = EditText(this)
        input.setText(phoneIp())
        input.hint = "192.168.0.23:8765"
        AlertDialog.Builder(this)
            .setTitle("PHONE IP")
            .setMessage("Endereço mostrado no app Amazon River Camera Companion, aberto no celular (mesma rede Wi-Fi).")
            .setView(input)
            .setPositiveButton("SALVAR") { _, _ ->
                prefs.edit().putString("phone_ip", input.text.toString().trim()).apply()
                buildPanel()
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }
}
