package org.dddml.uniauth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
@Validated
@Getter
@Setter
public class CorsProperties {

    @NotEmpty
    private List<@NotBlank String> allowedOrigins = new ArrayList<>();

    @NotEmpty
    private List<@NotBlank String> allowedMethods = new ArrayList<>();

    @NotEmpty
    private List<@NotBlank String> allowedHeaders = new ArrayList<>();

    private List<@NotBlank String> exposedHeaders = new ArrayList<>();

    private boolean allowCredentials;

    @Min(0)
    private long maxAge;

    @AssertTrue(
            message = "app.cors.allowed-origins must contain exact HTTP(S) origins; "
                    + "wildcard origin is forbidden when credentials are enabled"
    )
    public boolean isAllowedOriginConfigurationValid() {
        return allowedOrigins.stream().allMatch(origin -> {
            if ("*".equals(origin)) {
                return !allowCredentials;
            }
            return HttpUrlSafety.isValidHttpOrigin(origin);
        });
    }
}
