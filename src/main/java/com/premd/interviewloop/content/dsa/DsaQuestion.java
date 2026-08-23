package com.premd.interviewloop.content.dsa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One DSA question, loaded from {@code question-bank/dsa/*.yaml}.
 *
 * <p>{@code interviewerNotes} is never shown to the candidate — it goes into the
 * cached system prompt so the interviewer model can judge approach quality and choose
 * good hints, the same way a human interviewer would come in with a rubric of their own.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DsaQuestion {

    private String slug;
    private String title;
    private String difficulty;
    private List<String> tags;
    private String statement;
    private List<String> constraints;
    private String example;

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

    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }

    public List<String> getConstraints() { return constraints; }
    public void setConstraints(List<String> constraints) { this.constraints = constraints; }

    public String getExample() { return example; }
    public void setExample(String example) { this.example = example; }

    public String getInterviewerNotes() { return interviewerNotes; }
    public void setInterviewerNotes(String interviewerNotes) { this.interviewerNotes = interviewerNotes; }
}
