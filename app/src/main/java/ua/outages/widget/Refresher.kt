package ua.outages.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Refresher {
    private fun net() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES).setConstraints(net()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun refreshNow(c: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net()).build()
        WorkManager.getInstance(c).enqueueUniqueWork("now", ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun refresh(c: Context, a: Address): Cache {
        val root = DtekClient.withSession(c) { it.getHomeNum(a.city, a.street) }
        return applyRoot(c, a, root)
    }

    fun applyRoot(c: Context, a: Address, root: JSONObject): Cache {
        val group = ScheduleParser.groupOf(root, a.house)
            ?: throw IllegalStateException("Для адреси ${a.street}, ${a.house} не знайдено чергу відключень")
        val cache = ScheduleParser.parse(root, group)
        Prefs.saveCache(c, cache)
        Prefs.saveError(c, null)
        OutageWidget.updateAll(c)
        return cache
    }
}

class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val a = Prefs.address(applicationContext) ?: return Result.success()
        return try {
            Refresher.refresh(applicationContext, a)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Prefs.saveError(applicationContext, e.message ?: e.toString())
            OutageWidget.updateAll(applicationContext)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
