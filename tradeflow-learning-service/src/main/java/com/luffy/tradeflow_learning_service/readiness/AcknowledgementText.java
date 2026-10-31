package com.luffy.tradeflow_learning_service.readiness;

import com.luffy.tradeflow.common.web.ApiException;
import com.luffy.tradeflow_learning_service.config.AcademyProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/** Versioned risk-acknowledgement texts (academy/acknowledgement/v{n}.md); each signature records the hash. */
@Component
public class AcknowledgementText {

    private final String root;
    private final ConcurrentHashMap<Integer, String> cache = new ConcurrentHashMap<>();

    public AcknowledgementText(AcademyProperties properties) {
        this.root = properties.catalog().endsWith("/") ? properties.catalog() : properties.catalog() + "/";
    }

    public String text(int version) {
        return cache.computeIfAbsent(version, v -> {
            try (InputStream in = new DefaultResourceLoader().getResource(root + "acknowledgement/v" + v + ".md")
                    .getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw ApiException.notFound("Acknowledgement text v" + v);
            }
        });
    }

    public String sha256(int version) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text(version).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
