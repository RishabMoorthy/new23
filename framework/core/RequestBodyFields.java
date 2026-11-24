package org.framework.core;

import com.jayway.jsonpath.JsonPath;
import com.stubio.util.Request;
import org.framework.properties.MockRequest;
import org.framework.utils.GroovyUtils;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The body of one request, read as fields for Selective and Signature matching.
 *
 * <p>Field names follow the way the Stubio UI lists a sample request (Stubio {@code Utils}):
 * <ul>
 *   <li><b>JSON</b> - every value, named by its dotted path from the top; array items by
 *       position, e.g. {@code details.email}, {@code details[1].email}. Empty objects and
 *       arrays are not fields.</li>
 *   <li><b>XML / SOAP</b> - starts at the root element, local names only (namespace prefixes
 *       dropped), e.g. {@code Envelope.Body.GetCustomerDetailsRequest.CustomerId}. An element is
 *       a field when it has text and no child elements; attributes are {@code path.@name}.</li>
 *   <li><b>TLV</b> - every tag, read with the pair's Tag Length / Length Size, or the
 *       service's TCP Handler values when the pair has none.</li>
 * </ul>
 *
 * <p>For TCP the frame header and footer (TCP Handler HeaderLength / FooterLength) are cut off
 * first, so only the body is read. Older names keep working: {@code $...} is JSONPath,
 * {@code /...} is XPath, and a bare name like {@code NAME} is searched anywhere in XML.
 */
final class RequestBodyFields {

    private enum Kind { EMPTY, JSON, XML, TLV, UNREADABLE }

    private final String body;
    private final Kind kind;
    private final String problem;
    private final Object json;
    private final Element xmlRoot;
    private final Map<String, String> tlv;

    private RequestBodyFields(String body, Kind kind, String problem, Object json, Element xmlRoot,
            Map<String, String> tlv) {
        this.body = body;
        this.kind = kind;
        this.problem = problem;
        this.json = json;
        this.xmlRoot = xmlRoot;
        this.tlv = tlv;
    }

    /** Reads the body of {@code request} for the pair whose request spec is {@code spec}. */
    static RequestBodyFields of(Request spec, MockRequest request) {
        String body = bodyOf(request);
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return new RequestBodyFields(body, Kind.EMPTY, null, null, null, null);
        }

        String contentType = spec == null || spec.getContentType() == null ? "" : spec.getContentType().toLowerCase();
        boolean tlvDeclared = contentType.contains("tlv");

        if (!tlvDeclared && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            try {
                return new RequestBodyFields(body, Kind.JSON, null, JsonPath.parse(trimmed).json(), null, null);
            } catch (Exception e) {
                return unreadable(body, "body is not valid JSON");
            }
        }
        if (!tlvDeclared && trimmed.startsWith("<")) {
            try {
                return new RequestBodyFields(body, Kind.XML, null, null, parseXml(trimmed), null);
            } catch (Exception e) {
                return unreadable(body, "body is not valid XML");
            }
        }

        boolean tcp = request != null && "TCP".equalsIgnoreCase(request.getProtocol());
        if (tlvDeclared || tcp) {
            int tagLength = firstPositive(spec == null ? null : spec.getTagLength(),
                    request == null ? null : request.getTagLength());
            int lengthSize = firstPositive(spec == null ? null : spec.getLengthSize(),
                    request == null ? null : request.getLengthSize());
            if (tagLength <= 0 || lengthSize <= 0) {
                return unreadable(body, "TLV body but no Tag Length / Length Size on the pair or the service");
            }
            Map<String, String> tags = parseTlv(trimmed, tagLength, lengthSize);
            return tags == null
                    ? unreadable(body, "body is not valid TLV (Tag Length " + tagLength + ", Length Size " + lengthSize + ")")
                    : new RequestBodyFields(body, Kind.TLV, null, null, null, tags);
        }
        return unreadable(body, "body is not JSON, XML or TLV");
    }

    boolean isUnreadable() {
        return kind == Kind.UNREADABLE;
    }

    /** Why the body could not be read; null when it could. */
    String getProblem() {
        return problem;
    }

    /**
     * The value of one field, or null when the request does not have it.
     */
    String value(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String path = name.trim();

        // Older styles, kept exactly as before.
        if (path.startsWith("$")) {
            return jsonPath(path);
        }
        if (path.startsWith("/")) {
            return xpath(path);
        }

        switch (kind) {
            case JSON:
                return jsonPath(path.startsWith("[") ? "$" + path : "$." + path);
            case XML: {
                String value = xmlDotted(path);
                if (value == null && !path.contains("@")) {
                    // Bare names (and element names that themselves contain dots) are searched
                    // anywhere in the document, as before.
                    value = xpath("//" + path);
                }
                return value;
            }
            case TLV:
                return tlv.get(path);
            default:
                return null;
        }
    }

    /**
     * Every field name in the request, the way the UI would list it; an empty set for an
     * empty body; null when the body cannot be read.
     */
    Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        switch (kind) {
            case EMPTY:
                return out;
            case JSON:
                jsonNames("", json, out);
                return out;
            case XML:
                xmlNames(xmlRoot, localName(xmlRoot), out);
                return out;
            case TLV:
                out.addAll(tlv.keySet());
                return out;
            default:
                return null;
        }
    }

    /** True for a SOAP header field ({@code Envelope.Header...}), which Signature ignores. */
    static boolean isSoapHeaderName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        return n.equals("Envelope.Header") || n.startsWith("Envelope.Header.");
    }

    /** True for a JSONPath ({@code $...}) or XPath ({@code /...}) name. */
    static boolean isPathStyle(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        return n.startsWith("$") || n.startsWith("/");
    }

    // ---- body ----

    /** The request content; for TCP without the frame header and footer. */
    static String bodyOf(MockRequest request) {
        if (request == null || request.getRequestContent() == null) {
            return "";
        }
        String content = request.getRequestContent();
        if (!"TCP".equalsIgnoreCase(request.getProtocol())) {
            return content;
        }
        int header = Math.max(0, request.getHeaderLength() == null ? 0 : request.getHeaderLength());
        int footer = Math.max(0, request.getFooterLength() == null ? 0 : request.getFooterLength());
        if (header + footer == 0 || content.length() < header + footer) {
            return content;
        }
        return content.substring(header, content.length() - footer);
    }

    private static RequestBodyFields unreadable(String body, String problem) {
        return new RequestBodyFields(body, Kind.UNREADABLE, problem, null, null, null);
    }

    private static int firstPositive(Object first, Object second) {
        int a = toInt(first);
        return a > 0 ? a : toInt(second);
    }

    private static int toInt(Object value) {
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- JSON ----

    private String jsonPath(String path) {
        try {
            Object value = JsonPath.parse(body.trim()).read(path);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void jsonNames(String path, Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                jsonNames(path.isEmpty() ? key : path + "." + key, e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                jsonNames(path + "[" + i + "]", list.get(i), out);
            }
        } else if (!path.isEmpty()) {
            out.add(path);
        }
    }

    // ---- XML ----

    private static Element parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        root.normalize();
        return root;
    }

    /** Walks {@code Root.Child.Leaf} or {@code Root.Child.@attr}; null when any step is missing. */
    private String xmlDotted(String path) {
        if (xmlRoot == null) {
            return null;
        }
        String[] parts = path.split("\\.");
        if (parts.length == 0 || !parts[0].equals(localName(xmlRoot))) {
            return null;
        }
        Element current = xmlRoot;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.startsWith("@")) {
                return i == parts.length - 1 ? attribute(current, part.substring(1)) : null;
            }
            current = firstChild(current, part);
            if (current == null) {
                return null;
            }
        }
        return current.getTextContent() == null ? "" : current.getTextContent().trim();
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(child))) {
                return (Element) child;
            }
        }
        return null;
    }

    private static String attribute(Element element, String localName) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            if (!isNamespaceDeclaration(attr) && localName.equals(localName(attr))) {
                return attr.getNodeValue();
            }
        }
        return null;
    }

    private String xpath(String expression) {
        try {
            return new GroovyUtils(null).getXmlHolder(body.trim()).getNodeValue(expression);
        } catch (Exception e) {
            return null;
        }
    }

    /** Same rules as Stubio Utils.processXmlNode. */
    private static void xmlNames(Element element, String path, Set<String> out) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            if (!isNamespaceDeclaration(attr)) {
                out.add(path + ".@" + localName(attr));
            }
        }

        NodeList children = element.getChildNodes();
        boolean hasElementChildren = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                break;
            }
        }
        if (!hasElementChildren) {
            String text = element.getTextContent();
            if (text != null && !text.trim().isEmpty()) {
                out.add(path);
            }
        }

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                xmlNames((Element) child, path + "." + localName(child), out);
            }
        }
    }

    private static boolean isNamespaceDeclaration(Node attr) {
        String name = attr.getNodeName();
        return name.equals("xmlns") || name.startsWith("xmlns:");
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    // ---- TLV ----

    /** Tag -> value (first occurrence wins); null when the data does not split cleanly. */
    static Map<String, String> parseTlv(String data, int tagLength, int lengthSize) {
        Map<String, String> tags = new LinkedHashMap<>();
        int pos = 0;
        int n = data.length();
        while (pos < n) {
            if (pos + tagLength + lengthSize > n) {
                return null;
            }
            String tag = data.substring(pos, pos + tagLength);
            String lengthText = data.substring(pos + tagLength, pos + tagLength + lengthSize).trim();
            int length;
            try {
                length = Integer.parseInt(lengthText);
            } catch (NumberFormatException e) {
                return null;
            }
            int start = pos + tagLength + lengthSize;
            if (length < 0 || start + length > n) {
                return null;
            }
            tags.putIfAbsent(tag, data.substring(start, start + length));
            pos = start + length;
        }
        return tags;
    }
}
