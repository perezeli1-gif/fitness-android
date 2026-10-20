package il.eliperez.fitness

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * עדכון אפליקציית הטלפון מתוך האפליקציה.
 * הגרסאות החדשות נבנות ומתפרסמות אוטומטית ב-GitHub (releases/latest).
 */
object AppUpdater {
    private const val API = "https://api.github.com/repos/perezeli1-gif/fitness-android/releases/tags/latest"
    private val NAME = Regex("""fitness-sync-v(\d+(?:\.\d+)*)\.apk""")

    data class Latest(val version: String, val url: String, val size: Long)

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** הגרסה החדשה ביותר: מהשרת הביתי (שם נבנית האפליקציה) או מ-GitHub. */
    suspend fun latest(server: String?): Latest? {
        val home = if (server.isNullOrBlank()) null else fromServer(server)
        val gh = fromGithub()
        return when {
            home == null -> gh
            gh == null -> home
            newer(gh.version, home.version) -> gh
            else -> home
        }
    }

    private suspend fun fromServer(server: String): Latest? = withContext(Dispatchers.IO) {
        try {
            val base = if (server.endsWith("/")) server else "$server/"
            val c = (URL(base + "api/apk").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 12000
                setRequestProperty("User-Agent", "fitness-sync")
            }
            if (c.responseCode != 200) return@withContext null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val v = j.optString("version").takeIf { it.isNotBlank() && it != "null" } ?: return@withContext null
            Latest(v, base + j.getString("url"), j.optLong("size"))
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fromGithub(): Latest? = withContext(Dispatchers.IO) {
        try {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000; readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "fitness-sync")
            }
            if (c.responseCode != 200) return@withContext null
            val assets = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("assets")
            var best: Latest? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val m = NAME.matchEntire(a.optString("name")) ?: continue
                val v = m.groupValues[1]
                if (best == null || newer(v, best.version))
                    best = Latest(v, a.getString("browser_download_url"), a.optLong("size"))
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    /** מוריד את הקובץ. progress מקבל אחוזים. */
    suspend fun download(ctx: Context, l: Latest, progress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val f = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "update.apk")
            var url = URL(l.url)
            var c: HttpURLConnection
            var hops = 0
            while (true) {
                c = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "fitness-sync")
                }
                val code = c.responseCode
                if (code in 300..399 && hops < 5) {
                    url = URL(url, c.getHeaderField("Location")); hops++; continue
                }
                if (code != 200) return@withContext null
                break
            }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: l.size
            var done = 0L
            var last = -1
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; progress(p) }
                        }
                    }
                }
            }
            if (f.length() < 1_000_000) null else f
        } catch (e: Exception) {
            null
        }
    }

    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** תשובת מערכת ההתקנה: אם צריך אישור, פותח את מסך האישור של אנדרואיד. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> { }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "העדכון לא הותקן. $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
