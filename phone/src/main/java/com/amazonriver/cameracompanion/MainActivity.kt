package com.amazonriver.cameracompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * App "ponte": fica aberto no celular e espera um pedido do relógio (Amazon River
 * Camera) na rede local. Quando chega um pedido, tira uma foto com a câmera frontal
 * do celular e devolve a foto pela mesma conexão. Não guarda nem envia nada para
 * fora da rede local — é um servidor simples, só para o relógio conversar com o celular.
 */
class MainActivity : AppCompatActivity() {

    companion object { const val PORT = 8765 }

    private lateinit var tvAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var previewView: PreviewView
    private val mainExec by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var serverThread: Thread? = null
    @Volatile private var running = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status("PERMISSÃO DE CÂMERA NECESSÁRIA")
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        tvAddress = findViewById(R.id.tvAddress)
        tvStatus = findViewById(R.id.tvStatus)
        tvAddress.text = "${localIp() ?: "SEM WI-FI"}:$PORT"

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else perms.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        tvAddress.text = "${localIp() ?: "SEM WI-FI"}:$PORT"
        startServer()
    }

    override fun onPause() { super.onPause(); stopServer() }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
    } catch (_: Throwable) { null }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, ic)
                imageCapture = ic
            } catch (_: Throwable) {
                try {
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                    imageCapture = ic
                } catch (_: Throwable) { status("NENHUMA CÂMERA DISPONÍVEL") }
            }
        }, mainExec)
    }

    private fun status(t: String) { handler.post { tvStatus.text = t } }

    // =====================================================================
    //  Servidor local minimalista: qualquer pedido recebido -> tira 1 foto -> devolve o JPEG
    // =====================================================================
    private fun startServer() {
        if (running) return
        running = true
        serverThread = Thread {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                status("AGUARDANDO O RELÓGIO... (${localIp() ?: "?"}:$PORT)")
                while (running) {
                    val client = try { server.accept() } catch (_: Throwable) { if (running) continue else break }
                    handleClient(client)
                }
            } catch (_: Throwable) {
                status("ERRO AO ABRIR SERVIDOR (porta $PORT em uso?)")
            } finally {
                try { server?.close() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopServer() {
        running = false
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 8000
            // Lê e descarta a requisição (não precisamos do conteúdo, qualquer pedido = tirar foto)
            val buf = ByteArray(2048)
            try { client.getInputStream().read(buf) } catch (_: Throwable) {}

            status("CAPTURANDO...")
            val jpeg = captureSync()
            val out: OutputStream = client.getOutputStream()
            if (jpeg != null) {
                out.write(
                    (
                        "HTTP/1.0 200 OK\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpeg.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray()
                )
                out.write(jpeg)
                status("FOTO ENVIADA PARA O RELÓGIO")
            } else {
                out.write("HTTP/1.0 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
                status("FALHA AO CAPTURAR")
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try { client.close() } catch (_: Throwable) {}
        }
    }

    /** Pede a foto na thread principal (exigido pelo CameraX) e espera o resultado aqui. */
    private fun captureSync(): ByteArray? {
        val ic = imageCapture ?: return null
        val q = ArrayBlockingQueue<ByteArray?>(1)
        handler.post {
            ic.takePicture(mainExec, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        q.offer(bytes)
                    } catch (_: Throwable) { q.offer(null) }
                    finally { image.close() }
                }
                override fun onError(e: ImageCaptureException) { q.offer(null) }
            })
        }
        return try { q.poll(7, TimeUnit.SECONDS) } catch (_: Throwable) { null }
    }
}
