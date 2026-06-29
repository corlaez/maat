package com.corlaez.validation;

import java.util.Optional;
import java.util.function.Function;

/**
 * Static, report-free chain from {@link Maat#inline(Object)}. Captures the
 * first failure locally and ends in {@link #orElse}, {@link #get} (fail-fast),
 * {@link #optional}, or the bridge {@link #orElseAndReport(Object, Maat)} /
 * {@link #orElseAndReport(Object, Maat.MaatScope)}.
 */
public class MaatInline<T> extends Maat.Check<MaatInline<T>, T> {
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

    public boolean ok() { return !failed; }

    /** Value, or {@code fallback} if a check failed or the value is absent. Never reports. */
    public T orElse(T fallback) { return (failed || value == null) ? fallback : value; }

    /** Value (possibly null if absent), or throws if a check failed. Never reports. */
    public T get() {
        if (failed) throw new IllegalArgumentException(failMessage);
        return value;
    }

    public Optional<T> optional() {
        return failed ? Optional.<T>empty() : Optional.ofNullable(value);
    }

    /** On failure, records the captured reason at root into {@code maat}; returns value or fallback. */
    public T orElseAndReport(T fallback, Maat maat) {
        if (failed) {
            maat.record(null, failCode, failMessage);
            return fallback;
        }
        return value;
    }

    /** On failure, records the captured reason at {@code scope}'s path; returns value or fallback. */
    public T orElseAndReport(T fallback, Maat.MaatScope scope) {
        if (failed) {
            scope.reportHere(failCode, failMessage);
            return fallback;
        }
        return value;
    }
}
