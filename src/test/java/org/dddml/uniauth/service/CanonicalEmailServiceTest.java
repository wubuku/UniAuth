package org.dddml.uniauth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical email is the identity key for challenges, registration and
 * password reset. Every rejection branch here is a fail-closed boundary: if a
 * refactor accidentally accepts a malformed address, two distinct identities
 * could collapse into one stored row.
 */
class CanonicalEmailServiceTest {

    private final CanonicalEmailService service = new CanonicalEmailService();

    @Test
    void normalizesCaseAndSurroundingWhitespace() {
        assertThat(service.canonicalize("  User.Name+Tag@Example.COM  "))
                .isEqualTo("user.name+tag@example.com");
    }

    @Test
    void rejectsNullAndBlankInput() {
        assertThatThrownBy(() -> service.canonicalize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.canonicalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAddressesLongerThanTheMaximum() {
        // 64 + 1 + 189 = 254 characters, the largest accepted address.
        String valid = "a".repeat(64) + "@"
                + "b".repeat(63) + "." + "c".repeat(62) + "." + "d".repeat(62);
        assertThat(valid).hasSize(254);
        assertThat(service.canonicalize(valid)).hasSize(254);

        // 64 + 1 + 190 = 255 characters must be rejected.
        String tooLong = "a".repeat(64) + "@"
                + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);
        assertThat(tooLong).hasSize(255);
        assertThatThrownBy(() -> service.canonicalize(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonAsciiAndControlCharacters() {
        assertThatThrownBy(() -> service.canonicalize("üser@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.canonicalize("user\u0000@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingMultipleOrMisplacedAtSign() {
        for (String invalid : new String[]{
                "userexample.com",
                "@example.com",
                "user@",
                "user@@example.com",
                "user@example@com"
        }) {
            assertThatThrownBy(() -> service.canonicalize(invalid))
                    .as("input: %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsLocalPartLongerThanSixtyFour() {
        assertThatThrownBy(() -> service.canonicalize(
                "a".repeat(65) + "@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidLocalPartShapes() {
        for (String invalid : new String[]{
                ".user@example.com",
                "user.@example.com",
                "us..er@example.com",
                "us er@example.com"
        }) {
            assertThatThrownBy(() -> service.canonicalize(invalid))
                    .as("input: %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsInvalidDomainShapes() {
        for (String invalid : new String[]{
                "user@example",
                "user@.example.com",
                "user@example.com.",
                "user@-example.com",
                "user@example-.com",
                "user@exa_mple.com",
                "user@" + "a".repeat(64) + ".com",
                "user@example..com"
        }) {
            assertThatThrownBy(() -> service.canonicalize(invalid))
                    .as("input: %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void looksLikeEmailOnlyRequiresAnAtSign() {
        assertThat(service.looksLikeEmail(null)).isFalse();
        assertThat(service.looksLikeEmail("username")).isFalse();
        assertThat(service.looksLikeEmail("user@example.com")).isTrue();
        assertThat(service.looksLikeEmail("not-an-email@")).isTrue();
        // Any '@' at all, including index zero, routes the value through
        // email canonicalization so a malformed address cannot be treated as
        // a plain username.
        assertThat(service.looksLikeEmail("@example.com")).isTrue();
    }

    @Test
    void loginIdentifierAcceptsAnExactlyMaximumLengthUsername() {
        String maximum = "a".repeat(255);

        assertThat(service.canonicalizeLoginIdentifier(maximum))
                .isEqualTo(maximum);
    }

    @Test
    void loginIdentifierKeepsUsernamesAndCanonicalizesEmails() {
        assertThat(service.canonicalizeLoginIdentifier("  alice  "))
                .isEqualTo("alice");
        assertThat(service.canonicalizeLoginIdentifier(" Alice@Example.COM "))
                .isEqualTo("alice@example.com");
    }

    @Test
    void loginIdentifierRejectsEmptyOverlongAndControlCharacters() {
        assertThatThrownBy(() -> service.canonicalizeLoginIdentifier(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.canonicalizeLoginIdentifier("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.canonicalizeLoginIdentifier(
                "a".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.canonicalizeLoginIdentifier(
                "ali\u0000ce"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginIdentifierStillRejectsInvalidEmails() {
        assertThatThrownBy(() -> service.canonicalizeLoginIdentifier(
                "user@example"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
