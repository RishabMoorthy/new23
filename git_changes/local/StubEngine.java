package org.framework.local;

import com.stubio.util.VirtualServiceMapper;
import com.stubio.util.VirtualServiceObject;
import org.framework.config.LogConfigManager;
import org.framework.core.AbstractService;
import org.framework.core.RequestLog;
import org.framework.core.RequestTracker;
import org.framework.core.RequestTrackerDB;
import org.framework.core.RespTimeConfigManager;
import org.framework.core.ServerManager;
import org.framework.properties.MockRequest;
import org.framework.properties.MockResponse;
import org.framework.utils.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one virtual service from a .vs inside another app (Stubio), with the same engine as
 * the StubServer server: matching, scripts, REST / SOAP / TCP and all execution modes.
 *
 * <pre>
 * StubEngine tibco = new StubEngine();
 * tibco.setTransactionListener(tx -&gt; console.append(tx.toString()));
 * int code = tibco.start(vsXml);            // 0 = listening, 1 = failed
 * if (code != 0) console.appendError(tibco.getLastError());
 * ...
 * tibco.stop();
 * </pre>
 *
 * <p>One StubEngine runs one service. To run several, create one StubEngine per .vs; each
 * needs its own port and its own service name.
 *
 * <p>Nothing server-only runs: no database, no portal API, no pollers, and the .vs is never
 * written (Recording and LiveResponse update the response in memory until stop). A failure is
 * returned as {@link #FAILED} with the details in {@link #getLastError()}; it never ends the
 * calling app.
 */
public final class StubEngine {

    public static final int OK = 0;
    public static final int FAILED = 1;

    /** Service name -> the engine running it, so a transaction reaches the right listener. */
    private static final Map<String, StubEngine> RUNNING = new ConcurrentHashMap<>();

    private AbstractService service;
    private String serviceName;
    private ExecutorService events;
    private volatile String lastError;
    private volatile StubTransactionListener listener;

    /**
     * Starts the service described by the .vs.
     *
     * @param vsXml the full text of the .vs file
     * @return {@link #OK} when the service is listening, {@link #FAILED} otherwise
     */
    public synchronized int start(String vsXml) {
        lastError = null;
        if (service != null && service.isRunning()) {
            return fail("This StubEngine is already running '" + serviceName + "'. Call stop() first.", null);
        }
        if (vsXml == null || vsXml.isBlank()) {
            return fail("The .vs XML is empty.", null);
        }
        if (!vsXml.contains("stubVirtualService")) {
            return fail("Not a .vs file: the stubVirtualService root element was not found.", null);
        }

        LocalMode.activate();
        Path temp = null;
        String name = null;
        int port = 0;
        boolean claimed = false;
        AbstractService built = null;
        try {
            // The .vs parser reads from a URI, so the text goes through a temporary file.
            temp = Files.createTempFile("stubengine-", ".vs");
            Files.writeString(temp, vsXml, StandardCharsets.UTF_8);
            VirtualServiceObject vso = new VirtualServiceMapper(temp.toUri().toString()).getVso();

            name = vso.getVsName();
            port = vso.getPort();
            if (name == null || name.isBlank()) {
                return fail("The .vs has no service name.", null);
            }
            if (RUNNING.putIfAbsent(name, this) != null) {
                return fail("A service named '" + name + "' is already running locally. Stop it first.", null);
            }
            claimed = true;

            built = ServerManager.getInstance().buildLocalService(vso, vsXml);
            ServerManager.getInstance().getServices().put(name, built);
            RespTimeConfigManager.getConfig().put(name, "No");
            LogConfigManager.getConfig().put(name, "No");

            built.start();
            if (!built.isRunning()) {
                throw new IllegalStateException("the service did not report running after start");
            }

            service = built;
            serviceName = name;
            final String threadName = "StubEngine-events-" + name;
            events = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });
            Logger.getInstance().info("[StubEngine] " + name + " started locally on port " + port);
            return OK;

        } catch (Throwable t) {
            if (built != null) {
                try {
                    built.stop();
                } catch (Throwable ignored) {
                    // already failing; the start error is the one to report
                }
            }
            if (claimed) {
                ServerManager.getInstance().getServices().remove(name, built);
                RUNNING.remove(name, this);
            }
            if (name == null) {
                // The .vs could not be read at all - report the parser's own reason.
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                return fail("Invalid .vs: " + root.getMessage(), t);
            }
            return fail("Failed to start '" + name + "'" + (port > 0 ? " on port " + port : "")
                    + ": " + t.getMessage(), t);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // a leftover temp file is harmless
                }
            }
        }
    }

    /** Stops the service and frees its port. Safe to call when nothing is running. */
    public synchronized void stop() {
        if (service == null) {
            return;
        }
        String name = serviceName;
        try {
            service.stop();
            Logger.getInstance().info("[StubEngine] " + name + " stopped");
        } catch (Throwable t) {
            lastError = describe("Error while stopping '" + name + "': " + t.getMessage(), t);
        } finally {
            ServerManager.getInstance().getServices().remove(name, service);
            RUNNING.remove(name, this);
            RequestTracker.removeLogs(name);
            RequestTrackerDB.removeLogs(name);
            RespTimeConfigManager.getConfig().remove(name);
            LogConfigManager.getConfig().remove(name);
            if (events != null) {
                events.shutdown();
            }
            service = null;
            serviceName = null;
            events = null;
        }
    }

    public synchronized boolean isRunning() {
        return service != null && service.isRunning();
    }

    /** The message and full stack trace of the last failure, or null. */
    public String getLastError() {
        return lastError;
    }

    /** Name of the running service, or null. */
    public synchronized String getServiceName() {
        return serviceName;
    }

    /** Optional. Receives every request the service answers; null removes it. */
    public void setTransactionListener(StubTransactionListener listener) {
        this.listener = listener;
    }

    /** Called from RequestTracker for every logged request while in local mode. */
    public static void publish(String serviceName, RequestLog log) {
        StubEngine engine = serviceName == null ? null : RUNNING.get(serviceName);
        if (engine == null || log == null) {
            return;
        }
        StubTransactionListener target = engine.listener;
        ExecutorService queue = engine.events;
        if (target == null || queue == null) {
            return;
        }
        StubTransaction tx = toTransaction(log);
        try {
            queue.execute(() -> {
                try {
                    target.onTransaction(tx);
                } catch (Throwable t) {
                    Logger.getInstance().warn("[StubEngine] transaction listener failed: " + t.getMessage());
                }
            });
        } catch (Exception ignored) {
            // engine is stopping
        }
    }

    private static StubTransaction toTransaction(RequestLog log) {
        MockRequest req = log.getRequest();
        MockResponse resp = log.getResponse();
        long took = req == null ? 0 : (System.nanoTime() - req.getReceivedAtNanos()) / 1_000_000L;

        String request = req == null ? "" : firstNonEmpty(req.getRequestContent(), req.getRequest());
        String response = "";
        if (resp != null) {
            response = resp.getResponseContent();
            if ((response == null || response.isEmpty()) && resp.getResponseBytes() != null) {
                response = new String(resp.getResponseBytes(), StandardCharsets.ISO_8859_1);
            }
        }
        return new StubTransaction(
                log.getServiceName(),
                log.getType(),
                req == null ? "" : nz(req.getMethod()),
                req == null ? "" : nz(req.getPath()),
                resp == null ? "" : firstNonEmpty(resp.getOperationName(), req == null ? null : req.getOperation()),
                resp == null ? "" : nz(resp.getName()),
                resp == null ? "" : nz(resp.getSource()),
                resp == null ? 0 : resp.getStatusCode(),
                request,
                response,
                req == null ? null : req.getRequestHeaders(),
                resp == null ? null : resp.getHeaders(),
                took,
                log.getTimestamp());
    }

    private int fail(String message, Throwable cause) {
        lastError = describe(message, cause);
        Logger.getInstance().error("[StubEngine] " + message);
        return FAILED;
    }

    private static String describe(String message, Throwable cause) {
        if (cause == null) {
            return message;
        }
        StringWriter trace = new StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        return message + System.lineSeparator() + trace;
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : nz(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
