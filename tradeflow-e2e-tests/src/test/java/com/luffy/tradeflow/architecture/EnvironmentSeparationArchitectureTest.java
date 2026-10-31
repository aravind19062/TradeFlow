package com.luffy.tradeflow.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Compile-level guarantees behind the DEMO/LIVE separation (docs/DUAL_MODE_TRADING.md §2).
 * The simulated services may never reference live-trading or broker code, and live-trading
 * code may never reference the simulated engine. Messages and HTTP are the only links,
 * and those are guarded at runtime by the event environment check and the gateway.
 */
class EnvironmentSeparationArchitectureTest {

    private static final String[] DEMO = {
            "com.luffy.tradeflow_market_data_service..",
            "com.luffy.tradeflow_order_service..",
            "com.luffy.tradeflow_portfolio_service.."};
    private static final String[] LIVE = {"com.luffy.tradeflow_live_trading_service.."};

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.luffy");

    @Test
    void demoServicesNeverDependOnLiveTradingOrBrokerCode() {
        ArchRule rule = noClasses().that().resideInAnyPackage(DEMO)
                .should().dependOnClassesThat().resideInAnyPackage(LIVE)
                .orShould().dependOnClassesThat().resideInAnyPackage("..broker..")
                .because("demo orders must never be able to reach a real broker");
        rule.check(CLASSES);
    }

    @Test
    void liveTradingNeverDependsOnTheSimulatedEngine() {
        ArchRule rule = noClasses().that().resideInAnyPackage(LIVE)
                .should().dependOnClassesThat().resideInAnyPackage(DEMO)
                .because("live orders must never enter the simulated engine and virtual cash must never "
                        + "appear as broker funds")
                .allowEmptyShould(true);
        rule.check(CLASSES);
    }

    @Test
    void demoServicesDoNotShareCodeWithEachOther() {
        // Each simulated service talks to the others only through events and HTTP, so an
        // environment check can sit at every boundary.
        for (String own : DEMO) {
            String[] others = java.util.Arrays.stream(DEMO).filter(p -> !p.equals(own)).toArray(String[]::new);
            noClasses().that().resideInAPackage(own)
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .check(CLASSES);
        }
    }
}
