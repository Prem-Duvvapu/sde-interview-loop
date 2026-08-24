package com.premd.interviewloop.content.csf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One topic area inside a CS-fundamentals round pack — e.g. "Networking & HTTP" —
 * holding the rapid-fire questions for that area.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CsfTopic {

    private String name;
    private List<CsfQuestion> questions;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<CsfQuestion> getQuestions() { return questions; }
    public void setQuestions(List<CsfQuestion> questions) { this.questions = questions; }
}
