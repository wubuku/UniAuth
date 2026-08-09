package org.dddml.uniauth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.frontend")
@Validated
@Getter
@Setter
public class FrontendProperties {

    @NotBlank
    private String type = "react";

    @NotBlank
    private String url = "";

    private List<@NotBlank String> allowedRedirectOrigins = new ArrayList<>();

    @AssertTrue(
            message = "app.frontend.url must be an absolute HTTP(S) URL with a host "
                    + "and without user info, query, fragment, or control characters"
    )
    public boolean isFrontendUrlValid() {
        return HttpUrlSafety.isValidFrontendBaseUrl(url);
    }

    @AssertTrue(
            message = "app.frontend.allowed-redirect-origins must contain exact HTTP(S) origins"
    )
    public boolean isAllowedRedirectOriginConfigurationValid() {
        return allowedRedirectOrigins.stream().allMatch(HttpUrlSafety::isValidHttpOrigin);
    }
}
