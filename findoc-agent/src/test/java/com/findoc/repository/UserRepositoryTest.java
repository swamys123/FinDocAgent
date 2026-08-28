package com.findoc.repository;

import com.findoc.entity.Tenant;
import com.findoc.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserWithinTenantScope() {
        Tenant tenantA = tenantRepository.save(new Tenant("Tenant A"));
        Tenant tenantB = tenantRepository.save(new Tenant("Tenant B"));

        userRepository.save(new User(tenantA, "demo", "demo@findoc.local", "$2a$12$8xTq0x0Kj9L2r0g9qXu9ZOXrZ0F6Q3kH2avYIfnL6PMaVw3V4m7Qe"));
        userRepository.save(new User(tenantB, "demo", "demo@findoc.local", "$2a$12$8xTq0x0Kj9L2r0g9qXu9ZOXrZ0F6Q3kH2avYIfnL6PMaVw3V4m7Qe"));

        assertThat(userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull("demo", tenantA.getId())).isPresent();
        assertThat(userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull("demo", tenantB.getId())).isPresent();
        assertThat(userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull("demo", tenantA.getId()).get().getTenant().getId())
            .isEqualTo(tenantA.getId());
    }
}
