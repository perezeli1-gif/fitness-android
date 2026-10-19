package il.eliperez.fitness

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * יומן הכושר רץ מתוך הטלפון: הדף שמור בתוך האפליקציה והנתונים נשמרים בטלפון.
 * השרת (PythonAnywhere) משמש רק לסנכרון עם המחשב ולעדכוני גרסה. בלעדיו הכול ממשיך לעבוד.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_SERVER = "https://eli1959fit.pythonanywhere.com/"
        private const val HOST = "appassets.androidplatform.net"
        private const val PAGE_URL = "https://$HOST/app.html"
        private const val AUTO_SYNC_EVERY_MS = 10 * 60 * 1000L
        private const val FONT_CSS = "@font-face{font-family:'Rubik';font-style:normal;font-weight:300 900;font-display:swap;" +
            "src:url(https://$HOST/fonts/rh.woff2) format('woff2');unicode-range:U+0307-0308,U+0590-05FF,U+200C-2010,U+20AA,U+25CC,U+FB1D-FB4F;}" +
            "@font-face{font-family:'Rubik';font-style:normal;font-weight:300 900;font-display:swap;" +
            "src:url(https://$HOST/fonts/rl.woff2) format('woff2');unicode-range:U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD;}"
    }

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSave: String? = null
    private var lastAutoSync = 0L
    private var healthRunning = false
    private var updating = false
    private var resumeUpdate = false
    private var lastPageCheck = 0L
    private var askedPerms = false

    private val prefs by lazy { getSharedPreferences("fitness", Context.MODE_PRIVATE) }
    private val server: String get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
    private val token: String? get() = prefs.getString("token", null)
    private val webDir by lazy { File(filesDir, "web").apply { mkdirs() } }
    private val dataFile by lazy { File(filesDir, "fitness-data.json") }

    private val askPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty() || askedPerms) readHealth(manual = true)
        else toPage("onFitHealth", HealthSync.Result(false, 0, 0, 0, "לא ניתנה הרשאה ל-Health Connect").toJson())
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingSave
        pendingSave = null
        if (uri == null || text == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "הגיבוי נשמר", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "השמירה נכשלה", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- הדף מתוך האפליקציה ----------
    private fun versionOf(s: String?): List<Int> =
        (s ?: "0").trim().split('.').map { it.toIntOrNull() ?: 0 }

    private fun newer(a: String?, b: String?): Boolean {
        val x = versionOf(a); val y = versionOf(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun bundledVersion(): String = assets.open("web/version.txt").bufferedReader().readText().trim()
    private fun downloadedVersion(): String? = File(webDir, "version.txt").takeIf { it.exists() }?.readText()?.trim()
    private fun currentVersion(): String {
        val d = downloadedVersion()
        val b = bundledVersion()
        return if (d != null && newer(d, b) && File(webDir, "app.html").exists()) d else b
    }

    private fun pageStream(): InputStream {
        val d = downloadedVersion()
        val f = File(webDir, "app.html")
        return if (d != null && f.exists() && newer(d, bundledVersion())) FileInputStream(f) else assets.open("web/app.html")
    }

    private fun intercept(u: Uri): WebResourceResponse? {
        val host = u.host ?: return null
        if (host == "fonts.googleapis.com")
            return WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(FONT_CSS.toByteArray()))
        if (host != HOST) return null
        val path = u.path ?: "/"
        return try {
            when {
                path == "/" || path == "/app.html" -> WebResourceResponse("text/html", "utf-8", pageStream())
                path.startsWith("/fonts/") -> WebResourceResponse("font/woff2", null, assets.open("fonts/" + path.substringAfterLast('/')))
                path.endsWith(".png") -> WebResourceResponse("image/png", null, assets.open("web/" + path.substringAfterLast('/')))
                else -> WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        // אנדרואיד 15 מצייר את האפליקציה מתחת לשורת המצב ולכפתורי הניווט של הטלפון.
        // מרחיקים את הדף מהם, אחרת תפריט הלשוניות למטה מוסתר ולא לחיץ.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(), "FitAndroid")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                intercept(req.url)

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == HOST) return false
                openExternal(u)
                return true
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale("he", "IL")
        }

        web.loadUrl(PAGE_URL)
        checkPageUpdate()
        web.postDelayed({ checkApk(auto = true) }, 4000)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // חזרה ממסך ההרשאה "התקנת אפליקציות לא מוכרות": ממשיכים בעדכון
        if (resumeUpdate && packageManager.canRequestPackageInstalls()) {
            resumeUpdate = false
            updateApk()
        }
        // בדיקת עדכון מסכים גם כשחוזרים לאפליקציה, לא רק בפתיחה מחדש
        if (System.currentTimeMillis() - lastPageCheck > 60 * 1000L) checkPageUpdate()
        // קריאה שקטה מהשעון כשחוזרים לאפליקציה (למשל אחרי הליכה)
        if (System.currentTimeMillis() - lastAutoSync > AUTO_SYNC_EVERY_MS) {
            lastAutoSync = System.currentTimeMillis()
            web.postDelayed({ readHealth(manual = false) }, 2500)
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun openExternal(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: ActivityNotFoundException) { }
    }

    private fun toPage(fn: String, json: String) {
        runOnUiThread { web.evaluateJavascript("window.$fn&&window.$fn($json)", null) }
    }

    // ---------- רשת: רק כשהשרת זמין ----------
    private data class Http(val code: Int, val body: String)

    private suspend fun request(method: String, path: String, body: String?, auth: Boolean = true): Http =
        withContext(Dispatchers.IO) {
            try {
                val c = (URL(server.trimEnd('/') + "/" + path.trimStart('/')).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 12000
                    readTimeout = 25000
                    setRequestProperty("Accept", "application/json")
                    val t = token
                    if (auth && t != null) setRequestProperty("Authorization", "Bearer $t")
                    if (!body.isNullOrEmpty()) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
                if (!body.isNullOrEmpty()) OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                Http(code, text)
            } catch (e: Exception) {
                Http(0, "")
            }
        }

    private fun answer(cb: Int, r: Http) {
        val body = JSONObject.quote(r.body)
        runOnUiThread { web.evaluateJavascript("window.__fitHttp&&window.__fitHttp($cb,${r.code},$body)", null) }
    }

    /** מוריד גרסה חדשה של הדף מהשרת. אם השרת לא זמין, ממשיכים עם מה שיש. */
    private fun checkPageUpdate(manual: Boolean = false) {
        lastPageCheck = System.currentTimeMillis()
        lifecycleScope.launch {
            val v = request("GET", "api/version", null, auth = false)
            if (v.code != 200) { if (manual) toPage("onPageCheck", JSONObject().put("ok", false).toString()); return@launch }
            val remote = try { JSONObject(v.body).optString("version") } catch (_: Exception) { "" }
            if (remote.isEmpty() || !newer(remote, currentVersion()) || token == null) {
                if (manual) toPage("onPageCheck", JSONObject().put("ok", true).put("server", remote).put("page", currentVersion()).toString())
                return@launch
            }
            val r = request("GET", "api/app", null)
            if (r.code != 200) return@launch
            try {
                val o = JSONObject(r.body)
                val html = o.getString("html")
                if (!html.contains("FIT_PHONE") || !html.contains("APP_VERSION")) return@launch
                withContext(Dispatchers.IO) {
                    File(webDir, "app.html.tmp").writeText(html)
                    File(webDir, "app.html.tmp").renameTo(File(webDir, "app.html"))
                    File(webDir, "version.txt").writeText(o.getString("version"))
                }
                toPage("onFitUpdate", JSONObject.quote(o.getString("version")))
            } catch (_: Exception) { }
        }
    }

    // ---------- עדכון אפליקציית הטלפון ----------
    private fun apkInfo(l: AppUpdater.Latest?): String = JSONObject()
        .put("current", BuildConfig.VERSION_NAME)
        .put("latest", l?.version ?: "")
        .put("newer", l != null && AppUpdater.newer(l.version, BuildConfig.VERSION_NAME))
        .put("ok", l != null)
        .toString()

    /** בודק אם יש גרסה חדשה. auto = בדיקה שקטה פעם ב-6 שעות. */
    private fun checkApk(auto: Boolean) {
        if (auto && System.currentTimeMillis() - prefs.getLong("apkCheck", 0) < 3600 * 1000L) return
        lifecycleScope.launch {
            val l = AppUpdater.latest()
            if (l != null) prefs.edit().putLong("apkCheck", System.currentTimeMillis()).apply()
            if (!auto || (l != null && AppUpdater.newer(l.version, BuildConfig.VERSION_NAME)))
                toPage("onApkUpdate", apkInfo(l))
        }
    }

    private fun updateApk() {
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            resumeUpdate = true
            Toast.makeText(this, "פעם אחת בלבד: הפעל את המתג \"אפשר מהמקור הזה\" וחזור", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) { }
            return
        }
        updating = true
        lifecycleScope.launch {
            try {
                val l = AppUpdater.latest()
                if (l == null) { toPage("onApkProgress", JSONObject().put("error", "אין חיבור לאינטרנט").toString()); return@launch }
                if (!AppUpdater.newer(l.version, BuildConfig.VERSION_NAME)) {
                    toPage("onApkProgress", JSONObject().put("error", "כבר מותקנת הגרסה האחרונה (${BuildConfig.VERSION_NAME})").toString()); return@launch
                }
                val f = AppUpdater.download(this@MainActivity, l) { p ->
                    toPage("onApkProgress", JSONObject().put("pct", p).put("version", l.version).toString())
                }
                if (f == null) {
                    toPage("onApkProgress", JSONObject().put("error", "ההורדה בתוך האפליקציה נכשלה. פותח את ההורדה בדפדפן.").toString())
                    openExternal(Uri.parse(l.url)); return@launch
                }
                toPage("onApkProgress", JSONObject().put("installing", true).put("version", l.version).toString())
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    openExternal(Uri.parse(l.url))
                }
            } catch (e: Exception) {
                toPage("onApkProgress", JSONObject().put("error", "העדכון נכשל: ${e.message ?: ""}").toString())
            } finally {
                updating = false
            }
        }
    }

    // ---------- Health Connect ----------
    private fun readHealth(manual: Boolean) {
        when (HealthSync.availability(this)) {
            1 -> {
                if (manual) {
                    Toast.makeText(this, "צריך לעדכן את Health Connect", Toast.LENGTH_LONG).show()
                    openExternal(Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                }
                return
            }
            2 -> {
                if (manual) toPage("onFitHealth", HealthSync.Result(false, 0, 0, 0, "Health Connect לא זמין במכשיר הזה").toJson())
                return
            }
        }
        if (healthRunning) return
        healthRunning = true
        lifecycleScope.launch {
            try {
                val have = HealthSync.granted(this@MainActivity)
                val exercise = have.contains(HealthSync.PERMISSIONS.first())
                if (!have.containsAll(HealthSync.PERMISSIONS) && manual && !askedPerms) {
                    askedPerms = true
                    healthRunning = false
                    askPermissions.launch(HealthSync.PERMISSIONS)
                    return@launch
                }
                if (!exercise) {
                    toPage("onFitHealth", HealthSync.Result(false, 0, 0, 0, "אין הרשאה לקרוא אימונים. לחץ שוב \"סנכרן מהשעון עכשיו\" ואשר את ההרשאות, או: הגדרות הטלפון ← Health Connect ← הרשאות אפליקציה ← כושר + שעון ← לאפשר הכול.").toJson().replace("\"ok\":false", "\"ok\":false,\"noperm\":true,\"quiet\":" + (!manual)))
                    return@launch
                }
                val json = HealthSync.read(this@MainActivity)
                toPage("onFitHealth", json)
            } catch (e: Exception) {
                if (manual) toPage("onFitHealth", HealthSync.Result(false, 0, 0, 0, "הקריאה מהשעון נכשלה: ${e.message ?: ""}").toJson())
            } finally {
                healthRunning = false
            }
        }
    }

    /** פונקציות שהדף יכול לקרוא להן: window.FitAndroid.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun serverUrl(): String = server

        /** בקשה לשרת. התשובה חוזרת ל-window.__fitHttp(cb, status, text). status 0 = אין חיבור. */
        @JavascriptInterface
        fun http(method: String, path: String, body: String, cb: Int) {
            if (method !in setOf("GET", "PUT", "DELETE", "POST") || !path.startsWith("api/")) return
            lifecycleScope.launch { answer(cb, request(method, path, body.ifEmpty { null })) }
        }

        /** התחברות לשרת עם הסיסמה. שומר קוד חיבור קבוע. */
        @JavascriptInterface
        fun connect(password: String, cb: Int) {
            lifecycleScope.launch {
                val r = request("POST", "api/token", JSONObject().put("password", password).toString(), auth = false)
                if (r.code == 200) {
                    try {
                        val t = JSONObject(r.body).optString("token")
                        if (t.length >= 10) prefs.edit().putString("token", t).apply()
                    } catch (_: Exception) { }
                }
                answer(cb, r)
            }
        }

        /** עותק נוסף של כל הנתונים בקובץ באפליקציה (נכלל בגיבוי של גוגל). */
        @JavascriptInterface
        fun persist(text: String) {
            try {
                val tmp = File(filesDir, "fitness-data.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(dataFile)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun restore(): String = try { if (dataFile.exists()) dataFile.readText() else "" } catch (_: Exception) { "" }

        @JavascriptInterface
        fun saveFile(name: String, text: String) {
            runOnUiThread {
                pendingSave = text
                createDoc.launch(name)
            }
        }

        @JavascriptInterface
        fun checkPage() { runOnUiThread { this@MainActivity.checkPageUpdate(manual = true) } }

        @JavascriptInterface
        fun checkApk() { runOnUiThread { this@MainActivity.checkApk(auto = false) } }

        @JavascriptInterface
        fun updateApk() { runOnUiThread { this@MainActivity.updateApk() } }

        @JavascriptInterface
        fun syncNow() {
            runOnUiThread { readHealth(manual = true) }
        }

        @JavascriptInterface
        fun setToken(t: String) { /* גרסה ישנה של הדף. לא בשימוש. */ }

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
                    .setMessage("אם עוברים לשרת אחר: כותבים את הכתובת, ואז מתחברים עם הסיסמה. הנתונים בטלפון נשמרים.")
                    .setView(input)
                    .setPositiveButton("שמור") { _, _ ->
                        var s = input.text.toString().trim()
                        if (!s.startsWith("https://")) s = "https://" + s.removePrefix("http://")
                        if (!s.endsWith("/")) s += "/"
                        prefs.edit().putString("server", s).remove("token").apply()
                        web.reload()
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
        }

        @JavascriptInterface
        fun status(): String = JSONObject()
            .put("connected", token != null)
            .put("health", HealthSync.availability(this@MainActivity))
            .put("page", currentVersion())
            .toString()
    }
}
