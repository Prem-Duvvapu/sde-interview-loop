package com.premd.interviewloop.llm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles LLM requests in cache-stable order per §1.4.
 *
 * Layout (stable-to-volatile):
 *   tools → rubric → persona → problem → messages → latest artifact
 *
 * Two rules this must not break:
 * 1. No timestamps or per-request IDs in the cached prefix.
 * 2. The code artifact goes AFTER the last cache breakpoint, never inside the system block.
 */
@Component
public class PromptAssembler {

    /**
     * Assemble a complete LLM request for an interview turn.
     *
     * @param model           Model ID to use
     * @param tools           Tool definitions (fixed per module)
     * @param rubric          Versioned rubric text for the module
     * @param persona         Module persona + company quirks
     * @param problemStatement The question statement
     * @param transcript      Rolling conversation transcript
     * @param latestArtifact  Current editor buffer / diagram graph (volatile, always last)
     * @param maxTokens       Max response tokens
     */
    public LlmRequest assemble(String model,
                                List<LlmRequest.Tool> tools,
                                String rubric,
                                String persona,
                                String problemStatement,
                                List<LlmRequest.Message> transcript,
                                String latestArtifact,
                                int maxTokens) {

        // System messages in cache-stable order
        List<LlmRequest.Message> systemMessages = new ArrayList<>();

        // 1. Rubric (versioned, rarely changes) — cacheable
        if (rubric != null && !rubric.isBlank()) {
            systemMessages.add(new LlmRequest.Message("system", rubric, true));
        }

        // 2. Persona + company quirks — cacheable
        if (persona != null && !persona.isBlank()) {
            systemMessages.add(new LlmRequest.Message("system", persona, true));
        }

        // 3. Problem statement — cacheable (last cache breakpoint)
        if (problemStatement != null && !problemStatement.isBlank()) {
            systemMessages.add(new LlmRequest.Message("system", problemStatement, true));
        }

        // Conversation messages (volatile)
        List<LlmRequest.Message> conversationMessages = new ArrayList<>();
        if (transcript != null) {
            conversationMessages.addAll(transcript);
        }

        // Latest artifact goes last — most volatile, after all cache breakpoints
        if (latestArtifact != null && !latestArtifact.isBlank()) {
            conversationMessages.add(new LlmRequest.Message("user",
                    "[Current code/artifact]\n" + latestArtifact, false));
        }

        return new LlmRequest()
                .model(model)
                .tools(tools)
                .systemMessages(systemMessages)
                .conversationMessages(conversationMessages)
                .maxTokens(maxTokens);
    }

    /**
     * Assemble a turn that carries a phase directive.
     *
     * <p>The directive is what the interviewer should be doing right now, given the round's
     * current phase, hint level and remaining time. It is volatile by nature, so it goes
     * <b>after</b> the transcript and before the artifact — never in the system block, which
     * would invalidate the cached prefix on every single turn.
     *
     * @param phaseDirective volatile per-turn instruction; may be null
     */
    public LlmRequest assemble(String model,
                               List<LlmRequest.Tool> tools,
                               String rubric,
                               String persona,
                               String problemStatement,
                               List<LlmRequest.Message> transcript,
                               String phaseDirective,
                               String latestArtifact,
                               int maxTokens) {
        return assemble(model, tools, rubric, persona, problemStatement, transcript,
                phaseDirective, latestArtifact, "Current code/artifact", maxTokens);
    }

    /**
     * As above, with a module-specific label for the artifact block. The label is pure
     * presentation — it tells the model what kind of surface it is reading (code buffer,
     * design graph) — and sits with the artifact after the last cache breakpoint.
     */
    public LlmRequest assemble(String model,
                               List<LlmRequest.Tool> tools,
                               String rubric,
                               String persona,
                               String problemStatement,
                               List<LlmRequest.Message> transcript,
                               String phaseDirective,
                               String latestArtifact,
                               String artifactLabel,
                               int maxTokens) {

        LlmRequest request = assemble(model, tools, rubric, persona, problemStatement,
                transcript, null, maxTokens);

        List<LlmRequest.Message> messages = new ArrayList<>(request.getConversationMessages());

        if (phaseDirective != null && !phaseDirective.isBlank()) {
            messages.add(new LlmRequest.Message("user",
                    "[Current phase directive]\n" + phaseDirective, false));
        }

        if (latestArtifact != null && !latestArtifact.isBlank()) {
            messages.add(new LlmRequest.Message("user",
                    "[" + artifactLabel + "]\n" + latestArtifact, false));
        }

        return request.conversationMessages(messages);
    }
}
