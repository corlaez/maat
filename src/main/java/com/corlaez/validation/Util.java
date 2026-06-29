package com.corlaez.validation;

class Util {

    private Util() {}

    /** Join a scope prefix and a leaf name into a dotted path (either may be empty/null). */
    static String join(String prefix, String name) {
        boolean hasP = prefix != null && !prefix.isEmpty();
        boolean hasN = name != null && !name.isEmpty();
        if (hasP && hasN) return prefix + "." + name;
        if (hasP) return prefix;
        return hasN ? name : null;
    }
}
