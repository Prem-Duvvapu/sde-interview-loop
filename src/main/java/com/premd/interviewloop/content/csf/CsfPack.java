package com.premd.interviewloop.content.csf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One CS-fundamentals round pack, loaded from {@code question-bank/cs_fundamentals/*.yaml}.
 *
 * <p>A pack is several topic areas' worth of rapid-fire questions conducted in a single
 * round. This is why the file is a "pack" rather than a lone question: the whole pack is
 * rendered into the stable problem block once (cache prefix, §1.4) and the model walks it
 * adaptively during RAPID_FIRE — the backend does not swap questions mid-round, which
 * would invalidate the cached prefix on every turn.
 *
 * <p>Deliberately independent of the other modules' question classes (same reasoning as
 * {@link com.premd.interviewloop.content.dsa.DsaQuestion} vs LLD's).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CsfPack {

    private String slug;
    private String title;
    private String difficulty;
    private List<String> tags;
    private List<CsfTopic> topics;

    @JsonProperty("focus_hints")
    private List<String> focusHints;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<CsfTopic> getTopics() { return topics; }
    public void setTopics(List<CsfTopic> topics) { this.topics = topics; }

    /** Free-text note rendered to the interviewer about where this pack bites hardest. */
    @JsonProperty("focus_hints")
    public List<String> getFocusHints() { return focusHints; }
    public void setFocusHints(List<String> focusHints) { this.focusHints = focusHints; }
}
