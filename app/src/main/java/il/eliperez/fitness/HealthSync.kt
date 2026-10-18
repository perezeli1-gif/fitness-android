package il.eliperez.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** קורא הליכות וצעדים מ-Health Connect ושולח אותם לשרת יומן הכושר. */
object HealthSync {

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )

    /** 0 = זמין, 1 = צריך עדכון של Health Connect, 2 = לא נתמך במכשיר */
    fun availability(context: Context): Int =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> 0
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> 1
            else -> 2
        }

    suspend fun granted(context: Context): Set<String> =
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    private val WALK_TYPES = setOf(
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    )

    data class Result(val ok: Boolean, val added: Int, val skipped: Int, val found: Int, val message: String) {
        fun toJson(): String = JSONObject()
            .put("ok", ok).put("added", added).put("skipped", skipped)
            .put("found", found).put("message", message).toString()
    }

    suspend fun run(context: Context, server: String, token: String, days: Long = 30): Result {
        val client = HealthConnectClient.getOrCreate(context)
        val have = client.permissionController.getGrantedPermissions()
        if (!have.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) {
            return Result(false, 0, 0, 0, "אין הרשאה לקרוא אימונים מ-Health Connect")
        }
        fun can(cls: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) =
            have.contains(HealthPermission.getReadPermission(cls))

        val since = Instant.now().minus(Duration.ofDays(days))
        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(since),
            )
        ).records.filter { it.exerciseType in WALK_TYPES }

        val metrics = mutableSetOf<AggregateMetric<*>>()
        if (can(DistanceRecord::class)) metrics += DistanceRecord.DISTANCE_TOTAL
        if (can(StepsRecord::class)) metrics += StepsRecord.COUNT_TOTAL
        if (can(HeartRateRecord::class)) metrics += HeartRateRecord.BPM_AVG
        if (can(ActiveCaloriesBurnedRecord::class)) metrics += ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
        if (can(TotalCaloriesBurnedRecord::class)) metrics += TotalCaloriesBurnedRecord.ENERGY_TOTAL

        val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val walks = JSONArray()
        for (s in sessions) {
            val off = s.startZoneOffset
            val start = if (off != null) s.startTime.atOffset(off).toLocalDateTime()
            else LocalDateTime.ofInstant(s.startTime, ZoneId.systemDefault())
            val w = JSONObject()
                .put("id", s.metadata.id)
                .put("date", start.format(dayFmt))
                .put("time", start.format(timeFmt))
                .put("min", Duration.between(s.startTime, s.endTime).toMinutes())
            if (metrics.isNotEmpty()) {
                try {
                    val a = client.aggregate(
                        AggregateRequest(metrics, TimeRangeFilter.between(s.startTime, s.endTime))
                    )
                    a[DistanceRecord.DISTANCE_TOTAL]?.let { w.put("km", it.inKilometers) }
                    a[StepsRecord.COUNT_TOTAL]?.let { w.put("steps", it) }
                    a[HeartRateRecord.BPM_AVG]?.let { w.put("hr", it) }
                    val kcal = a[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                        ?: a[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                    kcal?.let { w.put("kcal", it) }
                } catch (_: Exception) { /* חלק מהנתונים חסרים: שולחים מה שיש */ }
            }
            walks.put(w)
        }

        val steps = JSONObject()
        if (can(StepsRecord::class)) {
            try {
                val end = LocalDate.now().plusDays(1).atStartOfDay()
                val begin = LocalDate.now().minusDays(days).atStartOfDay()
                val groups = client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(begin, end),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                )
                for (g in groups) {
                    g.result[StepsRecord.COUNT_TOTAL]?.let { steps.put(g.startTime.toLocalDate().format(dayFmt), it) }
                }
            } catch (_: Exception) { }
        }

        val body = JSONObject().put("walks", walks).put("steps", steps).toString()
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(server.trimEnd('/') + "/api/sync")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
                val code = c.responseCode
                if (code == 401) return@withContext Result(false, 0, 0, sessions.size, "קוד החיבור לא תקין. היכנס שוב לאפליקציה.")
                if (code != 200) return@withContext Result(false, 0, 0, sessions.size, "השרת החזיר שגיאה $code")
                val r = JSONObject(c.inputStream.bufferedReader().readText())
                Result(true, r.optInt("added"), r.optInt("skipped"), sessions.size, "")
            } catch (e: Exception) {
                Result(false, 0, 0, sessions.size, "אין חיבור לשרת. בדוק אינטרנט.")
            }
        }
    }
}
