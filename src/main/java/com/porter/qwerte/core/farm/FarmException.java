package com.porter.qwerte.core.farm;

/**
 * A farm-library failure. The message is the closed machine code and nothing else: no input text, no position, no key
 * material ever goes into it. No stack trace and no cause are kept, so nothing can leak through either.
 */
public final class FarmException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public FarmException(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    /** The closed code, for example {@code bad_program}, {@code bad_track}, {@code format_unsupported}. */
    public String code() {
        return code;
    }
}
