package ua.outages.widget

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var streets: Map<String, List<String>> = emptyMap()
    private var root: JSONObject? = null
    private var rootKey = ""

    private lateinit var city: AutoCompleteTextView
    private lateinit var street: AutoCompleteTextView
    private lateinit var house: AutoCompleteTextView
    private lateinit var save: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        city = findViewById(R.id.city)
        street = findViewById(R.id.street)
        house = findViewById(R.id.house)
        save = findViewById(R.id.save)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        Prefs.address(this)?.let {
            city.setText(it.city)
            street.setText(it.street)
            house.setText(it.house)
        }

        city.setOnItemClickListener { _, _, _, _ ->
            street.text.clear()
            house.text.clear()
            setStreetAdapter()
        }
        street.setOnItemClickListener { _, _, _, _ ->
            house.text.clear()
            loadHouses()
        }
        street.setOnFocusChangeListener { _, f -> if (f) setStreetAdapter() }
        house.setOnFocusChangeListener { _, f ->
            if (f) {
                if (rootKey != key()) loadHouses() else house.showDropDown()
            }
        }
        save.setOnClickListener { save() }
        loadCities()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun key() = city.text.toString().trim() + "|" + street.text.toString().trim()

    private fun busy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        save.isEnabled = !b
    }

    private fun adapter(list: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, list)

    private fun setStreetAdapter() {
        street.setAdapter(adapter(streets[city.text.toString().trim()] ?: emptyList()))
    }

    private fun loadCities(): Job = scope.launch {
        busy(true)
        status.text = "Завантажую список населених пунктів з сайту ДТЕК…"
        try {
            streets = DtekClient.withSession(this@MainActivity) { it.getStreets() }
            city.setAdapter(adapter(streets.keys.toList().sorted()))
            setStreetAdapter()
            status.text = "Список завантажено (${streets.size} населених пунктів)."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.text = "Помилка: ${e.message}"
        }
        busy(false)
    }

    private fun loadHouses(): Job = scope.launch {
        val c = city.text.toString().trim()
        val s = street.text.toString().trim()
        if (streets[c]?.contains(s) != true) return@launch
        busy(true)
        status.text = "Завантажую будинки…"
        try {
            val r = DtekClient.withSession(this@MainActivity) { it.getHomeNum(c, s) }
            root = r
            rootKey = "$c|$s"
            val hs = ScheduleParser.houses(r)
            house.setAdapter(adapter(hs))
            status.text = "Оберіть номер будинку (варіантів: ${hs.size})."
            house.requestFocus()
            house.showDropDown()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status.text = "Помилка: ${e.message}"
        }
        busy(false)
    }

    private fun save() {
        if (streets.isEmpty()) {
            loadCities()
            return
        }
        val a = Address(city.text.toString().trim(), street.text.toString().trim(), house.text.toString().trim())
        if (streets[a.city]?.contains(a.street) != true) {
            status.text = "Оберіть населений пункт і вулицю зі списку підказок."
            return
        }
        if (a.house.isEmpty()) {
            status.text = "Вкажіть номер будинку."
            return
        }
        scope.launch {
            busy(true)
            status.text = "Отримую графік…"
            try {
                val cached = root
                val r = if (cached != null && rootKey == "${a.city}|${a.street}") cached
                else DtekClient.withSession(this@MainActivity) { it.getHomeNum(a.city, a.street) }
                Prefs.saveAddress(this@MainActivity, a)
                val cache = Refresher.applyRoot(this@MainActivity, a, r)
                Refresher.schedule(this@MainActivity)
                val lines = OutageWidget.dayLines(cache)
                status.text = "Готово. Черга " + cache.group.removePrefix("GPV") + " (" + cache.note + ")\n" +
                    lines.first + "\n" + lines.second +
                    "\n\nТепер додайте віджет: довге натискання на робочому столі → Віджети → «Відключення світла»."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Prefs.saveError(this@MainActivity, e.message ?: e.toString())
                OutageWidget.updateAll(this@MainActivity)
                status.text = "Помилка: ${e.message}"
            }
            busy(false)
        }
    }
}
