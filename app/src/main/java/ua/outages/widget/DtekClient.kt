package ua.outages.widget

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Єдине місце, яке знає, ЯК отримати дані з ДТЕК.
 * Сайт захищений WAF, тому ми відкриваємо його у прихованому WebView (як справжній браузер),
 * а запити до /ua/ajax виконуємо з JavaScript усередині сторінки.
 */
object DtekClient {
    const val PAGE = "https://www.dtek-krem.com.ua/ua/shutdowns"

    private const val READY_JS =
        "(function(){return !!document.querySelector('meta[name=\"ajaxUrl\" i]')" +
            "||!!document.querySelector('script[src*=\"discon-schedule\"]');})()"

    class Session(private val web: WebView) {
        @Volatile
        private var pending: CancellableContinuation<Pair<Int, String>>? = null

        @JavascriptInterface
        fun onResult(status: Int, body: String) {
            val p = pending
            pending = null
            if (p != null && p.isActive) p.resume(Pair(status, body))
        }

        private suspend fun eval(js: String): String =
            suspendCancellableCoroutine { c -> web.evaluateJavascript(js) { c.resume(it ?: "null") } }

        suspend fun open() {
            web.loadUrl(PAGE)
            val ready = withTimeoutOrNull(45_000) {
                while (eval(READY_JS) != "true") delay(700)
                true
            }
            if (ready == null) {
                throw IOException("Не вдалося відкрити сайт ДТЕК (можливо, спрацював захист від ботів). Спробуйте пізніше.")
            }
        }

        suspend fun post(body: String): JSONObject {
            val js = """
                (async function(){
                  try {
                    var m = document.querySelector('meta[name="ajaxUrl" i]');
                    var url = m ? m.content : '/ua/ajax';
                    var c = document.querySelector('meta[name="csrf-token" i]');
                    var h = {'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                             'X-Requested-With':'XMLHttpRequest',
                             'Accept':'application/json, text/javascript, */*; q=0.01'};
                    if (c) h['X-CSRF-Token'] = c.content;
                    var r = await fetch(url, {method:'POST', headers:h, body:${JSONObject.quote(body)}, credentials:'same-origin'});
                    var t = await r.text();
                    Android.onResult(r.status, t);
                  } catch(e) { Android.onResult(0, String(e)); }
                })();
            """.trimIndent()

            val res = withTimeoutOrNull(40_000) {
                suspendCancellableCoroutine<Pair<Int, String>> { c ->
                    pending = c
                    web.evaluateJavascript(js, null)
                }
            } ?: throw IOException("Сайт ДТЕК не відповів вчасно")

            val status = res.first
            val text = res.second
            if (status != 200) throw IOException("Сайт ДТЕК повернув код $status: " + text.take(120))
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("Неочікувана відповідь сайту: " + text.take(150))
            }
        }

        suspend fun getStreets(): Map<String, List<String>> {
            val root = post("method=getStreets")
            val s = root.optJSONObject("streets") ?: throw IOException("У відповіді немає списку вулиць")
            val out = LinkedHashMap<String, List<String>>()
            val keys = s.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = s.optJSONArray(k)
                out[k] = if (arr == null) emptyList() else (0 until arr.length()).map { idx -> arr.getString(idx) }
            }
            return out
        }

        suspend fun getHomeNum(city: String, street: String): JSONObject {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("Europe/Kiev")
            val stamp = fmt.format(Date())
            fun e(s: String) = URLEncoder.encode(s, "UTF-8")
            return post(
                "method=getHomeNum" +
                    "&data%5B0%5D%5Bname%5D=city&data%5B0%5D%5Bvalue%5D=" + e(city) +
                    "&data%5B1%5D%5Bname%5D=street&data%5B1%5D%5Bvalue%5D=" + e(street) +
                    "&data%5B2%5D%5Bname%5D=updateFact&data%5B2%5D%5Bvalue%5D=" + e(stamp)
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun <T> withSession(ctx: Context, block: suspend (Session) -> T): T =
        withContext(Dispatchers.Main) {
            val web = WebView(ctx.applicationContext)
            try {
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.userAgentString =
                    web.settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                web.webViewClient = WebViewClient()
                web.onResume()
                web.resumeTimers()
                val s = Session(web)
                web.addJavascriptInterface(s, "Android")
                s.open()
                block(s)
            } finally {
                web.stopLoading()
                web.destroy()
            }
        }
}
