/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating.transformers;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.Platform;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// doesn't extend recipe transformer
public class NoImplementedTransformer {
    private static final String COMPONENT_DESCRIPTION = "Component expanded with EchoTransformer";
    private static final String COMPONENT_PUBLISHER = "Me";

    /*
      param1:
        type: string
        required: true
      param2:
        type: string
        required: true
     */
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    private static final String TEMPLATE_SCHEMA = "{\n" + "  \"param1\": {\n" + "    \"type\": \"string\",\n"
            + "    \"required\": true\n" + "  },\n" + "  \"param2\": {\n" + "    \"type\": \"string\",\n"
            + "    \"required\": true\n" + "  },\n" + "}";

    protected String initTemplateSchema() {
        return TEMPLATE_SCHEMA;
    }

    // generate a component recipe from a list of well-behaved parameters
    public ComponentRecipe transform(ComponentRecipe paramFile, JsonNode componentConfig) {
        Map<String, Object> newLifecyle = new HashMap<>();
        String runString = String.format("echo Param1: %s Param2: %s",
                componentConfig.get("param1").asText(), componentConfig.get("param2").asText());
        newLifecyle.put("run", runString);

        PlatformSpecificManifest manifest =
                PlatformSpecificManifest.builder().lifecycle(newLifecyle).platform(
                        Platform.builder().os(Platform.OS.ALL).build()).build();

        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName(paramFile.getComponentName())
                .componentVersion(paramFile.getComponentVersion())
                .componentDescription(COMPONENT_DESCRIPTION)
                .componentPublisher(COMPONENT_PUBLISHER)
                .componentType(ComponentType.GENERIC)
                .manifests(Collections.singletonList(manifest))
                .build();
    }
}
