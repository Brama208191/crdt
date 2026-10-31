package dev.crdt.editor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects API writes sent by a page on another site. The SameSite=Strict cookies already keep
 * browsers from attaching credentials to such requests; this is a second, independent check. Requests
 * without an Origin header (curl, server-to-server) carry no browser cookies by accident, so they pass.
 */
@Component
class SameOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !sameHost(origin, request.getHeader("Host"))
                && !sameHost(origin, request.getHeader("X-Forwarded-Host"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site request");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean sameHost(String origin, String host) {
        try {
            String authority = URI.create(origin).getRawAuthority();
            return authority != null && host != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
