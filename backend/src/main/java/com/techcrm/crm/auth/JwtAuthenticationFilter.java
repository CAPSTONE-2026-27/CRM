package com.techcrm.crm.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Deliberately NOT a @Component: this filter is wired into Spring
 * Security's chain via SecurityConfig#filterChain(), constructed directly.
 * If it were a discoverable Filter bean, Spring Boot's servlet
 * auto-configuration would ALSO register it as a raw container-level
 * filter outside FilterChainProxy — running it twice and, worse, letting
 * SecurityContextHolderFilter clear the context before @PreAuthorize
 * evaluates, which manifests as authenticated requests getting a bare 401
 * on any @PreAuthorize-protected endpoint even with a valid token.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthenticatedUser user = jwtService.parseAccessToken(header.substring("Bearer ".length()));

                List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));

                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Server-side enforcement of forced-password-change-on-first-login:
                // a raw API call can't bypass what a frontend redirect alone
                // wouldn't stop. Written directly (not sendError) for the same
                // /error-forwarding reason documented on SecurityConfig's
                // authenticationEntryPoint.
                if (user.mustChangePassword() && !isAllowedWhileMustChangePassword(request)) {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"MUST_CHANGE_PASSWORD\"}");
                    return;
                }
            } catch (Exception ignored) {
                // Invalid/expired token: leave the context unauthenticated so
                // Spring Security's normal 401 handling applies downstream.
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedWhileMustChangePassword(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/api/auth/change-password")
                || path.endsWith("/api/auth/me")
                || path.endsWith("/api/auth/logout");
    }
}
