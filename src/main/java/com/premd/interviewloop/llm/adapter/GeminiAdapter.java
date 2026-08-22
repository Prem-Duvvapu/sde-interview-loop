package com.premd.interviewloop.llm.adapter;

import com.google.genai.Client;
import com.google.genai.types.*;
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
 * Gemini adapter — the default provider.
 * Uses Google's official Java SDK (com.google.genai:google-genai).
 *
 * Only registered as a Spring bean if GEMINI_API_KEY is present.
 */
@Component
@ConditionalOnProperty(name = "GEMINI_API_KEY")
public class GeminiAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiAdapter.class);

    private final Client client;

    public GeminiAdapter() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GOOGLE_API_KEY");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        log.info("Gemini adapter initialised");
    }

    @Override
    public String id() { return "google"; }

    @Override
    public String displayName() { return "Gemini"; }

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
                log.error("Gemini streaming error", e);
                sink.next(LlmEvent.error(e.getMessage()));
                sink.complete();
            }
        });
    }

    private void streamInternal(LlmRequest request, FluxSink<LlmEvent> sink) {
        // Build system instruction from system messages
        StringBuilder systemInstruction = new StringBuilder();
        if (request.getSystemMessages() != null) {
            for (LlmRequest.Message msg : request.getSystemMessages()) {
                if (!systemInstruction.isEmpty()) systemInstruction.append("\n\n");
                systemInstruction.append(msg.getContent());
            }
        }

        // Build conversation contents
        List<Content> contents = new ArrayList<>();
        if (request.getConversationMessages() != null) {
            for (LlmRequest.Message msg : request.getConversationMessages()) {
                String role = "user".equals(msg.getRole()) ? "user" : "model";
                contents.add(Content.builder()
                        .role(role)
                        .addPart(Part.builder().text(msg.getContent()).build())
                        .build());
            }
        }

        // If no conversation messages, add a minimal user message
        if (contents.isEmpty()) {
            contents.add(Content.builder()
                    .role("user")
                    .addPart(Part.builder().text("Begin.").build())
                    .build());
        }

        // Build tool declarations if any
        List<Tool> tools = new ArrayList<>();
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<FunctionDeclaration> funcDecls = new ArrayList<>();
            for (LlmRequest.Tool tool : request.getTools()) {
                FunctionDeclaration.Builder funcBuilder = FunctionDeclaration.builder()
                        .name(tool.getName())
                        .description(tool.getDescription());
                if (tool.getInputSchema() != null) {
                    funcBuilder.parameters(Schema.builder()
                            .type("OBJECT")
                            .properties(convertSchemaProperties(tool.getInputSchema()))
                            .build());
                }
                funcDecls.add(funcBuilder.build());
            }
            tools.add(Tool.builder().functionDeclarations(funcDecls).build());
        }

        // Build generate config
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                .candidateCount(1)
                .maxOutputTokens(request.getMaxTokens())
                .temperature((float) request.getTemperature());

        if (!systemInstruction.isEmpty()) {
            configBuilder.systemInstruction(Content.builder()
                    .addPart(Part.builder().text(systemInstruction.toString()).build())
                    .build());
        }

        if (!tools.isEmpty()) {
            configBuilder.tools(tools);
        }

        String modelId = request.getModel() != null ? request.getModel() : "gemini-2.0-flash";

        // Use streaming
        try {
            GenerateContentResponse response = client.models.generateContent(
                    modelId,
                    contents,
                    configBuilder.build());

            // Process the response
            if (response.candidates() != null && !response.candidates().isEmpty()) {
                Candidate candidate = response.candidates().get(0);
                if (candidate.content() != null && candidate.content().parts() != null) {
                    for (Part part : candidate.content().parts()) {
                        if (part.text() != null) {
                            sink.next(LlmEvent.textDelta(part.text()));
                        }
                        if (part.functionCall() != null) {
                            FunctionCall fc = part.functionCall();
                            Map<String, Object> args = fc.args() != null ?
                                    new LinkedHashMap<>(fc.args()) : Map.of();
                            sink.next(LlmEvent.toolCall(fc.name(), UUID.randomUUID().toString(), args));
                        }
                    }
                }
            }

            // Emit usage if available
            if (response.usageMetadata() != null) {
                UsageMetadata um = response.usageMetadata();
                sink.next(LlmEvent.usage(new LlmEvent.Usage(
                        um.promptTokenCount() != null ? um.promptTokenCount() : 0,
                        um.candidatesTokenCount() != null ? um.candidatesTokenCount() : 0,
                        um.cachedContentTokenCount() != null ? um.cachedContentTokenCount() : 0,
                        0  // Gemini doesn't report cache write tokens separately
                )));
            }

            sink.next(LlmEvent.done());
            sink.complete();

        } catch (Exception e) {
            log.error("Gemini API call failed", e);
            sink.next(LlmEvent.error(e.getMessage()));
            sink.complete();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Schema> convertSchemaProperties(Map<String, Object> inputSchema) {
        Map<String, Schema> properties = new LinkedHashMap<>();
        Object props = inputSchema.get("properties");
        if (props instanceof Map<?, ?> propsMap) {
            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                String key = entry.getKey().toString();
                if (entry.getValue() instanceof Map<?, ?> propDef) {
                    String type = propDef.getOrDefault("type", "STRING").toString().toUpperCase();
                    String desc = propDef.getOrDefault("description", "").toString();
                    properties.put(key, Schema.builder()
                            .type(type)
                            .description(desc)
                            .build());
                }
            }
        }
        return properties;
    }
}
