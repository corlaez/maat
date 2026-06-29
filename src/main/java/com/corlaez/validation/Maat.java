package com.corlaez.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Maat — a validation <b>recording context</b>. You create one, thread it through
 * your validation as {@link #value} / {@link #scope} chains, then call
 * {@link #report()} to get an immutable {@link Report} of everything that failed.
 *
 * <p>Write side and read side are split on purpose. Maat is write-only: its only
 * public entry points are {@link #value(String, Object)} and {@link #scope(String)}
 * (plus the static, report-free {@link #inline(Object)}). The accumulated failures
 * live privately inside it; you never touch a mutable list. The read side is
 * {@link Report}, an immutable snapshot returned by {@link #report()}.
 *
 * <p>The mutable accumulation is an intentional, encapsulated compromise: an object
 * that validates itself in its constructor can only hand failures back by
 * side-effecting a shared sink (a constructor returns the object, not a list). That
 * sink is hidden; the value you pass around and read is the immutable Report.
 *
 * <p>Like Jakarta Bean Validation, it is binary: a recorded item is a failure, full
 * stop — no severities, no "warnings". Unlike Jakarta, validity is not declared on
 * the type with annotations; you write checks as ordinary code wherever they belong.
 *
 * <h2>Entry points</h2>
 * <ul>
 *   <li>{@link #value(String, Object)} — a top-level field chain.</li>
 *   <li>{@link #scope(String)} — a {@link MaatScope} that prefixes every path; pass
 *       it into a value object's constructor so the object validates itself while
 *       the field name comes from the caller. Scopes nest: {@code shipping.address.city}.</li>
 *   <li>{@link #inline(Object)} — static, tied to no Maat; pseudo-validate a single
 *       value for a default, a fail-fast, or an explicit bridge back via
 *       {@link MaatInline#orElseAndReport}.</li>
 * </ul>
 *
 * <pre>{@code
 * Maat maat = new Maat();
 *
 * maat.value("name", form.name()).notBlank("name.required", "Name is required");
 * Email email = new Email(form.email(), maat.scope("email"));
 *
 * Report report = maat.report();
 * if (report.hasErrors()) return Result.rejected(report.violations());
 *
 * // value-less rule -> attach it to a value:
 * maat.value("email", form.email()).must(e -> !users.exists(e), "email.taken", "Already registered");
 * }</pre>
 *
 * <p>Java 8 compatible. Not thread-safe by design: a validation pass normally runs
 * on a single thread; for concurrency, produce a {@link Report} per thread and
 * combine them with {@link Report#plus(Report)}.
 */
public final class Maat {
    private final List<Violation> sink = new ArrayList<>();

    /** Begin a collect-only chain for a top-level field. */
    public <T> MaatValue<T> value(String name, T value) {
        return new MaatValue<>(this, null, name, value);
    }

    /**
     * A prefixing view of this Maat. Every path it records is prefixed with
     * {@code name}; pass it into a value object's constructor. Scopes nest.
     */
    public MaatScope scope(String name) {
        return new MaatScope(this, name);
    }

    // ---- terminal: produce the immutable read side -------------------------

    /** Snapshot everything recorded so far into an immutable {@link Report}. */
    public Report report() {
        return new Report(sink);
    }

    /**
     * Static, report-free chain for a single value, tied to no Maat. Ends in a
     * default, a fail-fast, or an explicit bridge ({@link MaatInline#orElseAndReport}).
     */
    public static <T> MaatInline<T> inline(T value) {
        return new MaatInline<T>(value);
    }

    /** Internal sink writer. Only the chain types and the inline bridge call this. */
    Maat record(String path, String code, String message) {
        sink.add(new Violation(path, code, message));
        return this;
    }

    /** A single recorded failure. Immutable. */
    public static final class Violation {
        private final String path;    // what failed, e.g. "email" or "shipping.city" (nullable)
        private final String code;    // machine-readable key, e.g. "email.malformed" (nullable)
        private final String message; // human-readable, user-facing message

        public Violation(String path, String code, String message) {
            this.message = Objects.requireNonNull(message, "message");
            this.path = path;
            this.code = code;
        }

        public String path()    { return path; }
        public String code()    { return code; }
        public String message() { return message; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Violation)) return false;
            Violation that = (Violation) o;
            return Objects.equals(path, that.path)
                    && Objects.equals(code, that.code)
                    && Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, code, message);
        }

        @Override
        public String toString() {
            String p = (path == null) ? "" : path;
            return (p.isEmpty() ? "" : p + ": ")
                    + message
                    + (code == null ? "" : " (" + code + ")");
        }
    }

    /**
     * The immutable read side: a snapshot of everything a {@link Maat} recorded.
     * Returned by {@link Maat#report()}. This is the value you pass around, query,
     * or throw — Maat itself never exposes its mutable internals.
     */
    public static final class Report {
        private final List<Violation> violations;

        Report(List<Violation> source) {
            this.violations = Collections.unmodifiableList(new ArrayList<Violation>(source));
        }

        /** Unmodifiable list of all violations. */
        public List<Violation> violations() { return violations; }

        public boolean isValid()  { return violations.isEmpty(); }
        public boolean hasErrors() { return !violations.isEmpty(); }

        public boolean hasErrorsFor(String path) {
            for (Violation v : violations) {
                if (Objects.equals(v.path(), path)) return true;
            }
            return false;
        }

        public List<String> messages() {
            return violations.stream().map(Violation::message).collect(Collectors.toList());
        }

        /** A new report combining this one's violations with {@code other}'s. */
        public Report plus(Report other) {
            List<Violation> combined = new ArrayList<Violation>(this.violations);
            combined.addAll(other.violations);
            return new Report(combined);
        }

        /** Throw {@link ValidationException} if not valid. For an expected-bad-input boundary. */
        public void throwIfInvalid() {
            if (hasErrors()) throw new ValidationException(this);
        }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "Report: clean";
            return "Report (" + violations.size() + "):\n"
                    + violations.stream().map(v -> "  " + v).collect(Collectors.joining("\n"));
        }
    }

    /** Java 8 replacement for {@code String.isBlank()} (JDK 11+). Treats null as blank. */
    static boolean isBlank(CharSequence cs) {
        if (cs == null) return true;
        for (int i = 0, len = cs.length(); i < len; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) return false;
        }
        return true;
    }

    /**
     * A prefixing view of a Maat. Created by {@link Maat#scope(String)}; nesting
     * composes prefixes with dots. Like Maat, it only permits {@code value} and
     * {@code scope} — no raw recording.
     */
    public static final class MaatScope {
        private final Maat maat;
        private final String prefix;

        MaatScope(Maat maat, String prefix) {
            this.maat = maat;
            this.prefix = prefix;
        }

        /** This scope's full prefix path. */
        public String path() { return prefix; }

        /** A nested scope: {@code scope("address").scope("city")} -> "address.city". */
        public MaatScope scope(String name) {
            return new MaatScope(maat, Util.join(prefix, name));
        }

        /** Begin a collect-only chain for {@code name} under this scope. */
        public <T> MaatValue<T> value(String name, T value) {
            return new MaatValue<>(maat, prefix, name, value);
        }

        /** Record at exactly this scope's prefix (used by the inline bridge). */
        void reportHere(String code, String message) {
            maat.record(prefix, code, message);
        }
    }

    /**
     * Shared validation verbs for both chain types. Package-private: not part of
     * the public API. Self-type pattern so verbs return the concrete chain type.
     * The base calls {@link #capture}; {@link MaatValue} routes it to its Maat,
     * {@link MaatInline} keeps it local. Short-circuits after the first failure.
     *
     * <p>Optional by default: only {@code notNull}/{@code notBlank} fail on null;
     * other verbs skip a null value.
     */
    abstract static class Check<SELF extends Check<SELF, T>, T> {
        T value;
        boolean failed;

        Check(T value) { this.value = value; }

        abstract SELF self();

        abstract void capture(String code, String message);

        final SELF fail(String code, String message) {
//            if (!failed) {
                capture(code, message);
                failed = true;
//            }
            return self();
        }

        public SELF notNull(String code, String message) {
            if (failed) return self();
            return value == null ? fail(code, message) : self();
        }

        public SELF notBlank(String code, String message) {
            if (failed) return self();
            return (value == null || isBlank(value.toString())) ? fail(code, message) : self();
        }

        public SELF must(Predicate<? super T> rule, String code, String message) {
            if (failed || value == null) return self();
            return rule.test(value) ? self() : fail(code, message);
        }

        public SELF minLength(int min, String code, String message) {
            return must(v -> v.toString().length() >= min, code, message);
        }

        public SELF maxLength(int max, String code, String message) {
            return must(v -> v.toString().length() <= max, code, message);
        }

        public SELF matches(String regex, String code, String message) {
            return must(v -> v.toString().matches(regex), code, message);
        }

        public SELF when(boolean condition, Consumer<SELF> block) {
            if (!failed && condition) block.accept(self());
            return self();
        }
    }


    /** Carries the {@link Report} when a boundary chooses to throw rather than return. */
    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Report report;

        public ValidationException(Report report) {
            super(report.toString());
            this.report = report;
        }

        public Report report() { return report; }
    }
}