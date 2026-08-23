package com.premd.interviewloop.content.lld;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One LLD question, loaded from {@code question-bank/lld/*.yaml}.
 *
 * <p>Deliberately independent of {@link com.premd.interviewloop.content.dsa.DsaQuestion} even
 * though the shape is near-identical — PROJECT_PLAN.md §4 calls out Phases 2-5 as genuinely
 * independent, separate-package work by design, so each module's content stays free of a
 * shared base class that would recouple them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LldQuestion {

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
