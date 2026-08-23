package com.premd.interviewloop.llm.adapter;

import com.premd.interviewloop.llm.ProviderFactory;
import com.premd.interviewloop.llm.LlmProvider;
import org.springframework.stereotype.Component;

@Component
public class ClaudeProviderFactory implements ProviderFactory {

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public LlmProvider create(String apiKey) {
        return new ClaudeAdapter(apiKey);
    }
}
