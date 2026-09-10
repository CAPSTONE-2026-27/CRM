package com.techcrm.crm.config;

import com.techcrm.crm.auth.JwtAuthenticationFilter;
import com.techcrm.crm.auth.JwtService;
import com.techcrm.crm.auth.OAuth2SuccessHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            OAuth2SuccessHandler oAuth2SuccessHandler,
            @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                // The API itself stays token-based; a session is created only to
                // hold the OAuth authorization request across the provider
                // round-trip, then goes unused.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // /error must be permitAll: sendError()-based handlers
                        // (Spring's default AccessDeniedHandler included)
                        // internally forward to /error, which re-enters this
                        // filter chain as an anonymous request — without this,
                        // that forwarded request gets caught by
                        // anyRequest().authenticated() below and its status
                        // gets silently overwritten (a 403 becomes a 401).
                        // Deliberately NOT /api/auth/** as a whole — /api/auth/me
                        // must require a valid token, only these four don't.
                        .requestMatchers(
                                "/api/auth/signup", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout",
                                "/actuator/health", "/error",
                                // The OAuth handshake runs before any CRM token exists.
                                "/oauth2/**", "/login/oauth2/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        // Unauthenticated -> 401 (not the default 403) so the
                        // frontend's silent-refresh-then-retry logic in
                        // apiClient.ts actually triggers on an expired token.
                        // Written directly (not sendError) to avoid the same
                        // forward-to-/error trap described above.
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Unauthorized\"}");
                        })
                )
                // Constructed directly (not injected as a @Component bean) so
                // Spring Boot never auto-registers it as a second, raw
                // container-level filter outside this chain — see the class
                // Javadoc on JwtAuthenticationFilter for why that matters.
                .oauth2Login(oauth -> oauth
                        .successHandler(oAuth2SuccessHandler)
                        // A provider-side failure returns the user to the app with
                        // a flag rather than dumping a Spring error page on them.
                        //
                        // The redirect deliberately carries no detail — an OAuth
                        // error can name accounts and tenants, and that belongs in
                        // the server log rather than a URL the user can screenshot.
                        // But it does have to reach the log: without this the only
                        // symptom is "?oauth_error=1" and the cause is gone.
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof OAuth2AuthenticationException oauthEx) {
                                OAuth2Error error = oauthEx.getError();
                                log.warn("OAuth2 login failed — code='{}' description='{}' uri='{}'",
                                        error.getErrorCode(), error.getDescription(), error.getUri(), exception);
                            } else {
                                log.warn("OAuth2 login failed: {}", exception.getMessage(), exception);
                            }
                            response.sendRedirect(frontendUrl + "/?oauth_error=1");
                        }))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
