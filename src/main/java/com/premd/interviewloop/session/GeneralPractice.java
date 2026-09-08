package com.premd.interviewloop.session;

/**
 * Stable identity for a deliberately uncalibrated, single-module practice session.
 *
 * <p>This is not a company profile: it has no emphasis weights, interviewer quirks, or full
 * interview loop. The stored identity keeps the existing non-null session and trend keys intact
 * while making general-practice history separable from every company-specific calibration.
 */
public final class GeneralPractice {

    public static final String ID = "general-practice";
    public static final String CONTENT_VERSION = "builtin-general-practice-v1";

    private GeneralPractice() {
    }

    public static String normalizeId(String profileId) {
        return profileId == null || profileId.isBlank() ? ID : profileId.trim();
    }

    public static boolean isGeneralPractice(String profileId) {
        return ID.equals(normalizeId(profileId));
    }
}
