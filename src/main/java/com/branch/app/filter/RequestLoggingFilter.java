package com.branch.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    public static final String EXCEPTION_ATTRIBUTE = "app.exception";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            request.setAttribute(EXCEPTION_ATTRIBUTE, e);
            throw e;
        } finally {
            logRequest(request, response);
        }
    }

    private void logRequest(HttpServletRequest request, HttpServletResponse response) {
        String query = request.getQueryString();
        String path = query != null ? request.getRequestURI() + "?" + query : request.getRequestURI();
        int status = response.getStatus();
        Exception ex = (Exception) request.getAttribute(EXCEPTION_ATTRIBUTE);
        String prefix = "REQUEST: ";

        if (status >= 500) {
            log.error("{} {} {} -> {}", request.getMethod(), prefix, path, status, ex);
        } else if (status >= 400) {
            log.warn("{} {} {} -> {}", request.getMethod(), prefix, path, status, ex);
        } else {
            log.info("{} {} {} -> {}", request.getMethod(), prefix, path, status);
        }
    }
}
