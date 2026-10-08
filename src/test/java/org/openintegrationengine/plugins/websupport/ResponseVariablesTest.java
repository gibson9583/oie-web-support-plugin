package org.openintegrationengine.plugins.websupport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.providers.JsonMessageBodyReader;
import com.mirth.connect.client.core.api.providers.XmlMessageBodyReader;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.FilterTransformerElement;
import com.mirth.connect.model.Rule;
import com.mirth.connect.model.Step;
import com.mirth.connect.model.converters.ObjectJSONSerializer;
import com.mirth.connect.model.converters.ObjectXMLSerializer;

class ResponseVariablesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll static void configureEngineSerializer() throws Exception {
        ObjectXMLSerializer.getInstance().init("4.6.0");
        // As for an installed extension's shared models, permit these fixture types.
        ObjectXMLSerializer.getInstance().allowTypes(
                List.of(ResponseStepFixture.class.getName(), ResponseRuleFixture.class.getName()), null, null);
    }

    @Test void callsCustomModelsDeduplicatesAndPreservesKeysExactly() {
        var step = new ResponseStepFixture();
        step.responseKey = "ack-結果\t'\\";
        var rule = new ResponseRuleFixture();
        rule.responseKey = "ruleAck";
        assertEquals(List.of(step.responseKey, "shared", "ruleAck"),
                ResponseVariables.discover(List.of(step, rule, step)));
        rule.responseKey = "unsavedChange";
        assertEquals(List.of("unsavedChange"), ResponseVariables.discover(List.of(rule)));
    }

    @Test void callerControlsSourceAndDestinationInclusion() {
        var step = new ResponseStepFixture();
        step.setEnabled(false);
        step.responseKey = "disabledDestination";
        assertEquals(List.of("disabledDestination", "shared"), ResponseVariables.discover(List.of(step)));
        assertEquals(List.of(), ResponseVariables.discover(List.of()));
        assertEquals(List.of(), ResponseVariables.discover(List.of(new ResponseRuleFixture())));
    }

    @Test void rejectsBadInputsAndProviderResultsWithoutPartialSuccess() {
        assertThrows(IllegalArgumentException.class, () -> ResponseVariables.discover(null));
        assertThrows(IllegalArgumentException.class, () -> ResponseVariables.discover(Arrays.asList((Object) null)));
        assertThrows(IllegalArgumentException.class, () -> ResponseVariables.discover(List.of("not a step")));
        var step = new ResponseStepFixture();
        step.responseKey = "valid";
        var broken = new ResponseRuleFixture();
        broken.fail = true;
        assertThrows(IllegalStateException.class, () -> ResponseVariables.discover(List.of(step, broken)));
        broken.fail = false;
        broken.invalidKey = true;
        assertThrows(IllegalStateException.class, () -> ResponseVariables.discover(List.of(step, broken)));
        broken.invalidKey = false;
        broken.responseKey = "recovered";
        assertEquals(List.of("valid", "shared", "recovered"), ResponseVariables.discover(List.of(step, broken)));
    }

    @Test void deserializesClassKeyedUnsavedCustomStepsAndRulesThroughTheEngineReader() throws Exception {
        var step = new ResponseStepFixture();
        step.responseKey = "old";
        step.setSequenceNumber("0");
        var rule = new ResponseRuleFixture();
        rule.responseKey = "ruleAck";
        rule.setSequenceNumber("1");
        var bytes = new ByteArrayOutputStream();
        ObjectJSONSerializer.getInstance().serialize(new ArrayList<>(List.of(step, rule)), bytes);
        var wire = JSON.readTree(bytes.toByteArray());
        assertFalse(wire.toString().contains("responseVariables"), "Computed getters are absent from channel JSON");
        // Browser edits only a model field: the Java override must discover the new value.
        ((com.fasterxml.jackson.databind.node.ObjectNode) wire.get("list").get(ResponseStepFixture.class.getName()))
                .put("responseKey", "unsaved-結果");
        var elements = readElements(wire.toString());
        assertInstanceOf(ResponseStepFixture.class, elements.get(0));
        assertInstanceOf(ResponseRuleFixture.class, elements.get(1));
        assertEquals(List.of("unsaved-結果", "shared", "ruleAck"), ResponseVariables.discover(elements));
        assertEquals("old", step.responseKey, "Discovery works on detached request objects");
        assertEquals(List.of("unsaved-結果", "shared", "ruleAck"), ResponseVariables.discover(readElements(wire.toString())));
    }

    @Test void acceptsSingletonAndEmptyWireListsAndRejectsNonElements() throws Exception {
        String fixture = JSON.writeValueAsString(ResponseRuleFixture.class.getName());
        assertEquals(List.of("singleton"), ResponseVariables.discover(readElements(
                "{\"list\":{" + fixture + ":{\"@version\":\"4.6.0\",\"responseKey\":\"singleton\"}}}")));
        assertEquals(List.of(), ResponseVariables.discover(readElements("{\"list\":\"\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> ResponseVariables.discover(readElements("{\"list\":{\"string\":\"not a step\"}}")));
    }

    @Test void acceptsRepeatedClassEntriesInTheBrowserRequestShape() throws Exception {
        String fixture = JSON.writeValueAsString(ResponseRuleFixture.class.getName());
        var elements = readElements("{\"list\":{" + fixture + ":["
                + "{\"@version\":\"4.6.0\",\"sequenceNumber\":\"0\",\"responseKey\":\"first\"},"
                + "{\"@version\":\"4.6.0\",\"sequenceNumber\":\"1\",\"responseKey\":\"second\"}]}}");
        assertEquals(List.of("first", "second"), ResponseVariables.discover(elements));
    }

    @Test @SuppressWarnings("unchecked") void acceptsTheEquivalentXmlRequest() throws Exception {
        var step = new ResponseStepFixture();
        step.responseKey = "xmlAck";
        var xml = ObjectXMLSerializer.getInstance().serialize(new ArrayList<>(List.of(step)));
        var genericType = WebSupportServletInterface.class.getMethod("getElementResponseVariables", List.class)
                .getGenericParameterTypes()[0];
        var elements = (List<FilterTransformerElement>) new XmlMessageBodyReader().readFrom(
                (Class<Object>) (Class<?>) List.class, genericType, new Annotation[0],
                MediaType.APPLICATION_XML_TYPE, new MultivaluedHashMap<>(),
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("xmlAck", "shared"), ResponseVariables.discover(elements));
    }

    @Test void followsExistingAuthenticatedHelperOperationPolicy() throws Exception {
        var operation = WebSupportServletInterface.class.getMethod("getElementResponseVariables", List.class)
                .getAnnotation(MirthOperation.class);
        assertEquals("getElementResponseVariables", operation.name());
        assertFalse(operation.auditable());
        assertEquals("", operation.permission());
        assertTrue(OperationUtil.getOperations(WebSupportServletInterface.class).stream()
                .anyMatch(candidate -> candidate.getName().equals(operation.name())));
    }

    @Test void preservesTheEngineDeserializationAllowlist() {
        assertThrows(com.mirth.connect.donkey.util.xstream.SerializerException.class, () -> readElements(
                "{\"list\":{\"" + UnregisteredStepFixture.class.getName()
                        + "\":{\"@version\":\"4.6.0\",\"responseKey\":\"forbidden\"}}}"));
    }

    @SuppressWarnings("unchecked")
    private static List<FilterTransformerElement> readElements(String json) throws Exception {
        var genericType = WebSupportServletInterface.class.getMethod("getElementResponseVariables", List.class)
                .getGenericParameterTypes()[0];
        return (List<FilterTransformerElement>) new JsonMessageBodyReader().readFrom(
                (Class<Object>) (Class<?>) List.class, genericType, new Annotation[0],
                MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}

// Stand-ins for custom extension shared models: discovery depends on model fields,
// not a built-in class name, a serialized responseVariables getter, or generated JS.
class ResponseStepFixture extends Step {
    String responseKey;
    @Override public Collection<String> getResponseVariables() { return List.of(responseKey, "shared"); }
    @Override public String getScript(boolean loadFiles) { throw new AssertionError("Do not generate scripts"); }
    @Override public String getType() { return "Custom response step"; }
    @Override public Step clone() { throw new UnsupportedOperationException(); }
}

class ResponseRuleFixture extends Rule {
    String responseKey;
    boolean fail;
    boolean invalidKey;
    @Override public Collection<String> getResponseVariables() {
        if (fail) throw new IllegalStateException("Provider failed");
        if (invalidKey) return Arrays.asList((String) null);
        return responseKey == null ? super.getResponseVariables() : List.of(responseKey);
    }
    @Override public String getScript(boolean loadFiles) { throw new AssertionError("Do not generate scripts"); }
    @Override public String getType() { return "Custom response rule"; }
    @Override public Rule clone() { throw new UnsupportedOperationException(); }
}

class UnregisteredStepFixture extends ResponseStepFixture { }
