package com.findoc.config;

import com.findoc.service.auth.JwtService;
import com.findoc.util.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/api/v1/auth/token", "/swagger-ui/**", "/v3/api-docs/**").permitAll().anyRequest().authenticated())
            .addFilterBefore(new TenantJwtFilter(jwtService), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(new MdcTraceFilter(), TenantJwtFilter.class);
        return http.build();
    }

    static final class TenantJwtFilter extends OncePerRequestFilter {
        private final JwtService jwtService;
        TenantJwtFilter(JwtService jwtService) { this.jwtService = jwtService; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            try {
                String header = request.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    var claims = jwtService.parse(header.substring(7));
                    UUID tenantId = UUID.fromString(claims.get("tenant_id", String.class));
                    UUID userId = UUID.fromString(claims.get("user_id", String.class));
                    TenantContext.set(tenantId, userId);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(claims.getSubject(), null, List.of()));
                }
                chain.doFilter(request, response);
            } catch (RuntimeException ex) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid bearer token");
            } finally {
                TenantContext.clear();
                SecurityContextHolder.clearContext();
            }
        }
    }
}
