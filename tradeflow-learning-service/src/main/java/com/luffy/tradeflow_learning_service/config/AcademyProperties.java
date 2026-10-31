package com.luffy.tradeflow_learning_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param catalog resource location of the Academy content (catalog.json, lessons/, questions/)
 */
@ConfigurationProperties("tradeflow.academy")
public record AcademyProperties(String catalog) {

    public AcademyProperties {
        catalog = catalog == null ? "classpath:academy/" : catalog;
    }
}
