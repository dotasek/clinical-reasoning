package org.opencds.cqf.fhir.utility.repository;

import com.google.common.collect.Sets;
import java.util.Set;

public enum ResourceCategory {
    DATA,
    TERMINOLOGY,
    CONTENT;

    private static final Set<String> TERMINOLOGY_RESOURCES = Sets.newHashSet("ValueSet", "CodeSystem");
    private static final Set<String> CONTENT_RESOURCES = Sets.newHashSet(
            "Library", "Questionnaire", "Measure", "PlanDefinition", "StructureDefinition", "ActivityDefinition");

    public static ResourceCategory forType(String resourceType) {
        if (TERMINOLOGY_RESOURCES.contains(resourceType)) {
            return TERMINOLOGY;
        } else if (CONTENT_RESOURCES.contains(resourceType)) {
            return CONTENT;
        }

        return DATA;
    }
}
