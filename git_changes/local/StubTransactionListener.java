package org.framework.local;

/**
 * Receives every request a locally running service answered, so the app that started it
 * (Stubio) can show it, for example in its output console.
 *
 * <p>Called on a background thread, one transaction at a time and in arrival order. A UI must
 * hand the update to its own UI thread (JavaFX: {@code Platform.runLater}).
 */
@FunctionalInterface
public interface StubTransactionListener {

    void onTransaction(StubTransaction transaction);
}
