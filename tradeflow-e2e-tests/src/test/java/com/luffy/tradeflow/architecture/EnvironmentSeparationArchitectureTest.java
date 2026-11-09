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
                        + "appear as broker funds");
        rule.check(CLASSES);
    }

    @Test
    void theLiveModuleIsActuallyImported() {
        // Guards against the rules above passing vacuously because the live classes are missing.
        org.assertj.core.api.Assertions.assertThat(CLASSES.containPackage("com.luffy.tradeflow_live_trading_service.access"))
                .isTrue();
    }

    @Test
    void onlyApprovedBrokerAdaptersExist() {
        // DisabledBrokerAdapter (no broker) and FyersBrokerAdapter (approved 2026-11-01) are the only
        // production implementations; any other broker needs a new approval and a change here.
        noClasses().that().implement(com.luffy.tradeflow_live_trading_service.broker.BrokerAdapter.class)
                .and().doNotHaveFullyQualifiedName("com.luffy.tradeflow_live_trading_service.broker.DisabledBrokerAdapter")
                .and().doNotHaveFullyQualifiedName("com.luffy.tradeflow_live_trading_service.broker.fyers.FyersBrokerAdapter")
                .should().bePublic().orShould().notBePublic()
                .because("only approved brokers may be integrated")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void fyersSpecificCodeStaysBehindTheBrokerAdapter() {
        // Everything else uses the BrokerAdapter interface; only the configuration chooses Fyers.
        noClasses().that().resideOutsideOfPackages("com.luffy.tradeflow_live_trading_service.broker.fyers..",
                        "com.luffy.tradeflow_live_trading_service.config..")
                .should().dependOnClassesThat().resideInAPackage("com.luffy.tradeflow_live_trading_service.broker.fyers..")
                .check(CLASSES);
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
