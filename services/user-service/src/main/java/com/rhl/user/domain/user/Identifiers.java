package com.rhl.user.domain.user;

import com.rhl.user.domain.DomainException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes login identifiers before they are stored or looked up (FR-IAM-002), so
 * {@code "A@x.com "} and {@code "a@x.com"} or {@code 0912...} and {@code +84912...} are the same
 * account.
 */
public final class Identifiers {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern VN_LOCAL = Pattern.compile("^0[35789]\\d{8}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private Identifiers() {
    }

    public static String email(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !EMAIL.matcher(value).matches()) {
            throw DomainException.rule("Email address is not valid");
        }
        return value;
    }

    /** Vietnamese mobile numbers ({@code 09xxxxxxxx}) or any E.164 number, stored as E.164. */
    public static String phone(String raw) {
        String value = raw.replaceAll("[\\s.()-]", "");
        if (VN_LOCAL.matcher(value).matches()) {
            return "+84" + value.substring(1);
        }
        if (value.startsWith("84") && VN_LOCAL.matcher("0" + value.substring(2)).matches()) {
            return "+" + value;
        }
        if (E164.matcher(value).matches()) {
            return value;
        }
        throw DomainException.rule("Phone number is not valid");
    }

    /** Email when the identifier contains {@code @}, phone number otherwise. */
    public static String loginIdentifier(String raw) {
        return raw.contains("@") ? email(raw) : phone(raw);
    }
}
