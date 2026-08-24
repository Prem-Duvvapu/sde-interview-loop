package com.premd.interviewloop.content.java;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One Java/Spring deep-dive scenario, loaded from {@code question-bank/java_deep_dive/*.yaml}.
 *
 * <p>A scenario is a production-failure story: the candidate must diagnose it, and the
 * round then descends a depth ladder into the framework/JVM internals behind the bug
 * before asking what they'd trade off to fix it. Deliberately independent of the other
 * modules' question classes — same reasoning as {@code DsaQuestion} vs LLD's.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JavaScenario {

    private String slug;
    private String title;
    private String difficulty;
    private List<String> tags;

    @JsonProperty("scenario")
    private String scenarioText;

    private List<String> constraints;

    @JsonProperty("expected_diagnosis")
    private String expectedDiagnosis;

    /** Ordered deep-to-shallow or shallow-to-deep probes; rendered to the interviewer only. */
    private List<String> probes;

    @JsonProperty("trade_off_questions")
    private List<String> tradeOffQuestions;

    @JsonProperty("interviewer_notes")
    private String interviewerNotes;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getScenarioText() { return scenarioText; }
    public void setScenarioText(String scenarioText) { this.scenarioText = scenarioText; }

    public List<String> getConstraints() { return constraints; }
    public void setConstraints(List<String> constraints) { this.constraints = constraints; }

    public String getExpectedDiagnosis() { return expectedDiagnosis; }
    public void setExpectedDiagnosis(String expectedDiagnosis) { this.expectedDiagnosis = expectedDiagnosis; }

    public List<String> getProbes() { return probes; }
    public void setProbes(List<String> probes) { this.probes = probes; }

    public List<String> getTradeOffQuestions() { return tradeOffQuestions; }
    public void setTradeOffQuestions(List<String> tradeOffQuestions) { this.tradeOffQuestions = tradeOffQuestions; }

    public String getInterviewerNotes() { return interviewerNotes; }
    public void setInterviewerNotes(String interviewerNotes) { this.interviewerNotes = interviewerNotes; }
}
