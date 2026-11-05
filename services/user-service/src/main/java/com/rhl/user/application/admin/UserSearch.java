package com.rhl.user.application.admin;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What an administrator typed in the user lookup, turned into the search parameters: an exact id,
 * a "contains" pattern for email and name, and a digits pattern for the phone number.
 *
 * @param id    set when the text is a UUID; the other patterns are then null
 * @param text  lower-case LIKE pattern for email and full name, wildcards escaped with '!'
 * @param phone LIKE pattern on the stored E.164 number, or null when the text has too few digits
 */
public record UserSearch(UUID id, String text, String phone) {

    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** Fewer digits than this match far too many numbers to be useful. */
    private static final int MIN_PHONE_DIGITS = 3;

    public static final UserSearch ANY = new UserSearch(null, null, null);

    public static UserSearch of(String raw) {
        if (raw == null || raw.isBlank()) {
            return ANY;
        }
        String query = raw.strip();
        if (UUID_TEXT.matcher(query).matches()) {
            return new UserSearch(UUID.fromString(query), null, null);
        }
        String text = "%" + escape(query.toLowerCase(Locale.ROOT)) + "%";
        return new UserSearch(null, text, phonePattern(query));
    }

    /**
     * Phones are stored as E.164 (+84912345678) while people type them the local way
     * (0912 345 678): keep the digits and drop a leading 0 so both forms match.
     */
    private static String phonePattern(String query) {
        if (!query.matches("^[+0-9 .()-]+$")) {
            return null;
        }
        String digits = query.replaceAll("\\D", "");
        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.length() < MIN_PHONE_DIGITS ? null : "%" + digits + "%";
    }

    private static String escape(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
