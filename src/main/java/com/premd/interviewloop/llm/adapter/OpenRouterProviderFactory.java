package com.premd.interviewloop.llm.adapter;

import com.premd.interviewloop.llm.ProviderFactory;
import com.premd.interviewloop.llm.LlmProvider;
import org.springframework.stereotype.Component;

@Component
public class OpenRouterProviderFactory implements ProviderFactory {

    @Override
    public String id() {
        return "openrouter";
    }

    @Override
    public LlmProvider create(String apiKey) {
        return new OpenRouterAdapter(apiKey);
    }
}
