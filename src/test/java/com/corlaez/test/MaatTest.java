package com.corlaez.test;

import com.corlaez.validation.Maat;
import com.corlaez.validation.MaatCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

enum Error implements MaatCode {
    EMAIL_REQUIRED,
    EMAIL_MALFORMED,
    MIN_LENGTH;

    @Override
    public String toMessage(Locale locale, Object ... args) {
        String template = ResourceBundle
                .getBundle("errors", locale)
                .getString(this.name());
        return MessageFormat.format(template, args);
    }
}

public class MaatTest {

    @Test
    public void f() {
        Maat<Error> maat = new Maat<>();

        String validateThis = "caca";

        maat.value("validateThis", validateThis)
                .must(x -> false, Error.EMAIL_REQUIRED)
                .maxLength(3, Error.EMAIL_MALFORMED)
                .minLength(12, Error.MIN_LENGTH)
                        .value("validateThat", validateThis)
                                .minLength(123123, Error.MIN_LENGTH);
        Maat.Report<Error> report = maat.report();
        Assertions.assertThrows(Maat.ValidationException.class, () -> {
            report.throwIfInvalid(Locale.getDefault());
        });
        List<Maat.Violation<Error>> list = new ArrayList<>();
        list.add(new Maat.Violation<>("validateThis", Error.EMAIL_REQUIRED));
        list.add(new Maat.Violation<>("validateThat", Error.MIN_LENGTH));

        Assertions.assertEquals(
                list, report.violations()
        );
    }
}
