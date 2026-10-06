package org.openintegrationengine.plugins.websupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.model.util.MessageVocabulary;

/** Server-side counterpart to Swing's DataTypeClientPlugin.getVocabulary(). */
final class MessageVocabularyResolver {
    private static final Logger LOGGER = LogManager.getLogger(MessageVocabularyResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Map<String, String> BUILT_INS = Map.of(
            "HL7V2", "com.mirth.connect.plugins.datatypes.hl7v2.HL7v2Vocabulary",
            "EDI/X12", "com.mirth.connect.plugins.datatypes.edi.X12Vocabulary",
            "NCPDP", "com.mirth.connect.plugins.datatypes.ncpdp.NCPDPVocabulary",
            "DICOM", "com.mirth.connect.plugins.datatypes.dicom.DICOMVocabulary");

    private MessageVocabularyResolver() {
    }

    static MessageVocabulary resolve(String dataType, String version, String type, Path extensionsRoot,
            Supplier<? extends Iterable<String>> enabledPaths, ClassLoader loader) {
        String className = BUILT_INS.get(dataType);
        boolean builtIn = className != null;
        try {
            if (!builtIn) {
                className = declaredClass(dataType, extensionsRoot, enabledPaths.get());
            }
            if (className == null) {
                return null;
            }
            // Verify the superclass before initialization: a wrong class must not run its
            // static initializer. The datatype's engine loader sees its SHARED libraries.
            Class<? extends MessageVocabulary> clazz = Class.forName(className, false, loader)
                    .asSubclass(MessageVocabulary.class);
            MessageVocabulary vocabulary = clazz.getConstructor(String.class, String.class)
                    .newInstance(version, type);
            if (!builtIn && !dataType.equals(vocabulary.getDataType())) {
                LOGGER.warn("Ignoring vocabulary {}: it does not describe datatype {}", className, dataType);
                return null;
            }
            return vocabulary;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            // Vocabulary is optional. Bad declarations/dependencies must not break serialization.
            LOGGER.warn("Unable to load vocabulary {} for datatype {}: {}", className, dataType, e.toString());
            return null;
        }
    }

    private static String declaredClass(String dataType, Path extensionsRoot, Iterable<String> paths) {
        String selected = null;
        for (String extension : paths) {
            if (extension == null || !SAFE_SEGMENT.matcher(extension).matches()
                    || extension.equals(".") || extension.equals("..")) {
                continue;
            }
            try {
                Path webRoot = extensionsRoot.toRealPath().resolve(extension).resolve("webadmin");
                Path manifest = webRoot.resolve("plugin.json").toRealPath();
                // Reject symlinks as well as directory traversal outside this extension's web half.
                if (!manifest.startsWith(webRoot) || !Files.isRegularFile(manifest)) {
                    continue;
                }
                JsonNode json = MAPPER.readTree(manifest.toFile());
                if (json == null || (json.path("enabled").isBoolean() && !json.path("enabled").asBoolean())) {
                    continue;
                }
                JsonNode declaration = json.path("vocabularies").path(dataType);
                if (declaration.isMissingNode() || declaration.isNull()) {
                    continue;
                }
                if (!declaration.isTextual() || declaration.asText().isBlank()) {
                    LOGGER.warn("Ignoring invalid vocabulary declaration for {} in {}", dataType, manifest);
                    continue;
                }
                if (selected != null) {
                    // Do not let extension enumeration order pick the vocabulary for a datatype.
                    LOGGER.warn("Multiple enabled extensions declare a vocabulary for {}; using bare labels", dataType);
                    return null;
                }
                selected = declaration.asText().trim();
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Unable to read vocabulary manifest for extension {}: {}", extension, e.toString());
            }
        }
        // Deliberately do not cache manifests or instances: disable/re-enable and manifest
        // replacement are visible on the next request; constructors receive each message's metadata.
        return selected;
    }
}
