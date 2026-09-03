package com.premd.interviewloop.transport;

import com.premd.interviewloop.domain.Resume;
import com.premd.interviewloop.resume.ResumeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Upload, inspect, and clear the candidate's resume. There is at most one "current" resume
 * — see {@code Resume} for why. The extracted text is returned on upload/fetch so the
 * candidate can confirm parsing worked; it is never logged (see {@code ResumeService}).
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    public record ResumeDto(String originalFilename, String contentText, int contentLength,
                            Instant uploadedAt) {}

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file was received"));
        }
        try {
            byte[] bytes = file.getBytes();
            Resume resume = resumeService.upload(file.getOriginalFilename(), bytes);
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(resume));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not read the uploaded file"));
        }
    }

    @GetMapping
    public ResponseEntity<?> current() {
        return resumeService.current()
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(toDto(r)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No resume uploaded yet")));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        resumeService.delete();
        return ResponseEntity.noContent().build();
    }

    private ResumeDto toDto(Resume r) {
        return new ResumeDto(r.getOriginalFilename(), r.getContentText(),
                r.getContentText().length(), r.getUploadedAt());
    }
}
