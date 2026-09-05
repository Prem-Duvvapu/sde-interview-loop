package com.premd.interviewloop.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.premd.interviewloop.llm.*;
import com.premd.interviewloop.llm.Capabilities.PromptCachingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;

/**
 * OpenRouter adapter — reaches DeepSeek (and any other OpenRouter-hosted model) through
 * OpenRouter's own infrastructure, using an OpenRouter API key.
 *
 * This is a deliberate, documented exception to "every adapter uses the vendor's own SDK"
 * (see AGENTS.md's "Stack" section): OpenRouter is a third-party proxy, not DeepSeek's own
 * endpoint, chosen anyway because the owner wants DeepSeek access without a direct
 * DeepSeek key. Built with OpenAI's official Java SDK pointed at OpenRouter's base URL —
 * the same integration path OpenRouter's own docs recommend, since its API is
 * OpenAI-compatible.
 *
 * Built per-request by {@link OpenRouterProviderFactory} from whatever key
 * {@link com.premd.interviewloop.llm.ProviderKeyStore} currently resolves for "openrouter".
 */
public class OpenRouterAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL = "https://openrouter.ai/api/v1";

    private final OpenAIClient client;

    public OpenRouterAdapter(String apiKey) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(BASE_URL)
                // Attributes usage to this app in OpenRouter's own dashboard; purely
                // cosmetic, no functional effect if omitted.
                .putHeader("X-Title", "SDE Interview Loop")
                .build();
    }

    @Override
    public String id() { return "openrouter"; }

    @Override
    public String displayName() { return "OpenRouter"; }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, PromptCachingMode.AUTOMATIC, false);
    }

    @Override
    public Flux<LlmEvent> stream(LlmRequest request) {
        return Flux.create(sink -> {
            try {
                streamInternal(request, sink);
            } catch (Exception e) {
                log.error("OpenRouter streaming error", e);
                sink.next(LlmEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    private void streamInternal(LlmRequest request, FluxSink<LlmEvent> sink) {
        ChatCompletionCreateParams params = buildParams(request);

        // Real streaming: content deltas as they arrive, not a single blocking call
        // dressed up as a stream. The interviewer capability floor requires streaming
        // and this adapter declares it, so it has to actually do it.
        Map<Long, PartialToolCall> toolCallsByIndex = new LinkedHashMap<>();
        LlmEvent.Usage usage = null;

        try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
            for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) stream.stream()::iterator) {
                boolean finished = false;
                for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                    choice.delta().content().filter(t -> !t.isEmpty())
                            .ifPresent(t -> sink.next(LlmEvent.textDelta(t)));

                    choice.delta().toolCalls().ifPresent(calls -> accumulateToolCalls(calls, toolCallsByIndex));

                    if (choice.finishReason().isPresent()) {
                        finished = true;
                    }
                }
                if (finished) {
                    flushToolCalls(toolCallsByIndex, sink);
                }

                if (chunk.usage().isPresent()) {
                    var u = chunk.usage().get();
                    long cacheRead = u.promptTokensDetails()
                            .flatMap(com.openai.models.completions.CompletionUsage.PromptTokensDetails::cachedTokens)
                            .orElse(0L);
                    usage = new LlmEvent.Usage(
                            (int) u.promptTokens(), (int) u.completionTokens(),
                            (int) cacheRead,
                            // OpenRouter/DeepSeek prefix caching is automatic and reports no
                            // separate cache-write token count (unlike Anthropic's explicit
                            // breakpoints or Gemini's cached-content objects).
                            0);
                }
            }
        }

        // Safety net in case the provider never sent a finish_reason chunk.
        flushToolCalls(toolCallsByIndex, sink);

        if (usage != null) {
            sink.next(LlmEvent.usage(usage));
        }
        sink.next(LlmEvent.done());
        sink.complete();
    }

    private void accumulateToolCalls(
            List<ChatCompletionChunk.Choice.Delta.ToolCall> calls, Map<Long, PartialToolCall> byIndex) {
        for (ChatCompletionChunk.Choice.Delta.ToolCall call : calls) {
            PartialToolCall partial = byIndex.computeIfAbsent(call.index(), i -> new PartialToolCall());
            call.id().ifPresent(id -> partial.id = id);
            call.function().flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::name)
                    .ifPresent(name -> partial.name = name);
            call.function().flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::arguments)
                    .ifPresent(partial.arguments::append);
        }
    }

    private void flushToolCalls(Map<Long, PartialToolCall> byIndex, FluxSink<LlmEvent> sink) {
        if (byIndex.isEmpty()) {
            return;
        }
        for (PartialToolCall partial : byIndex.values()) {
            sink.next(LlmEvent.toolCall(
                    partial.name != null ? partial.name : "unknown",
                    partial.id != null ? partial.id : UUID.randomUUID().toString(),
                    parseArguments(partial.arguments.toString())));
        }
        byIndex.clear();
    }

    private Map<String, Object> parseArguments(String json) {
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(json, Map.class);
            return parsed;
        } catch (Exception e) {
            log.warn("OpenRouter tool call arguments were not valid JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private ChatCompletionCreateParams buildParams(LlmRequest request) {
        String modelId = request.getModel();
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("No model specified for OpenRouter request");
        }

        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(modelId)
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());

        String systemPrompt = joinSystemMessages(request);
        if (!systemPrompt.isEmpty()) {
            builder.addSystemMessage(systemPrompt);
        }

        boolean anyMessage = false;
        if (request.getConversationMessages() != null) {
            for (LlmRequest.Message msg : request.getConversationMessages()) {
                if ("assistant".equals(msg.getRole())) {
                    builder.addAssistantMessage(msg.getContent());
                } else {
                    builder.addUserMessage(msg.getContent());
                }
                anyMessage = true;
            }
        }
        if (!anyMessage) {
            builder.addUserMessage("Begin.");
        }

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            for (LlmRequest.Tool tool : request.getTools()) {
                builder.addFunctionTool(FunctionDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parameters(buildParameters(tool.getInputSchema()))
                        .build());
            }
        }

        return builder.build();
    }

    /**
     * Hands the JSON Schema over whole, same as the Gemini adapter — translating it into
     * hand-built objects field-by-field is how a schema silently loses `required`/`enum`.
     */
    private FunctionParameters buildParameters(Map<String, Object> schema) {
        FunctionParameters.Builder builder = FunctionParameters.builder();
        if (schema != null) {
            for (Map.Entry<String, Object> entry : schema.entrySet()) {
                builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }
        return builder.build();
    }

    private String joinSystemMessages(LlmRequest request) {
        if (request.getSystemMessages() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (LlmRequest.Message msg : request.getSystemMessages()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(msg.getContent());
        }
        return sb.toString();
    }

    private static final class PartialToolCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
