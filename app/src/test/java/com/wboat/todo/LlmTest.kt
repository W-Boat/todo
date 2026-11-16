package com.wboat.todo

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LlmTest {
    @Test fun decodesMultipleTasksAndNormalizesAliases() {
        val tasks = Llm.decode(envelope("""
            {"tasks":[
                {"title":" 拿快递 ","due":"2026-11-17T15:30:00+08:00","place":"驿站",
                  "intent":"取","note":"带取件码","lead":10,"notify":true,
                  "strong":true,"vibration":true,"live":true,"done":true,"id":"server"},
                {"title":"取快递","due":"2026-11-17T07:30:00Z","place":"驿站"},
                {"title":"买菜","due":null,"notify":false}
            ]}
        """))
        assertEquals(listOf("取快递", "买菜"), tasks.map(Task::title))
        val pickup = tasks.first()
        assertEquals(Instant.parse("2026-11-17T07:30:00Z").toEpochMilli(), pickup.due)
        assertEquals("驿站", pickup.place)
        assertEquals("取", pickup.intent)
        assertEquals("带取件码", pickup.note)
        assertEquals(10, pickup.lead)
        assertTrue(pickup.strong && pickup.vibration && pickup.live && pickup.notify)
        assertFalse(pickup.done)
        assertNotEquals("server", pickup.id)
        assertNull(tasks.last().due)
        assertFalse(tasks.last().notify)
    }

    @Test fun acceptsEmptyTaskList() {
        assertTrue(Llm.decode(envelope("""{"tasks":[]}""")).isEmpty())
    }

    private fun envelope(content: String): String = JSONObject().put(
        "choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))
    ).toString()
}
