package org.openintegrationengine.plugins.websupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mirth.connect.model.FilterTransformerElement;

/** Invokes the installed element models' discovery contract, just as Swing does. */
final class ResponseVariables {
    private ResponseVariables() { }

    static List<String> discover(List<?> elements) {
        if (elements == null || elements.stream().anyMatch(e -> !(e instanceof FilterTransformerElement))) {
            throw new IllegalArgumentException("Expected a list of filter rules or transformer steps.");
        }
        Set<String> variables = new LinkedHashSet<>();
        for (Object value : elements) {
            // The caller selects enabled source elements and all destination elements.
            // Do not generate/evaluate scripts or require a browser-side plugin counterpart.
            Collection<?> found = ((FilterTransformerElement) value).getResponseVariables();
            if (found == null) continue;
            for (Object variable : found) {
                if (!(variable instanceof String)) {
                    throw new IllegalStateException("The response-variable provider returned an invalid key.");
                }
                variables.add((String) variable);
            }
        }
        return new ArrayList<>(variables);
    }
}
