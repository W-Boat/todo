package com.wboat.todo

import android.Manifest
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.checkbox.Checkbox
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CouiTheme(darkTheme = isSystemInDarkTheme()) { Screen(this) } }
    }

    override fun onResume() {
        super.onResume()
        Tasks.read(this).forEach { Reminders.schedule(this, it) }
    }

    fun notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    fun exactSettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    fun calendar(task: Task) {
        val due = task.due ?: return
        startActivity(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, task.title)
            putExtra(CalendarContract.Events.EVENT_LOCATION, task.place)
            putExtra(CalendarContract.Events.DESCRIPTION, task.note)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, due)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, due + 30 * 60_000L)
        })
    }
}

private val ink = staticCompositionLocalOf { Color(0xFF192C23) }
private val paper = staticCompositionLocalOf { Color.White }
private val canvas = staticCompositionLocalOf { Color(0xFFF0F4EF) }
private val format = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@Composable
private fun Screen(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences("model", Context.MODE_PRIVATE) }
    var tasks by remember { mutableStateOf(Tasks.read(activity)) }
    var draft by remember { mutableStateOf<List<Task>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var view by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(prefs.getBoolean("offline", false)) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions").orEmpty()) }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini").orEmpty()) }
    var key by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    fun save(next: List<Task>) {
        Tasks.save(activity, next)
        tasks = next
    }

    CompositionLocalProvider(
        ink provides if (dark) Color(0xFFE4EEE7) else Color(0xFF192C23),
        paper provides if (dark) Color(0xFF23312B) else Color.White,
        canvas provides if (dark) Color(0xFF121B17) else Color(0xFFF0F4EF)
    ) {
        LazyColumn(Modifier.fillMaxSize().background(canvas.current).safeDrawingPadding().imePadding(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label("交汇", 38, true)
                    Action(if (settings) "收起设置" else "设置") { settings = !settings }
                }
                Label(tasks.count { !it.done }.toString() + " 项待办", 14)
            }
            if (settings) item {
                Panel {
                    SegmentedControl(labels = listOf("模型解析", "离线解析"), selectedIndex = if (offline) 1 else 0,
                        onSelected = { offline = it == 1; prefs.edit().putBoolean("offline", offline).apply() })
                    if (!offline) {
                        Label("模型服务会收到本次输入。密钥只保留在当前会话。", 13)
                        Field(endpoint, { endpoint = it }, "Chat Completions HTTPS 地址", single = true)
                        Field(model, { model = it }, "模型名称", single = true)
                        Field(key, { key = it }, "API 密钥", single = true, password = true)
                        Action("保存接口和模型") {
                            prefs.edit().putString("endpoint", endpoint).putString("model", model).apply()
                            message = "设置已保存"
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("通知权限") { activity.notificationSettings() }
                        Action("精确提醒权限") { activity.exactSettings() }
                    }
                }
            }
            item {
                Panel {
                    Label("记下待办", 21, true)
                    Field(input, { input = it; draft = emptyList() },
                        "明天下午三点在会议室开会，提前十分钟提醒，然后去驿站取快递")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action(if (busy) "识别中…" else "识别待办", filled = true, enabled = input.isNotBlank() && !busy) {
                            val source = input
                            val local = offline
                            val url = endpoint
                            val chosen = model
                            val token = key
                            busy = true
                            draft = emptyList()
                            message = ""
                            scope.launch {
                                try {
                                    val result = if (local) Parser.parse(source) else Llm.parse(url, chosen, token, source)
                                    if (input == source) {
                                        draft = result.map { if (it.live && it.lead == 0) it.copy(lead = 15) else it }
                                        message = if (draft.isEmpty()) "未识别到待办" else "已识别 " + draft.size + " 项"
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = e.message?.takeIf { it.startsWith("模型接口响应") || it.startsWith("请填写") }
                                        ?: "识别失败，请检查网络和模型设置"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        if (draft.isNotEmpty()) Action("保存待办") {
                            val existing = tasks.filterNot(Task::done).map(Task::key).toSet()
                            val fresh = draft.filter { it.title.isNotBlank() && it.key() !in existing }.distinctBy(Task::key)
                            save(tasks + fresh)
                            message = "已保存 " + fresh.size + " 项，跳过 " + (draft.size - fresh.size) + " 项"
                            if (fresh.any { it.notify && it.due != null } && Build.VERSION.SDK_INT >= 33 &&
                                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                activity.notificationSettings()
                            }
                            input = ""
                            draft = emptyList()
                        }
                    }
                    if (message.isNotBlank()) Label(message, 13)
                }
            }
            items(draft, key = { "draft-" + it.id }) { task ->
                Panel {
                    Field(task.title, { title -> draft = draft.map { if (it.id == task.id) it.copy(title = title) else it } },
                        "待办标题", single = true)
                    Details(task)
                    Action("移除") { draft = draft.filterNot { it.id == task.id } }
                }
            }
            item {
                SegmentedControl(labels = listOf("简洁", "日历", "便签", "流线"), selectedIndex = view, onSelected = { view = it })
            }
            if (view == 1) item {
                MonthGrid(month, day, tasks.mapNotNull(Task::day).toSet(), {
                    month = month.plusMonths(it.toLong())
                    day = month.atDay(1)
                }, { day = it })
            }
            val visible = when (view) {
                1 -> tasks.filter { it.day() == day }
                3 -> tasks.sortedBy { it.due ?: Long.MAX_VALUE }
                else -> tasks.sortedBy(Task::done)
            }
            if (visible.isEmpty()) item { Panel { Label(if (view == 1) "当天没有待办" else "还没有待办", 15) } }
            items(visible, key = Task::id) { task ->
                var expanded by remember { mutableStateOf(false) }
                if (view == 3) Label(task.day()?.toString() ?: "未安排", 13, true)
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(48.dp).clickable(onClickLabel = if (task.done) "重新打开" else "完成") {
                            save(tasks.map { if (it.id == task.id) task.copy(done = !task.done) else it })
                        }, contentAlignment = Alignment.Center) { Checkbox(checked = task.done) }
                        Column(Modifier.weight(1f).clickable { expanded = !expanded }) {
                            Label(task.title, if (view == 2) 22 else 17, true)
                            Summary(task)
                        }
                    }
                    if (view == 2 && task.note.isNotBlank() && !expanded) Label(task.note, 15)
                    if (expanded) {
                        Details(task, includeSummary = false)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (task.due != null) Action("添加到系统日历") {
                                runCatching { activity.calendar(task) }.onFailure { message = "设备上没有可用的日历应用" }
                            }
                            Action("删除") { save(tasks.filterNot { it.id == task.id }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, day: LocalDate, marked: Set<LocalDate>, move: (Int) -> Unit, select: (LocalDate) -> Unit) {
    val offset = month.atDay(1).dayOfWeek.value - 1
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Action("上月") { move(-1) }
            Label(month.toString(), 17, true)
            Action("下月") { move(1) }
        }
        Row(Modifier.fillMaxWidth()) {
            "一二三四五六日".forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Label(it.toString(), 13) } }
        }
        repeat((month.lengthOfMonth() + offset + 6) / 7) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val number = week * 7 + column - offset + 1
                    val cell = Modifier.weight(1f).height(42.dp)
                    if (number !in 1..month.lengthOfMonth()) Spacer(cell)
                    else {
                        val date = month.atDay(number)
                        Box(cell.background(if (date == day) canvas.current else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { select(date) }, contentAlignment = Alignment.Center) {
                            Label(number.toString() + if (date in marked) "·" else "", 15, date == day)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(task: Task) {
    val time = task.due?.let { format.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
    val values = listOfNotNull(time, task.place).filter(String::isNotBlank)
    if (values.isNotEmpty()) Label(values.joinToString(" · "), 13)
}

@Composable
private fun Details(task: Task, includeSummary: Boolean = true) {
    if (includeSummary) Summary(task)
    if (task.intent.isNotBlank()) Label(task.intent, 14)
    if (task.note.isNotBlank()) Label(task.note, 14)
    if (task.due != null && task.notify) {
        val modes = listOfNotNull(
            "通知", "强提醒".takeIf { task.strong }, "震动".takeIf { task.vibration },
            "倒计时".takeIf { task.live }, ("提前 " + task.lead + " 分钟").takeIf { task.lead > 0 }
        )
        Label(modes.joinToString(" · "), 13)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(paper.current, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Label(text: String, size: Int, bold: Boolean = false, color: Color = ink.current) {
    BasicText(text, style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}

@Composable
private fun Action(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (filled && enabled) Color(0xFF315F50) else canvas.current
    Box(Modifier.background(color, RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Label(text, 14, true, if (filled && enabled) Color.White else ink.current.copy(alpha = if (enabled) 1f else .45f))
    }
}

@Composable
private fun Field(value: String, change: (String) -> Unit, placeholder: String, single: Boolean = false, password: Boolean = false) {
    BasicTextField(value, change, Modifier.fillMaxWidth(), textStyle = TextStyle(color = ink.current, fontSize = 16.sp),
        cursorBrush = SolidColor(ink.current), singleLine = single, minLines = if (single) 1 else 3,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(Modifier.background(canvas.current, RoundedCornerShape(14.dp)).padding(13.dp)) {
                if (value.isEmpty()) Label(placeholder, 15, color = ink.current.copy(alpha = .55f))
                inner()
            }
        })
}
