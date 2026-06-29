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
 * Maat — an error accumulator. You create one, thread it through your validation,
 * and it gathers ALL failures in one place so they can be reported together,
 * instead of failing on the first bad value.
 *
 * <p>Like Jakarta Bean Validation, it has simple violations. What differs is *placement*. Validity
 * is not declared on the type itself with annotations; you write checks as ordinary code wherever they belong.
 *
 * <h2>Three ways in</h2>
 *
 * <ul>
 *   <li><b>Root</b> — {@link #value(String, Object)} records a top-level field
 *       into this Maat.</li>
 *   <li><b>Scoped</b> — {@link #scope(String)} returns a {@link MaatScope} that
 *       prefixes every path it records. Pass it into a value object's constructor
 *       so the object can do its own intrinsic validation while the field name
 *       (e.g. {@code "email"}) comes from the caller. Scopes nest, so paths
 *       compose: {@code shipping.address.city}.</li>
 *   <li><b>Static / report-free</b> — {@link #inline(Object)} pseudo-validates a
 *       single value with no Maat at all, ending in a default, a fail-fast, or an
 *       explicit bridge back into a Maat/scope.</li>
 * </ul>
 *
 * <pre>{@code
 * Maat maat = new Maat();
 *
 * // ROOT, top-level fields:
 * maat.value("name", form.name()).notBlank("name.required", "Name is required");
 *
 * // SCOPED: hand a prefixing scope to a constructor; its paths become email.*
 * Email email = new Email(form.email(), maat.scope("email"));
 *
 * // STATIC, no report — default or fail-fast:
 * int port = Maat.inline(raw).convert(Integer::parseInt, "port.nan", "NaN").orElse(8080);
 *
 * if (maat.hasErrors()) return Result.rejected(maat.violations());
 *
 * // ...where Email validates itself under whatever scope it was given:
 * // public Email(String raw, Maat.MaatScope scope) {
 * //     this.value = Maat.inline(raw)
 * //         .notBlank("required",  "Email is required")
 * //         .matches(".+@.+\\..+", "malformed", "Email is malformed")
 * //         .orElseAndReport(null, scope);   // records at the scope's path, e.g. "email"
 * // }
 * }</pre>
 *
 * <p>Java 8 compatible: no records, no {@code List.copyOf}, no {@code Stream.toList},
 * no {@code String.isBlank} — those are reimplemented or avoided below.
 *
 * <p>Not thread-safe by design: a validation pass normally runs on a single
 * thread. If you validate concurrently, collect into per-thread instances and
 * {@link #merge(Maat)} them.
 */
public final class Maat {
    private final List<Violation> violations = new ArrayList<>();

    /**
     * A single recorded failure. Immutable.
     */
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

    // ---- raw recording (instance) ------------------------------------------

    /** Record a failure at an absolute path. Returns {@code this} for chaining. */
    public Maat add(String path, String code, String message) {
        violations.add(new Violation(path, code, message));
        return this;
    }

    /** Value-less guard: records at {@code path} only when {@code ok} is false. Returns {@code ok}. */
    public boolean require(boolean ok, String path, String code, String message) {
        if (!ok) add(path, code, message);
        return ok;
    }

    /** Begin a collect-only chain for a top-level field. */
    public <T> Value<T> value(String name, T value) {
        return new Value<T>(this, null, name, value);
    }

    /**
     * A prefixing view of this Maat. Every path it records is prefixed with
     * {@code name}; pass it into a value object's constructor so the object
     * validates itself while the field name comes from the caller.
     */
    public MaatScope scope(String name) {
        return new MaatScope(this, name);
    }

    // ---- static, report-free validation ------------------------------------

    /**
     * Begin a <b>static</b> validation chain for a single value, tied to no Maat
     * and no collection. Ends in {@link MaatInline#orElse default},
     * {@link MaatInline#get fail-fast}, or {@link MaatInline#orElseAndReport} to
     * bridge a failure into a Maat/scope.
     */
    public static <T> MaatInline<T> inline(T value) {
        return new MaatInline<T>(value);
    }

    // ---- queries -----------------------------------------------------------

    public boolean hasErrors() { return !violations.isEmpty(); }

    public boolean isClean() { return violations.isEmpty(); }

    public boolean hasErrorsFor(String path) {
        return violations.stream().anyMatch(v -> Objects.equals(v.path(), path));
    }

    /** Defensive, unmodifiable copy of all violations. */
    public List<Violation> violations() {
        return Collections.unmodifiableList(new ArrayList<Violation>(violations));
    }

    public List<String> messages() {
        return violations.stream()
                .map(Violation::message)
                .collect(Collectors.toList());
    }

    // ---- composition & boundary --------------------------------------------

    /** Fold another Maat's violations into this one. */
    public Maat merge(Maat other) {
        this.violations.addAll(other.violations);
        return this;
    }

    /**
     * Throw at a boundary that prefers exceptions over a result object. This is
     * meant for expected bad input at a boundary, not for programmer-bug
     * assertions deep in your code.
     */
    public void throwIfErrors() {
        if (hasErrors()) throw new ValidationException(this);
    }

    @Override
    public String toString() {
        if (violations.isEmpty()) return "Maat: clean";
        return "Maat (" + violations.size() + "):\n"
                + violations.stream().map(v -> "  " + v).collect(Collectors.joining("\n"));
    }

    // ---- helpers -----------------------------------------------------------

    /** Join a scope prefix and a leaf name into a dotted path (either may be empty/null). */
    static String join(String prefix, String name) {
        boolean hasP = prefix != null && !prefix.isEmpty();
        boolean hasN = name != null && !name.isEmpty();
        if (hasP && hasN) return prefix + "." + name;
        if (hasP) return prefix;
        return hasN ? name : null;
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
     * A prefixing view of a Maat. Created by {@link Maat#scope(String)} or
     * {@link #scope(String)}; nesting composes prefixes with dots. Hand one to a
     * constructor so the object reports under the caller-chosen path.
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
            return new MaatScope(maat, join(prefix, name));
        }

        /** Begin a collect-only chain for {@code name} under this scope. */
        public <T> Value<T> value(String name, T value) {
            return new Value<T>(maat, prefix, name, value);
        }

        /** Record a failure at {@code prefix.name}. Returns the underlying Maat. */
        public Maat add(String name, String code, String message) {
            maat.add(join(prefix, name), code, message);
            return maat;
        }

        /** Value-less guard under this scope. */
        public boolean require(boolean ok, String name, String code, String message) {
            return maat.require(ok, join(prefix, name), code, message);
        }

        /** Record at exactly this scope's prefix (used by the inline bridge). */
        void reportHere(String code, String message) {
            maat.add(prefix, code, message);
        }
    }

    /**
     * Shared validation verbs for both chain types. Package-private: not part of
     * the public API. Uses the self-type pattern so each verb returns the concrete
     * chain type ({@link Value} or {@link MaatInline}). The base does not know
     * where a failure goes; it calls {@link #capture}, which {@link Value} sends to
     * its Maat and {@link MaatInline} keeps locally. The chain short-circuits after
     * the first failure.
     *
     * <p>Optional by default: only {@code notNull}/{@code notBlank} fail on null;
     * other verbs skip a null value. Lead with a presence check to require a field.
     */
    abstract static class Check<SELF extends Check<SELF, T>, T> {
        T value;
        boolean failed;

        Check(T value) { this.value = value; }

        abstract SELF self();

        abstract void capture(String code, String message);

        final SELF fail(String code, String message) {
            if (!failed) {
                capture(code, message);
                failed = true;
            }
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

        /** Applies {@code block} only when {@code condition} holds and the chain is still clean. */
        public SELF when(boolean condition, Consumer<SELF> block) {
            if (!failed && condition) block.accept(self());
            return self();
        }
    }

    /**
     * Collect-only chain. Records into its Maat at {@code prefix.name} and chains
     * across fields within the same scope via {@link #value(String, Object)}.
     * Intentionally has no {@code get}/{@code orElse}.
     */
    public static final class Value<T> extends Check<Value<T>, T> {
        private final Maat maat;
        private final String prefix; // scope prefix (nullable), kept for cross-field chaining
        private final String name;   // this field's leaf name

        Value(Maat maat, String prefix, String name, T value) {
            super(value);
            this.maat = maat;
            this.prefix = prefix;
            this.name = name;
        }

        @Override
        Value<T> self() { return this; }

        @Override
        void capture(String code, String message) { maat.add(join(prefix, name), code, message); }

        /** Start a fresh chain for another field in the same scope. */
        public <R> Value<R> value(String name, R value) {
            return new Value<R>(maat, prefix, name, value);
        }

        /** Record a parse failure for this field; the converted value is not retrievable. */
        public <R> Value<R> convert(Function<? super T, ? extends R> fn,
                                    String code, String message) {
            Value<R> next = new Value<R>(maat, prefix, name, null);
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
    }

    /**
     * Static, report-free chain from {@link Maat#inline(Object)}. Captures the
     * first failure locally and ends in:
     * <ul>
     *   <li>{@link #orElse(Object)} — value or default; never reports.</li>
     *   <li>{@link #get()} — value, or throws {@link IllegalArgumentException}.</li>
     *   <li>{@link #optional()} — present unless a check failed.</li>
     *   <li>{@link #orElseAndReport(Object, Maat)} /
     *       {@link #orElseAndReport(Object, MaatScope)} — bridge a failure into a
     *       Maat (at root) or a scope (at the scope's path), returning the default.</li>
     * </ul>
     */
    public static final class MaatInline<T> extends Check<MaatInline<T>, T> {
        private String failCode;
        private String failMessage;

        MaatInline(T value) { super(value); }

        @Override
        MaatInline<T> self() { return this; }

        @Override
        void capture(String code, String message) {
            this.failCode = code;
            this.failMessage = message;
        }

        public <R> MaatInline<R> convert(Function<? super T, ? extends R> fn,
                                         String code, String message) {
            MaatInline<R> next = new MaatInline<R>(null);
            next.failed = this.failed;
            next.failCode = this.failCode;
            next.failMessage = this.failMessage;
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

        /** True if no check failed. */
        public boolean ok() { return !failed; }

        /** Value, or {@code fallback} if a check failed or the value is absent. Never reports. */
        public T orElse(T fallback) { return (failed || value == null) ? fallback : value; }

        /** Value (possibly null if absent), or throws if a check failed. Never reports. */
        public T get() {
            if (failed) throw new IllegalArgumentException(failMessage);
            return value;
        }

        /** Present unless a check failed. */
        public Optional<T> optional() {
            return failed ? Optional.<T>empty() : Optional.ofNullable(value);
        }

        /** On failure, records the captured reason at root (no path) into {@code maat}; returns value or fallback. */
        public T orElseAndReport(T fallback, Maat maat) {
            if (failed) {
                maat.add(null, failCode, failMessage);
                return fallback;
            }
            return value;
        }

        /** On failure, records the captured reason at {@code scope}'s path; returns value or fallback. */
        public T orElseAndReport(T fallback, MaatScope scope) {
            if (failed) {
                scope.reportHere(failCode, failMessage);
                return fallback;
            }
            return value;
        }
    }

    /** Carries the full Maat when a boundary chooses to throw rather than return. */
    public static final class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Maat maat;

        public ValidationException(Maat maat) {
            super(maat.toString());
            this.maat = maat;
        }

        public Maat maat() { return maat; }
    }
}