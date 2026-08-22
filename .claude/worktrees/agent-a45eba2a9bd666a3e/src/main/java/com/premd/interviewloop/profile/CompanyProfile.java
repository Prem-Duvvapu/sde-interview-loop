package com.premd.interviewloop.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.premd.interviewloop.domain.enums.DifficultyTarget;
import com.premd.interviewloop.domain.enums.ModuleType;
import java.util.List;
import java.util.Map;

/**
 * POJO mapping a company profile YAML file.
 * Matches the structure defined by company-profiles/_schema.json.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class CompanyProfile {

    private String id;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("target_role")
    private TargetRole targetRole;

    private String difficulty;

    private Calibration calibration;

    private Map<String, Double> emphasis;

    private LoopConfig loop;

    private List<Quirk> quirks;

    private Readiness readiness;

    // -- Nested DTOs --

    public static class TargetRole {
        private String title;
        @JsonProperty("level_code")
        private String levelCode;
        @JsonProperty("location_context")
        private String locationContext;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getLevelCode() { return levelCode; }
        public void setLevelCode(String levelCode) { this.levelCode = levelCode; }
        public String getLocationContext() { return locationContext; }
        public void setLocationContext(String locationContext) { this.locationContext = locationContext; }
    }

    public static class Calibration {
        private String confidence;
        @JsonProperty("last_updated")
        private String lastUpdated;
        private List<Source> sources;
        private String notes;

        public static class Source {
            private String date;
            private String kind;
            private String note;

            public String getDate() { return date; }
            public void setDate(String date) { this.date = date; }
            public String getKind() { return kind; }
            public void setKind(String kind) { this.kind = kind; }
            public String getNote() { return note; }
            public void setNote(String note) { this.note = note; }
        }

        public String getConfidence() { return confidence; }
        public void setConfidence(String confidence) { this.confidence = confidence; }
        public String getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
        public List<Source> getSources() { return sources; }
        public void setSources(List<Source> sources) { this.sources = sources; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    public static class LoopConfig {
        @JsonProperty("total_wall_clock_min")
        private int totalWallClockMin;
        @JsonProperty("difficulty_curve")
        private String difficultyCurve;
        private List<Round> rounds;

        public int getTotalWallClockMin() { return totalWallClockMin; }
        public void setTotalWallClockMin(int totalWallClockMin) { this.totalWallClockMin = totalWallClockMin; }
        public String getDifficultyCurve() { return difficultyCurve; }
        public void setDifficultyCurve(String difficultyCurve) { this.difficultyCurve = difficultyCurve; }
        public List<Round> getRounds() { return rounds; }
        public void setRounds(List<Round> rounds) { this.rounds = rounds; }
    }

    public static class Round {
        private int ordinal;
        private String module;
        private String name;
        private String stage;
        @JsonProperty("duration_min")
        private int durationMin;
        @JsonProperty("difficulty_target")
        private String difficultyTarget;
        @JsonProperty("enabled_in_v1")
        private Boolean enabledInV1;
        @JsonProperty("focus_tags")
        private List<String> focusTags;
        private String notes;

        public int getOrdinal() { return ordinal; }
        public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
        public String getModule() { return module; }
        public void setModule(String module) { this.module = module; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStage() { return stage; }
        public void setStage(String stage) { this.stage = stage; }
        public int getDurationMin() { return durationMin; }
        public void setDurationMin(int durationMin) { this.durationMin = durationMin; }
        public String getDifficultyTarget() { return difficultyTarget; }
        public void setDifficultyTarget(String difficultyTarget) { this.difficultyTarget = difficultyTarget; }
        public Boolean getEnabledInV1() { return enabledInV1; }
        public void setEnabledInV1(Boolean enabledInV1) { this.enabledInV1 = enabledInV1; }
        public List<String> getFocusTags() { return focusTags; }
        public void setFocusTags(List<String> focusTags) { this.focusTags = focusTags; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }

        /** Returns true unless explicitly set to false. */
        public boolean isEnabledInV1() {
            return enabledInV1 == null || enabledInV1;
        }
    }

    public static class Quirk {
        private String id;
        private String label;
        private String description;
        @JsonProperty("applies_to_rounds")
        private List<Integer> appliesToRounds;
        @JsonProperty("interviewer_behavior")
        private String interviewerBehavior;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<Integer> getAppliesToRounds() { return appliesToRounds; }
        public void setAppliesToRounds(List<Integer> appliesToRounds) { this.appliesToRounds = appliesToRounds; }
        public String getInterviewerBehavior() { return interviewerBehavior; }
        public void setInterviewerBehavior(String interviewerBehavior) { this.interviewerBehavior = interviewerBehavior; }

        /** Returns true if this quirk applies to the given round ordinal. */
        public boolean appliesTo(int roundOrdinal) {
            return appliesToRounds == null || appliesToRounds.isEmpty() || appliesToRounds.contains(roundOrdinal);
        }
    }

    public static class Readiness {
        @JsonProperty("bar_band")
        private String barBand;
        @JsonProperty("module_minimums")
        private Map<String, Double> moduleMinimums;
        @JsonProperty("min_sessions_for_confidence")
        private Integer minSessionsForConfidence;

        public String getBarBand() { return barBand; }
        public void setBarBand(String barBand) { this.barBand = barBand; }
        public Map<String, Double> getModuleMinimums() { return moduleMinimums; }
        public void setModuleMinimums(Map<String, Double> moduleMinimums) { this.moduleMinimums = moduleMinimums; }
        public Integer getMinSessionsForConfidence() { return minSessionsForConfidence; }
        public void setMinSessionsForConfidence(Integer minSessionsForConfidence) { this.minSessionsForConfidence = minSessionsForConfidence; }
    }

    // -- Top-level getters / setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public TargetRole getTargetRole() { return targetRole; }
    public void setTargetRole(TargetRole targetRole) { this.targetRole = targetRole; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public Calibration getCalibration() { return calibration; }
    public void setCalibration(Calibration calibration) { this.calibration = calibration; }

    public Map<String, Double> getEmphasis() { return emphasis; }
    public void setEmphasis(Map<String, Double> emphasis) { this.emphasis = emphasis; }

    public LoopConfig getLoop() { return loop; }
    public void setLoop(LoopConfig loop) { this.loop = loop; }

    public List<Quirk> getQuirks() { return quirks; }
    public void setQuirks(List<Quirk> quirks) { this.quirks = quirks; }

    public Readiness getReadiness() { return readiness; }
    public void setReadiness(Readiness readiness) { this.readiness = readiness; }
}
