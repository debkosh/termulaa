package com.debkosh.termulaa.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.debkosh.termulaa.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The safety net under [PushService]: every 15 minutes (and once, soon,
 * when the service dies) it restarts the service and fetches anything
 * newer than the last message handled.
 */
class PushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = AppGraph.get(applicationContext)
        val store = graph.pushStore
        if (!store.enabled || !store.isSetUp) return@withContext Result.success()
        PushService.start(applicationContext)
        val since = store.lastId ?: return@withContext Result.success()
        try {
            val url = "${store.endpoint}/json?poll=1&since=$since"
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.success()
                resp.body?.string()?.lineSequence()?.filter { it.isNotBlank() }?.forEach {
                    Log.i(TAG, "poll: ${graph.pushInbox.handle(it)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "poll failed: ${e.message}")
        }
        Result.success()
    }

    companion object {
        private const val TAG = "termulaa.push"
        private const val PERIODIC = "push-periodic"
        private const val ONCE = "push-kick"
        private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PushWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }

        /** Once, about a minute from now. */
        fun kick(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PushWorker>().setInitialDelay(1, TimeUnit.MINUTES)
                    .setConstraints(online).build(),
            )
        }
    }
}

/** Brings the listening service back after a reboot and after an update installs. */
class PushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = AppGraph.get(context).pushStore
        if (!store.enabled || !store.isSetUp) return
        PushService.start(context)
        PushWorker.schedule(context)
    }
}
