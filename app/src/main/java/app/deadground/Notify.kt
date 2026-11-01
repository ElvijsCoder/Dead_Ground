package app.deadground

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import java.time.LocalTime
import kotlin.concurrent.thread

/** Every ~30 min (Android decides exactly): refresh, check rules, notify. No extra libraries — plain JobScheduler. */
class AlertJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            runCatching { runAlerts(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val ID = 4100

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, AlertJob::class.java))
                    .setPeriodic(30 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true) // survives reboot
                    .build(),
            )
        }
    }
}

/**
 * Alerts follow the last CURRENT POS fix ("here"), never a saved AO.
 * The app/widget AO is refreshed too, so the widget stays fresh as before.
 */
fun runAlerts(ctx: Context) {
    val p = prefs(ctx)
    val ao = coords(ctx, "lat", "lon")
    ao?.let { runCatching { fetchAndStore(ctx, it.first, it.second) } }
    runCatching { saveWarnings(ctx, OpenMeteo.fetchWarnings()) }
    DeadGroundWidget.renderAll(ctx)

    val here = coords(ctx, "here_lat", "here_lon") ?: return
    val json = (if (here == ao) p.getString("json", null) else null)
        ?: runCatching { OpenMeteo.fetch(here.first, here.second) }.getOrNull() ?: return
    val aqi = runCatching { OpenMeteo.fetchAqi(here.first, here.second) }.getOrNull()
    val now = System.currentTimeMillis()
    val sent = loadSent(ctx).filterValues { now - it < 3 * 86_400_000L }
    val fire = gate(evaluate(OpenMeteo.parse(json), aqi, loadWarnings(ctx), now), sent, LocalTime.now().hour, now)
    fire.forEach { post(ctx, it) }
    saveSent(ctx, sent + fire.associate { it.key to now })
}

private fun channels(nm: NotificationManager) = nm.createNotificationChannels(
    listOf(
        NotificationChannel("rain", "Rain inbound", NotificationManager.IMPORTANCE_HIGH),
        NotificationChannel("type", "Precip type change", NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannel("ice", "Ice", NotificationManager.IMPORTANCE_HIGH),
        NotificationChannel("warn", "Official warnings", NotificationManager.IMPORTANCE_HIGH),
        NotificationChannel("air", "Air quality", NotificationManager.IMPORTANCE_DEFAULT),
    ),
)

/** Signal format: "■ PRIORITY" + first line collapsed; all lines + WXCON bar expanded. */
@SuppressLint("MissingPermission") // checked just below
fun post(ctx: Context, a: Alert) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val nm = ctx.getSystemService(NotificationManager::class.java)
    channels(nm)
    val id = a.key.hashCode()
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    fun openTab(tab: String, code: Int) = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, MainActivity::class.java).putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        flags,
    )
    val ack = PendingIntent.getBroadcast(ctx, id, Intent(ctx, AckReceiver::class.java).putExtra("id", id), flags)
    val n = Notification.Builder(ctx, a.channel)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("■ ${a.prec}")
        .setContentText(a.lines.first())
        .setStyle(Notification.BigTextStyle().bigText((a.lines + wxconBar(a.wxcon)).joinToString("\n")))
        .setColor(if (a.prec == Prec.PRIORITY) 0xFF9A9A9A.toInt() else 0xFFFF3B30.toInt())
        .setContentIntent(openTab("BRIEF", id))
        .setAutoCancel(true)
        .addAction(Notification.Action.Builder(null as Icon?, "RADAR", openTab("RADAR", id + 1)).build())
        .addAction(Notification.Action.Builder(null as Icon?, "ACK", ack).build())
        .build()
    nm.notify(id, n)
}

/** ACK = acknowledged: just clears the notification. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("id", 0))
    }
}
