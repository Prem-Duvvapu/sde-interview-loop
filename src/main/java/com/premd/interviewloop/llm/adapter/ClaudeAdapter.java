package com.premd.interviewloop.llm.adapter;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.premd.interviewloop.llm.*;
import com.premd.interviewloop.llm.Capabilities.PromptCachingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;

/**
 * Claude adapter — alternative interviewer provider.
 * Uses Anthropic's official Java SDK (com.anthropic:anthropic-java).
 *
 * Only registered as a Spring bean if ANTHROPIC_API_KEY is present.
 */
@Component
@ConditionalOnProperty(name = "ANTHROPIC_API_KEY")
public class ClaudeAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeAdapter.class);

    private final AnthropicClient client;

    public ClaudeAdapter() {
        this.client = AnthropicOkHttpClient.fromEnv();
        log.info("Claude adapter initialised");
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
        // Build system prompt from system messages
        StringBuilder systemPrompt = new StringBuilder();
        if (request.getSystemMessages() != null) {
            for (LlmRequest.Message msg : request.getSystemMessages()) {
                if (!systemPrompt.isEmpty()) systemPrompt.append("\n\n");
                systemPrompt.append(msg.getContent());
            }
        }

        // Build messages
        List<MessageParam> messages = new ArrayList<>();
        if (request.getConversationMessages() != null) {
            for (LlmRequest.Message msg : request.getConversationMessages()) {
                MessageParam.Role role = "assistant".equals(msg.getRole()) ?
                        MessageParam.Role.ASSISTANT : MessageParam.Role.USER;
                messages.add(MessageParam.builder()
                        .role(role)
                        .content(msg.getContent())
                        .build());
            }
        }

        // Ensure at least one user message
        if (messages.isEmpty()) {
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content("Begin.")
                    .build());
        }

        // Build the request
        String modelId = request.getModel() != null ? request.getModel() : "claude-sonnet-4-20250514";

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(request.getMaxTokens())
                .messages(messages);

        if (!systemPrompt.isEmpty()) {
            paramsBuilder.system(systemPrompt.toString());
        }

        // Add tools if specified
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<com.anthropic.models.messages.Tool> anthropicTools = new ArrayList<>();
            for (LlmRequest.Tool tool : request.getTools()) {
                anthropicTools.add(com.anthropic.models.messages.Tool.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .inputSchema(com.anthropic.models.messages.Tool.InputSchema.builder()
                                .properties(tool.getInputSchema() != null ?
                                        convertToJsonValue(tool.getInputSchema()) : Map.of())
                                .build())
                        .build());
            }
            paramsBuilder.tools(anthropicTools);
        }

        try {
            // Use non-streaming for now (streaming requires MessageStream handling)
            Message response = client.messages().create(paramsBuilder.build());

            // Process content blocks
            for (ContentBlock block : response.content()) {
                if (block.isText()) {
                    sink.next(LlmEvent.textDelta(block.text().get().text()));
                } else if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.toolUse().get();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = (Map<String, Object>) (Object) toolUse.input();
                    sink.next(LlmEvent.toolCall(toolUse.name(), toolUse.id(), args != null ? args : Map.of()));
                }
            }

            // Emit usage
            if (response.usage() != null) {
                Usage usage = response.usage();
                sink.next(LlmEvent.usage(new LlmEvent.Usage(
                        (int) usage.inputTokens(),
                        (int) usage.outputTokens(),
                        usage.cacheReadInputTokens().isPresent() ?
                                usage.cacheReadInputTokens().get().intValue() : 0,
                        usage.cacheCreationInputTokens().isPresent() ?
                                usage.cacheCreationInputTokens().get().intValue() : 0
                )));
            }

            sink.next(LlmEvent.done());
            sink.complete();

        } catch (Exception e) {
            log.error("Claude API call failed", e);
            sink.next(LlmEvent.error(e.getMessage()));
            sink.complete();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertToJsonValue(Map<String, Object> schema) {
        // Pass through — the Anthropic SDK accepts raw map structures
        return schema;
    }
}
