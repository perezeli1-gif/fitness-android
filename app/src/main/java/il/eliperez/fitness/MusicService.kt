package il.eliperez.fitness

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * נגן המוזיקה: ממשיך לנגן גם כשהמסך כבוי, עם כפתורים בהתראה ובמסך הנעילה.
 * שיר שנשמר בטלפון מנוגן מהקובץ, אחרת מהשרת הביתי.
 */
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("fitness", Context.MODE_PRIVATE)
        val http = DefaultHttpDataSource.Factory().setUserAgent("fitness-music").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
        prefs.getString("token", null)?.let { http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $it")) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)   // אוזניות שהתנתקו: עוצר
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // שיר שלא מתנגן (פורמט לא נתמך וכד'): ממשיכים לשיר הבא, עד 5 פעמים ברצף
        player.addListener(object : androidx.media3.common.Player.Listener {
            var fails = 0
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val io = error.errorCode in 2000..2999
                if (!io && fails < 5 && player.mediaItemCount > 1) { fails++; player.seekToNextMediaItem(); player.prepare(); player.play() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) fails = 0 }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** שירים שנשמרו בטלפון (בתיקיית האפליקציה, לא בגלריה). */
object MusicStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var busy = false
    @Volatile var stopFlag = false

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "music").apply { mkdirs() }

    fun local(ctx: Context, rel: String): File? {
        val f = File(dir(ctx), rel)
        return if (f.isFile && f.length() > 0 && f.canonicalPath.startsWith(dir(ctx).canonicalPath)) f else null
    }

    fun encode(rel: String): String = rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun item(ctx: Context, server: String, rel: String): MediaItem {
        val f = local(ctx, rel)
        val uri = if (f != null) Uri.fromFile(f) else Uri.parse((if (server.endsWith("/")) server else "$server/") + "music/" + encode(rel))
        val name = rel.substringAfterLast('/').substringBeforeLast('.')
        val folder = rel.substringBeforeLast('/', "").substringAfterLast('/')
        return MediaItem.Builder().setMediaId(rel).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setArtist(folder).build()).build()
    }

    fun list(ctx: Context): JSONObject {
        val root = dir(ctx)
        val arr = JSONArray()
        var total = 0L
        root.walkTopDown().filter { it.isFile }.forEach {
            total += it.length()
            arr.put(it.relativeTo(root).path.replace(File.separatorChar, '/'))
        }
        return JSONObject().put("files", arr).put("bytes", total).put("free", root.usableSpace)
    }

    fun delete(ctx: Context, rels: List<String>) {
        rels.forEach { local(ctx, it)?.delete() }
        dir(ctx).walkBottomUp().filter { it.isDirectory && it != dir(ctx) && (it.list()?.isEmpty() == true) }.forEach { it.delete() }
    }

    /** מוריד את השירים אחד אחרי השני. progress(done, total, error) */
    fun download(ctx: Context, server: String, token: String?, rels: List<String>, progress: (Int, Int, String?) -> Unit) {
        if (busy) return
        busy = true; stopFlag = false
        scope.launch {
            var done = 0
            var err: String? = null
            try {
                for (rel in rels) {
                    if (stopFlag) break
                    if (local(ctx, rel) == null) {
                        if (dir(ctx).usableSpace < 300L * 1024 * 1024) { err = "אין מספיק מקום בטלפון"; break }
                        val target = File(dir(ctx), rel)
                        if (!target.canonicalPath.startsWith(dir(ctx).canonicalPath)) continue
                        target.parentFile?.mkdirs()
                        val tmp = File(target.path + ".part")
                        val c = (URL((if (server.endsWith("/")) server else "$server/") + "music/" + encode(rel)).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000; readTimeout = 60000
                            token?.let { setRequestProperty("Authorization", "Bearer $it") }
                        }
                        if (c.responseCode != 200) { err = "השרת החזיר ${c.responseCode}"; tmp.delete(); break }
                        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                        tmp.renameTo(target)
                    }
                    done++
                    withContext(Dispatchers.Main) { progress(done, rels.size, null) }
                }
            } catch (e: Exception) {
                err = "ההורדה נעצרה: ${e.message ?: "אין חיבור"}"
            } finally {
                busy = false
                val e2 = err
                withContext(Dispatchers.Main) { progress(done, rels.size, e2 ?: if (stopFlag) "נעצר" else "") }
            }
        }
    }
}
