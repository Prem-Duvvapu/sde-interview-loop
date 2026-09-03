package com.premd.interviewloop.content.behavioral;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One behavioral question, loaded from {@code question-bank/behavioral/*.yaml}.
 *
 * <p>Deliberately independent of the other modules' question classes — see
 * {@code content.dsa.DsaQuestion}'s javadoc for why. Prompts here are common, widely-used
 * behavioral question *phrasings* ("tell me about a time you disagreed with a teammate") —
 * these are generic industry phrasing, not the kind of proprietary text D-5 is about
 * (PROJECT_PLAN.md), but the wording and interviewer notes below are still original to
 * this project.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BehavioralQuestion {

    private String slug;
    private String title;
    private String difficulty;
    private List<String> tags;
    private String prompt;

    @JsonProperty("star_focus")
    private String starFocus;

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

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getStarFocus() { return starFocus; }
    public void setStarFocus(String starFocus) { this.starFocus = starFocus; }

    public String getInterviewerNotes() { return interviewerNotes; }
    public void setInterviewerNotes(String interviewerNotes) { this.interviewerNotes = interviewerNotes; }
}
