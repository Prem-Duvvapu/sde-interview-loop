package com.premd.interviewloop.llm.adapter;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
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
        GenerateContentConfig config = buildConfig(request);
        List<Content> contents = buildContents(request);
        String modelId = request.getModel();
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("No model specified for Gemini request");
        }

        LlmEvent.Usage usage = null;

        // Real streaming, not a single blocking call dressed up as a stream: this adapter
        // declares streaming = true, and the interviewer capability floor requires it.
        try (ResponseStream<GenerateContentResponse> stream =
                     client.models.generateContentStream(modelId, contents, config)) {

            for (GenerateContentResponse chunk : stream) {
                for (Part part : partsOf(chunk)) {
                    part.text().filter(t -> !t.isEmpty())
                            .ifPresent(t -> sink.next(LlmEvent.textDelta(t)));

                    part.functionCall().ifPresent(fc -> sink.next(LlmEvent.toolCall(
                            fc.name().orElse("unknown"),
                            fc.id().orElseGet(() -> UUID.randomUUID().toString()),
                            new LinkedHashMap<>(fc.args().orElse(Map.of())))));
                }

                // Usage arrives on the trailing chunks and is cumulative, so the last one wins.
                LlmEvent.Usage chunkUsage = usageOf(chunk);
                if (chunkUsage != null) {
                    usage = chunkUsage;
                }
            }
        }

        if (usage != null) {
            sink.next(LlmEvent.usage(usage));
        }
        sink.next(LlmEvent.done());
        sink.complete();
    }

    private List<Part> partsOf(GenerateContentResponse chunk) {
        return chunk.candidates()
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .flatMap(Candidate::content)
                .flatMap(Content::parts)
                .orElse(List.of());
    }

    private LlmEvent.Usage usageOf(GenerateContentResponse chunk) {
        return chunk.usageMetadata()
                .map(um -> new LlmEvent.Usage(
                        um.promptTokenCount().orElse(0),
                        um.candidatesTokenCount().orElse(0),
                        um.cachedContentTokenCount().orElse(0),
                        // Gemini bills cache creation through its cached-content objects rather
                        // than reporting cache-write tokens on the response.
                        0))
                .orElse(null);
    }

    private List<Content> buildContents(LlmRequest request) {
        List<Content> contents = new ArrayList<>();
        if (request.getConversationMessages() != null) {
            for (LlmRequest.Message msg : request.getConversationMessages()) {
                // Gemini has no "system" role inside contents; system text is hoisted into
                // systemInstruction, so anything left here is either candidate or interviewer.
                String role = "assistant".equals(msg.getRole()) ? "model" : "user";
                contents.add(Content.builder()
                        .role(role)
                        .parts(List.of(Part.fromText(msg.getContent())))
                        .build());
            }
        }
        if (contents.isEmpty()) {
            contents.add(Content.builder()
                    .role("user")
                    .parts(List.of(Part.fromText("Begin.")))
                    .build());
        }
        return contents;
    }

    private GenerateContentConfig buildConfig(LlmRequest request) {
        GenerateContentConfig.Builder config = GenerateContentConfig.builder()
                .candidateCount(1)
                .maxOutputTokens(request.getMaxTokens())
                .temperature((float) request.getTemperature());

        String systemInstruction = joinSystemMessages(request);
        if (!systemInstruction.isEmpty()) {
            config.systemInstruction(Content.builder()
                    .parts(List.of(Part.fromText(systemInstruction)))
                    .build());
        }

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<FunctionDeclaration> declarations = new ArrayList<>();
            for (LlmRequest.Tool tool : request.getTools()) {
                FunctionDeclaration.Builder declaration = FunctionDeclaration.builder()
                        .name(tool.getName())
                        .description(tool.getDescription());
                if (tool.getInputSchema() != null) {
                    // Hand the JSON Schema over whole. Translating it into Schema objects by hand
                    // silently dropped `required` and `enum`, which is how a model ends up
                    // omitting a score or inventing a phase name.
                    declaration.parametersJsonSchema(tool.getInputSchema());
                }
                declarations.add(declaration.build());
            }
            config.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
        }

        return config.build();
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
}
