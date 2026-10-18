package il.eliperez.fitness

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_SERVER = "https://eli1959fit.pythonanywhere.com/"
        private const val AUTO_SYNC_EVERY_MS = 10 * 60 * 1000L
    }

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastAutoSync = 0L
    private var syncRunning = false

    private val prefs by lazy { getSharedPreferences("fitness", Context.MODE_PRIVATE) }
    private val server: String get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
    private val token: String? get() = prefs.getString("token", null)

    private val askPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty()) syncNow(manual = true)
        else reportToPage(HealthSync.Result(false, 0, 0, 0, "לא ניתנה הרשאה ל-Health Connect").toJson())
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        CookieManager.getInstance().setAcceptCookie(true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "FitAndroid")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == Uri.parse(server).host) return false
                openExternal(u)
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickFiles.launch("*/*")
                return true
            }
        }
        web.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale("he", "IL")
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(server)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // סנכרון שקט כשחוזרים לאפליקציה (למשל אחרי הליכה)
        if (token != null && System.currentTimeMillis() - lastAutoSync > AUTO_SYNC_EVERY_MS) {
            lastAutoSync = System.currentTimeMillis()
            syncNow(manual = false)
        }
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: ActivityNotFoundException) { }
    }

    private fun reportToPage(json: String) {
        runOnUiThread {
            web.evaluateJavascript("window.onFitSync&&window.onFitSync($json)", null)
        }
    }

    private fun syncNow(manual: Boolean) {
        val tok = token
        if (tok == null) {
            if (manual) reportToPage(HealthSync.Result(false, 0, 0, 0, "היכנס לאפליקציה עם הסיסמה ונסה שוב").toJson())
            return
        }
        when (HealthSync.availability(this)) {
            1 -> {
                if (manual) {
                    Toast.makeText(this, "צריך לעדכן את Health Connect", Toast.LENGTH_LONG).show()
                    openExternal(Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                }
                return
            }
            2 -> {
                if (manual) reportToPage(HealthSync.Result(false, 0, 0, 0, "Health Connect לא זמין במכשיר הזה").toJson())
                return
            }
        }
        if (syncRunning) return
        syncRunning = true
        lifecycleScope.launch {
            try {
                val have = HealthSync.granted(this@MainActivity)
                if (!have.containsAll(HealthSync.PERMISSIONS) && manual) {
                    syncRunning = false
                    askPermissions.launch(HealthSync.PERMISSIONS)
                    return@launch
                }
                val r = HealthSync.run(this@MainActivity, server, tok)
                if (manual || r.added > 0) reportToPage(r.toJson())
            } catch (e: Exception) {
                if (manual) reportToPage(HealthSync.Result(false, 0, 0, 0, "הסנכרון נכשל: ${e.message ?: ""}").toJson())
            } finally {
                syncRunning = false
            }
        }
    }

    private fun isOurPage(): Boolean {
        val cur = web.url ?: return false
        return Uri.parse(cur).host == Uri.parse(server).host
    }

    /** פונקציות שדף יומן הכושר יכול לקרוא להן: window.FitAndroid.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun version(): String = "1.0"

        @JavascriptInterface
        fun setToken(t: String) {
            runOnUiThread {
                if (!isOurPage() || t.length < 10) return@runOnUiThread
                val first = token == null
                prefs.edit().putString("token", t).apply()
                if (first) this@MainActivity.syncNow(manual = false)
            }
        }

        @JavascriptInterface
        fun syncNow() {
            runOnUiThread { if (isOurPage()) this@MainActivity.syncNow(manual = true) }
        }

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fit")
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            val v = getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, ms, 150, ms, 150, ms * 2), -1))
        }

        @JavascriptInterface
        fun serverSettings() {
            runOnUiThread {
                val input = EditText(this@MainActivity).apply { setText(server) }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("כתובת השרת")
                    .setView(input)
                    .setPositiveButton("שמור") { _, _ ->
                        var s = input.text.toString().trim()
                        if (!s.startsWith("https://")) s = "https://" + s.removePrefix("http://")
                        if (!s.endsWith("/")) s += "/"
                        prefs.edit().putString("server", s).remove("token").apply()
                        web.loadUrl(s)
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
        }

        @JavascriptInterface
        fun status(): String = JSONObject()
            .put("connected", token != null)
            .put("health", HealthSync.availability(this@MainActivity))
            .toString()
    }
}
