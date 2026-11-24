package org.framework.core;

import com.stubio.util.RRPair;

/**
 * The RRPair match styles.
 *
 * <p>
 * An absent {@code <vs:MatchStyle>} or any unrecognised value falls back to
 * {@link #OPERATION}. Falling back rather than declining preserves the
 * behaviour services had before styles were distinguished, so no deployed
 * service changes on upgrade. The fallback is logged loudly at deploy because
 * an unrecognised style silently becoming a catch-all is exactly the kind of
 * thing that must not pass unnoticed.
 */
public enum MatchStyle {

    /** Runs the pair's MatchScript; it claims or declines. */
    SCRIPT,

    /** Compares the values of the pair's ticked args (body, headers, query). */
    SELECTIVE,

    /**
     * Compares field names only: the request must carry exactly the pair's
     * listed body and query names - none missing, none extra. Values and headers
     * are ignored.
     */
    SIGNATURE,

    /**
     * Compares nothing and claims unconditionally - REST path + method and SOAP
     * operation name are already resolved before the chain runs, so an
     * Operation pair has nothing left to test.
     */
    OPERATION;

    /**
     * @return the parsed style, or {@code null} when the value is absent or not
     *         recognised. Callers decide how to report the fallback.
     */
    public static MatchStyle parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (MatchStyle style : values()) {
            if (style.name().equalsIgnoreCase(value)) {
                return style;
            }
        }
        return null;
    }

    /** The raw {@code <vs:MatchStyle>} text of a pair, or null when absent. */
    public static String rawOf(RRPair pair) {
        if (pair == null || pair.getResponseSelection() == null) {
            return null;
        }
        return pair.getResponseSelection().getMatchStyle();
    }
}

