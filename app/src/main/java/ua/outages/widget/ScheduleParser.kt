package ua.outages.widget

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Розбір відповіді getHomeNum сайту ДТЕК.
 * Формат (за описом відкритих клієнтів): data[будинок].sub_type_reason[0] = "GPV3.1";
 * fact.data[unix-день][група]["1".."24"] = yes|no|maybe|first|second|mfirst|msecond;
 * preset.data[група][день тижня 1..7]["1".."24"] = те саме.
 * Якщо ДТЕК змінить формат — правити треба лише цей файл і DtekClient.kt.
 */
object ScheduleParser {
    val TZ: ZoneId = ZoneId.of("Europe/Kiev")

    fun houses(root: JSONObject): List<String> {
        val d = root.optJSONObject("data") ?: return emptyList()
        val list = ArrayList<String>()
        val keys = d.keys()
        while (keys.hasNext()) list.add(keys.next())
        return list.sortedWith(
            compareBy<String>({ it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it })
        )
    }

    fun groupOf(root: JSONObject, house: String): String? {
        val h = root.optJSONObject("data")?.optJSONObject(house) ?: return null
        val arr = h.optJSONArray("sub_type_reason") ?: return null
        val g = if (arr.length() > 0) arr.optString(0) else ""
        return g.ifEmpty { null }
    }

    private fun label(i: Int) = if (i == 0) "сьогодні" else "завтра"

    private fun ms(days: List<LocalDate>, i: Int): Long {
        val end = days.size * 48
        val d = if (i >= end) days.last() else days[i / 48]
        val min = if (i >= end) 1440 else (i % 48) * 30
        return d.atStartOfDay().plusMinutes(min.toLong()).atZone(TZ).toInstant().toEpochMilli()
    }

    fun parse(root: JSONObject, group: String): Cache {
        if (root.has("result") && !root.optBoolean("result", true)) {
            throw IllegalStateException("Сайт повернув помилку: " + root.toString().take(200))
        }
        val today = LocalDate.now(TZ)
        val days = listOf(today, today.plusDays(1))
        val fact = root.optJSONObject("fact")?.optJSONObject("data")
        val preset = root.optJSONObject("preset")?.optJSONObject("data")?.optJSONObject(group)

        val slots = IntArray(days.size * 48) // 0 - світло є, 1 - відключення, 2 - можливе
        val notes = ArrayList<String>()
        var any = false

        for ((di, d) in days.withIndex()) {
            var hours: JSONObject? = null
            var src = ""
            if (fact != null) {
                val keys = fact.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val ts = k.toLongOrNull() ?: continue
                    if (Instant.ofEpochSecond(ts).atZone(TZ).toLocalDate() == d) {
                        hours = fact.optJSONObject(k)?.optJSONObject(group)
                        src = "факт"
                        break
                    }
                }
            }
            if (hours == null) {
                hours = preset?.optJSONObject(d.dayOfWeek.value.toString())
                if (hours != null) src = "типовий"
            }
            if (hours == null) {
                notes.add(label(di) + ": немає даних")
                continue
            }
            any = true
            notes.add(label(di) + ": " + src)
            for (h in 1..24) {
                val pair = when (hours.optString(h.toString(), "yes")) {
                    "no" -> Pair(1, 1)
                    "first" -> Pair(1, 0)
                    "second" -> Pair(0, 1)
                    "maybe" -> Pair(2, 2)
                    "mfirst" -> Pair(2, 0)
                    "msecond" -> Pair(0, 2)
                    else -> Pair(0, 0)
                }
                slots[di * 48 + (h - 1) * 2] = pair.first
                slots[di * 48 + (h - 1) * 2 + 1] = pair.second
            }
        }

        if (!any) {
            val keys = ArrayList<String>()
            val it = root.keys()
            while (it.hasNext()) keys.add(it.next())
            throw IllegalStateException("Не знайдено графік для черги $group. Поля відповіді: $keys")
        }

        val out = ArrayList<Interval>()
        var i = 0
        while (i < slots.size) {
            val st = slots[i]
            if (st == 0) { i++; continue }
            var j = i
            while (j < slots.size && slots[j] == st) j++
            out.add(Interval(ms(days, i), ms(days, j), st == 2))
            i = j
        }
        return Cache(group, out, System.currentTimeMillis(), notes.joinToString(", "))
    }
}
