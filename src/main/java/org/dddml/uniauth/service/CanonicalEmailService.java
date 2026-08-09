package org.dddml.uniauth.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class CanonicalEmailService {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final Pattern LOCAL_PART = Pattern.compile(
            "[a-z0-9.!#$%&'*+/=?^_`{|}~-]+"
    );
    private static final Pattern DOMAIN_LABEL = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    );

    public String canonicalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Invalid email address");
        }
        String canonical = input.trim().toLowerCase(Locale.ROOT);
        if (canonical.isEmpty()
                || canonical.length() > MAX_EMAIL_LENGTH
                || canonical.codePoints().anyMatch(this::isRejectedCharacter)) {
            throw new IllegalArgumentException("Invalid email address");
        }

        int separator = canonical.indexOf('@');
        if (separator <= 0
                || separator != canonical.lastIndexOf('@')
                || separator > MAX_LOCAL_PART_LENGTH
                || separator == canonical.length() - 1) {
            throw new IllegalArgumentException("Invalid email address");
        }

        String localPart = canonical.substring(0, separator);
        String domain = canonical.substring(separator + 1);
        if (!LOCAL_PART.matcher(localPart).matches()
                || localPart.startsWith(".")
                || localPart.endsWith(".")
                || localPart.contains("..")
                || !isValidDomain(domain)) {
            throw new IllegalArgumentException("Invalid email address");
        }
        return canonical;
    }

    public boolean looksLikeEmail(String input) {
        return input != null && input.indexOf('@') >= 0;
    }

    public String canonicalizeLoginIdentifier(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()
                || trimmed.length() > 255
                || trimmed.codePoints().anyMatch(this::isRejectedCharacter)) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return looksLikeEmail(trimmed) ? canonicalize(trimmed) : trimmed;
    }

    private boolean isValidDomain(String domain) {
        if (domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private boolean isRejectedCharacter(int character) {
        return character > 0x7f || Character.isISOControl(character);
    }
}
