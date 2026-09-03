package com.premd.interviewloop.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The candidate's current resume, extracted to plain text.
 *
 * <p>Single-user app: at most one row is ever "current" — {@link #uploadedAt} orders
 * replacement. Only extracted text is stored; the uploaded file's bytes are parsed
 * in-memory and discarded (see {@code resume.ResumeService}) — this is real personal
 * data and the smallest footprint that still serves the feature is the right one.
 */
@Entity
@Table(name = "candidate_resume")
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_filename")
    private String originalFilename;

    @Lob
    @Column(name = "content_text", nullable = false)
    private String contentText;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    protected Resume() {}

    public Resume(String originalFilename, String contentText, String contentHash) {
        this.originalFilename = originalFilename;
        this.contentText = contentText;
        this.contentHash = contentHash;
    }

    public Long getId() { return id; }

    public String getOriginalFilename() { return originalFilename; }

    public String getContentText() { return contentText; }

    public String getContentHash() { return contentHash; }

    public Instant getUploadedAt() { return uploadedAt; }
}
