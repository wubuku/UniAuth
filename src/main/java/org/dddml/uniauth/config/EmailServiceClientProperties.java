package org.dddml.uniauth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.Locale;

@ConfigurationProperties(prefix = "app.email.service")
@Validated
@Getter
@Setter
public class EmailServiceClientProperties {

    @NotBlank
    private String url = "http://localhost:8095";

    @Min(100)
    @Max(600_000)
    private long timeout = 5000;

    @Size(max = 1024)
    @Pattern(regexp = "\\A[^\\r\\n]*\\z")
    private String apiKey = "";

    @AssertTrue(
        message = "app.email.service.url must be an absolute HTTP(S) URL with a host "
            + "and without user info, query, or fragment"
    )
    public boolean isValidUrl() {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return scheme != null
                && ("http".equals(scheme.toLowerCase(Locale.ROOT))
                    || "https".equals(scheme.toLowerCase(Locale.ROOT)))
                && StringUtils.hasText(uri.getHost())
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
