package com.premd.interviewloop.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime overrides of the provider/model bindings in config/providers.yaml, in memory
 * only — the yaml stays the source of truth for what happens on a fresh start.
 *
 * The interviewer binding is a free override: any configured provider meeting the
 * interviewer floor may take it. The evaluator binding is deliberately harder to change —
 * {@link #setEvaluator} bumps the comparability epoch whenever the effective provider or
 * model actually changes, per PROJECT_PLAN.md §1.5, so a readiness trend that crosses an
 * epoch boundary can be flagged instead of silently mixing two rubrics' scores.
 */
@Component
public class AppSettingsStore {

    public record RoleBinding(String provider, String model) {}

    private final ProvidersConfig providersConfig;
    private volatile RoleBinding interviewer;
    private volatile RoleBinding evaluator;
    private final AtomicInteger comparabilityEpoch;

    public AppSettingsStore(ProvidersConfig providersConfig) {
        this.providersConfig = providersConfig;
        this.comparabilityEpoch = new AtomicInteger(providersConfig.getDefaults().evaluator.comparabilityEpoch);
    }

    public RoleBinding interviewerBinding() {
        RoleBinding override = interviewer;
        if (override != null) {
            return override;
        }
        ProvidersConfig.InterviewerDefault d = providersConfig.getDefaults().interviewer;
        return new RoleBinding(d.provider, d.model);
    }

    public RoleBinding evaluatorBinding() {
        RoleBinding override = evaluator;
        if (override != null) {
            return override;
        }
        ProvidersConfig.EvaluatorDefault d = providersConfig.getDefaults().evaluator;
        return new RoleBinding(d.provider, d.model);
    }

    public int comparabilityEpoch() {
        return comparabilityEpoch.get();
    }

    public void setInterviewer(String provider, String model) {
        this.interviewer = new RoleBinding(provider, model);
    }

    /** Returns the resulting comparability epoch — bumped only if the binding actually changed. */
    public int setEvaluator(String provider, String model) {
        RoleBinding current = evaluatorBinding();
        boolean changed = !current.provider().equals(provider) || !current.model().equals(model);
        this.evaluator = new RoleBinding(provider, model);
        return changed ? comparabilityEpoch.incrementAndGet() : comparabilityEpoch.get();
    }
}
