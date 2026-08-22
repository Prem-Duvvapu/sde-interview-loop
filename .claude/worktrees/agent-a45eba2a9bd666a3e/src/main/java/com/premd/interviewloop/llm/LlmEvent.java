package com.premd.interviewloop.llm;

import java.util.Map;

/**
 * A normalised event emitted during an LLM streaming response.
 * All provider adapters must emit events in this format.
 */
public class LlmEvent {

    public enum Type {
        /** A chunk of generated text. */
        TEXT_DELTA,
        /** A tool/function call from the model. */
        TOOL_CALL,
        /** Token usage statistics (typically at end of stream). */
        USAGE,
        /** Stream completed successfully. */
        DONE,
        /** An error occurred. */
        ERROR
    }

    private final Type type;
    private String textDelta;
    private ToolCall toolCall;
    private Usage usage;
    private String errorMessage;

    private LlmEvent(Type type) {
        this.type = type;
    }

    // -- Factory methods --

    public static LlmEvent textDelta(String delta) {
        LlmEvent e = new LlmEvent(Type.TEXT_DELTA);
        e.textDelta = delta;
        return e;
    }

    public static LlmEvent toolCall(String name, String id, Map<String, Object> arguments) {
        LlmEvent e = new LlmEvent(Type.TOOL_CALL);
        e.toolCall = new ToolCall(name, id, arguments);
        return e;
    }

    public static LlmEvent usage(Usage usage) {
        LlmEvent e = new LlmEvent(Type.USAGE);
        e.usage = usage;
        return e;
    }

    public static LlmEvent done() {
        return new LlmEvent(Type.DONE);
    }

    public static LlmEvent error(String message) {
        LlmEvent e = new LlmEvent(Type.ERROR);
        e.errorMessage = message;
        return e;
    }

    // -- Getters --

    public Type getType() { return type; }
    public String getTextDelta() { return textDelta; }
    public ToolCall getToolCall() { return toolCall; }
    public Usage getUsage() { return usage; }
    public String getErrorMessage() { return errorMessage; }

    // -- Nested types --

    public record ToolCall(String name, String id, Map<String, Object> arguments) {}

    public record Usage(
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheWriteTokens
    ) {}
}
