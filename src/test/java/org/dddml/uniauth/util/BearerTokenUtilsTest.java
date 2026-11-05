package org.dddml.uniauth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BearerTokenUtilsTest {

    @Test
    void rejectsNullHeader() {
        assertThat(BearerTokenUtils.extract(null)).isEmpty();
    }

    @Test
    void rejectsSchemeWithoutToken() {
        assertThat(BearerTokenUtils.extract("Bearer")).isEmpty();
        assertThat(BearerTokenUtils.extract("Bearer ")).isEmpty();
        assertThat(BearerTokenUtils.extract("Bearer    ")).isEmpty();
    }

    @Test
    void rejectsNonBearerSchemes() {
        assertThat(BearerTokenUtils.extract("Basic dXNlcjpwYXNz")).isEmpty();
        assertThat(BearerTokenUtils.extract("Token abc")).isEmpty();
        assertThat(BearerTokenUtils.extract("Bearer\tabc")).isEmpty();
    }

    @Test
    void acceptsCaseInsensitiveBearerScheme() {
        assertThat(BearerTokenUtils.extract("Bearer abc")).contains("abc");
        assertThat(BearerTokenUtils.extract("bearer abc")).contains("abc");
        assertThat(BearerTokenUtils.extract("BEARER abc")).contains("abc");
        assertThat(BearerTokenUtils.extract("BeArEr abc")).contains("abc");
    }

    @Test
    void preservesTokenContentForDownstreamValidation() {
        assertThat(BearerTokenUtils.extract("Bearer a.b.c")).contains("a.b.c");
        assertThat(BearerTokenUtils.extract("Bearer abc def")).contains("abc def");
    }
}
