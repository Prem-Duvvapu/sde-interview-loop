package com.premd.interviewloop.e2e;

import com.premd.interviewloop.InterviewLoopApplication;
import com.premd.interviewloop.llm.AppSettingsStore;
import com.premd.interviewloop.llm.ProviderKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The real application, with one addition: a deterministic scripted LLM provider bound as both
 * interviewer and evaluator. Used only by the browser suite ({@code web/e2e}) so UI flows can be
 * exercised end to end — real REST, real WebSocket, real H2 — with zero provider calls and zero
 * quota spent.
 *
 * <p>Lives on the test classpath so it can never ship in the application. Launch it with
 * {@code scripts/e2e-backend.sh}, which also points H2 at a throwaway directory under
 * {@code target/} and strips real provider keys from the environment.
 */
public class E2eApplication {

    public static void main(String[] args) {
        SpringApplication.from(InterviewLoopApplication::main)
                .with(ScriptedProviderConfig.class)
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedProviderConfig {
        private static final Logger log = LoggerFactory.getLogger(ScriptedProviderConfig.class);

        @Bean
        ScriptedProviderFactory scriptedProviderFactory() {
            return new ScriptedProviderFactory();
        }

        @Bean
        ApplicationRunner bindScriptedProvider(ProviderKeyStore keys, AppSettingsStore settings) {
            return args -> {
                keys.putUiKey(ScriptedProviderFactory.ID, "scripted-e2e-key");
                settings.setInterviewer(ScriptedProviderFactory.ID, ScriptedProviderFactory.MODEL);
                settings.setEvaluator(ScriptedProviderFactory.ID, ScriptedProviderFactory.MODEL);
                log.warn("E2E MODE: interviewer and evaluator are the scripted provider; no real LLM calls will be made.");
            };
        }
    }
}
