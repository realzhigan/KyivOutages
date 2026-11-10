package ua.outages.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class OutageWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) m.updateAppWidget(id, build(c))
    }

    override fun onEnabled(c: Context) {
        Refresher.schedule(c)
        Refresher.refreshNow(c)
    }

    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == ACTION_REFRESH) Refresher.refreshNow(c)
    }

    companion object {
        const val ACTION_REFRESH = "ua.outages.widget.REFRESH"
        private val TZ = ScheduleParser.TZ
        private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(TZ)
        private val DHM = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(TZ)

        private fun t(ms: Long): String = HM.format(Instant.ofEpochMilli(ms))

        fun updateAll(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, OutageWidget::class.java))
            for (id in ids) m.updateAppWidget(id, build(c))
        }

        private fun dayLine(label: String, ivs: List<Interval>, ds: Long, de: Long): String {
            val parts = ivs.filter { it.end > ds && it.start < de }.map {
                val s = maxOf(it.start, ds)
                val e = minOf(it.end, de)
                t(s) + "–" + (if (e >= de) "24:00" else t(e)) + (if (it.maybe) "?" else "")
            }
            return label + ": " + (if (parts.isEmpty()) "без відключень" else parts.joinToString("  "))
        }

        fun dayLines(cache: Cache): Pair<String, String> {
            val d = LocalDate.now(TZ)
            val d0 = d.atStartOfDay(TZ).toInstant().toEpochMilli()
            val d1 = d.plusDays(1).atStartOfDay(TZ).toInstant().toEpochMilli()
            val d2 = d.plusDays(2).atStartOfDay(TZ).toInstant().toEpochMilli()
            return Pair(
                dayLine("Сьогодні", cache.intervals, d0, d1),
                dayLine("Завтра", cache.intervals, d1, d2)
            )
        }

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), flags)
            )
            v.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(
                    c, 1, Intent(c, OutageWidget::class.java).setAction(ACTION_REFRESH), flags
                )
            )

            val a = Prefs.address(c)
            val cache = Prefs.loadCache(c)
            val err = Prefs.error(c)

            if (a == null) {
                v.setTextViewText(R.id.title, "Відключення світла")
                v.setTextViewText(R.id.status, "Відкрийте застосунок і оберіть адресу")
                v.setTextViewText(R.id.today, "")
                v.setTextViewText(R.id.tomorrow, "")
                v.setTextViewText(R.id.footer, "")
                return v
            }
            v.setTextViewText(R.id.title, a.street + ", " + a.house)

            if (cache == null) {
                v.setTextViewText(R.id.status, if (err != null) "⚠ $err" else "Завантаження…")
                v.setTextViewText(R.id.today, "")
                v.setTextViewText(R.id.tomorrow, "")
                v.setTextViewText(R.id.footer, "Торкніться ↻ щоб оновити")
                return v
            }

            val now = System.currentTimeMillis()
            val cur = cache.intervals.firstOrNull { now >= it.start && now < it.end }
            val nxt = cache.intervals.firstOrNull { it.start > now }
            val status = when {
                cur != null && !cur.maybe -> "🔴 Світла немає до " + t(cur.end)
                cur != null -> "🟡 Можливе відключення до " + t(cur.end)
                nxt != null -> "🟢 Світло є · далі " + (if (nxt.maybe) "можливе " else "") + "відключення о " + t(nxt.start)
                else -> "🟢 Відключень не заплановано"
            }
            val lines = dayLines(cache)
            val upd = Instant.ofEpochMilli(cache.updated)
            val sameDay = upd.atZone(TZ).toLocalDate() == LocalDate.now(TZ)
            val updStr = (if (sameDay) HM else DHM).format(upd)

            v.setTextViewText(R.id.status, status)
            v.setTextViewText(R.id.today, lines.first)
            v.setTextViewText(R.id.tomorrow, lines.second)
            v.setTextViewText(
                R.id.footer,
                "Черга " + cache.group.removePrefix("GPV") + " · оновлено " + updStr +
                    (if (err != null) " · ⚠ помилка оновлення" else "")
            )
            return v
        }
    }
}
