package il.eliperez.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
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
        val allSessions = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(since),
            )
        ).records
        val sessions = allSessions.filter { it.exerciseType in WALK_TYPES }
        // אבחון: אילו אימונים אחרים נמצאו ב-3 הימים האחרונים (אולי ההליכה נשמרה כסוג אחר)
        val recentCut = Instant.now().minus(Duration.ofDays(3))
        val others = JSONArray()
        allSessions.filter { it.exerciseType !in WALK_TYPES && it.startTime.isAfter(recentCut) }.forEach { o ->
            val off = o.startZoneOffset
            val st = if (off != null) o.startTime.atOffset(off).toLocalDateTime() else LocalDateTime.ofInstant(o.startTime, ZoneId.systemDefault())
            others.put(JSONObject().put("type", o.exerciseType).put("when", st.format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))
                .put("min", Duration.between(o.startTime, o.endTime).toMinutes()).put("app", o.metadata.dataOrigin.packageName))
        }
        val perms = JSONObject()
            .put("distance", can(DistanceRecord::class)).put("steps", can(StepsRecord::class))
            .put("hr", can(HeartRateRecord::class)).put("kcal", can(ActiveCaloriesBurnedRecord::class) || can(TotalCaloriesBurnedRecord::class))

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
            // הפסקות שהשעון רשם כמקטעי הפסקה/מנוחה
            try {
                val paused = s.segments.filter {
                    it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE ||
                        it.segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST
                }.sumOf { Duration.between(it.startTime, it.endTime).seconds }
                if (paused in 1 until totalSec) sec = totalSec - paused
            } catch (_: Exception) { }
            // זמן תנועה בפועל: רק הזמן שבו נרשמו צעדים או מרחק (כמו "משך אימון הכושר" ב-Samsung Health)
            var moving: List<Pair<Instant, Instant>> = emptyList()
            try {
                moving = movingIntervals(client, s, can(StepsRecord::class), can(DistanceRecord::class))
                val mv = moving.sumOf { Duration.between(it.first, it.second).seconds }
                w.put("dbgMove", mv)
                if (mv >= totalSec * 0.4 && mv < sec) sec = mv
            } catch (_: Exception) { }
            w.put("total", totalSec)
            try {
                // רק הנתונים שהאפליקציה שרשמה את האימון כתבה (Samsung Health), בלי צעדים מהטלפון או מאפליקציות אחרות
                val range = TimeRangeFilter.between(s.startTime, s.endTime)
                var a: AggregationResult = client.aggregate(AggregateRequest(metrics, range, setOf(s.metadata.dataOrigin)))
                if (a[DistanceRecord.DISTANCE_TOTAL] == null && a[StepsRecord.COUNT_TOTAL] == null)
                    a = client.aggregate(AggregateRequest(metrics, range, emptySet<DataOrigin>()))
                a[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { d ->
                    if (d.seconds in 60 until sec) sec = d.seconds   // זמן האימון בלי ההפסקות
                }
                var st = a[StepsRecord.COUNT_TOTAL] ?: 0L
                var km = a[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0
                try {
                    // צעדים ומרחק מכל המקורות (השעון לפעמים כותב את הצעדים בשם אחר): לוקחים את הגדול
                    val all = client.aggregate(AggregateRequest(setOfNotNull(
                        if (can(StepsRecord::class)) StepsRecord.COUNT_TOTAL else null,
                        if (can(DistanceRecord::class)) DistanceRecord.DISTANCE_TOTAL else null), range, emptySet<DataOrigin>()))
                    w.put("dbgSteps", "$st/${all[StepsRecord.COUNT_TOTAL] ?: 0}")
                    all[StepsRecord.COUNT_TOTAL]?.let { if (it > st) st = it }
                    all[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.let { if (km < 0.1 && it > km) km = it }
                } catch (_: Exception) { }
                if (st > 0) w.put("steps", st)
                if (km > 0) w.put("km", km)
                var hr: Double? = null
                if (can(HeartRateRecord::class) && moving.isNotEmpty()) try {
                    // דופק ממוצע רק בזמן התנועה (בלי ההפסקות)
                    val bpm = client.readRecords(ReadRecordsRequest(recordType = HeartRateRecord::class, timeRangeFilter = range)).records
                        .flatMap { it.samples }.filter { smp -> moving.any { smp.time >= it.first && smp.time <= it.second } }
                        .map { it.beatsPerMinute }
                    if (bpm.size >= 10) hr = bpm.average()
                } catch (_: Exception) { }
                (hr ?: a[HeartRateRecord.BPM_AVG]?.toDouble())?.let { w.put("hr", it) }
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

        return JSONObject().put("ok", true).put("walks", walks).put("steps", steps).put("found", sessions.size)
            .put("all", allSessions.size).put("others", others).put("perms", perms).toString()
    }

    /** הזמנים שבהם נרשמו צעדים או מרחק בתוך האימון (מאוחדים, בלי חפיפות). הפסקות נשארות בחוץ. */
    private suspend fun movingIntervals(client: HealthConnectClient, s: ExerciseSessionRecord, steps: Boolean, dist: Boolean): List<Pair<Instant, Instant>> {
        val range = TimeRangeFilter.between(s.startTime, s.endTime)
        val iv = mutableListOf<Pair<Instant, Instant>>()
        if (steps) client.readRecords(ReadRecordsRequest(recordType = StepsRecord::class, timeRangeFilter = range)).records
            .filter { it.count > 0 }.forEach { iv += it.startTime to it.endTime }
        if (dist) client.readRecords(ReadRecordsRequest(recordType = DistanceRecord::class, timeRangeFilter = range)).records
            .filter { it.distance.inMeters > 1 }.forEach { iv += it.startTime to it.endTime }
        // רשומה ארוכה אחת לכל האימון לא אומרת כלום על הפסקות
        val usable = iv.filter { Duration.between(it.first, it.second).seconds <= 600 }
        if (usable.size < 5) return emptyList()
        val sorted = usable.sortedBy { it.first }
        val out = mutableListOf<Pair<Instant, Instant>>()
        var cs = sorted[0].first; var ce = sorted[0].second
        for ((x, y) in sorted.drop(1)) {
            if (x <= ce) { if (y > ce) ce = y } else { out += cs to ce; cs = x; ce = y }
        }
        out += cs to ce
        return out
    }
}
