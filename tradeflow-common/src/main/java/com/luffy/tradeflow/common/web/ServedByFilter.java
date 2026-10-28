package com.luffy.tradeflow.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Adds X-Served-By (service name and host) to every response, which makes load
 * balancing across replicas observable from the outside. Contains no secrets.
 */
@Component
public class ServedByFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Served-By";
    private final String value;

    public ServedByFilter(@Value("${spring.application.name}") String app) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        this.value = app + "@" + host;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, value);
        chain.doFilter(request, response);
    }
}
