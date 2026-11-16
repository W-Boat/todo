package com.wboat.todo

import java.time.*
import java.util.Locale
import java.util.UUID

data class Task(
    val title: String,
    val due: Long? = null,
    val place: String = "",
    val intent: String = "",
    val note: String = "",
    val lead: Int = 0,
    val strong: Boolean = false,
    val vibration: Boolean = false,
    val live: Boolean = false,
    val notify: Boolean = true,
    val done: Boolean = false,
    val id: String = UUID.randomUUID().toString()
) {
    fun key() = listOf(title, due?.toString().orEmpty(), place).map {
        it.lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Punct}，。]+"), "")
    }
}

object Parser {
    private const val DIGITS = "零〇一二两三四五六七八九十"
    private const val N = "[0-9$DIGITS]{1,3}"
    private const val VERBS = "买|采购|购买|缴|交|取|拿|寄|发|回复|打电话|开会|会议|整理|写|提交|复习|预约|吃药|喝水|运动|跑步|接|还|看|提醒我"
    private const val DAYS = "大后天|后天|明天|今晚|今天|下周[一二三四五六日天]|(?:本?周)[一二三四五六日天]"
    private val separator = Regex("[，,；;。\\n]+|(?:然后|接着|另外|再去|再(?=(?:$VERBS)))|\\s+(?=(?:$DAYS|$VERBS))")
    private val date = Regex("(?:(\\d{4})[-/年])?(\\d{1,2})[-/月](\\d{1,2})日?|($DAYS)")
    private val time = Regex("(凌晨|早上|上午|中午|下午|晚上)?\\s*($N)(?:[:：]([0-9]{2})|点(半|一刻|三刻|$N)?分?)")
    private val relative = Regex("($N)\\s*(分钟|小时|天)后")
    private val note = Regex("(?:备注|附注|补充)[:：]?\\s*(.+)$")
    private val place = Regex("(?:地点[:：]\\s*([^\\s]+)|(?:在|去)([^\\s]{1,16}?)(?=(?:$VERBS)|\\s|$))")
    private val alias = mapOf(
        "取快递" to "拿快递,收快递,领快递",
        "缴电费" to "交电费,付电费",
        "喝水" to "饮水",
        "开会" to "参加会议,会议",
        "买菜" to "购买蔬菜,采购食材"
    ).flatMap { (canonical, words) -> words.split(",").map { it to canonical } }.toMap()

    fun parse(input: String, now: ZonedDateTime = ZonedDateTime.now()): List<Task> {
        val chunks = mutableListOf<String>()
        for (part in separator.split(input).map(String::trim).filter(String::isNotBlank)) {
            if (Regex("^(备注|附注|补充|提前|强提醒|震动提醒|振动提醒|实时活动|通知提醒|无需提醒|不提醒)").containsMatchIn(part)) {
                if (chunks.isNotEmpty()) chunks[chunks.lastIndex] += " $part"
                else chunks.add(part)
            } else chunks.add(part)
        }
        var inherited: Long? = null
        return chunks.mapNotNull { chunk ->
            val task = parseOne(chunk, now, inherited)
            if (task?.due != null) inherited = task.due
            task
        }.distinctBy(Task::key)
    }

    private fun parseOne(chunk: String, now: ZonedDateTime, inherited: Long?): Task? {
        var body = chunk
        fun take(regex: Regex): MatchResult? {
            val match = regex.find(body) ?: return null
            body = body.removeRange(match.range).trim()
            return match
        }
        val extra = take(note)?.groupValues?.get(1).orEmpty()
        val lead = take(Regex("提前\\s*($N)\\s*(分钟|小时|天)(?:提醒)?"))?.let {
            number(it.groupValues[1]) * when (it.groupValues[2]) { "天" -> 1440; "小时" -> 60; else -> 1 }
        } ?: 0
        val strong = take(Regex("强提醒")) != null
        val vibration = take(Regex("震动提醒|振动提醒")) != null
        val live = take(Regex("实时活动(?:提醒)?")) != null
        val notify = take(Regex("无需提醒|不提醒")) == null
        take(Regex("自动提醒|通知提醒|提醒我"))
        val rel = take(relative)
        val d = take(date)
        val t = take(time)
        val location = take(place)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank).orEmpty()
        val title = body.trim().ifEmpty { if (location.isNotEmpty()) "去$location" else return null }
        val due = when {
            rel != null -> {
                val count = number(rel.groupValues[1]).toLong()
                when (rel.groupValues[2]) {
                    "天" -> now.plusDays(count)
                    "小时" -> now.plusHours(count)
                    else -> now.plusMinutes(count)
                }.toInstant().toEpochMilli()
            }
            d != null || t != null -> runCatching {
                val day = d?.let { resolveDate(it, now.toLocalDate()) } ?: now.toLocalDate()
                val clock = t?.let { resolveTime(it, chunk) }
                    ?: if ("今晚" in chunk) LocalTime.of(20, 0) else LocalTime.of(9, 0)
                var result = day.atTime(clock).atZone(now.zone)
                if (d == null && !result.isAfter(now)) result = result.plusDays(1)
                result.toInstant().toEpochMilli()
            }.getOrNull()
            else -> inherited
        }
        val canonical = alias[title] ?: title
        val action = Regex("^(?:$VERBS)").find(canonical)?.value.orEmpty()
        return Task(canonical, due, location, action, extra, lead, strong, vibration, live, notify)
    }

    private fun resolveDate(match: MatchResult, today: LocalDate): LocalDate {
        val (year, month, day, word) = match.destructured
        if (month.isNotEmpty()) {
            val result = LocalDate.of(year.toIntOrNull() ?: today.year, month.toInt(), day.toInt())
            return if (year.isEmpty() && result.isBefore(today)) result.plusYears(1) else result
        }
        return when (word) {
            "今天", "今晚" -> today
            "明天" -> today.plusDays(1)
            "后天" -> today.plusDays(2)
            "大后天" -> today.plusDays(3)
            else -> {
                val index = "一二三四五六日".indexOf(word.last().let { if (it == '天') '日' else it }) + 1
                val delta = index - today.dayOfWeek.value
                today.plusDays((if (word.startsWith("下周") || delta < 0) delta + 7 else delta).toLong())
            }
        }
    }

    private fun resolveTime(match: MatchResult, original: String): LocalTime {
        val (period, hours, clockMinutes, spokenMinutes) = match.destructured
        var hour = number(hours)
        val effectivePeriod = period.ifEmpty { if ("今晚" in original) "晚上" else "" }
        if (effectivePeriod in listOf("中午", "下午", "晚上") && hour in 1..11) hour += 12
        if (effectivePeriod == "凌晨" && hour == 12) hour = 0
        val minutes = when (spokenMinutes) {
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> number(clockMinutes.ifEmpty { spokenMinutes })
        }
        return LocalTime.of(hour, minutes)
    }

    private fun number(value: String): Int {
        value.toIntOrNull()?.let { return it }
        fun digit(c: Char?) = when (c) { '两' -> 2; '〇', '零', null -> 0; else -> "一二三四五六七八九".indexOf(c) + 1 }
        return if ('十' in value) {
            val (before, after) = value.split('十', limit = 2)
            (if (before.isEmpty()) 1 else digit(before.last())) * 10 + digit(after.firstOrNull())
        } else value.fold(0) { n, c -> n * 10 + digit(c) }
    }
}
