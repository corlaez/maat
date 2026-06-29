package com.corlaez.test;

import com.corlaez.validation.Maat;
import org.junit.jupiter.api.Test;

public class MaatTest {

    @Test
    public void f() {
        Maat maat = new Maat();

        String validateThis = "caca";

        maat.value("validateThis", validateThis)
                .must(x -> false, "", "asdas")
                .maxLength(3, "max.length", "Must be shorter")
                .minLength(12, "caca.cacaa", "Must be longer");
        maat.report().throwIfInvalid();


    }
}
