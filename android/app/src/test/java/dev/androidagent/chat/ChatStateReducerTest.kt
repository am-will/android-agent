package dev.androidagent.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateReducerTest {
    @Test
    fun historyReplacesTimelineWithMessageRows() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.history")
            .put("sessionKey", "agent:main:main")
            .put("messages", JSONArray()
                .put(JSONObject().put("id", "u1").put("role", "user").put("text", "Hello"))
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "Hi there"))))

        assertEquals("agent:main:main", state.sessionKey)
        assertEquals(2, state.timeline.size)
        assertEquals("user", state.timeline[0].role)
        assertEquals("Hi there", state.timeline[1].text)
    }

    @Test
    fun deltasAppendThenFinalStopsRun() {
        val withDelta = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Hel"))
        val withMoreDelta = ChatStateReducer.reduce(withDelta, JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "lo"))
        val final = ChatStateReducer.reduce(withMoreDelta, JSONObject()
            .put("type", "chat.final")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("text", "Hello"))

        assertTrue(withMoreDelta.isRunning)
        assertEquals("Hello", withMoreDelta.timeline.single().text)
        assertFalse(final.isRunning)
        assertEquals("Hello", final.timeline.single().text)
        assertFalse(final.timeline.single().isStreaming)
    }

    @Test
    fun toolEventsUpsertAndKeepExpansionState() {
        val first = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "agent:main:main")
            .put("eventId", "tool1")
            .put("toolName", "exec")
            .put("title", "Running tests")
            .put("status", "running")
            .put("args", JSONObject().put("command", "npm test")))
        val expanded = ChatStateReducer.toggleTool(first, "tool1")
        val completed = ChatStateReducer.reduce(expanded, JSONObject()
            .put("type", "chat.tool_event")
            .put("sessionKey", "agent:main:main")
            .put("eventId", "tool1")
            .put("toolName", "exec")
            .put("title", "Tests passed")
            .put("status", "completed")
            .put("output", "ok"))

        assertEquals(1, completed.timeline.size)
        val tool = completed.timeline.single().toolEvent!!
        assertEquals("completed", tool.status)
        assertEquals("ok", tool.output)
        assertTrue(tool.isExpanded)
    }

    @Test
    fun reasoningDeltasStreamIntoTemporaryRowThenAssistantClearsIt() {
        val withReasoning = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.reasoning_delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Checking"))
        val withMoreReasoning = ChatStateReducer.reduce(withReasoning, JSONObject()
            .put("type", "chat.reasoning_delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", " files"))
        val withAssistant = ChatStateReducer.reduce(withMoreReasoning, JSONObject()
            .put("type", "chat.delta")
            .put("sessionKey", "agent:main:main")
            .put("runId", "run1")
            .put("delta", "Done"))

        val reasoning = withMoreReasoning.timeline.single()
        assertEquals(ChatTimelineKind.REASONING, reasoning.kind)
        assertEquals("Checking files", reasoning.text)
        assertTrue(reasoning.isStreaming)
        assertEquals(true, withMoreReasoning.reasoningStreamEnabled)

        val clearingReasoning = withAssistant.timeline.first { it.kind == ChatTimelineKind.REASONING }
        assertTrue(clearingReasoning.isClearing)
        assertFalse(clearingReasoning.isStreaming)
        assertEquals("Done", withAssistant.timeline.first { it.role == "assistant" }.text)
    }

    @Test
    fun stateAndSessionsTrackReasoningStream() {
        val fromState = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.state")
            .put("sessionKey", "agent:main:main")
            .put("reasoningStream", true))
        val fromSessions = ChatStateReducer.reduce(fromState, JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:main")
                .put("reasoningLevel", "off"))))

        assertEquals(true, fromState.reasoningStreamEnabled)
        assertEquals(false, fromSessions.reasoningStreamEnabled)
    }

    @Test
    fun sessionsUpdateUsageAndSelections() {
        val state = ChatStateReducer.reduce(ChatState(), JSONObject()
            .put("type", "chat.sessions")
            .put("selectedSessionKey", "agent:main:main")
            .put("sessions", JSONArray().put(JSONObject()
                .put("key", "agent:main:main")
                .put("model", "gpt-5.5")
                .put("thinkingLevel", "high")
                .put("totalTokens", 50)
                .put("contextTokens", 100))))

        assertEquals("gpt-5.5", state.selectedModel)
        assertEquals("high", state.reasoningEffort)
        assertEquals(50L, state.usage.totalTokens)
        assertEquals(0.5f, state.usage.contextRatio)
    }
}
