package org.framework.local;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One request and the response a locally running service sent back. Read-only. */
public final class StubTransaction {

    private final String serviceName;
    private final String protocol;
    private final String method;
    private final String path;
    private final String operation;
    private final String responseName;
    private final String source;
    private final int statusCode;
    private final String request;
    private final String response;
    private final Map<String, String> requestHeaders;
    private final Map<String, String> responseHeaders;
    private final long timeTakenMs;
    private final String timestamp;

    StubTransaction(String serviceName, String protocol, String method, String path, String operation,
            String responseName, String source, int statusCode, String request, String response,
            Map<String, List<String>> requestHeaders, Map<String, List<String>> responseHeaders,
            long timeTakenMs, String timestamp) {
        this.serviceName = serviceName;
        this.protocol = protocol;
        this.method = method;
        this.path = path;
        this.operation = operation;
        this.responseName = responseName;
        this.source = source;
        this.statusCode = statusCode;
        this.request = request == null ? "" : request;
        this.response = response == null ? "" : response;
        this.requestHeaders = flatten(requestHeaders);
        this.responseHeaders = flatten(responseHeaders);
        this.timeTakenMs = timeTakenMs;
        this.timestamp = timestamp;
    }

    public String getServiceName() { return serviceName; }

    /** Rest, Soap or Tcp. */
    public String getProtocol() { return protocol; }

    /** HTTP method; empty for TCP. */
    public String getMethod() { return method; }

    /** Request path; empty for TCP. */
    public String getPath() { return path; }

    /** SOAP / TCP operation name, when known. */
    public String getOperation() { return operation; }

    /** Name of the response that was sent, e.g. "Response 1" or "LIVE". */
    public String getResponseName() { return responseName; }

    /** Where the response came from, e.g. Mock or Live, as the server reports it. */
    public String getSource() { return source; }

    /** HTTP status for REST/SOAP. */
    public int getStatusCode() { return statusCode; }

    public String getRequest() { return request; }

    public String getResponse() { return response; }

    public Map<String, String> getRequestHeaders() { return requestHeaders; }

    public Map<String, String> getResponseHeaders() { return responseHeaders; }

    /** From the moment the request arrived until it was logged, in milliseconds. */
    public long getTimeTakenMs() { return timeTakenMs; }

    /** yyyy-MM-dd HH:mm:ss */
    public String getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return timestamp + "  " + serviceName + "  " + (operation == null || operation.isEmpty() ? path : operation)
                + "  " + responseName + "  " + source + "  " + statusCode + "  " + timeTakenMs + " ms";
    }

    private static Map<String, String> flatten(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> flat = new LinkedHashMap<>();
        headers.forEach((k, v) -> flat.put(k, v == null ? "" : String.join(", ", v)));
        return Collections.unmodifiableMap(flat);
    }
}
