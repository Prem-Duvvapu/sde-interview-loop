package com.premd.interviewloop.content.csf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One question inside a {@link CsfTopic}: a rapid-fire prompt, what a solid answer
 * contains, and the probes that take a good answer one level deeper.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CsfQuestion {

    private String prompt;
    private List<String> expectedPoints;
    private List<String> probes;

    @JsonProperty("prompt")
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    @JsonProperty("expected_points")
    public List<String> getExpectedPoints() { return expectedPoints; }
    public void setExpectedPoints(List<String> expectedPoints) { this.expectedPoints = expectedPoints; }

    @JsonProperty("probes")
    public List<String> getProbes() { return probes; }
    public void setProbes(List<String> probes) { this.probes = probes; }
}
