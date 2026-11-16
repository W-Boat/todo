package com.wboat.todo

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    private val now = ZonedDateTime.parse("2026-11-16T10:00:00+08:00[Asia/Shanghai]")

    @Test fun extractsTimePlaceAndReminder() {
        val task = Parser.parse("明天下午三点在会议室开会，提前十分钟提醒，备注带设计稿", now).single()
        assertEquals("开会", task.title)
        assertEquals("会议室", task.place)
        assertEquals("带设计稿", task.note)
        assertEquals(10, task.lead)
        assertEquals(now.plusDays(1).withHour(15).toInstant().toEpochMilli(), task.due)
    }

    @Test fun splitsAndDeduplicatesCommonTasks() {
        val tasks = Parser.parse("取快递，拿快递 然后喝水 买菜", now)
        assertEquals(listOf("取快递", "喝水", "买菜"), tasks.map(Task::title))
    }
}
