package com.corlaez.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Maat helps to define validation constraints and enforce them.
 * Similar to jakarta validation, all violations are gathered in one place and reported together; in contrast to it
 * constraints are defined at validation sites and external to data declaration.
 *
 * Validation rules should not be collocated with data classes as they usually differ from use case
 * to use case.
 *
 *   - intrinsic (parse) errors      -> recorded by Data objects   (Source.DATA)
 *   - contextual (use case) errors  -> recorded by the Context     (Source.CONTEXT)
 *
 * This is the DCI-compatible answer to "Jakarta Validation can show many
 * violations at once": instead of each value object throwing on the first bad
 * field (fail-fast, one error per round-trip), the value object records into the
 * shared Notification and remains constructible-but-tainted. The Context inspects
 * {@link #hasErrors()} before enacting the use case and never uses a tainted object.
 *
 * <p>Java 8 compatible: no records, no {@code List.copyOf}, no {@code Stream.toList},
 * no {@code String.isBlank} — those are reimplemented or avoided below.
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * Notification n = new Notification();                 // created in the Interaction
 *
 * EmailAddress email = new EmailAddress(form.email(), n);   // DATA parse errors -> n
 * Password     pw    = new Password(form.password(), n);    // DATA parse errors -> n
 *
 * Notification.Scope c = n.forContext("UserRegistration");  // CONTEXT rules -> n
 * c.require(!users.existsByEmail(form.email()), "email", "email.taken",  "Email already registered");
 * c.require(form.acceptedTerms(),               "terms", "terms.required","You must accept the terms");
 *
 * if (n.hasErrors()) return Result.rejected(n.violations()); // report ALL at once
 * // ...enact
 * }</pre>
 *
 * <h2>Inside a Data constructor</h2>
 * <pre>{@code
 * public EmailAddress(String raw, Notification n) {
 *     Notification.Scope v = n.forData("EmailAddress");
 *     v.require(raw != null && raw.trim().length() > 0,   "email", "email.required",  "Email is required");
 *     v.require(raw != null && raw.matches(".+@.+\\..+"), "email", "email.malformed", "Email is malformed");
 *     this.value = raw; // best-effort; Context discards this object if n.hasErrors()
 * }
 * }</pre>
 *
 * <p>Not thread-safe by design: a use-case enactment is single-threaded within
 * its Context. If you enact concurrently, wrap the mutating methods or collect
 * into per-thread Notifications and {@link #merge(Notification)} them.
 */
public final class Maat {

    /** Where a violation originated, so reports can separate parse vs use-case failures. */
    public enum Source { DATA, CONTEXT }

    public enum Severity { WARNING, ERROR }

    /**
     * A single recorded problem. Plain immutable value class (Java 8 — no record).
     */
    public static final class Violation {
        private final Source source;     // DATA (intrinsic/parse) or CONTEXT (use case)
        private final String scope;      // originating type, e.g. "EmailAddress" (nullable)
        private final String field;      // offending field/role, e.g. "email" (nullable)
        private final String code;       // machine-readable key, e.g. "email.malformed" (nullable)
        private final String message;    // human-readable, user-facing message
        private final Severity severity; // ERROR blocks enactment; WARNING does not

        public Violation(Source source, String scope, String field,
                         String code, String message, Severity severity) {
            this.source = Objects.requireNonNull(source, "source");
            this.message = Objects.requireNonNull(message, "message");
            this.scope = scope;
            this.field = field;
            this.code = code;
            this.severity = (severity == null) ? Severity.ERROR : severity;
        }

        public Source source()     { return source; }
        public String scope()      { return scope; }
        public String field()      { return field; }
        public String code()       { return code; }
        public String message()    { return message; }
        public Severity severity() { return severity; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Violation)) return false;
            Violation that = (Violation) o;
            return source == that.source
                    && severity == that.severity
                    && Objects.equals(scope, that.scope)
                    && Objects.equals(field, that.field)
                    && Objects.equals(code, that.code)
                    && Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, scope, field, code, message, severity);
        }

        @Override
        public String toString() {
            String loc = (scope == null ? "" : scope) + (field == null ? "" : "." + field);
            return "[" + severity + "] "
                    + (loc.isEmpty() ? "" : loc + ": ")
                    + message
                    + (code == null ? "" : " (" + code + ")");
        }
    }

    private final List<Violation> violations = new ArrayList<Violation>();

    // ---- raw recording -----------------------------------------------------

    /** Record a violation directly. Returns {@code this} for chaining. */
    public Notification add(Source source, String scope, String field,
                            String code, String message, Severity severity) {
        violations.add(new Violation(source, scope, field, code, message, severity));
        return this;
    }

    /**
     * Guard helper: records an ERROR only when {@code ok} is false.
     * Returns {@code ok} so callers can also branch on the result.
     */
    public boolean require(boolean ok, Source source, String scope, String field,
                           String code, String message) {
        if (!ok) add(source, scope, field, code, message, Severity.ERROR);
        return ok;
    }

    // ---- scoped recorders --------------------------------------------------

    /** A recorder pre-tagged as DATA + the given type (for a Data constructor). */
    public Scope forData(String scope) { return new Scope(Source.DATA, scope); }

    /** A recorder pre-tagged as CONTEXT + the given type (for the Interaction). */
    public Scope forContext(String scope) { return new Scope(Source.CONTEXT, scope); }

    // ---- queries -----------------------------------------------------------

    public boolean hasErrors() {
        return violations.stream().anyMatch(v -> v.severity() == Severity.ERROR);
    }

    public boolean isClean() { return !hasErrors(); }

    public boolean hasErrorsFor(String field) {
        return violations.stream()
                .anyMatch(v -> v.severity() == Severity.ERROR && Objects.equals(v.field(), field));
    }

    /** Defensive, unmodifiable copy of all violations. */
    public List<Violation> violations() {
        return Collections.unmodifiableList(new ArrayList<Violation>(violations));
    }

    public List<Violation> violations(Source source) {
        return violations.stream()
                .filter(v -> v.source() == source)
                .collect(Collectors.toList());
    }

    public List<String> messages() {
        return violations.stream()
                .map(Violation::message)
                .collect(Collectors.toList());
    }

    // ---- composition & boundary --------------------------------------------

    /** Fold another Notification's violations into this one. */
    public Notification merge(Notification other) {
        this.violations.addAll(other.violations);
        return this;
    }

    /**
     * Throw at a boundary that prefers exceptions over a result object.
     * Note: in DbC terms this is for the *input boundary* (expected bad input),
     * not for programmer-bug assertions deeper in the model.
     */
    public void throwIfErrors() {
        if (hasErrors()) throw new ValidationException(this);
    }

    @Override
    public String toString() {
        if (violations.isEmpty()) return "Notification: clean";
        return "Notification (" + violations.size() + "):\n"
                + violations.stream().map(v -> "  " + v).collect(Collectors.joining("\n"));
    }

    /**
     * Java 8 replacement for {@code String.isBlank()} (JDK 11+).
     * Treats null as blank. Whitespace per {@link Character#isWhitespace(char)}.
     */
    static boolean isBlank(CharSequence cs) {
        if (cs == null) return true;
        for (int i = 0, len = cs.length(); i < len; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A lightweight view that tags every error it records with a fixed
     * {@link Source} and scope, so a Data constructor or Context doesn't repeat
     * its own type name on every check.
     */
    public final class Scope {
        private final Source source;
        private final String scope;

        Scope(Source source, String scope) {
            this.source = source;
            this.scope = scope;
        }

        /** Records an ERROR when {@code ok} is false. Returns {@code ok}. */
        public boolean require(boolean ok, String field, String code, String message) {
            return Notification.this.require(ok, source, scope, field, code, message);
        }

        public Scope error(String field, String code, String message) {
            add(source, scope, field, code, message, Severity.ERROR);
            return this;
        }

        public Scope warn(String field, String code, String message) {
            add(source, scope, field, code, message, Severity.WARNING);
            return this;
        }

        /**
         * Begin a fluent, short-circuiting validation chain for one field.
         * Every error the chain records inherits this scope's Source + scope,
         * so the DCI origin tagging is preserved.
         */
        public <T> Field<T> field(String field, T value) {
            return new Field<T>(Notification.this, source, scope, field, value);
        }
    }

    /**
     * A fluent, single-field validation chain that records into the enclosing
     * Notification with a fixed Source + scope.
     *
     * <p>This is the Validly-style ergonomics layer — declarative verbs, a
     * type-changing {@code convert} (parse-as-validation), and per-field
     * short-circuit — but it does NOT fight DCI: you only obtain a Field from
     * {@link Scope#field(String, Object)} (i.e. via {@code forData(...)} or
     * {@code forContext(...)}), so the collector stays Context-owned and every
     * error remains tagged by origin. There is no separate validator object;
     * the chain lives wherever the Data constructor or Context already is.
     *
     * <p>Semantics:
     * <ul>
     *   <li><b>Short-circuit per field:</b> once a step fails, later steps in
     *       this chain are skipped — one bad value yields one error, not a
     *       cascade of follow-on errors.</li>
     *   <li><b>Optional by default:</b> only the presence checks ({@link #notNull},
     *       {@link #notBlank}) fail on null; other verbs skip a null value.
     *       Lead with a presence check to make a field required.</li>
     *   <li><b>convert short-circuits:</b> a throwing or null-returning
     *       conversion records an error and stops the chain, mirroring Validly's
     *       mustConvert — even though the overall Notification is "note-all".</li>
     * </ul>
     *
     * <pre>{@code
     * // DATA: intrinsic parse + format, valid-or-noted by construction
     * this.value = n.forData("EmailAddress")
     *         .field("email", raw)
     *         .notBlank("email.required",  "Email is required")
     *         .matches(".+@.+\\..+", "email.malformed", "Email is malformed")
     *         .convert(String::toLowerCase, "email.malformed", "Email is malformed")
     *         .orElse(null);   // Context discards this object if n.hasErrors()
     *
     * // CONTEXT: a conditional, contextual rule
     * n.forContext("UserRegistration")
     *         .field("ssn", form.ssn())
     *         .when(form.age() >= 18, f -> f.notBlank("ssn.required", "Required for adults"));
     * }</pre>
     */
    public static final class Field<T> {
        private final Notification note;
        private final Source source;
        private final String scope;
        private final String field;
        private T value;
        private boolean failed;

        Field(Notification note, Source source, String scope, String field, T value) {
            this.note = note;
            this.source = source;
            this.scope = scope;
            this.field = field;
            this.value = value;
        }

        private Field<T> fail(String code, String message) {
            note.add(source, scope, field, code, message, Severity.ERROR);
            failed = true;
            return this;
        }

        // ---- presence (use these to make a field required) -----------------

        public Field<T> notNull(String code, String message) {
            if (failed) return this;
            return value == null ? fail(code, message) : this;
        }

        public Field<T> notBlank(String code, String message) {
            if (failed) return this;
            return (value == null || isBlank(value.toString())) ? fail(code, message) : this;
        }

        // ---- generic rule (skips a null value: optional by default) --------

        public Field<T> must(Predicate<? super T> rule, String code, String message) {
            if (failed || value == null) return this;
            return rule.test(value) ? this : fail(code, message);
        }

        // ---- string conveniences (assume a CharSequence value) -------------

        public Field<T> minLength(int min, String code, String message) {
            return must(v -> v.toString().length() >= min, code, message);
        }

        public Field<T> maxLength(int max, String code, String message) {
            return must(v -> v.toString().length() <= max, code, message);
        }

        public Field<T> matches(String regex, String code, String message) {
            return must(v -> v.toString().matches(regex), code, message);
        }

        // ---- conditional (Validly's when/Then, contextual rules) -----------

        /** Applies {@code block} only when {@code condition} holds and the chain is still clean. */
        public Field<T> when(boolean condition, Consumer<Field<T>> block) {
            if (!failed && condition) block.accept(this);
            return this;
        }

        // ---- type-changing parse (Validly's mustConvert) -------------------

        /**
         * Parse/convert the value to a new type. On exception or null result,
         * records an error and short-circuits; subsequent steps see a failed
         * chain. A null input (optional, absent) is carried forward untouched.
         */
        public <R> Field<R> convert(Function<? super T, ? extends R> fn,
                                    String code, String message) {
            Field<R> next = new Field<R>(note, source, scope, field, null);
            next.failed = this.failed;
            if (failed || value == null) return next;
            try {
                R converted = fn.apply(value);
                if (converted == null) next.fail(code, message);
                else next.value = converted;
            } catch (RuntimeException ex) {
                next.fail(code, message);
            }
            return next;
        }

        // ---- terminals -----------------------------------------------------

        /** True if this field accumulated no error (the whole Notification may still have others). */
        public boolean ok() { return !failed; }

        /** Best-effort value: the (possibly converted) value, or null if a step failed. */
        public T get() { return failed ? null : value; }

        public Optional<T> optional() { return Optional.ofNullable(get()); }

        public T orElse(T fallback) { return failed || value == null ? fallback : value; }
    }

    /** Carries the full Notification when a boundary chooses to throw rather than return. */
    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Notification notification;

        public ValidationException(Notification notification) {
            super(notification.toString());
            this.notification = notification;
        }

        public Notification notification() { return notification; }
    }
}