package com.premd.interviewloop.progress;

import com.premd.interviewloop.domain.ReadinessSnapshot;
import com.premd.interviewloop.domain.repository.ReadinessSnapshotRepository;
import com.premd.interviewloop.profile.CompanyProfile;
import com.premd.interviewloop.profile.ProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for readiness computation: decay, module minimum gating, and confidence.
 * No Spring context, no database, no network.
 */
class ReadinessCalculatorTest {

    private ReadinessSnapshotRepository snapshotRepo;
    private ProfileLoader profileLoader;
    private ReadinessCalculator calculator;

    @BeforeEach
    void setUp() {
        snapshotRepo = mock(ReadinessSnapshotRepository.class);
        profileLoader = mock(ProfileLoader.class);
        calculator = new ReadinessCalculator(snapshotRepo, profileLoader);
    }

    // -- Decay weight tests --

    @Test
    void decayWeight_now_returns1() {
        Instant now = Instant.now();
        assertEquals(1.0, ReadinessCalculator.decayWeight(now, now), 0.001);
    }

    @Test
    void decayWeight_halfLifeAgo_returnsHalf() {
        Instant now = Instant.now();
        Instant halfLifeAgo = now.minus(Duration.ofDays((long) ReadinessCalculator.DECAY_HALF_LIFE_DAYS));
        assertEquals(0.5, ReadinessCalculator.decayWeight(halfLifeAgo, now), 0.01);
    }

    @Test
    void decayWeight_twoHalfLivesAgo_returnsQuarter() {
        Instant now = Instant.now();
        Instant twoHalfLivesAgo = now.minus(Duration.ofDays((long) (2 * ReadinessCalculator.DECAY_HALF_LIFE_DAYS)));
        assertEquals(0.25, ReadinessCalculator.decayWeight(twoHalfLivesAgo, now), 0.01);
    }

    @Test
    void decayWeight_future_clampedTo1() {
        Instant now = Instant.now();
        Instant future = now.plus(Duration.ofDays(5));
        assertEquals(1.0, ReadinessCalculator.decayWeight(future, now), 0.001);
    }

    // -- Readiness computation tests --

    @Test
    void computeReadiness_noSnapshots_returnsUnavailable() {
        CompanyProfile profile = buildProfile(Map.of("dsa", 0.5, "lld", 0.5), null, null);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of());
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "lld"))
                .thenReturn(List.of());

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        assertNotNull(result.error());
        assertEquals("none", result.confidence());
    }

    @Test
    void computeReadiness_simpleWeightedMean() {
        CompanyProfile profile = buildProfile(Map.of("dsa", 0.6, "lld", 0.4), null, null);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);

        Instant now = Instant.now();
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of(snap("dsa", "test-co", 4.0, now)));
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "lld"))
                .thenReturn(List.of(snap("lld", "test-co", 3.0, now)));

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        assertNull(result.error());
        // 0.6 * 4.0 + 0.4 * 3.0 = 2.4 + 1.2 = 3.6
        assertEquals(3.6, result.overallScore(), 0.01);
        assertEquals("hire", result.band());
    }

    @Test
    void computeReadiness_moduleMinimumGating() {
        // Profile requires dsa >= 3.5 to be "ready"
        CompanyProfile profile = buildProfile(
                Map.of("dsa", 0.6, "lld", 0.4),
                Map.of("dsa", 3.5),
                3);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);

        Instant now = Instant.now();
        // DSA score is 2.0 (below minimum of 3.5)
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of(snap("dsa", "test-co", 2.0, now)));
        // LLD score is 5.0 (high)
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "lld"))
                .thenReturn(List.of(snap("lld", "test-co", 5.0, now)));

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        assertNull(result.error());
        // Overall: 0.6*2.0 + 0.4*5.0 = 3.2 → would be lean-hire on score alone
        // But DSA is below minimum, so band is capped at lean-hire regardless
        assertFalse(result.failingMinimums().isEmpty());
        assertTrue(result.failingMinimums().containsKey("dsa"));
        assertEquals("lean-hire", result.band());
    }

    @Test
    void computeReadiness_moduleMinimumGating_capsBandDown() {
        // Profile requires lld >= 3.0
        CompanyProfile profile = buildProfile(
                Map.of("dsa", 0.5, "lld", 0.5),
                Map.of("lld", 3.0),
                null);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);

        Instant now = Instant.now();
        // High DSA, low LLD (below minimum)
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of(snap("dsa", "test-co", 5.0, now)));
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "lld"))
                .thenReturn(List.of(snap("lld", "test-co", 2.0, now)));

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        // Overall: 0.5*5.0 + 0.5*2.0 = 3.5 → hire band normally, but gated to lean-hire
        assertTrue(result.failingMinimums().containsKey("lld"));
        assertEquals("lean-hire", result.band());
    }

    @Test
    void computeReadiness_confidenceLevelReflectsSampleSize() {
        CompanyProfile profile = buildProfile(Map.of("dsa", 1.0), null, 5);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);

        Instant now = Instant.now();
        // Only 2 samples — below minSessionsForConfidence of 5
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of(
                        snap("dsa", "test-co", 4.0, now),
                        snap("dsa", "test-co", 3.5, now.minus(Duration.ofDays(1)))));

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        assertEquals("low", result.confidence());
        assertEquals(2, result.totalSamples());
    }

    @Test
    void computeReadiness_confidentWhenEnoughSamples() {
        CompanyProfile profile = buildProfile(Map.of("dsa", 1.0), null, 3);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);

        Instant now = Instant.now();
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of(
                        snap("dsa", "test-co", 4.0, now),
                        snap("dsa", "test-co", 3.5, now.minus(Duration.ofDays(1))),
                        snap("dsa", "test-co", 3.0, now.minus(Duration.ofDays(2)))));

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        assertEquals("confident", result.confidence());
    }

    @Test
    void computeReadiness_recencyWeightingFavoursRecent() {
        CompanyProfile profile = buildProfile(Map.of("dsa", 1.0), null, null);
        when(profileLoader.getProfile("test-co")).thenReturn(profile);

        Instant now = Instant.now();
        // Recent score: 4.0 (weight ~1.0), old score: 2.0 (weight ~0.25)
        Instant twoHalfLivesAgo = now.minus(Duration.ofDays((long) (2 * ReadinessCalculator.DECAY_HALF_LIFE_DAYS)));
        when(snapshotRepo.findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("test-co", "dsa"))
                .thenReturn(List.of(
                        snap("dsa", "test-co", 4.0, now),
                        snap("dsa", "test-co", 2.0, twoHalfLivesAgo)));

        ReadinessCalculator.ReadinessResult result = calculator.computeReadiness("test-co");

        // Without decay: (4.0+2.0)/2 = 3.0
        // With decay: (4.0*1.0 + 2.0*0.25) / (1.0+0.25) = 4.5/1.25 = 3.6
        assertTrue(result.overallScore() > 3.5, "Recency weighting should favour the recent high score");
        assertEquals("hire", result.band());
    }

    // -- Helpers --

    private CompanyProfile buildProfile(Map<String, Double> emphasis,
                                         Map<String, Double> moduleMinimums,
                                         Integer minSessionsForConfidence) {
        CompanyProfile profile = new CompanyProfile();
        profile.setEmphasis(emphasis);
        CompanyProfile.Readiness readiness = new CompanyProfile.Readiness();
        readiness.setModuleMinimums(moduleMinimums);
        readiness.setMinSessionsForConfidence(minSessionsForConfidence);
        profile.setReadiness(readiness);
        return profile;
    }

    private ReadinessSnapshot snap(String module, String company, double score, Instant takenAt) {
        // Use the public constructor, then set takenAt reflectively since there's no setter
        ReadinessSnapshot snapshot = new ReadinessSnapshot(module, company, score, 1);
        try {
            var field = ReadinessSnapshot.class.getDeclaredField("takenAt");
            field.setAccessible(true);
            field.set(snapshot, takenAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set takenAt on test snapshot", e);
        }
        return snapshot;
    }
}
