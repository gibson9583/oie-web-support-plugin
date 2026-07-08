/*
 * OIE Web Support
 *
 * Published under the terms of the Mozilla Public License 2.0.
 */

package org.openintegrationengine.plugins.websupport;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.donkey.model.message.SerializationType;
import com.mirth.connect.model.MetaData;
import com.mirth.connect.model.converters.IMessageSerializer;
import com.mirth.connect.model.datatype.DataTypeProperties;
import com.mirth.connect.model.datatype.SerializationProperties;
import com.mirth.connect.model.datatype.SerializerProperties;
import com.mirth.connect.model.util.MessageVocabulary;
import com.mirth.connect.plugins.DataTypeServerPlugin;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.util.JavaScriptSharedUtil;

public class WebSupportServlet extends MirthServlet implements WebSupportServletInterface {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExtensionController extensionController = ControllerFactory.getFactory().createExtensionController();

    private static final String WEBADMIN_DIR = "webadmin";
    private static final String MANIFEST = "plugin.json";
    // An extension's install-directory name — a single, safe path segment. Guards
    // both discovery and asset serving against traversal via the extension name.
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Data type name -> vocabulary class (element descriptions, the same text the Swing message
     * tree shows). Upstream this is a {@code DataTypeServerPlugin.getVocabulary()} override in
     * each data type; as a plugin we cannot add to that interface, so the built-in vocabularies
     * are resolved by name instead. Reflection keeps this compilable without the datatype plugin
     * jars; at runtime the engine loads every extension's SHARED libraries onto one classpath,
     * so the classes are present whenever the data type itself is installed.
     */
    private static final Map<String, String> VOCABULARY_CLASSES = Map.of(
            "HL7V2", "com.mirth.connect.plugins.datatypes.hl7v2.HL7v2Vocabulary",
            "EDI/X12", "com.mirth.connect.plugins.datatypes.edi.X12Vocabulary",
            "NCPDP", "com.mirth.connect.plugins.datatypes.ncpdp.NCPDPVocabulary",
            "DICOM", "com.mirth.connect.plugins.datatypes.dicom.DICOMVocabulary");

    private final ServletContext servletContext;

    public WebSupportServlet(@Context HttpServletRequest request, @Context ServletContext servletContext, @Context SecurityContext sc) {
        // The extension-name form also rejects calls while the extension is disabled.
        super(request, sc, PLUGIN_POINT);
        this.servletContext = servletContext;
    }

    /* ---- data types: byte-exact serialization + vocabulary ---------------------------- */

    @Override
    public Response serializeMessage(String dataType, String props, String message) {
        // Look the data type up in the installed plugins (keyed by the data type name — "HL7V2",
        // "XML", "EDI/X12", …). This uses only core interfaces, so no concrete datatype import.
        DataTypeServerPlugin plugin = dataType == null ? null : extensionController.getDataTypePlugins().get(dataType);
        if (plugin == null) {
            throw new MirthApiException(Status.BAD_REQUEST);
        }
        String msg = message == null ? "" : message;

        try {
            DataTypeProperties dtProps = plugin.getDefaultProperties();
            applyOverrides(dtProps, props);

            SerializerProperties serializerProps = dtProps.getSerializerProperties();
            IMessageSerializer serializer = plugin.getSerializer(serializerProps);

            boolean json = SerializationType.JSON.equals(plugin.getDefaultSerializationType());
            String data = json ? serializer.toJSON(msg) : serializer.toXML(msg);
            if (data == null) {
                data = "";
            }

            // Message type/version from the serializer's own metadata, then the data type's
            // vocabulary (element descriptions) — the same text the Swing tree shows. Only the
            // XML-serialized types decorate nodes; JSON is a plain object tree.
            String[] tv = typeAndVersion(serializer, msg);
            MessageVocabulary vocab = vocabularyFor(dataType, tv[1], tv[0]);
            String root = buildRoot(tv[0], tv[1], vocab);

            ObjectNode out = MAPPER.createObjectNode();
            out.put("format", json ? "json" : "xml");
            out.put("data", data);
            ObjectNode meta = out.putObject("meta");
            meta.put("root", root);
            meta.set("descriptions", (!json && vocab != null) ? buildDescriptions(data, vocab) : MAPPER.createObjectNode());
            return Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build();
        } catch (MirthApiException e) {
            throw e;
        } catch (Exception e) {
            throw new MirthApiException(e);
        }
    }

    /** Resolves the vocabulary for a data type by class name; null when it has none or isn't installed. */
    private static MessageVocabulary vocabularyFor(String dataType, String version, String type) {
        String className = VOCABULARY_CLASSES.get(dataType);
        if (className == null) {
            return null;
        }
        try {
            return (MessageVocabulary) Class.forName(className)
                    .getConstructor(String.class, String.class).newInstance(version, type);
        } catch (Throwable t) {
            return null;
        }
    }

    // Apply newline-separated key=value overrides to the data type's SerializationProperties,
    // coercing each value to the type the property currently holds (boolean/int/String). Only keys
    // the property group already exposes are touched; unknown keys are ignored.
    private static void applyOverrides(DataTypeProperties dtProps, String props) {
        if (isBlank(props)) {
            return;
        }
        SerializationProperties serProp = dtProps.getSerializationProperties();
        if (serProp == null) {
            return;
        }
        Map<String, Object> current = serProp.getProperties();
        if (current == null || current.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String line : props.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1);
            if (!current.containsKey(key)) {
                continue;
            }
            Object cur = current.get(key);
            Object coerced;
            if (cur instanceof Boolean) {
                coerced = Boolean.parseBoolean(val.trim());
            } else if (cur instanceof Integer) {
                try {
                    coerced = Integer.parseInt(val.trim());
                } catch (NumberFormatException nfe) {
                    continue;
                }
            } else {
                coerced = val;
            }
            current.put(key, coerced);
            changed = true;
        }
        if (changed) {
            serProp.setProperties(current);
        }
    }

    // [type, version] from the serializer's own message metadata (mirth_type / mirth_version).
    private static String[] typeAndVersion(IMessageSerializer serializer, String message) {
        try {
            Map<String, Object> md = serializer.getMetaDataFromMessage(message);
            if (md != null) {
                Object t = md.get("mirth_type");
                Object v = md.get("mirth_version");
                return new String[] { t == null ? "" : t.toString().trim(), v == null ? "" : v.toString().trim() };
            }
        } catch (Exception e) {
            // no metadata for this type
        }
        return new String[] { "", "" };
    }

    // "<type> (<version>)" plus the type's own description when the vocabulary has one — the
    // message-tree root label. Empty when the data type reports no type (e.g. XML/JSON).
    private static String buildRoot(String type, String version, MessageVocabulary vocab) {
        if (type.isEmpty()) {
            return "";
        }
        String root = type + " (" + (version.isEmpty() ? "Unknown version" : version) + ")";
        String desc = vocab == null ? "" : safeDesc(vocab, type.replace("-", ""));
        if (!desc.isEmpty()) {
            root += " (" + desc + ")";
        }
        return root;
    }

    private static String safeDesc(MessageVocabulary vocab, String elementId) {
        try {
            String r = vocab.getDescription(elementId);
            return r == null ? "" : r;
        } catch (Exception e) {
            return "";
        }
    }

    // Build a { "<nodeName>": "<description>" } object from the serialized XML by walking each
    // distinct element and looking up its vocabulary description. Best-effort: any failure yields
    // an empty object so the client falls back to bare node names.
    private static ObjectNode buildDescriptions(String xml, MessageVocabulary vocab) {
        if (isBlank(xml)) {
            return MAPPER.createObjectNode();
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            // XXE hardening: this parses the engine serializer's own output, but the factory runs
            // on the full engine classpath behind a network endpoint — disable DOCTYPE/entities.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

            Map<String, String> map = new LinkedHashMap<String, String>();
            walk(doc.getDocumentElement(), vocab, map, new HashSet<String>());

            ObjectNode descriptions = MAPPER.createObjectNode();
            for (Map.Entry<String, String> e : map.entrySet()) {
                descriptions.put(e.getKey(), e.getValue());
            }
            return descriptions;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static void walk(Element el, MessageVocabulary vocab, Map<String, String> map, Set<String> seen) {
        if (el == null) {
            return;
        }
        String name = el.getTagName();
        if (seen.add(name)) {
            String d = safeDesc(vocab, name);
            if (!d.isEmpty()) {
                map.put(name, d);
            }
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k instanceof Element) {
                walk((Element) k, vocab, map, seen);
            }
        }
    }

    /* ---- javascript: Rhino validation --------------------------------------------------- */

    @Override
    public Response validateScript(String script) {
        // JavaScriptSharedUtil.validateScript returns null when the script compiles, else the
        // error text ("Error on line N: ..."). Report it as { "error": <string|null> }.
        String error = JavaScriptSharedUtil.validateScript(script == null ? "" : script);
        ObjectNode out = MAPPER.createObjectNode();
        if (error == null) {
            out.putNull("error");
        } else {
            out.put("error", error);
        }
        return Response.ok(out.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    /* ---- web plugins: discovery + static serving ---------------------------------------- */

    @Override
    public List<String> getWebPluginPaths() {
        Set<String> paths = new LinkedHashSet<String>();
        File extRoot = new File(ExtensionController.getExtensionsPath());
        addWebPluginPaths(paths, extRoot, extensionController.getPluginMetaData());
        addWebPluginPaths(paths, extRoot, extensionController.getConnectorMetaData());
        return new ArrayList<String>(paths);
    }

    private void addWebPluginPaths(Set<String> paths, File extRoot, Map<String, ? extends MetaData> metaDataMap) {
        if (metaDataMap == null) {
            return;
        }
        for (MetaData metaData : metaDataMap.values()) {
            if (metaData == null) {
                continue;
            }
            String path = metaData.getPath();
            if (isBlank(path) || !SAFE_SEGMENT.matcher(path).matches()) {
                continue;
            }
            // A disabled extension's engine half is inactive, so hide its web half too.
            if (!extensionController.isExtensionEnabled(metaData.getName())) {
                continue;
            }
            File manifest = new File(new File(new File(extRoot, path), WEBADMIN_DIR), MANIFEST);
            if (manifest.isFile()) {
                paths.add(path);
            }
        }
    }

    @Override
    public Response getWebPluginResource(String extensionPath, String resourcePath) {
        if (isBlank(extensionPath) || !SAFE_SEGMENT.matcher(extensionPath).matches()) {
            throw new MirthApiException(Status.NOT_FOUND);
        }
        // A bare .../webadmin request serves the manifest (mirrors an index).
        if (isBlank(resourcePath)) {
            resourcePath = MANIFEST;
        }

        try {
            File webadminRoot = new File(new File(new File(ExtensionController.getExtensionsPath()), extensionPath), WEBADMIN_DIR).getCanonicalFile();
            File target = new File(webadminRoot, resourcePath).getCanonicalFile();

            // Confine the resolved file to the extension's webadmin/ folder: canonicalizing
            // first collapses any ".." and follows symlinks, so an entry that escapes the
            // folder (traversal or a planted symlink) is rejected here rather than served.
            String rootPath = webadminRoot.getPath();
            if (!target.getPath().equals(rootPath) && !target.getPath().startsWith(rootPath + File.separator)) {
                throw new MirthApiException(Status.FORBIDDEN);
            }
            if (!target.isFile()) {
                throw new MirthApiException(Status.NOT_FOUND);
            }

            byte[] data = Files.readAllBytes(target.toPath());
            return Response.ok(data)
                    .type(contentType(target.getName()))
                    // Plugin code changes only on install/restart, but revalidate so an
                    // updated web half is never served stale after an extension upgrade.
                    .header("Cache-Control", "no-cache")
                    .build();
        } catch (MirthApiException e) {
            throw e;
        } catch (IOException e) {
            throw new MirthApiException(e);
        }
    }

    /**
     * MIME type for a served asset. ES modules MUST be a JavaScript type with the right charset
     * or the browser refuses to execute them, so .js/.mjs (and .json/.map, which some containers
     * mislabel) are pinned here; everything else defers to the servlet container's own MIME
     * table ({@link ServletContext#getMimeType}) rather than a hand-rolled extension map.
     */
    private String contentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return "application/json; charset=utf-8";
        }
        String mapped = servletContext == null ? null : servletContext.getMimeType(lower);
        if (mapped != null) {
            return mapped.startsWith("text/") ? mapped + "; charset=utf-8" : mapped;
        }
        return "application/octet-stream";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
