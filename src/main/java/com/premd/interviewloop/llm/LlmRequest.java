package com.premd.interviewloop.llm;

import java.util.List;
import java.util.Map;

/**
 * Normalised LLM request, assembled in cache-stable order by PromptAssembler.
 *
 * Layout (stable-to-volatile per §1.4):
 *   tools → rubric → persona → problem → messages → latest artifact
 */
public class LlmRequest {

    private String model;
    private List<Tool> tools;
    private List<Message> systemMessages;
    private List<Message> conversationMessages;
    private int maxTokens = 4096;
    private double temperature = 0.7;

    // -- Nested types --

    public static class Message {
        private String role;  // "system", "user", "assistant"
        private String content;
        private boolean cacheable;

        public Message() {}

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public Message(String role, String content, boolean cacheable) {
            this.role = role;
            this.content = content;
            this.cacheable = cacheable;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public boolean isCacheable() { return cacheable; }
        public void setCacheable(boolean cacheable) { this.cacheable = cacheable; }
    }

    public static class Tool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;

        public Tool() {}

        public Tool(String name, String description, Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getInputSchema() { return inputSchema; }
        public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
    }

    // -- Builder-style methods --

    public LlmRequest model(String model) { this.model = model; return this; }
    public LlmRequest tools(List<Tool> tools) { this.tools = tools; return this; }
    public LlmRequest systemMessages(List<Message> messages) { this.systemMessages = messages; return this; }
    public LlmRequest conversationMessages(List<Message> messages) { this.conversationMessages = messages; return this; }
    public LlmRequest maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
    public LlmRequest temperature(double temperature) { this.temperature = temperature; return this; }

    // -- Getters --

    public String getModel() { return model; }
    public List<Tool> getTools() { return tools; }
    public List<Message> getSystemMessages() { return systemMessages; }
    public List<Message> getConversationMessages() { return conversationMessages; }
    public int getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }
}
