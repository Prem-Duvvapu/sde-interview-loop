package com.premd.interviewloop.interviewer;

import com.premd.interviewloop.domain.enums.ModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a {@link ModuleType} to its interviewer implementation.
 *
 * <p>Modules register themselves by being Spring beans, so Phases 2–5 add one class each and
 * touch nothing shared. Until those phases land the registry is legitimately empty — the
 * orchestrator reports "module not implemented" rather than pretending to run a round.
 */
@Component
public class ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    private final Map<ModuleType, InterviewerModule> modules = new EnumMap<>(ModuleType.class);

    public ModuleRegistry(List<InterviewerModule> moduleBeans) {
        for (InterviewerModule module : moduleBeans) {
            InterviewerModule previous = modules.put(module.moduleType(), module);
            if (previous != null) {
                throw new IllegalStateException(String.format(
                        "Two interviewer modules claim %s: %s and %s",
                        module.moduleType(),
                        previous.getClass().getName(),
                        module.getClass().getName()));
            }
        }
        log.info("Interviewer modules registered: {}", modules.keySet());
    }

    public Optional<InterviewerModule> find(ModuleType type) {
        return Optional.ofNullable(modules.get(type));
    }

    public InterviewerModule require(ModuleType type) {
        InterviewerModule module = modules.get(type);
        if (module == null) {
            throw new ModuleNotAvailableException(type);
        }
        return module;
    }

    public boolean isImplemented(ModuleType type) {
        return modules.containsKey(type);
    }

    public Collection<ModuleType> implementedModules() {
        return modules.keySet();
    }

    /** Thrown when a round asks for a module that has not been built yet. */
    public static class ModuleNotAvailableException extends RuntimeException {
        private final ModuleType moduleType;

        public ModuleNotAvailableException(ModuleType moduleType) {
            super("No interviewer module is implemented for " + moduleType);
            this.moduleType = moduleType;
        }

        public ModuleType getModuleType() { return moduleType; }
    }
}
