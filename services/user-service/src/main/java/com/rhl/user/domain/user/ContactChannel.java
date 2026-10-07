package com.rhl.user.domain.user;

import com.rhl.user.domain.DomainException;

import java.util.Locale;

/** Where one-time codes are sent: the account's email or phone number. */
public enum ContactChannel {
    EMAIL,
    PHONE;

    /** From a path segment such as {@code email} or {@code phone}. */
    public static ContactChannel parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw DomainException.rule("Channel must be email or phone");
        }
    }

    /** The channel of a normalized login identifier. */
    public static ContactChannel of(String normalizedIdentifier) {
        return normalizedIdentifier.contains("@") ? EMAIL : PHONE;
    }

    /** Enough to recognise the address without disclosing it: {@code a***@example.com}, {@code +849***678}. */
    public static String mask(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        if (at > 0) {
            return address.charAt(0) + "***" + address.substring(at);
        }
        return address.length() <= 7 ? "***"
                : address.substring(0, 4) + "***" + address.substring(address.length() - 3);
    }
}
