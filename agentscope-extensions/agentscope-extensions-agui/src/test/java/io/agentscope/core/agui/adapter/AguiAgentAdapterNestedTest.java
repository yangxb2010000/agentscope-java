/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for AguiAgentAdapter nested/agent-as-tool scenarios.
 */
class AguiAgentAdapterNestedTest {

    private Agent mockAgent;
    private AguiAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        mockAgent = mock(Agent.class);
        adapter = new AguiAgentAdapter(mockAgent, AguiAdapterConfig.defaultConfig());
    }

    @Test
    void testRunWithNestedAgentToolResult() {
        // Construct a ToolResultBlock that simulates a sub-agent execution trace
        // The sub-agent emits events which are serialized to JSON and wrapped in
        // TextBlocks

        // 1. Thinking
        ThinkingBlock innerThinking = ThinkingBlock.builder().thinking("I need data").build();
        Msg thinkingMsg = Msg.builder().role(MsgRole.ASSISTANT).content(innerThinking).build();
        Event thinkingEvent = new Event(EventType.REASONING, thinkingMsg, false);

        // 2. Inner Tool Call
        ToolUseBlock innerToolUse =
                ToolUseBlock.builder()
                        .id("inner-tc-1")
                        .name("inner_tool")
                        .input(Map.of("q", "query"))
                        .build();
        Msg toolUseMsg = Msg.builder().role(MsgRole.ASSISTANT).content(innerToolUse).build();
        Event toolUseEvent = new Event(EventType.REASONING, toolUseMsg, false);

        // 3. Inner Tool Result
        ToolResultBlock innerToolResult =
                ToolResultBlock.builder()
                        .id("inner-tc-1")
                        .output(TextBlock.builder().text("tool_output").build())
                        .build();
        Msg toolResultMsgInner = Msg.builder().role(MsgRole.TOOL).content(innerToolResult).build();
        Event toolResultEventInner = new Event(EventType.TOOL_RESULT, toolResultMsgInner, false);

        // 4. Final Text (not an event, just returned text)
        TextBlock innerText = TextBlock.builder().text("Final inner answer").build();

        // Helper to serialize event to TextBlock
        var codec = io.agentscope.core.util.JsonUtils.getJsonCodec();
        TextBlock t1 = TextBlock.builder().text(codec.toJson(thinkingEvent)).build();
        TextBlock t2 = TextBlock.builder().text(codec.toJson(toolUseEvent)).build();
        TextBlock t3 = TextBlock.builder().text(codec.toJson(toolResultEventInner)).build();

        // Outer Tool Result containing the trace (serialized events + final text)
        ToolResultBlock outerToolResult =
                ToolResultBlock.builder()
                        .id("outer-tc-1")
                        .output(List.of(t1, t2, t3, innerText))
                        .build();

        Msg toolResultMsg =
                Msg.builder().id("msg-tr1").role(MsgRole.TOOL).content(outerToolResult).build();

        Event toolResultEvent = new Event(EventType.TOOL_RESULT, toolResultMsg, true);

        when(mockAgent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(toolResultEvent));

        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("t1")
                        .runId("r1")
                        .messages(List.of(AguiMessage.userMessage("m1", "Go")))
                        .build();

        List<AguiEvent> events = adapter.run(input).collectList().block();
        assertNotNull(events);

        // Verify that we have the sequence of unpacked events

        // 1. Check for Thinking (as TextMessage)
        boolean hasThinking =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.TextMessageContent)
                        .map(e -> ((AguiEvent.TextMessageContent) e).delta())
                        .anyMatch(txt -> txt.contains("I need data"));
        assertTrue(hasThinking, "Should contain inner thinking content");

        // 2. Check for Inner Tool Call Start
        boolean hasInnerToolStart =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallStart)
                        .map(e -> (AguiEvent.ToolCallStart) e)
                        .anyMatch(tc -> "inner-tc-1".equals(tc.toolCallId()));
        assertTrue(hasInnerToolStart, "Should contain inner tool call start");

        // 3. Check for Inner Tool Result
        boolean hasInnerToolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .anyMatch(
                                tr ->
                                        "inner-tc-1".equals(tr.toolCallId())
                                                && tr.content().contains("tool_output"));
        assertTrue(hasInnerToolResult, "Should contain inner tool result");

        // 4. Check for Outer Tool Result (at the end)
        long toolResultCount =
                events.stream().filter(e -> e instanceof AguiEvent.ToolCallResult).count();
        // Should have at least 2 results (inner and outer)
        assertTrue(toolResultCount >= 2, "Should have multiple tool results (inner + outer)");

        // Verify Outer Tool Result contains the final text "Final inner answer"
        boolean hasOuterToolResult =
                events.stream()
                        .filter(e -> e instanceof AguiEvent.ToolCallResult)
                        .map(e -> (AguiEvent.ToolCallResult) e)
                        .anyMatch(
                                tr ->
                                        "outer-tc-1".equals(tr.toolCallId())
                                                && tr.content().contains("Final inner answer"));
        assertTrue(hasOuterToolResult, "Outer tool result should contain final text");
    }
}
