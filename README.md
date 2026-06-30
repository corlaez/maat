# Maat

A reference to the egipcian god that would use their balance to compare hearts with her ostrich feather...

Maat is a validation library with clear objectives:

1. Make no use of annotations for validation.
2. Separate use case constraints from data class declaration
3. Provide a way to support data class invariant validations (scopes)
4. Have the ability to report many validation violations at once
5. Have the ability to produce internationalized messages (complete with params template)
6. Provide a way to generate reports that display the data shape (paths + scopes)
7. Support fail fast validations with same fluent syntax validation (MaatInline)
8. Support recovery with default with same fluent syntax validation (MaatInline)

## Basic example

```
enum Error {
    EXAMPLE
}
var maat = new Maat<Error>();;
var request = null;

maat.value("request", request).notNull(Error.EXAMPLE)

```

### Intrinsics

Data classes are meant to avoid throwing, and instead taking a maatScope instance and report errors there.
Fill value with empties or placeholders. The assumption is that the caller won't use this values 

## Recommendations 

Use Field constants by lombok to get ccompile time breaks if data shape changes