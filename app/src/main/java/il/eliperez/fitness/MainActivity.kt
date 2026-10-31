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
import org.json.JSONArray
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
        const val DEFAULT_SERVER = "https://homeserver.tail8db545.ts.net:10000/"
        /** כתובות ישנות של יומן הכושר: עוברים לכתובת החדשה אוטומטית, בלי להתחבר מחדש */
        val OLD_SERVERS = setOf("https://homeserver.tail8db545.ts.net:8445/", "https://homeserver.tail8db545.ts.net:8445", "https://homeserver.tail8db545.ts.net/", "https://homeserver.tail8db545.ts.net", "http://192.168.50.83:5050/")
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
    private var pendingBin: ByteArray? = null
    private var printView: WebView? = null
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

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---------- מוזיקה ----------
    private var controller: androidx.media3.session.MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    private fun withController(block: (androidx.media3.session.MediaController) -> Unit) {
        controller?.let { block(it); return }
        val f = controllerFuture ?: androidx.media3.session.MediaController.Builder(
            this, androidx.media3.session.SessionToken(this, android.content.ComponentName(this, MusicService::class.java))
        ).buildAsync().also { controllerFuture = it }
        f.addListener({
            try { controller = f.get(); controller?.let(block) } catch (_: Exception) { controllerFuture = null }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun musicStateJson(c: androidx.media3.session.MediaController?): String {
        val o = JSONObject()
        if (c == null || c.mediaItemCount == 0) return o.put("idle", true).toString()
        val m = c.mediaMetadata
        o.put("playing", c.isPlaying).put("buffering", c.playbackState == androidx.media3.common.Player.STATE_BUFFERING)
            .put("title", m.title?.toString() ?: "").put("artist", m.artist?.toString() ?: "")
            .put("id", c.currentMediaItem?.mediaId ?: "").put("index", c.currentMediaItemIndex).put("count", c.mediaItemCount)
            .put("pos", c.currentPosition).put("dur", c.duration.takeIf { it > 0 } ?: 0).put("shuffle", c.shuffleModeEnabled)
        c.playerError?.let { o.put("error", it.errorCodeName) }
        return o.toString()
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

    // קובץ אקסל לשמירה
    private val createXlsx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        val data = pendingBin
        pendingBin = null
        if (uri == null || data == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
            Toast.makeText(this, "הקובץ נשמר", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "השמירה נכשלה", Toast.LENGTH_LONG).show()
        }
    }

    /** הדפסה או שמירה כ-PDF: מסך ההדפסה של אנדרואיד, עם "שמירה כ-PDF". */
    private fun printHtml(title: String, html: String) {
        val pv = WebView(this)
        pv.settings.javaScriptEnabled = false
        pv.settings.allowFileAccess = false
        pv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                pm.print(title, view.createPrintDocumentAdapter(title),
                    android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build())
            }
        }
        printView = pv   // נשמר עד שההדפסה מסתיימת
        pv.loadDataWithBaseURL("https://$HOST/", html, "text/html", "utf-8", null)
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
        // הכתובת הראשית של השרת עברה לשרת הסרטים: יומן הכושר נמצא עכשיו בפורט 8445. אותו שרת, אז קוד החיבור נשאר תקף.
        prefs.getString("server", null)?.let { if (it.trim() in OLD_SERVERS) prefs.edit().putString("server", DEFAULT_SERVER).apply() }
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
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
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
        controller?.release(); controller = null
        tts?.shutdown()
        super.onDestroy()
    }

    private fun openExternal(u: Uri) {
        // רק קישורי אינטרנט וחנות: לא פותחים intent:// או סוגים אחרים שדף יכול לנצל
        if (u.scheme !in setOf("https", "http", "market")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: ActivityNotFoundException) { }
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
            val l = AppUpdater.latest(server)
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
                val l = AppUpdater.latest(server)
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
                val json = SamsungSync.enrich(this@MainActivity, HealthSync.read(this@MainActivity), manual)
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
        fun saveXlsx(name: String, b64: String) {
            runOnUiThread {
                try {
                    pendingBin = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    createXlsx.launch(name)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "השמירה נכשלה", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun musicPlay(json: String) {
            runOnUiThread {
                try {
                    val o = JSONObject(json)
                    val a = o.getJSONArray("items")
                    val items = (0 until a.length()).map { MusicStore.item(this@MainActivity, server, a.getString(it)) }
                    if (items.isEmpty()) return@runOnUiThread
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    withController { c ->
                        c.setMediaItems(items, o.optInt("index", 0).coerceIn(0, items.size - 1), 0L)
                        c.shuffleModeEnabled = o.optBoolean("shuffle", false)
                        c.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        c.prepare(); c.play()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "הניגון נכשל", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun musicCmd(cmd: String) {
            runOnUiThread {
                withController { c ->
                    when {
                        cmd == "play" -> c.play()
                        cmd == "pause" -> c.pause()
                        cmd == "next" -> c.seekToNextMediaItem()
                        cmd == "prev" -> if (c.currentPosition > 5000) c.seekTo(0) else c.seekToPreviousMediaItem()
                        cmd == "stop" -> { c.stop(); c.clearMediaItems() }
                        cmd == "shuffle" -> c.shuffleModeEnabled = !c.shuffleModeEnabled
                        cmd.startsWith("seek:") -> cmd.substringAfter(':').toLongOrNull()?.let { c.seekTo(it) }
                    }
                    toPage("onMusic", musicStateJson(c))
                }
            }
        }

        @JavascriptInterface
        fun musicState() { runOnUiThread { val c = controller; if (c != null) toPage("onMusic", musicStateJson(c)) else withController { toPage("onMusic", musicStateJson(it)) } } }

        @JavascriptInterface
        fun musicLocal(): String = MusicStore.list(this@MainActivity).toString()

        @JavascriptInterface
        fun musicDownload(json: String) {
            val a = JSONArray(json)
            val rels = (0 until a.length()).map { a.getString(it) }
            MusicStore.download(this@MainActivity, server, token, rels) { done, total, err ->
                toPage("onMusicDl", JSONObject().put("done", done).put("total", total).put("end", err != null).put("error", err ?: "").toString())
            }
        }

        @JavascriptInterface
        fun musicStopDl() { MusicStore.stopFlag = true }

        @JavascriptInterface
        fun musicDelete(json: String) {
            val a = JSONArray(json)
            MusicStore.delete(this@MainActivity, (0 until a.length()).map { a.getString(it) })
        }

        @JavascriptInterface
        fun openSettings(kind: String) {
            runOnUiThread {
                val tries = when (kind) {
                    "unknown" -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    else -> listOf(
                        // סמסונג: מסך "חוסם אוטומטי" (אם קיים בגרסה הזו), אחרת הגדרות אבטחה
                        Intent().setClassName("com.android.settings", "com.samsung.android.settings.privacy.AutoBlockerSettingsActivity"),
                        Intent(Settings.ACTION_SECURITY_SETTINGS),
                        Intent(Settings.ACTION_SETTINGS))
                }
                for (i in tries) {
                    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@runOnUiThread } catch (_: Exception) { }
                }
            }
        }

        @JavascriptInterface
        fun printHtml(title: String, html: String) { runOnUiThread { this@MainActivity.printHtml(title, html) } }

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
