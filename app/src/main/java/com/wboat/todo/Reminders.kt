package com.wboat.todo

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

internal fun Task.json() = JSONObject().apply {
    put("id", id); put("title", title); put("due", due ?: JSONObject.NULL)
    put("place", place); put("intent", intent); put("note", note); put("lead", lead)
    put("strong", strong); put("vibration", vibration); put("live", live)
    put("notify", notify); put("done", done)
}

internal fun JSONObject.task() = Task(
    title = getString("title"), due = if (isNull("due")) null else getLong("due"),
    place = optString("place"), intent = optString("intent"), note = optString("note"),
    lead = optInt("lead").coerceIn(0, 43200), strong = optBoolean("strong"),
    vibration = optBoolean("vibration"), live = optBoolean("live"),
    notify = optBoolean("notify", true), done = optBoolean("done"),
    id = optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
)

object Tasks {
    private fun prefs(context: Context) = context.getSharedPreferences("todo", Context.MODE_PRIVATE)
    fun read(context: Context): List<Task> {
        val array = JSONArray(prefs(context).getString("tasks", "[]") ?: "[]")
        return List(array.length()) { array.getJSONObject(it).task() }
    }

    fun save(context: Context, items: List<Task>) {
        val previous = read(context).associateBy(Task::id)
        prefs(context).edit().putString("tasks", JSONArray(items.map(Task::json)).toString()).apply()
        val next = items.associateBy(Task::id)
        previous.values.filter { next[it.id] != it }.forEach { Reminders.cancel(context, it) }
        items.filter { previous[it.id] != it }.forEach { Reminders.schedule(context, it) }
    }
}

object Reminders {
    private fun alarm(context: Context) = context.getSystemService(AlarmManager::class.java)
    fun exact(context: Context) = alarm(context).canScheduleExactAlarms()
    private fun pending(context: Context, id: String, phase: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).apply {
            action = "com.wboat.todo.REMIND"
            data = Uri.Builder().scheme("todo").authority("alarm").appendPath(id).appendPath(phase).build()
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context, task: Task) {
        val due = task.due ?: return
        if (task.done || !task.notify) return
        val now = System.currentTimeMillis()
        if (due <= now) return
        val early = due - task.lead * 60_000L
        for ((phase, at) in listOf("due" to due, "early" to early)) {
            if (phase == "early") {
                if (task.lead == 0 || deliveredEarly(context, task)) continue
                if (at <= now) {
                    if (task.live) show(context, task, phase)
                    continue
                }
            }
            val intent = pending(context, task.id, phase)
            val manager = alarm(context)
            if (manager.canScheduleExactAlarms()) {
                try {
                    if (task.strong && phase == "due") {
                        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(context)), intent)
                    } else {
                        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                    }
                    continue
                } catch (_: SecurityException) {
                    // The permission may have been revoked since the preceding check.
                }
            }
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun delivery(context: Context) =
        context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)

    private fun deliveredEarly(context: Context, task: Task) =
        delivery(context).getLong(task.id, Long.MIN_VALUE) == task.due

    fun cancel(context: Context, task: Task) {
        delivery(context).edit().remove(task.id).apply()
        for (phase in listOf("due", "early")) {
            alarm(context).cancel(pending(context, task.id, phase))
            context.getSystemService(NotificationManager::class.java).cancel(task.id + phase, 0)
        }
    }

    fun status(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = if (manager.areNotificationsEnabled()) "通知已开启" else "通知已关闭"
        val exactness = if (exact(context)) "精确提醒已开启" else "使用非精确提醒"
        val muted = listOf("normal" to "待办提醒", "strong" to "强提醒", "vibration" to "震动提醒")
            .filter { (id, _) -> manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
            .joinToString("、") { it.second }
        return listOf(notifications, exactness, if (muted.isEmpty()) "" else "已关闭渠道 $muted")
            .filter(String::isNotBlank).joinToString(" · ")
    }

    fun show(context: Context, task: Task, phase: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (task.strong) "strong" else if (task.vibration) "vibration" else "normal"
        manager.createNotificationChannel(NotificationChannel(
            channel, when (channel) { "strong" -> "强提醒"; "vibration" -> "震动提醒"; else -> "待办提醒" },
            if (task.strong) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        ).apply { enableVibration(task.vibration || task.strong) })
        if (phase == "early" && deliveredEarly(context, task)) return false
        if (phase == "due") {
            manager.cancel(task.id + "early", 0)
            delivery(context).edit().remove(task.id).apply()
        }
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(task.title)
            .setContentText(listOf(if (phase == "early") "即将到期" else "现在到期", task.place, task.note)
                .filter(String::isNotBlank).joinToString(" · "))
            .setContentIntent(openApp(context))
            .setCategory(if (task.strong && phase == "due") Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
        if (task.live && phase == "early") {
            if (Build.VERSION.SDK_INT >= 36) notification.setRequestPromotedOngoing(true)
            notification.setAutoCancel(false)
            notification.setWhen(task.due ?: System.currentTimeMillis())
                .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true).setOngoing(true)
        }
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        manager.notify(task.id + phase, 0, notification.build())
        if (phase == "early" && task.due != null) {
            delivery(context).edit().putLong(task.id, task.due).apply()
        }
        return true
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tasks = Tasks.read(context)
        if (intent.action != "com.wboat.todo.REMIND") {
            tasks.forEach { Reminders.schedule(context, it) }
            return
        }
        val path = intent.data?.pathSegments ?: return
        val id = path.getOrNull(0) ?: return
        val phase = path.getOrNull(1) ?: return
        val task = tasks.firstOrNull { it.id == id && it.notify && !it.done } ?: return
        if (phase == "early" && (task.due ?: 0) <= System.currentTimeMillis()) return
        Reminders.show(context, task, phase)
    }
}

internal fun Task.day() = due?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
