package com.findoc.service.auth;

import com.findoc.dto.request.AuthRequest;
import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import com.findoc.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Test
    void authenticatesOnlyWithTheRequestedTenant() {
        UserRepository repository = mock(UserRepository.class);
        JwtService jwtService = new JwtService("test-secret-at-least-32-characters-long", 3600);
        Tenant tenant = mock(Tenant.class);
        User user = mock(User.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(user.getTenant()).thenReturn(tenant);
        when(user.getPasswordHash()).thenReturn(new BCryptPasswordEncoder().encode("password"));
        when(user.getId()).thenReturn(UUID.randomUUID());
        when(user.getUsername()).thenReturn("demo");
        when(repository.findByUsernameAndTenantIdAndDeletedAtIsNull("demo", TENANT_ID)).thenReturn(java.util.Optional.of(user));

        new AuthService(jwtService, repository, 3600).authenticate(new AuthRequest(TENANT_ID, "demo", "password"));

        verify(repository).findByUsernameAndTenantIdAndDeletedAtIsNull("demo", TENANT_ID);
    }

    @Test
    void rejectsAUserFromAnotherTenant() {
        UserRepository repository = mock(UserRepository.class);
        JwtService jwtService = new JwtService("test-secret-at-least-32-characters-long", 3600);
        UUID requestedTenant = UUID.randomUUID();
        when(repository.findByUsernameAndTenantIdAndDeletedAtIsNull("demo", requestedTenant))
            .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> new AuthService(jwtService, repository, 3600)
            .authenticate(new AuthRequest(requestedTenant, "demo", "password")))
            .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }
}