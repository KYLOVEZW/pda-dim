package com.lecangs.pdadim

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File

/**
 * 拍照量方测试版：用 WebView 承载 assets/index.html（与浏览器版同一份程序）。
 * 页面通过 https://appassets.androidplatform.net 加载，属于安全来源，可使用实时取景(getUserMedia)。
 * 本壳负责：相机运行时权限、系统相机拍照(<input capture>)、保存文件到“下载/拍照量方”、返回键。
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingAfterCameraPermission: (() -> Unit)? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null
    @Volatile private var cameraActive = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingWebPermission?.let { req ->
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
        }
        pendingWebPermission = null
        val after = pendingAfterCameraPermission
        pendingAfterCameraPermission = null
        if (granted) after?.invoke() else {
            fileCallback?.onReceiveValue(null); fileCallback = null
            if (after != null) Toast.makeText(this, "未开启相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        fileCallback?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // 启动时先显示深色底，避免白屏闪烁；WebView 用硬件加速
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF16181D.toInt()))
        web = WebView(this)
        web.setBackgroundColor(0xFFEEF0F3.toInt())
        web.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // 测试记录、标定保存在 localStorage
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            // 实时取景：页面调用 getUserMedia 时
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { request.deny(); return@runOnUiThread }
                    if (hasCamera()) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    else { pendingWebPermission = request; cameraPermission.launch(Manifest.permission.CAMERA) }
                }
            }

            // 系统相机：<input type=file capture>
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val launch = {
                    val dir = File(cacheDir, "photos").apply { mkdirs() }
                    val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                    val u = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    photoUri = u
                    takePicture.launch(u)
                }
                if (hasCamera()) launch() else { pendingAfterCameraPermission = launch; cameraPermission.launch(Manifest.permission.CAMERA) }
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__appBack && window.__appBack()) ? 'y' : 'n'") { r ->
                    if (r?.contains("y") != true) finish()
                }
            }
        })

        if (savedInstanceState == null) web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        else web.restoreState(savedInstanceState)
    }

    /** 拍照页打开时：音量键、相机键、PDA 侧键/扫描键（常见键码 280-293、520-523、F1-F12）都当作拍照键 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val shutterKey = k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN ||
            k == KeyEvent.KEYCODE_CAMERA || k == KeyEvent.KEYCODE_FOCUS ||
            k in 280..293 || k in 520..523 || k in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
        if (cameraActive && shutterKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                web.evaluateJavascript("window.__shutter && window.__shutter()", null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 页面可调用的原生能力：window.AndroidBridge.xxx */
    inner class Bridge {
        @JavascriptInterface
        fun setCameraActive(active: Boolean) { cameraActive = active }

        @JavascriptInterface
        fun openAppSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }

        /** 保存到 下载/拍照量方/，返回是否成功 */
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String): Boolean = try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/拍照量方")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }
}
