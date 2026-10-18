package il.eliperez.fitness

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

/** המסך ש-Health Connect מציג כשלוחצים "למה האפליקציה צריכה את ההרשאות". */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val t = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            textSize = 17f
            gravity = Gravity.START
            text = """
                יומן כושר ביתי · פרטיות

                האפליקציה קוראת מ-Health Connect רק את הנתונים האלה:
                • אימוני הליכה: תאריך, שעה ומשך
                • מרחק, צעדים, דופק ממוצע וקלוריות בזמן ההליכה
                • סך הצעדים היומי

                הנתונים נשמרים בטלפון, ונשלחים רק לשרת הפרטי שלך (יומן הכושר) כדי לסנכרן עם המחשב.
                האפליקציה לא כותבת נתונים ל-Health Connect ולא משתפת אותם עם אף גורם אחר.

                אפשר לבטל את ההרשאות בכל רגע: הגדרות ← Health Connect ← הרשאות אפליקציה.

                מפתח האפליקציה · אלי פרץ
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(t) })
    }
}
