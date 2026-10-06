package org.openintegrationengine.plugins.websupport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.model.util.MessageVocabulary;
import com.mirth.connect.model.util.MessageVocabularyFactory;

class MessageVocabularyResolverTest {
    @TempDir Path extensions;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean WRONG_CLASS_INITIALIZED = new AtomicBoolean();

    public static class FixtureVocabulary extends MessageVocabulary {
        private final String version;
        private final String type;
        public FixtureVocabulary(String version, String type) {
            super(version, type);
            this.version = version;
            this.type = type;
        }
        @Override public String getDataType() { return "EDIFACT"; }
        @Override public String getDescription(String elementId) {
            return version + "/" + type + "/" + elementId;
        }
    }

    public static class OtherVocabulary extends FixtureVocabulary {
        public OtherVocabulary(String version, String type) { super(version, type); }
        @Override public String getDataType() { return "OTHER"; }
    }

    public static class BrokenVocabulary extends FixtureVocabulary {
        public BrokenVocabulary(String version, String type) {
            super(version, type);
            throw new IllegalStateException("bad constructor");
        }
    }

    public static class MissingConstructor extends MessageVocabulary {
        public MissingConstructor() { super("", ""); }
        @Override public String getDataType() { return "EDIFACT"; }
        @Override public String getDescription(String id) { return ""; }
    }

    public static class NotAVocabulary {
        static { WRONG_CLASS_INITIALIZED.set(true); }
        public NotAVocabulary(String version, String type) { }
    }

    private Path manifest(String extension, Object vocabularies) throws Exception {
        Path path = extensions.resolve(extension).resolve("webadmin/plugin.json");
        Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), Map.of("id", extension, "vocabularies", vocabularies));
        return path;
    }

    private MessageVocabulary resolve(String... paths) {
        return MessageVocabularyResolver.resolve("EDIFACT", "D96A", "MEDLAB", extensions,
                () -> List.of(paths), getClass().getClassLoader());
    }

    @Test void matchesSwingFactoryAndUsesEachMessagesVersionAndType() throws Exception {
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        var swing = MessageVocabularyFactory.getInstance(null, Map.of("EDIFACT", FixtureVocabulary.class));
        for (String version : List.of("D96A", "D99B")) {
            for (String type : List.of("MEDLAB", "ORDERS")) {
                var web = MessageVocabularyResolver.resolve("EDIFACT", version, type, extensions,
                        () -> List.of("edifact"), getClass().getClassLoader());
                var desktop = swing.getVocabulary("EDIFACT", version, type);
                assertNotNull(web);
                for (String element : List.of("MEDLAB", "BEP", "BEP.02")) {
                    assertEquals(desktop.getDescription(element), web.getDescription(element));
                }
            }
        }
        assertNotSame(resolve("edifact"), resolve("edifact"));
    }

    @Test void ignoresAbsentDisabledAndRemovedExtensionsWithoutStaleResults() throws Exception {
        Path path = manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        assertNull(resolve()); // A disabled engine extension is absent from enabled discovery.
        assertNotNull(resolve("edifact"));
        assertNull(resolve());
        JSON.writeValue(path.toFile(), Map.of("enabled", false,
                "vocabularies", Map.of("EDIFACT", FixtureVocabulary.class.getName())));
        assertNull(resolve("edifact"));
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        assertNotNull(resolve("edifact"));
        Files.delete(path);
        assertNull(resolve("edifact"));
    }

    @Test void refreshesReplacedManifestsAndRetriesAfterAnInvalidClass() throws Exception {
        manifest("edifact", Map.of("EDIFACT", "not.installed.Vocabulary"));
        assertNull(resolve("edifact"));
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        assertNotNull(resolve("edifact"));
        manifest("edifact", Map.of("OTHER", FixtureVocabulary.class.getName()));
        assertNull(resolve("edifact"));
    }

    @Test void malformedManifestCannotHideAValidDeclaration() throws Exception {
        Path bad = manifest("bad", Map.of());
        Files.writeString(bad, "{broken json");
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        assertNotNull(resolve("bad", "edifact"));
        for (Object invalid : List.of(1, true, List.of("class"), Map.of("class", "value"), " ")) {
            manifest("bad", Map.of("EDIFACT", invalid));
            assertNotNull(resolve("bad", "edifact"));
        }
    }

    @Test void conflictingDeclarationsAreRejectedRegardlessOfEnumerationOrder() throws Exception {
        manifest("one", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        manifest("two", Map.of("EDIFACT", OtherVocabulary.class.getName()));
        assertNull(resolve("one", "two"));
        assertNull(resolve("two", "one"));
        assertNotNull(resolve("one"));
    }

    @Test void validatesSuperclassBeforeInitializingClassesAndFailsSoftly() throws Exception {
        for (Class<?> invalid : List.of(NotAVocabulary.class, MissingConstructor.class,
                BrokenVocabulary.class, OtherVocabulary.class)) {
            manifest("edifact", Map.of("EDIFACT", invalid.getName()));
            assertNull(resolve("edifact"));
        }
        assertFalse(WRONG_CLASS_INITIALIZED.get());
    }

    @Test void usesTheDatatypeLoaderAndHandlesMissingSharedDependencies() throws Exception {
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        ClassLoader brokenLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(FixtureVocabulary.class.getName())) throw new NoClassDefFoundError("missing shared dependency");
                return super.loadClass(name, resolve);
            }
        };
        assertNull(MessageVocabularyResolver.resolve("EDIFACT", "D96A", "MEDLAB", extensions,
                () -> List.of("edifact"), brokenLoader));
        assertNotNull(resolve("edifact"));
    }

    @Test void builtInsRemainAuthoritativeAndDoNotScanExtensionManifests() {
        for (String type : List.of("HL7V2", "EDI/X12", "NCPDP", "DICOM")) {
            AtomicBoolean scanned = new AtomicBoolean();
            AtomicBoolean loadedBuiltIn = new AtomicBoolean();
            ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("com.mirth.connect.plugins.datatypes.")) {
                        loadedBuiltIn.set(true);
                        throw new ClassNotFoundException(name);
                    }
                    return super.loadClass(name, resolve);
                }
            };
            assertNull(MessageVocabularyResolver.resolve(type, "", "", extensions,
                    () -> { scanned.set(true); return List.of("edifact"); }, loader));
            assertFalse(scanned.get());
            assertTrue(loadedBuiltIn.get());
        }
    }

    @Test void rejectsTraversalAndSymlinksEscapingTheExtensionsWebDirectory() throws Exception {
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        assertNull(resolve("..", ".", "../edifact", "/edifact", "edifact/webadmin"));
        Path elsewhere = extensions.resolve("elsewhere.json");
        JSON.writeValue(elsewhere.toFile(), Map.of("vocabularies", Map.of("EDIFACT", FixtureVocabulary.class.getName())));
        Path linked = extensions.resolve("linked/webadmin/plugin.json");
        Files.createDirectories(linked.getParent());
        Files.createSymbolicLink(linked, elsewhere);
        assertNull(resolve("linked"));
        Files.createSymbolicLink(extensions.resolve("aliased"), extensions.resolve("edifact"));
        assertNull(resolve("aliased"));
    }

    @Test void discoveryFailureAndConcurrentLookupsAreIsolated() throws Exception {
        assertNull(MessageVocabularyResolver.resolve("EDIFACT", "", "", extensions,
                () -> { throw new IllegalStateException("discovery unavailable"); }, getClass().getClassLoader()));
        manifest("edifact", Map.of("EDIFACT", FixtureVocabulary.class.getName()));
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> jobs = java.util.stream.IntStream.range(0, 16).mapToObj(i -> (Callable<String>) () -> {
                var vocabulary = MessageVocabularyResolver.resolve("EDIFACT", "V" + i, "T" + i, extensions,
                        () -> List.of("edifact"), getClass().getClassLoader());
                return vocabulary.getDescription("BEP.02");
            }).toList();
            var results = executor.invokeAll(jobs);
            for (int i = 0; i < results.size(); i++) assertEquals("V" + i + "/T" + i + "/BEP.02", results.get(i).get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
