package org.framework.local;

/**
 * True when StubServer runs inside another app (Stubio) through {@link StubEngine} instead of
 * as the standalone server started by {@code Main}.
 *
 * <p>In local mode the services skip everything that belongs to the server: the database
 * (execution mode sync, portal delay, catalog, metrics) and writing responses back into the
 * .vs file. Matching, scripts, data sources and live calls work exactly as on the server.
 *
 * <p>{@code Main} never turns it on, so the server behaves as before.
 */
public final class LocalMode {

    private static volatile boolean active;

    private LocalMode() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Called by {@link StubEngine} before its first service starts. */
    static void activate() {
        active = true;
    }
}
