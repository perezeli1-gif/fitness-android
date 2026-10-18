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
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** קריאת הליכות וצעדים מ-Health Connect. */
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

    /** קורא הליכות וצעדים מ-Health Connect ומחזיר JSON לדף. הדף שומר אותם בטלפון ושולח לשרת כשיש חיבור. */
    suspend fun read(context: Context, days: Long = 30): String {
        val client = HealthConnectClient.getOrCreate(context)
        val have = client.permissionController.getGrantedPermissions()
        if (!have.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) {
            return Result(false, 0, 0, 0, "אין הרשאה לקרוא אימונים מ-Health Connect").toJson()
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

        val metrics = mutableSetOf<AggregateMetric<*>>(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
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
            val totalSec = Duration.between(s.startTime, s.endTime).seconds
            val w = JSONObject()
                .put("id", s.metadata.id)
                .put("date", start.format(dayFmt))
                .put("time", start.format(timeFmt))
                .put("rec", s.metadata.recordingMethod)
                .put("title", s.title ?: "")
                .put("app", s.metadata.dataOrigin.packageName)
            var sec = totalSec
            try {
                // רק הנתונים שהאפליקציה שרשמה את האימון כתבה (Samsung Health), בלי צעדים מהטלפון או מאפליקציות אחרות
                val range = TimeRangeFilter.between(s.startTime, s.endTime)
                var a: AggregationResult = client.aggregate(AggregateRequest(metrics, range, setOf(s.metadata.dataOrigin)))
                if (a[DistanceRecord.DISTANCE_TOTAL] == null && a[StepsRecord.COUNT_TOTAL] == null)
                    a = client.aggregate(AggregateRequest(metrics, range, emptySet<DataOrigin>()))
                a[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { d ->
                    if (d.seconds in 60..totalSec) sec = d.seconds   // זמן האימון בלי ההפסקות
                }
                a[DistanceRecord.DISTANCE_TOTAL]?.let { w.put("km", it.inKilometers) }
                a[StepsRecord.COUNT_TOTAL]?.let { w.put("steps", it) }
                a[HeartRateRecord.BPM_AVG]?.let { w.put("hr", it) }
                val total = a[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                val active = a[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                total?.let { w.put("kcalT", it) }
                active?.let { w.put("kcalA", it) }
                // Samsung Health מציג את סך הקלוריות באימון
                val kcal = if (total != null && total > 0) total else active
                kcal?.let { w.put("kcal", it) }
            } catch (_: Exception) { /* חלק מהנתונים חסרים: שולחים מה שיש */ }
            w.put("sec", sec).put("min", sec / 60.0)
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

        return JSONObject().put("ok", true).put("walks", walks).put("steps", steps).put("found", sessions.size).toString()
    }
}
