package il.eliperez.fitness

import android.app.Activity
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * קריאה ישירה מ-Samsung Health (דרך Samsung Health Data SDK, במצב מפתח).
 * כאן יש את הנתונים המלאים של האימון: זמן בלי הפסקות, צעדים, דופק ממוצע, מרחק וקלוריות.
 * הנתונים מחליפים את מה שהגיע מ-Health Connect, שמעביר רק חלק מהם.
 */
object SamsungSync {
    private val PERM = setOf(Permission.of(DataTypes.EXERCISE, AccessType.READ), Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ))
    private val WALK = setOf(
        DataType.ExerciseType.PredefinedExerciseType.WALKING,
        DataType.ExerciseType.PredefinedExerciseType.HIKING,
    )

    /** מקבל את ה-JSON של Health Connect ומחזיר אותו עם הנתונים של Samsung Health. אם משהו נכשל, מחזיר אותו כמו שהוא עם הסבר. */
    suspend fun enrich(act: Activity, hcJson: String, manual: Boolean, days: Long = 30): String {
        val out = JSONObject(hcJson)
        try {
            val store = HealthDataService.getStore(act)
            var granted = store.getGrantedPermissions(PERM)
            val exPerm = Permission.of(DataTypes.EXERCISE, AccessType.READ)
            val pr = act.getSharedPreferences("fitness", android.content.Context.MODE_PRIVATE)
            val exPerm0 = Permission.of(DataTypes.EXERCISE, AccessType.READ)
            // מבקשים שוב רק אם חסרה הרשאת האימונים, או בפעם הראשונה אחרי שנוספה הרשאת הרכב הגוף
            if (!granted.containsAll(PERM) && manual && (exPerm0 !in granted || !pr.getBoolean("askedBody", false))) {
                pr.edit().putBoolean("askedBody", true).apply()
                granted = store.requestPermissions(PERM, act)
            }
            if (exPerm !in granted) {
                out.put("shealth", if (manual) "לא ניתנה הרשאה לקרוא מ-Samsung Health" else "צריך לאשר הרשאה של Samsung Health (לחץ \"סנכרון מהשעון עכשיו\")")
                return out.toString()
            }
            val zone = ZoneId.systemDefault()
            val filter = LocalTimeFilter.of(LocalDateTime.now().minusDays(days), LocalDateTime.now().plusHours(1))
            val req = DataTypes.EXERCISE.readDataRequestBuilder.setLocalTimeFilter(filter).build()
            val points = store.readData(req).dataList

            val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            val hcWalks = out.optJSONArray("walks") ?: JSONArray()
            val used = mutableSetOf<Int>()
            val merged = JSONArray()
            var n = 0
            for (p in points) {
                val sessions = p.getValue(DataType.ExerciseType.SESSIONS) ?: continue
                for (s in sessions) {
                    if (s.exerciseType !in WALK) continue
                    n++
                    val start = LocalDateTime.ofInstant(s.startTime, zone)
                    // אותה הליכה ב-Health Connect (שעת התחלה עד 3 דקות הבדל): שומרים את המזהה שלה כדי לא לכפול
                    var match = -1
                    for (i in 0 until hcWalks.length()) {
                        if (i in used) continue
                        val h = hcWalks.getJSONObject(i)
                        if (h.optString("date") != start.format(dayFmt)) continue
                        val t = h.optString("time").split(":")
                        val hm = (t.getOrNull(0)?.toIntOrNull() ?: -999) * 60 + (t.getOrNull(1)?.toIntOrNull() ?: 0)
                        if (kotlin.math.abs(hm - (start.hour * 60 + start.minute)) <= 3) { match = i; break }
                    }
                    val w = if (match >= 0) { used += match; hcWalks.getJSONObject(match) }
                            else JSONObject().put("id", "sh" + (p.uid ?: s.startTime.epochSecond.toString()))
                    val total = Duration.between(s.startTime, s.endTime).seconds
                    val sec = s.duration?.seconds?.takeIf { it in 60..total + 60 } ?: total
                    w.put("date", start.format(dayFmt)).put("time", start.format(timeFmt))
                        .put("sec", sec).put("min", sec / 60.0).put("total", total)
                        .put("src2", "samsung").put("app", "com.sec.android.app.shealth")
                    s.distance?.takeIf { it > 0 }?.let { w.put("km", it / 1000.0) }
                    s.count?.takeIf { it > 0 }?.let { w.put("steps", it) }
                    s.meanHeartRate?.takeIf { it > 0 }?.let { w.put("hr", it.toDouble()) }
                    s.calories.takeIf { it > 0 }?.let { w.put("kcal", it.toDouble()) }
                    s.meanSpeed?.takeIf { it > 0 }?.let { w.put("speed", it * 3.6) }
                    w.remove("dbgMove"); w.remove("dbgSteps")
                    if (sec >= 15 * 60) try { track(s, zone)?.let { w.put("trk", it) } } catch (_: Throwable) { }
                    merged.put(w)
                }
            }
            // הליכות שנמצאו רק ב-Health Connect נשארות כמו שהן
            for (i in 0 until hcWalks.length()) if (i !in used) merged.put(hcWalks.getJSONObject(i))
            out.put("walks", merged).put("found", merged.length())
            out.put("shealth", "ok").put("shealthWalks", n)
            // הרכב גוף: מהשעון (מדידת BIA) או ממשקל חכם שמסונכרן ל-Samsung Health
            if (Permission.of(DataTypes.BODY_COMPOSITION, AccessType.READ) in granted) try {
                val bf = LocalTimeFilter.of(LocalDateTime.now().minusDays(120), LocalDateTime.now().plusHours(1))
                val body = JSONArray()
                for (p in store.readData(DataTypes.BODY_COMPOSITION.readDataRequestBuilder.setLocalTimeFilter(bf).build()).dataList) {
                    val t = LocalDateTime.ofInstant(p.startTime, zone)
                    val o = JSONObject().put("date", t.format(dayFmt)).put("time", t.format(timeFmt))
                    fun f(k: String, v: Float?) { if (v != null && v > 0) o.put(k, Math.round(v * 10) / 10.0) }
                    f("weight", p.getValue(DataType.BodyCompositionType.WEIGHT))
                    f("fat", p.getValue(DataType.BodyCompositionType.BODY_FAT))
                    f("fatMass", p.getValue(DataType.BodyCompositionType.BODY_FAT_MASS))
                    f("muscle", p.getValue(DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS))
                    f("water", p.getValue(DataType.BodyCompositionType.TOTAL_BODY_WATER))
                    f("bmi", p.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX))
                    p.getValue(DataType.BodyCompositionType.BASAL_METABOLIC_RATE)?.takeIf { it > 0 }?.let { o.put("bmr", it) }
                    if (o.length() > 2) body.put(o)
                }
                out.put("body", body)
            } catch (_: Throwable) { }
        } catch (e: ResolvablePlatformException) {
            if (manual && e.hasResolution) try { e.resolve(act) } catch (_: Exception) { }
            out.put("shealth", "Samsung Health צריך עדכון או אישור: ${e.message ?: ""}")
        } catch (e: Throwable) {
            out.put("shealth", "הקריאה מ-Samsung Health נכשלה: ${e.message ?: e.javaClass.simpleName}")
        }
        return out.toString()
    }

    /** המסלול (GPS) ונתוני הדופק והמהירות לאורך ההליכה, מדוללים לכ-120 נקודות כדי לשמור מקום. */
    private fun <T> thin(l: List<T>, n: Int = 120): List<T> =
        if (l.size <= n) l else (0 until n).map { l[(it.toLong() * (l.size - 1) / (n - 1)).toInt()] }

    private fun r5(v: Double) = Math.round(v * 100000) / 100000.0
    private fun r1(v: Double) = Math.round(v * 10) / 10.0

    private fun track(s: com.samsung.android.sdk.health.data.data.entries.ExerciseSession, zone: ZoneId): JSONObject? {
        val out = JSONObject()
        val route = s.route ?: emptyList()
        if (route.size >= 5) {
            val r = JSONArray()
            for (p in thin(route.sortedBy { it.timestamp }, 300)) r.put(JSONArray().put(r5(p.latitude.toDouble())).put(r5(p.longitude.toDouble()))
                .put(p.altitude?.let { r1(it.toDouble()) } ?: JSONObject.NULL)
                .put(p.timestamp?.let { Duration.between(s.startTime, it).seconds } ?: JSONObject.NULL))
            out.put("r", r)
        }
        val log = (s.log ?: emptyList()).filter { it.timestamp != null }.sortedBy { it.timestamp }
        if (log.size >= 5) {
            val t0 = s.startTime
            val l = JSONArray()
            for (e in thin(log)) {
                val row = JSONArray().put(Duration.between(t0, e.timestamp).seconds)
                row.put(e.heartRate?.takeIf { it > 30 }?.let { Math.round(it).toInt() } ?: JSONObject.NULL)
                row.put(e.speed?.takeIf { it >= 0 }?.let { r1(it * 3.6) } ?: JSONObject.NULL)
                row.put(e.cadence?.takeIf { it > 0 }?.let { Math.round(it).toInt() } ?: JSONObject.NULL)
                l.put(row)
            }
            out.put("l", l)
        }
        return if (out.length() == 0) null else out
    }
}
