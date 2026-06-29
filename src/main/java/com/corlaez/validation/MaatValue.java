package com.corlaez.validation;


import java.util.function.Function;

/**
 * Collect-only chain. Records into its Maat at {@code prefix.name}, chains
 * across fields in the same scope via {@link #value(String, Object)}.
 * Intentionally has no {@code get}/{@code orElse}.
 */
public class MaatValue<T> extends Maat.Check<MaatValue<T>, T> {
    private final Maat maat;
    private final String prefix; // scope prefix (nullable), kept for cross-field chaining
    private final String name;   // this field's leaf name

    MaatValue(Maat maat, String prefix, String name, T value) {
        super(value);
        this.maat = maat;
        this.prefix = prefix;
        this.name = name;
    }

    @Override
    MaatValue<T> self() { return this; }

    @Override
    void capture(String code, String message) {
        maat.record(Util.join(prefix, name), code, message);
    }

    /** Start a fresh chain for another field in the same scope. */
    public <R> MaatValue<R> value(String name, R value) {
        return new MaatValue<R>(maat, prefix, name, value);
    }

    /** Record a parse failure for this field; the converted value is not retrievable. */
    public <R> MaatValue<R> convert(Function<? super T, ? extends R> fn,
                                String code, String message) {
        MaatValue<R> next = new MaatValue<R>(maat, prefix, name, null);
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