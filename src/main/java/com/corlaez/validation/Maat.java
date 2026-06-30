package com.corlaez.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Maat — a validation recording context, parameterized on an enum of error codes.
 *
 * <p>{@code T} must be an enum that implements {@link MaatCode}. Every recorded
 * failure carries a {@code T} code plus optional template {@code args} (for
 * messages like {@code "Must be at least {0} characters"}); human-readable
 * messages are the enum's concern, not Maat's. A {@link Locale} is always
 * required explicitly when rendering messages.
 *
 * <p>Write side and read side are split on purpose:
 * <ul>
 *   <li>Maat is write-only: entry points are {@link #value}, {@link #scope},
 *       and the static {@link #inline}.</li>
 *   <li>{@link Report} is the immutable read side, returned by {@link #report()}.</li>
 * </ul>
 *
 * <pre>{@code
 * Maat<ErrorCode> maat = new Maat<>();
 *
 * maat.value("name", form.name())
 *     .notBlank(ErrorCode.NAME_REQUIRED);
 *
 * maat.value("bio", form.bio())
 *     .minLength(10, ErrorCode.MIN_LENGTH); // args = [10], for "{0} chars min"
 *
 * Email email = new Email(form.email(), maat.scope("email"));
 *
 * Report<ErrorCode> report = maat.report();
 * if (report.hasErrors()) return Result.rejected(report.violations());
 * }</pre>
 *
 * <p>Java 8 compatible. Not thread-safe by design.
 */
public final class Maat<T extends Enum<T> & MaatCode> {

    /** A single recorded failure: a path, a code, and optional template args. */
    public static final class Violation<T extends Enum<T> & MaatCode> {
        private final String path; // e.g. "email" or "shipping.city" (nullable)
        private final T code;
        private final Object[] args;

        public Violation(String path, T code, Object... args) {
            this.code = Objects.requireNonNull(code, "code");
            this.path = path;
            this.args = (args == null) ? new Object[0] : args;
        }

        public String path()    { return path; }
        public T code()         { return code; }

        /** Template args passed to this violation (empty array if none). */
        public Object[] args()  { return args.clone(); }

        /** Human-readable message for this violation in the given locale. */
        public String toMessage(Locale locale) { return code.toMessage(locale, args); }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Violation)) return false;
            Violation<?> that = (Violation<?>) o;
            return Objects.equals(path, that.path)
                    && Objects.equals(code, that.code);
            // args intentionally excluded from equality: same path+code is the
            // same logical violation regardless of template parameters.
        }

        @Override
        public int hashCode() { return Objects.hash(path, code); }

        /** Code-only representation. Use {@link #toMessage(Locale)} for human rendering. */
        @Override
        public String toString() {
            String p = (path == null) ? "" : path;
            return (p.isEmpty() ? "" : p + ": ") + code.name();
        }
    }

    /**
     * Immutable read side. Returned by {@link Maat#report()}.
     * All query methods live here; Maat itself exposes none.
     */
    public static final class Report<T extends Enum<T> & MaatCode> {
        private final List<Violation<T>> violations;

        Report(List<Violation<T>> source) {
            this.violations = Collections.unmodifiableList(new ArrayList<Violation<T>>(source));
        }

        public List<Violation<T>> violations() { return violations; }

        public boolean isValid()   { return violations.isEmpty(); }
        public boolean hasErrors() { return !violations.isEmpty(); }

        public boolean hasErrorsFor(String path) {
            for (Violation<T> v : violations) {
                if (Objects.equals(v.path(), path)) return true;
            }
            return false;
        }

        /** A new report combining this one's violations with {@code other}'s. */
        public Report<T> plus(Report<T> other) {
            List<Violation<T>> combined = new ArrayList<Violation<T>>(this.violations);
            combined.addAll(other.violations);
            return new Report<T>(combined);
        }

        /**
         * Throw {@link ValidationException} if not valid.
         * A locale is required to render the exception message.
         */
        public void throwIfInvalid(Locale locale) {
            if (hasErrors()) throw new ValidationException(this, locale);
        }

        /** Code-only representation. Use {@link Violation#toMessage(Locale)} for human rendering. */
        @Override
        public String toString() {
            if (violations.isEmpty()) return "Report: clean";
            return "Report (" + violations.size() + "):\n"
                    + violations.stream().map(v -> "  " + v).collect(Collectors.joining("\n"));
        }
    }

    private final List<Violation<T>> sink = new ArrayList<Violation<T>>();

    // ---- public entry points: value + scope only ---------------------------

    /** Begin a collect-only chain for a top-level field. */
    public <V> Value<T, V> value(String name, V value) {
        return new Value<T, V>(this, null, name, value);
    }

    /** A prefixing view of this Maat. Pass into value-object constructors. */
    public MaatScope<T> scope(String name) {
        return new MaatScope<T>(this, name);
    }

    /**
     * Static, report-free chain for a single value, tied to no Maat.
     * Ends in {@link MaatInline#orElse}, {@link MaatInline#get(Locale)}, or
     * {@link MaatInline#orElseAndReport}.
     */
    public static <V, T extends Enum<T> & MaatCode> MaatInline<T, V> inline(V value) {
        return new MaatInline<T, V>(value);
    }

    // ---- terminal: produce the immutable read side -------------------------

    /** Snapshot everything recorded so far into an immutable {@link Report}. */
    public Report<T> report() {
        return new Report<>(sink);
    }

    // ---- internal plumbing -------------------------------------------------

    Maat<T> record(String path, T code, Object... args) {
        sink.add(new Violation<T>(path, code, args));
        return this;
    }

    static String join(String prefix, String name) {
        boolean hasP = prefix != null && !prefix.isEmpty();
        boolean hasN = name != null && !name.isEmpty();
        if (hasP && hasN) return prefix + "." + name;
        if (hasP) return prefix;
        return hasN ? name : null;
    }

    static boolean isBlank(CharSequence cs) {
        if (cs == null) return true;
        for (int i = 0, len = cs.length(); i < len; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) return false;
        }
        return true;
    }

    // ---- MaatScope ---------------------------------------------------------

    /** Prefixing view of a Maat. Only permits {@code value} and {@code scope}. */
    public static final class MaatScope<T extends Enum<T> & MaatCode> {
        private final Maat<T> maat;
        private final String prefix;

        MaatScope(Maat<T> maat, String prefix) {
            this.maat = maat;
            this.prefix = prefix;
        }

        public String path() { return prefix; }

        public MaatScope<T> scope(String name) {
            return new MaatScope<T>(maat, join(prefix, name));
        }

        public <V> Value<T, V> value(String name, V value) {
            return new Value<T, V>(maat, prefix, name, value);
        }

        void reportHere(T code, Object... args) {
            maat.record(prefix, code, args);
        }
    }

    // ---- Check base --------------------------------------------------------

    /**
     * Shared validation verbs. Self-type pattern; delegates failure recording
     * to {@link #capture}, which also receives any template {@code args}.
     * Short-circuits per field after the first failure.
     */
    abstract static class Check<SELF extends Check<SELF, T, V>, T extends Enum<T> & MaatCode, V> {
        V value;
        boolean failed;

        Check(V value) { this.value = value; }

        abstract SELF self();
        abstract void capture(T code, Object... args);

        final SELF fail(T code, Object... args) {
            if (!failed) {
                capture(code, args);
                failed = true;
            }
            return self();
        }

        public SELF notNull(T code) {
            if (failed) return self();
            return value == null ? fail(code) : self();
        }

        public SELF notBlank(T code) {
            if (failed) return self();
            return (value == null || isBlank(value.toString())) ? fail(code) : self();
        }

        public SELF must(Predicate<? super V> rule, T code, Object... args) {
            if (failed || value == null) return self();
            return rule.test(value) ? self() : fail(code, args);
        }

        /** Fails with {@code args = [min]} so the code's template can render it. */
        public SELF minLength(int min, T code) {
            return must(v -> v.toString().length() >= min, code, min);
        }

        /** Fails with {@code args = [max]} so the code's template can render it. */
        public SELF maxLength(int max, T code) {
            return must(v -> v.toString().length() <= max, code, max);
        }

        public SELF matches(String regex, T code) {
            return must(v -> v.toString().matches(regex), code);
        }

        public SELF when(boolean condition, Consumer<SELF> block) {
            if (!failed && condition) block.accept(self());
            return self();
        }
    }

    // ---- Value -------------------------------------------------------------

    /** Collect-only chain. No {@code get}/{@code orElse}. */
    public static final class Value<T extends Enum<T> & MaatCode, V>
            extends Check<Value<T, V>, T, V> {

        private final Maat<T> maat;
        private final String prefix;
        private final String name;

        Value(Maat<T> maat, String prefix, String name, V value) {
            super(value);
            this.maat = maat;
            this.prefix = prefix;
            this.name = name;
        }

        @Override Value<T, V> self() { return this; }

        @Override void capture(T code, Object... args) {
            maat.record(join(prefix, name), code, args);
        }

        /** Chain to the next field in the same scope. */
        public <R> Value<T, R> value(String name, R value) {
            return new Value<T, R>(maat, prefix, name, value);
        }

        public <R> Value<T, R> convert(Function<? super V, ? extends R> fn, T code) {
            Value<T, R> next = new Value<T, R>(maat, prefix, name, null);
            next.failed = this.failed;
            if (failed || value == null) return next;
            try {
                R converted = fn.apply(value);
                if (converted == null) next.fail(code);
                else next.value = converted;
            } catch (RuntimeException ex) {
                next.fail(code);
            }
            return next;
        }
    }

    // ---- MaatInline --------------------------------------------------------

    /** Static, report-free chain. Captures the first failure locally. */
    public static final class MaatInline<T extends Enum<T> & MaatCode, V>
            extends Check<MaatInline<T, V>, T, V> {

        private T failCode;
        private Object[] failArgs = new Object[0];

        MaatInline(V value) { super(value); }

        @Override MaatInline<T, V> self() { return this; }

        @Override void capture(T code, Object... args) {
            this.failCode = code;
            this.failArgs = (args == null) ? new Object[0] : args;
        }

        public <R> MaatInline<T, R> convert(Function<? super V, ? extends R> fn, T code) {
            MaatInline<T, R> next = new MaatInline<T, R>(null);
            next.failed = this.failed;
            next.failCode = this.failCode;
            next.failArgs = this.failArgs;
            if (failed || value == null) return next;
            try {
                R converted = fn.apply(value);
                if (converted == null) next.fail(code);
                else next.value = converted;
            } catch (RuntimeException ex) {
                next.fail(code);
            }
            return next;
        }

        public boolean ok() { return !failed; }

        /** Value or fallback. Never reports. */
        public V orElse(V fallback) { return (failed || value == null) ? fallback : value; }

        /**
         * Value, or throws {@link IllegalArgumentException} using
         * {@code failCode.toMessage(locale, failArgs)}. Never reports.
         * A locale is required — it is your responsibility to pass the right one.
         */
        public V get(Locale locale) {
            if (failed) throw new IllegalArgumentException(failCode.toMessage(locale, failArgs));
            return value;
        }

        public Optional<V> optional() {
            return failed ? Optional.<V>empty() : Optional.ofNullable(value);
        }

        /** On failure, records the code (with its args) at root into {@code maat}; returns value or fallback. */
        public V orElseAndReport(V fallback, Maat<T> maat) {
            if (failed) {
                maat.record(null, failCode, failArgs);
                return fallback;
            }
            return value;
        }

        /** On failure, records the code (with its args) at the scope's path; returns value or fallback. */
        public V orElseAndReport(V fallback, MaatScope<T> scope) {
            if (failed) {
                scope.reportHere(failCode, failArgs);
                return fallback;
            }
            return value;
        }
    }

    // ---- ValidationException -----------------------------------------------

    /**
     * Carries the {@link Report} when a boundary chooses to throw.
     *
     * <p>Not generic: Java forbids generic Throwables (JLS 8.1.2 — type erasure
     * makes them unverifiable at catch sites). The report is stored as
     * {@code Report<?>}; use {@link #report(Class)} for a typed accessor.
     */
    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Report<?> report;

        public ValidationException(Report<?> report, Locale locale) {
            super(report.violations().stream()
                    .map(v -> v.toString() + ": " + v.toMessage(locale))
                    .collect(Collectors.joining("\n")));
            this.report = report;
        }

        /** Wildcard accessor — sufficient when you only need messages or paths. */
        public Report<?> report() { return report; }

        /**
         * Typed accessor for when you know the code enum at the catch site.
         * Safe as long as you pass the same enum type used to create the
         * originating {@link Maat} instance.
         */
        @SuppressWarnings("unchecked")
        public <T extends Enum<T> & MaatCode> Report<T> report(Class<T> codeType) {
            return (Report<T>) report;
        }
    }
}