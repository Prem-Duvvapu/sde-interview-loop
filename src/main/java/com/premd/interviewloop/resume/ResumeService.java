package com.premd.interviewloop.resume;

import com.premd.interviewloop.domain.Resume;
import com.premd.interviewloop.domain.repository.ResumeRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Parses an uploaded resume to plain text and stores only the text — never the file.
 *
 * <p>A resume is real personal data, a step up in sensitivity from anything else this app
 * persists (company profiles and question banks are all fine in a public repo; a resume is
 * not). The uploaded bytes are parsed in-memory in {@link #upload} and never written to
 * disk; nothing here logs resume content. See {@code AGENTS.md} / {@code RCA.md} for the
 * same discipline already applied to API keys.
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    /** PDF only for v1 — see the resume module's own notes for why DOCX was left out. */
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final ResumeRepository repository;

    public ResumeService(ResumeRepository repository) {
        this.repository = repository;
    }

    public Resume upload(String originalFilename, byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("The uploaded file was empty");
        }
        if (pdfBytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("The uploaded file is larger than 10MB");
        }

        String text = extractText(pdfBytes);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "No extractable text was found in that PDF — a scanned/image-only resume "
                            + "can't be read this way. Try a text-based PDF export instead.");
        }

        Resume resume = new Resume(originalFilename, text, sha256(text));
        Resume saved = repository.save(resume);
        log.info("Resume uploaded: {} chars extracted, hash={}", text.length(),
                saved.getContentHash().substring(0, 8));
        return saved;
    }

    public Optional<Resume> current() {
        return repository.findFirstByOrderByUploadedAtDesc();
    }

    public Resume requireCurrent() {
        return current().orElseThrow(() -> new NoSuchElementException(
                "No resume has been uploaded yet — upload one before starting a resume round."));
    }

    /**
     * Look up a resume by its pinned content hash — used mid-round so a round stays on the
     * resume text it started with even if a newer one is uploaded before it ends.
     */
    public Resume byHash(String contentHash) {
        return repository.findFirstByContentHashOrderByUploadedAtDesc(contentHash)
                .orElseThrow(() -> new NoSuchElementException("Resume version no longer available"));
    }

    public void delete() {
        repository.deleteAll();
    }

    private String extractText(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(document).strip();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "That file couldn't be read as a PDF: " + e.getMessage());
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
