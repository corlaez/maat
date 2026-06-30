package com.corlaez.validation;

import java.util.Locale;

/**
 * Contract for enum types used as Maat error codes.
 *
 * <p>Implement this on your error-code enum to give Maat a way to produce a
 * human-readable message from a code — without Maat knowing anything about
 * i18n, message bundles, or string formatting. The message concern lives
 * entirely in your enum.
 *
 * <p>A {@link Locale} is always required explicitly — there is no default-locale
 * overload. This is intentional: locale must always be a deliberate caller
 * decision, never an implicit one.
 *
 * <pre>{@code
 * enum ErrorCode implements MaatCode {
 *     EMAIL_REQUIRED("validation.email.required"),
 *     EMAIL_MALFORMED("validation.email.malformed");
 *
 *     private final String i18nKey;
 *     ErrorCode(String i18nKey) { this.i18nKey = i18nKey; }
 *
 *     public String toMessage(Locale locale) {
 *         try {
 *             return ResourceBundle.getBundle("messages", locale).getString(i18nKey);
 *         } catch (MissingResourceException e) {
 *             return i18nKey; // fallback to key if bundle/locale not found
 *         }
 *     }
 * }
 * }</pre>
 */
public interface MaatCode {

    /**
     * Produce a human-readable message for this code in the given locale.
     * A locale is always required — never assume a default.
     */
    String toMessage(Locale locale, Object ... args);
}
