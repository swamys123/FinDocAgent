package com.findoc.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DemoSeedPasswordTest {

    @Test
    void demoSeedPasswordShouldMatchDocumentedPassword() throws IOException {
        String content = Files.readString(Path.of("src/main/resources/db/changelog/db.changelog-001-init.xml"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("<column name=\"password_hash\" value=\"([^\"]+)\"/>")
            .matcher(content);

        assertThat(matcher.find()).isTrue();

        String hash = matcher.group(1);
        assertThat(new BCryptPasswordEncoder().matches("demo123", hash)).isTrue();
    }
}
