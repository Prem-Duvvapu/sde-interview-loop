package com.premd.interviewloop.llm.adapter;

import com.premd.interviewloop.llm.ProviderFactory;
import com.premd.interviewloop.llm.LlmProvider;
import org.springframework.stereotype.Component;

@Component
public class GeminiProviderFactory implements ProviderFactory {

    @Override
    public String id() {
        return "google";
    }

    @Override
    public LlmProvider create(String apiKey) {
        return new GeminiAdapter(apiKey);
    }
}
