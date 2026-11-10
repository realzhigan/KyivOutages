package ua.outages.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Address(val city: String, val street: String, val house: String)
data class Interval(val start: Long, val end: Long, val maybe: Boolean)
data class Cache(val group: String, val intervals: List<Interval>, val updated: Long, val note: String)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("outages", Context.MODE_PRIVATE)

    fun address(c: Context): Address? {
        val s = sp(c)
        val city = s.getString("city", null) ?: return null
        val street = s.getString("street", null) ?: return null
        val house = s.getString("house", null) ?: return null
        return Address(city, street, house)
    }

    fun saveAddress(c: Context, a: Address) {
        sp(c).edit()
            .putString("city", a.city).putString("street", a.street).putString("house", a.house)
            .remove("cache").remove("error")
            .apply()
    }

    fun saveCache(c: Context, g: Cache) {
        val arr = JSONArray()
        for (i in g.intervals) arr.put(JSONObject().put("s", i.start).put("e", i.end).put("m", i.maybe))
        val o = JSONObject().put("group", g.group).put("updated", g.updated).put("note", g.note).put("list", arr)
        sp(c).edit().putString("cache", o.toString()).apply()
    }

    fun loadCache(c: Context): Cache? {
        val raw = sp(c).getString("cache", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("list")
            val list = (0 until arr.length()).map {
                val x = arr.getJSONObject(it)
                Interval(x.getLong("s"), x.getLong("e"), x.getBoolean("m"))
            }
            Cache(o.getString("group"), list, o.getLong("updated"), o.optString("note"))
        } catch (e: Exception) {
            null
        }
    }

    fun saveError(c: Context, msg: String?) {
        val ed = sp(c).edit()
        if (msg == null) ed.remove("error") else ed.putString("error", msg)
        ed.apply()
    }

    fun error(c: Context): String? = sp(c).getString("error", null)
}
