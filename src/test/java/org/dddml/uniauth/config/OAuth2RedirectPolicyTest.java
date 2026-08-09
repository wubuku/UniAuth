package org.dddml.uniauth.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2RedirectPolicyTest {

    private OAuth2RedirectPolicy policy;

    @BeforeEach
    void setUp() {
        FrontendProperties properties = new FrontendProperties();
        properties.setUrl("https://frontend.example.test");
        properties.setAllowedRedirectOrigins(List.of("https://alternate.example.test"));
        policy = new OAuth2RedirectPolicy(properties);
    }

    @Test
    void allowsConfiguredOriginsAndPreservesExistingQueryParameters() {
        assertThat(policy.errorRedirect(
                "https://alternate.example.test/oauth/complete?source=provider",
                "access denied"
        )).isEqualTo(
                "https://alternate.example.test/oauth/complete"
                        + "?source=provider&error=access%20denied"
        );
    }

    @Test
    void treatsDefaultHttpsPortAsTheSameOrigin() {
        assertThat(policy.errorRedirect(
                "https://frontend.example.test:443/oauth/complete",
                "failed"
        )).isEqualTo(
                "https://frontend.example.test:443/oauth/complete?error=failed"
        );
    }

    @Test
    void preservesTheConfiguredFrontendContextPath() {
        FrontendProperties properties = new FrontendProperties();
        properties.setUrl("https://frontend.example.test/console/");
        OAuth2RedirectPolicy contextPathPolicy = new OAuth2RedirectPolicy(properties);

        assertThat(contextPathPolicy.successRedirect())
                .isEqualTo("https://frontend.example.test/console/");
        assertThat(contextPathPolicy.loginErrorRedirect("failed"))
                .isEqualTo("https://frontend.example.test/console/login?error=failed");
        assertThat(contextPathPolicy.errorRedirect("https://evil.example/collect", "failed"))
                .isEqualTo("https://frontend.example.test/console/login?error=failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/collect",
            "https://frontend.example.test.evil.example/collect",
            "https://frontend.example.test@evil.example/collect",
            "https://frontend.example.test:444/collect",
            "https://frontend.example.test:/collect",
            "https://frontend.example.test:0/collect",
            "https://frontend.example.test:65536/collect",
            "//evil.example/collect",
            "javascript:alert(1)",
            "https://frontend.example.test/%250d%250aLocation:%2520https://evil.example"
    })
    void rejectsCrossOriginAndMalformedRedirectCandidates(String candidate) {
        assertThat(policy.errorRedirect(candidate, "failed"))
                .isEqualTo("https://frontend.example.test/login?error=failed");
    }
}
