package com.premd.interviewloop.llm.adapter;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.llm.*;
import com.premd.interviewloop.llm.Capabilities.PromptCachingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;

/**
 * Claude adapter — alternative interviewer provider.
 * Uses Anthropic's official Java SDK (com.anthropic:anthropic-java).
 *
 * Built per-request by {@link ClaudeProviderFactory} from whatever key
 * {@link com.premd.interviewloop.llm.ProviderKeyStore} currently resolves for "anthropic".
 */
public class ClaudeAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicClient client;

    public ClaudeAdapter(String apiKey) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    @Override
    public String id() { return "anthropic"; }

    @Override
    public String displayName() { return "Claude"; }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, PromptCachingMode.EXPLICIT, true);
    }

    @Override
    public Flux<LlmEvent> stream(LlmRequest request) {
        return Flux.create(sink -> {
            try {
                streamInternal(request, sink);
            } catch (Exception e) {
                log.error("Claude streaming error", e);
                sink.next(LlmEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    private void streamInternal(LlmRequest request, FluxSink<LlmEvent> sink) {
        MessageCreateParams params = buildParams(request);

        // Real streaming: content_block_delta events as they arrive, not a single blocking
        // call dressed up as a stream. The interviewer capability floor requires streaming
        // and this adapter declares it, so it has to actually do it.
        Map<Long, StringBuilder> textByIndex = new HashMap<>();
        Map<Long, String[]> toolByIndex = new HashMap<>();       // [id, name]
        Map<Long, StringBuilder> toolJsonByIndex = new HashMap<>();
        long inputTokens = 0, cacheRead = 0, cacheCreation = 0, outputTokens = 0;

        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            for (RawMessageStreamEvent event : (Iterable<RawMessageStreamEvent>) stream.stream()::iterator) {
                if (event.isMessageStart()) {
                    Usage usage = event.asMessageStart().message().usage();
                    inputTokens = usage.inputTokens();
                    cacheRead = usage.cacheReadInputTokens().orElse(0L);
                    cacheCreation = usage.cacheCreationInputTokens().orElse(0L);
                } else if (event.isContentBlockStart()) {
                    RawContentBlockStartEvent start = event.asContentBlockStart();
                    long index = start.index();
                    start.contentBlock().toolUse().ifPresent(tu ->
                            toolByIndex.put(index, new String[]{tu.id(), tu.name()}));
                } else if (event.isContentBlockDelta()) {
                    RawContentBlockDeltaEvent delta = event.asContentBlockDelta();
                    long index = delta.index();
                    delta.delta().text().ifPresent(t ->
                            sink.next(LlmEvent.textDelta(t.text())));
                    delta.delta().inputJson().ifPresent(j ->
                            toolJsonByIndex.computeIfAbsent(index, i -> new StringBuilder()).append(j.partialJson()));
                } else if (event.isContentBlockStop()) {
                    long index = event.asContentBlockStop().index();
                    String[] tool = toolByIndex.get(index);
                    if (tool != null) {
                        StringBuilder json = toolJsonByIndex.get(index);
                        Map<String, Object> args = parseToolInput(json == null ? "" : json.toString());
                        sink.next(LlmEvent.toolCall(tool[1], tool[0], args));
                    }
                } else if (event.isMessageDelta()) {
                    outputTokens = event.asMessageDelta().usage().outputTokens();
                }
            }
        }

        sink.next(LlmEvent.usage(new LlmEvent.Usage(
                (int) inputTokens, (int) outputTokens, (int) cacheRead, (int) cacheCreation)));
        sink.next(LlmEvent.done());
        sink.complete();
    }

    private Map<String, Object> parseToolInput(String partialJson) {
        if (partialJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(partialJson, Map.class);
            return parsed;
        } catch (Exception e) {
            log.warn("Claude tool call arguments were not valid JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private MessageCreateParams buildParams(LlmRequest request) {
        StringBuilder systemPrompt = new StringBuilder();
        if (request.getSystemMessages() != null) {
            for (LlmRequest.Message msg : request.getSystemMessages()) {
                if (!systemPrompt.isEmpty()) systemPrompt.append("\n\n");
                systemPrompt.append(msg.getContent());
            }
        }

        List<MessageParam> messages = new ArrayList<>();
        if (request.getConversationMessages() != null) {
            for (LlmRequest.Message msg : request.getConversationMessages()) {
                MessageParam.Role role = "assistant".equals(msg.getRole()) ?
                        MessageParam.Role.ASSISTANT : MessageParam.Role.USER;
                messages.add(MessageParam.builder().role(role).content(msg.getContent()).build());
            }
        }
        if (messages.isEmpty()) {
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).content("Begin.").build());
        }

        String modelId = request.getModel() != null ? request.getModel() : "claude-opus-5";

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(request.getMaxTokens())
                .messages(messages);

        if (!systemPrompt.isEmpty()) {
            builder.system(systemPrompt.toString());
        }

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<ToolUnion> tools = new ArrayList<>();
            for (LlmRequest.Tool tool : request.getTools()) {
                tools.add(ToolUnion.ofTool(Tool.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .inputSchema(buildInputSchema(tool.getInputSchema()))
                        .build()));
            }
            builder.tools(tools);
        }

        return builder.build();
    }

    /**
     * Hands the JSON Schema over field-by-field rather than translating it into hand-built
     * {@code Schema} objects, which previously dropped {@code required} — exactly the
     * constraint that stops a model omitting a score or inventing a phase name.
     */
    private Tool.InputSchema buildInputSchema(Map<String, Object> schema) {
        Tool.InputSchema.Builder builder = Tool.InputSchema.builder().type(JsonValue.from("object"));
        if (schema == null) {
            return builder.build();
        }
        Object propsObj = schema.get("properties");
        if (propsObj instanceof Map<?, ?> propsMap) {
            Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                propsBuilder.putAdditionalProperty((String) entry.getKey(), JsonValue.from(entry.getValue()));
            }
            builder.properties(propsBuilder.build());
        }
        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            builder.required(requiredList.stream().map(String::valueOf).toList());
        }
        return builder.build();
    }
}
