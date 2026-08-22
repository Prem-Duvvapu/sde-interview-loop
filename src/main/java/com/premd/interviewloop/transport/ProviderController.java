package com.premd.interviewloop.transport;

import com.premd.interviewloop.llm.LlmProvider;
import com.premd.interviewloop.llm.ProviderRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    private final ProviderRegistry providerRegistry;

    public ProviderController(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    @GetMapping
    public List<Map<String, Object>> listProviders() {
        return providerRegistry.getAllProviders().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @GetMapping("/interviewers")
    public List<Map<String, Object>> listInterviewerProviders() {
        return providerRegistry.getInterviewerProviders().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @GetMapping("/evaluators")
    public List<Map<String, Object>> listEvaluatorProviders() {
        return providerRegistry.getEvaluatorProviders().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toSummary(LlmProvider provider) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", provider.id());
        summary.put("displayName", provider.displayName());
        summary.put("capabilities", Map.of(
                "streaming", provider.capabilities().streaming(),
                "toolUse", provider.capabilities().toolUse(),
                "promptCaching", provider.capabilities().promptCaching().name(),
                "vision", provider.capabilities().vision()
        ));
        summary.put("meetsInterviewerFloor", provider.capabilities().meetsInterviewerFloor());
        summary.put("meetsEvaluatorFloor", provider.capabilities().meetsEvaluatorFloor());
        return summary;
    }
}
