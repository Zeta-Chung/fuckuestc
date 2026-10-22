package edu.uestc.checkin.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebSettings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import edu.uestc.checkin.model.CheckinConfig
import edu.uestc.checkin.model.LocationPreset
import edu.uestc.checkin.model.LocationPresets
import edu.uestc.checkin.model.RunOutcome
import edu.uestc.checkin.run.CheckinRunner
import edu.uestc.checkin.run.PreferencesAttemptLedger
import edu.uestc.checkin.run.RunMode
import edu.uestc.checkin.schedule.DailyScheduler
import edu.uestc.checkin.storage.RunLogStore
import edu.uestc.checkin.storage.SecretStore
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private val pageBg = Color.rgb(243, 245, 249)
    private val cardColor = Color.WHITE
    private val textColor = Color.rgb(15, 23, 42)
    private val mutedColor = Color.rgb(100, 116, 139)
    private val primaryColor = Color.rgb(37, 99, 235)
    private val successColor = Color.rgb(22, 163, 74)
    private val dangerColor = Color.rgb(220, 38, 38)
    private val fieldColor = Color.rgb(248, 250, 252)
    private val strokeColor = Color.rgb(226, 232, 240)

    private lateinit var account: EditText
    private lateinit var password: EditText
    private lateinit var fingerprint: EditText
    private lateinit var latitude: EditText
    private lateinit var longitude: EditText
    private lateinit var address: EditText
    private lateinit var time: EditText
    private lateinit var zone: EditText
    private lateinit var userAgent: EditText
    private lateinit var accuracy: EditText
    private lateinit var duration: EditText
    private lateinit var network: EditText
    private lateinit var locationEnabled: EditText
    private lateinit var locationAuthorized: EditText
    private lateinit var locationAccuracy: EditText
    private lateinit var errorMessage: EditText
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var locationSummary: TextView
    private lateinit var xinyuanChip: TextView
    private lateinit var xiaoneiChip: TextView
    private lateinit var customToggle: TextView
    private lateinit var customBox: LinearLayout
    private lateinit var passwordToggle: TextView
    private var passwordVisible = false
    private var selectedPreset: LocationPreset? = null
    private var applyingPreset = false
    private var advancedExpanded = false
    private var popupShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildScreen()
        } catch (t: Throwable) {
            showStartupError(t)
        }
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageBg)
            setOnApplyWindowInsetsListener { view, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    top = 0
                    bottom = 0
                }
                view.setPadding(0, top, 0, bottom)
                insets
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        // window.insetsController 需要 decor 已创建，必须在 setContentView 之后调用。
        window.statusBarColor = pageBg
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        buildHeader(root)
        buildSummaryCard(root)
        buildAccountCard(root)
        buildLocationCard(root)
        buildTimeCard(root)
        buildAdvancedCard(root)
        buildActions(root)
        buildLinks(root)
        buildStatusCard(root)
        buildFooter(root)

        SecretStore(this).load()?.let { populate(it) }
        refreshStatus()
    }

    private fun showStartupError(t: Throwable) {
        val message = "启动失败，请把这段文字截图发给开发者：\n\n" + Log.getStackTraceString(t)
        val text = TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor(Color.rgb(127, 29, 29))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(text)
        })
    }

    override fun onResume() {
        super.onResume()
        FailureAlert.register(this)
        if (!popupShowing) {
            RunLogStore(this).latestUnacknowledgedFailure()?.let { result ->
                displayFailure(result)
            }
        }
    }

    override fun onPause() {
        FailureAlert.unregister(this)
        super.onPause()
    }

    fun displayFailure(result: edu.uestc.checkin.model.RunResult) {
        if (popupShowing) return
        popupShowing = true
        FailureAlert.showForeground(this, result) {
            RunLogStore(this).acknowledgeLatestFailure()
            popupShowing = false
        }
    }

    // ---------- 界面构建 ----------

    private fun buildHeader(root: LinearLayout) {
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply {
            text = "打卡助手"
            textSize = 24f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(linkText("使用说明") { showGuide() }, wrapWrap())
        titleRow.addView(linkText("常见问题") { showFaq() }, wrapWrap().apply { leftMargin = dp(4) })
        root.addView(titleRow, matchWrap(top = 4))
        root.addView(TextView(this).apply {
            text = "填好一次，之后每天自动登录并打卡；失败会及时提醒。"
            textSize = 13f
            setTextColor(mutedColor)
        }, matchWrap(top = 6, bottom = 14))
    }

    private fun buildSummaryCard(root: LinearLayout) {
        val card = card(root, "运行概览")
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(textColor)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        card.addView(summary)
    }

    private fun buildAccountCard(root: LinearLayout) {
        val card = card(root, "账号与验证")
        account = field(
            card, "账号", "学号 / 统一身份认证账号",
            helper = "与学校统一身份认证（IDAS）使用同一个账号。"
        )
        password = field(
            card, "密码", "登录密码",
            secret = true,
            helper = "仅保存在本机，使用 Android Keystore 加密。"
        )
        passwordToggle = linkText("显示密码") { togglePassword() }
        card.addView(passwordToggle, matchWrap(bottom = 4))
        fingerprint = field(
            card, "浏览器指纹", "MULTIFACTOR_BROWSER_FINGERPRINT 的值",
            secret = true,
            helper = "只复制 Cookie 的值，不要填整段 Cookie 或 SESSION。也可点下方“自动获取”。"
        )
        card.addView(linkText("自动获取：在应用内登录一次") {
            startActivityForResult(Intent(this, FingerprintCaptureActivity::class.java), REQUEST_FINGERPRINT)
        }, matchWrap(bottom = 4))
    }

    private fun buildLocationCard(root: LinearLayout) {
        val card = card(root, "打卡位置")
        card.addView(hintText("选择后会自动填入该位置的坐标和地址；也可以手动填写。"))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        xinyuanChip = chip("欣苑") { selectPreset(LocationPresets.XINYUAN) }
        xiaoneiChip = chip("校内") { selectPreset(LocationPresets.XIAONEI) }
        chips.addView(xinyuanChip, wrapWrap(right = 10))
        chips.addView(xiaoneiChip, wrapWrap())
        card.addView(chips, matchWrap(top = 10, bottom = 8))
        renderChips()

        locationSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(mutedColor)
            setLineSpacing(dp(4).toFloat(), 1f)
            text = "尚未选择位置"
        }
        card.addView(locationSummary, matchWrap(bottom = 4))

        customToggle = linkText("自定义坐标  ▸") { showCustom(customBox.visibility != View.VISIBLE) }
        card.addView(customToggle)
        customBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        card.addView(customBox)
        latitude = field(customBox, "纬度（GCJ-02）", "例如 30.751234")
        longitude = field(customBox, "经度（GCJ-02）", "例如 103.931234")
        address = field(customBox, "地址文字", "例如 四川省成都市……")
        watchManualEdit(latitude)
        watchManualEdit(longitude)
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!applyingPreset) updateLocationSummary()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun buildTimeCard(root: LinearLayout) {
        val card = card(root, "打卡时间")
        time = field(
            card, "每日打卡时间（HH:mm）", "例如 22:00", value = "22:00",
            helper = "允许时间 ${CheckinConfig.WINDOW_LABEL}，默认 22:00。"
        )
        zone = field(card, "时区", "Asia/Shanghai", value = "Asia/Shanghai", helper = "中国大陆一般保持默认。")
        card.addView(
            actionButton("选择时间", filled = false) { chooseTime() },
            wrapWrap(top = 2)
        )
    }

    private fun buildAdvancedCard(root: LinearLayout) {
        val card = cardWithoutTitle(root)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val header = TextView(this).apply {
            text = "高级参数（一般无需修改）  ▸"
            textSize = 17f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(6))
            setOnClickListener {
                advancedExpanded = !advancedExpanded
                text = if (advancedExpanded) "高级参数（一般无需修改）  ▾" else "高级参数（一般无需修改）  ▸"
                body.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            }
        }
        card.addView(header)
        card.addView(body)
        userAgent = field(
            body, "User-Agent", "自动填写", value = WebSettings.getDefaultUserAgent(this),
            helper = "打开应用时自动填入当前设备 WebView 的值，通常直接保留。"
        )
        accuracy = field(body, "accuracy", "30", value = "30", helper = "定位精度（米），一般保持默认。")
        duration = field(body, "locationDurationMs", "612", value = "612", helper = "定位耗时（毫秒），一般保持默认。")
        network = field(body, "networkTypeCode", "wifi", value = "wifi")
        locationEnabled = field(body, "locationEnabled（true 或 false）", "false", value = "false")
        locationAuthorized = field(body, "locationAuthorized（可留空）", "")
        locationAccuracy = field(body, "locationAccuracy（可留空）", "")
        errorMessage = field(body, "errorMessage（可留空）", "")
    }

    private fun buildActions(root: LinearLayout) {
        root.addView(actionButton("保存加密配置", filled = true) { saveConfig() }, matchWrap(top = 4, bottom = 10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            actionButton("只读检查", filled = false, tint = primaryColor) { execute(RunMode.READ_ONLY) },
            weightWrap(1f, right = 6)
        )
        row.addView(
            actionButton("立即打卡一次", filled = false, tint = primaryColor) { execute(RunMode.SUBMIT_ONCE) },
            weightWrap(1f, left = 6)
        )
        root.addView(row, matchWrap(bottom = 10))
        root.addView(actionButton("开启每日自动打卡", filled = true, tint = successColor) { enableSchedule() }, matchWrap(bottom = 8))
        root.addView(actionButton("关闭每日自动打卡", filled = false, tint = mutedColor) {
            DailyScheduler(this).cancel()
            setStatus("已关闭每日任务", textColor)
            refreshStatus()
        })
    }

    private fun buildLinks(root: LinearLayout) {
        val links = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        links.addView(linkText("打开可见 IDAS 对照页") {
            if (saveConfig()) startActivity(Intent(this, LoginProbeActivity::class.java))
        })
        links.addView(linkText("打开失败通知设置") {
            FailureAlert.notificationReady(this)
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, FailureAlert.CHANNEL_ID)
            })
        })
        if (Build.VERSION.SDK_INT >= 31) links.addView(linkText("打开精确闹钟权限设置") {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:$packageName")
            })
        })
        root.addView(links, matchWrap(top = 4, bottom = 4))
    }

    private fun showFaq() {
        val faqText = """
            1. 保存提示“打卡时间必须在 21:00 到 23:30 之间”：把时间改到范围内，时区保持 Asia/Shanghai，并让手机自动同步时间。
            2. 指纹格式无效或提示为空：只填 MULTIFACTOR_BROWSER_FINGERPRINT 的值，不要带分号、换行或整段 Cookie。
            3. 只读检查显示 NEEDS_HUMAN：按学校登录页提示完成人工验证，再重新只读检查；自动任务不会越过验证。
            4. 显示 OUTSIDE_WINDOW：当前不在学校允许时段，核对手机时间和学校页面上的时段。
            5. 显示 CONFIG_LOCATION_OUTSIDE：坐标不在允许范围，重新点“欣苑”或“校内”，或按 available-locations 重填 GCJ-02 坐标。
            6. 显示 ALREADY_ATTEMPTED 或“结果不确定”：当天已发过提交，先查学校当天记录，不要反复点“立即打卡一次”。
            7. 显示 POST_REJECTED：服务器拒绝请求，核对坐标、地址、User-Agent 等参数是否与学校页面一致。
            8. 显示 NETWORK_IO 或 LOCATIONS_UNKNOWN：网络或接口异常，切换网络并确认能打开学校页面后重试。
            9. 收不到失败通知：允许通知权限，确认“打卡失败提醒”渠道为高重要性并关闭免打扰；Android 13 及以上允许后需再点一次开启。
            10. 定时没有按点触发：允许精确闹钟权限；系统省电策略可能导致延迟，应用会显示“近似”调度。
            11. 保存失败“设备密钥不可用”：重启手机后重试；仍失败需清除应用数据重新配置。
            12. 每晚不固定时间收到学校或微信群发的“未打卡”提醒，但应用显示成功：这类通知可能是群发或滞后发送，先到学校打卡页面核对当天记录，确认 CHECKED_IN 后即可忽略。
            13. 换手机、重装或长时间未用后提示需要人工验证：清除应用数据或卸载重装，重新点“自动获取”抓取本机指纹，保存后再做只读检查。
        """.trimIndent()
        val content = TextView(this).apply {
            text = faqText
            textSize = 14f
            setTextColor(textColor)
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("常见问题")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun buildStatusCard(root: LinearLayout) {
        val card = card(root, "运行状态")
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(textColor)
            setLineSpacing(dp(5).toFloat(), 1f)
            text = "尚未运行"
        }
        card.addView(status)
    }

    private fun buildFooter(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "本程序仅供学习交流，请遵守学校相关规定。"
            textSize = 12f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
        }, matchWrap(top = 6))
    }

    private fun showGuide() {
        val steps = """
            1. 准备：本人账号、密码、浏览器指纹和打卡位置。
            2. 获取浏览器指纹（电脑上操作，Chrome 或 Edge 均可）：
               ① 用本人账号打开 https://jzsz.uestc.edu.cn，等页面跳到 IDAS 登录页（地址栏显示 idas.uestc.edu.cn）；
               ② 按 F12（或 Ctrl+Shift+I）打开开发者工具并保持打开；
               ③ 按页面提示完成登录和人工验证；
               ④ 顶部选“应用程序 / Application”，左侧依次展开“存储 / Storage → Cookie / Cookies → https://idas.uestc.edu.cn”；
               ⑤ 找到 MULTIFACTOR_BROWSER_FINGERPRINT，只复制该行的“值 / Value”；
               ⑥ 回到手机粘贴到“浏览器指纹”，不要填整段 Cookie，也不要填 SESSION。
               注意：列表里没有 IDAS 域时重新打开登录入口再看；没有这个 Cookie 就先完成学校要求的可信设备验证。换手机或清除浏览器数据后需要重新获取，不要使用同学的指纹。
               也可以点“浏览器指纹”下方的“自动获取”，在应用内登录一次，程序会自动填入。
            3. 在“账号与验证”里填账号、密码和上一步复制的指纹。
            4. 在“打卡位置”点“欣苑”或“校内”，坐标和地址会自动填好；都不是你的位置时展开“自定义坐标”手填。
            5. 在“打卡时间”确认时间在 ${CheckinConfig.WINDOW_LABEL} 之间，可点“选择时间”。
            6. 点“保存加密配置”，看到“配置已加密保存”。
            7. 点“只读检查”：只核对登录和当天记录，不会提交打卡。
            8. 需要提交时点一次“立即打卡一次”，再到学校页面核对记录。
            9. 点“开启每日自动打卡”；若弹出通知权限，允许后再点一次。
            10. 失败时按顶部提示处理；可在“打开失败通知设置”里允许横幅。
        """.trimIndent()
        val content = TextView(this).apply {
            text = steps
            textSize = 14f
            setTextColor(textColor)
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("使用说明")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------- 表单辅助 ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun wrapWrap(top: Int = 0, right: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); rightMargin = dp(right) }

    private fun weightWrap(weight: Float, left: Int = 0, right: Int = 0) = LinearLayout.LayoutParams(
        0, dp(48), weight
    ).apply { leftMargin = dp(left); rightMargin = dp(right) }

    private fun cardWithoutTitle(root: LinearLayout): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 16)
            setPadding(dp(18), dp(16), dp(18), dp(18))
            elevation = dp(2).toFloat()
        }
        root.addView(card, matchWrap(bottom = 14))
        return card
    }

    private fun card(root: LinearLayout, title: String): LinearLayout {
        val card = cardWithoutTitle(root)
        card.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        return card
    }

    private fun hintText(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 12f
        setTextColor(mutedColor)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun linkText(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(primaryColor)
        setPadding(0, dp(8), 0, dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        hint: String,
        value: String = "",
        helper: String? = null,
        secret: Boolean = false,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        }, matchWrap(top = 10))
        val edit = EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            setTextColor(textColor)
            setHintTextColor(Color.rgb(148, 163, 184))
            this.inputType = if (secret)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else inputType
            background = rounded(fieldColor, 10, strokeColor)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            minHeight = dp(46)
        }
        parent.addView(edit, matchWrap(top = 6))
        if (helper != null) parent.addView(hintText(helper), matchWrap(top = 4))
        return edit
    }

    private fun chip(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun chipBackground(selected: Boolean): GradientDrawable =
        if (selected) rounded(primaryColor, 20)
        else rounded(fieldColor, 20, strokeColor)

    private fun actionButton(
        label: String,
        filled: Boolean,
        tint: Int = primaryColor,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (filled) Color.WHITE else tint)
        background = if (filled) rounded(tint, 12) else rounded(Color.WHITE, 12, tint)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun togglePassword() {
        passwordVisible = !passwordVisible
        password.inputType = if (passwordVisible)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.setSelection(password.text.length)
        passwordToggle.text = if (passwordVisible) "隐藏密码" else "显示密码"
    }

    private fun chooseTime() {
        val initial = runCatching { LocalTime.parse(time.text.toString().trim()) }
            .getOrDefault(LocalTime.of(22, 0))
        TimePickerDialog(this, { _, hour, minute ->
            time.setText(String.format(Locale.CHINA, "%02d:%02d", hour, minute))
        }, initial.hour, initial.minute, true).show()
    }

    private fun showCustom(show: Boolean) {
        if (show) {
            customBox.visibility = View.VISIBLE
            customToggle.text = "自定义坐标  ▾"
        } else {
            customBox.visibility = View.GONE
            customToggle.text = "自定义坐标  ▸"
        }
    }

    private fun selectPreset(preset: LocationPreset) {
        if (!preset.configured) {
            setStatus("「${preset.label}」的坐标尚未配置，请先在「自定义坐标」中填写。", dangerColor)
            return
        }
        applyingPreset = true
        if (preset.address.isNotBlank()) address.setText(preset.address)
        latitude.setText(preset.latitude)
        longitude.setText(preset.longitude)
        applyingPreset = false
        selectedPreset = preset
        showCustom(false)
        updateLocationSummary()
        renderChips()
        setStatus("已选择位置：${preset.label}", successColor)
    }

    private fun watchManualEdit(edit: EditText) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (applyingPreset) return
                if (selectedPreset != null) {
                    selectedPreset = null
                    renderChips()
                }
                updateLocationSummary()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun syncLocationSelection() {
        selectedPreset = LocationPresets.find(latitude.text.toString(), longitude.text.toString())
        showCustom(selectedPreset == null)
        updateLocationSummary()
        renderChips()
    }

    private fun updateLocationSummary() {
        val preset = selectedPreset
        val text = when {
            preset != null -> "已选：${preset.label} · ${preset.latitude}, ${preset.longitude}"
            latitude.text.isNotBlank() && longitude.text.isNotBlank() ->
                "自定义坐标：${latitude.text}, ${longitude.text}"
            else -> "尚未选择位置"
        }
        val addressText = address.text.toString().trim()
        locationSummary.text = if (addressText.isNotBlank()) "$text\n地址：$addressText" else text
    }

    private fun renderChips() {
        listOf(xinyuanChip to LocationPresets.XINYUAN, xiaoneiChip to LocationPresets.XIAONEI).forEach { (view, preset) ->
            val selected = selectedPreset?.id == preset.id
            view.background = chipBackground(selected)
            view.setTextColor(if (selected) Color.WHITE else textColor)
            view.alpha = if (preset.configured) 1f else 0.55f
        }
    }

    // ---------- 配置读写与运行 ----------

    private fun populate(config: CheckinConfig) {
        account.setText(config.account)
        password.setText(config.password)
        fingerprint.setText(config.fingerprint)
        latitude.setText(config.latitude.toPlainString())
        longitude.setText(config.longitude.toPlainString())
        address.setText(config.address)
        time.setText(config.checkinTime.toString())
        zone.setText(config.zoneId.id)
        userAgent.setText(config.userAgent)
        accuracy.setText(config.accuracy.toString())
        duration.setText(config.locationDurationMs.toString())
        network.setText(config.networkTypeCode)
        locationEnabled.setText(config.locationEnabled.toString())
        locationAuthorized.setText(config.locationAuthorized)
        locationAccuracy.setText(config.locationAccuracy)
        errorMessage.setText(config.errorMessage)
        syncLocationSelection()
    }

    private fun setStatus(message: String, color: Int = textColor) {
        status.text = message
        status.setTextColor(color)
    }

    private fun currentConfig(): CheckinConfig? {
        val config = try {
            CheckinConfig(
                account.text.toString().trim(), password.text.toString(), fingerprint.text.toString().trim(),
                BigDecimal(latitude.text.toString().trim()), BigDecimal(longitude.text.toString().trim()),
                address.text.toString().trim(), LocalTime.parse(time.text.toString().trim()), ZoneId.of(zone.text.toString().trim()),
                userAgent.text.toString().trim(), accuracy.text.toString().toInt(), duration.text.toString().toLong(),
                network.text.toString().trim(), locationEnabled.text.toString().trim().toBooleanStrict(),
                locationAuthorized.text.toString(), locationAccuracy.text.toString(), errorMessage.text.toString()
            )
        } catch (_: Exception) {
            setStatus("配置有误：请检查必填项、坐标、时间（HH:mm）和数字格式", dangerColor)
            return null
        }
        val errors = config.validate()
        if (errors.isNotEmpty()) {
            setStatus(errors.joinToString("；"), dangerColor)
            return null
        }
        return config
    }

    private fun saveConfig(): Boolean {
        val config = currentConfig() ?: return false
        try {
            SecretStore(this).save(config)
        } catch (_: Exception) {
            setStatus("保存失败：设备密钥不可用", dangerColor)
            return false
        }
        val scheduled = try {
            val scheduler = DailyScheduler(this)
            if (scheduler.isEnabled()) scheduler.schedule(config) else null
        } catch (_: Exception) {
            setStatus("配置已保存；定时更新失败，请重新开启", dangerColor)
            refreshStatus()
            return true
        }
        setStatus(
            when {
                scheduled == null -> "配置已加密保存"
                scheduled.enabled -> "配置已保存；下次任务：${scheduled.nextAt}（${scheduled.reasonCode}）"
                else -> "配置已保存；定时已暂停：${scheduled.reasonCode}"
            },
            if (scheduled != null && !scheduled.enabled) dangerColor else successColor
        )
        refreshStatus()
        return true
    }

    private fun execute(mode: RunMode) {
        val config = currentConfig() ?: return
        setStatus(if (mode == RunMode.READ_ONLY) "正在只读检查…" else "正在执行单次打卡…", textColor)
        Thread {
            val result = CheckinRunner(PreferencesAttemptLedger(this)).runOnce(config, mode) { step ->
                runOnUiThread {
                    setStatus("${step.stage} · ${step.resultCode} · HTTP ${step.httpStatus ?: "—"}", textColor)
                }
            }
            RunLogStore(this).append(result)
            runOnUiThread {
                val failed = result.outcome == RunOutcome.FAILURE || result.outcome == RunOutcome.UNCERTAIN
                setStatus(
                    result.steps.joinToString("\n") { "${it.stage} · ${it.resultCode} · HTTP ${it.httpStatus ?: "—"}" } +
                        "\n最终：${result.outcome} · ${result.safeReasonCode}",
                    if (failed) dangerColor else successColor
                )
                refreshStatus()
                if (failed) displayFailure(result)
            }
        }.start()
    }

    private fun enableSchedule() {
        val config = currentConfig() ?: return
        if (!saveConfig()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            setStatus("请允许通知后再次开启定时", dangerColor)
            return
        }
        val scheduled = DailyScheduler(this).schedule(config)
        setStatus(
            if (scheduled.enabled) "每日任务已开启：${scheduled.nextAt}（${scheduled.reasonCode}）"
            else "无法开启定时：${scheduled.reasonCode}。请检查通知权限和通知渠道。",
            if (scheduled.enabled) successColor else dangerColor
        )
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FINGERPRINT || resultCode != RESULT_OK) return
        val value = data?.getStringExtra(FingerprintCaptureActivity.EXTRA_FINGERPRINT).orEmpty().trim()
        if (value.isEmpty()) return
        fingerprint.setText(value)
        Toast.makeText(this, "已填入浏览器指纹，请点“保存加密配置”", Toast.LENGTH_LONG).show()
    }

    private fun refreshStatus() {
        val recent = RunLogStore(this).recent().firstOrNull()
        val schedule = DailyScheduler(this).currentStatus()
        val mode = if (schedule.exact) "精确" else "近似"
        summary.text = "每日任务：${if (schedule.enabled) "开启（$mode）" else "关闭"}" +
            "\n下次时间：${schedule.nextAt?.format(DISPLAY_TIME) ?: "未安排"}" +
            "\n最近运行：${recent?.occurredAt?.atZone(ZoneId.of("Asia/Shanghai")) ?: "无"}" +
            "\n最近结果：${recent?.let { "${it.outcome} · ${it.safeReasonCode}" } ?: "无"}"
    }

    companion object {
        private const val REQUEST_FINGERPRINT = 1001
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}