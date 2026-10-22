package edu.uestc.checkin.ui

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import edu.uestc.checkin.model.RunResult
import edu.uestc.checkin.model.RunOutcome
import java.lang.ref.WeakReference

object FailureAlert {
    const val CHANNEL_ID = "checkin_failures_v1"
    @Volatile private var foregroundActivity: WeakReference<MainActivity>? = null

    fun register(activity: MainActivity) { foregroundActivity = WeakReference(activity) }

    fun unregister(activity: MainActivity) {
        if (foregroundActivity?.get() === activity) foregroundActivity = null
    }

    fun present(context: Context, result: RunResult) {
        Handler(Looper.getMainLooper()).post {
            val activity = foregroundActivity?.get()
            if (activity != null && !activity.isFinishing) activity.displayFailure(result)
            else postBackground(context, result)
        }
    }

    fun showForeground(activity: Activity, result: RunResult, onDismiss: () -> Unit = {}) {
        val dialog = Dialog(activity)
        dialog.setOnDismissListener { onDismiss() }
        val title = if (result.outcome == RunOutcome.UNCERTAIN) "打卡结果不确定" else "打卡失败"
        val explanation = explain(result.safeReasonCode)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.rgb(170, 28, 42))
            addView(TextView(activity).apply {
                text = "$title · ${result.safeStep}"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = "$explanation（${result.safeReasonCode}）"
                textSize = 15f
                setTextColor(Color.WHITE)
            })
            addView(Button(activity).apply { text = "关闭"; setOnClickListener { dialog.dismiss() } })
        }
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.TOP)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0f)
        }
    }

    fun notificationReady(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return manager.areNotificationsEnabled() && (manager.getNotificationChannel(CHANNEL_ID)?.importance ?: 0) >= NotificationManager.IMPORTANCE_HIGH
    }

    fun postBackground(context: Context, result: RunResult) {
        if (!notificationReady(context)) return
        val title = if (result.outcome == RunOutcome.UNCERTAIN) "自动打卡结果不确定" else "自动打卡失败"
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$title：${result.safeStep}")
            .setContentText(explain(result.safeReasonCode))
            .setStyle(Notification.BigTextStyle().bigText("失败步骤：${result.safeStep}；${explain(result.safeReasonCode)}（${result.safeReasonCode}）。点击查看记录。"))
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1001, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "打卡失败提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "自动打卡失败时在顶部显示提醒"
            })
        }
    }

    private fun explain(reasonCode: String): String = when (reasonCode) {
        "NEEDS_HUMAN" -> "登录需要人工验证"
        "SESSION_MISSING", "JZSZ_SESSION_MISSING" -> "登录后未取得打卡会话"
        "TODAY_UNKNOWN" -> "无法确认当天打卡状态"
        "SCHEDULE_UNKNOWN" -> "无法确认打卡时间范围"
        "OUTSIDE_WINDOW" -> "当前不在打卡时间范围内"
        "LOCATIONS_UNKNOWN" -> "无法读取允许打卡的位置"
        "CONFIG_LOCATION_OUTSIDE" -> "配置的位置不在允许范围内"
        "POST_REJECTED" -> "服务器拒绝了打卡请求"
        "POST_UNCONFIRMED" -> "请求已发出，服务端结果无法确认"
        "ALREADY_ATTEMPTED" -> "今天已有提交尝试，请核对学校记录"
        "NETWORK_IO" -> "网络连接失败"
        "WORKER_ERROR" -> "后台任务运行失败"
        "SCHEDULE_RECOVERY_FAILED" -> "无法恢复明天的自动任务，请重新开启每日打卡"
        "CONFIG_MISSING", "CONFIG_INVALID" -> "打卡配置缺失或无效"
        else -> "请查看失败步骤与原因码"
    }
}
