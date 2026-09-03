package com.premd.interviewloop.resume;

import com.premd.interviewloop.domain.Resume;
import com.premd.interviewloop.domain.repository.ResumeRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * No Spring context, no database, no network — a real PDFBox-generated PDF is parsed for
 * real (the parsing itself is the thing worth testing), but persistence is a mocked
 * {@link ResumeRepository}.
 */
class ResumeServiceTest {

    private ResumeRepository repository;
    private ResumeService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResumeRepository.class);
        // save() should hand back whatever was passed to it, like a real JPA save would.
        when(repository.save(any(Resume.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ResumeService(repository);
    }

    @Test
    void uploadExtractsRealTextFromARealPdf() throws IOException {
        byte[] pdf = pdfWithText("Jane Doe", "Built a rate limiter using a token bucket algorithm.");

        Resume resume = service.upload("resume.pdf", pdf);

        assertThat(resume.getOriginalFilename()).isEqualTo("resume.pdf");
        assertThat(resume.getContentText())
                .contains("Jane Doe")
                .contains("token bucket algorithm");
        assertThat(resume.getContentHash()).hasSize(64);
        verify(repository).save(any(Resume.class));
    }

    @Test
    void uploadRejectsEmptyFile() {
        assertThatThrownBy(() -> service.upload("empty.pdf", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        verifyNoInteractions(repository);
    }

    @Test
    void uploadRejectsFilesOverTenMegabytes() {
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        assertThatThrownBy(() -> service.upload("huge.pdf", tooLarge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10MB");
        verifyNoInteractions(repository);
    }

    @Test
    void uploadRejectsBytesThatArentAPdfAtAll() {
        byte[] notAPdf = "this is definitely not a pdf".getBytes();
        assertThatThrownBy(() -> service.upload("fake.pdf", notAPdf))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void uploadRejectsAPdfWithNoExtractableText() throws IOException {
        // A valid PDF with a page but no text content stream — the scanned-resume case.
        byte[] blankPdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            blankPdf = out.toByteArray();
        }

        assertThatThrownBy(() -> service.upload("scanned.pdf", blankPdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No extractable text");
        verifyNoInteractions(repository);
    }

    @Test
    void currentReturnsEmptyWhenNoneUploaded() {
        when(repository.findFirstByOrderByUploadedAtDesc()).thenReturn(Optional.empty());
        assertThat(service.current()).isEmpty();
    }

    @Test
    void requireCurrentThrowsAClearMessageWhenNoneUploaded() {
        when(repository.findFirstByOrderByUploadedAtDesc()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireCurrent())
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No resume has been uploaded yet");
    }

    @Test
    void byHashLooksUpAPinnedVersionRegardlessOfWhatIsCurrent() {
        Resume pinned = new Resume("old.pdf", "old content", "abc123");
        when(repository.findFirstByContentHashOrderByUploadedAtDesc("abc123"))
                .thenReturn(Optional.of(pinned));

        assertThat(service.byHash("abc123")).isSameAs(pinned);
    }

    @Test
    void byHashThrowsWhenThatVersionIsGone() {
        when(repository.findFirstByContentHashOrderByUploadedAtDesc("gone"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.byHash("gone")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void deleteClearsAllHistoryNotJustTheCurrentOne() {
        service.delete();
        verify(repository).deleteAll();
    }

    @Test
    void reUploadingTheSameTextProducesTheSameHash() throws IOException {
        byte[] pdfA = pdfWithText("Same content, uploaded twice");
        byte[] pdfB = pdfWithText("Same content, uploaded twice");

        Resume a = service.upload("a.pdf", pdfA);
        Resume b = service.upload("b.pdf", pdfB);

        assertThat(a.getContentHash()).isEqualTo(b.getContentHash());
    }

    /** Builds a real, parseable single-page PDF with the given lines of text. */
    private static byte[] pdfWithText(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                for (String line : List.of(lines)) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -18);
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
