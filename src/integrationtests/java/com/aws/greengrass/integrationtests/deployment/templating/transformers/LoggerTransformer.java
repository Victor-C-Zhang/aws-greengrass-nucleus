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
import com.aws.greengrass.deployment.templating.RecipeTransformer;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;

public class LoggerTransformer extends RecipeTransformer {
    private static final String PARAMETER_SCHEMA = "intervalInSecs:\n" + "  type: number\n" + "  required: true\n" +
            "timestamp:\n" + "  type: boolean\n" + "  required: false\n" + "message:\n" + "  type: string\n"
            + "  required: false";

    @Override
    protected JsonNode initTemplateSchema() throws TemplateParameterException {
        try {
            return getRecipeSerializer().readTree(PARAMETER_SCHEMA);
        } catch (JsonProcessingException e) {
            throw new TemplateParameterException(e);
        }
    }

    @Override
    public ComponentRecipe transform(ComponentRecipe paramFile, JsonNode componentParams)
            throws RecipeTransformerException {
        String runScript =
                "for ((i=30; i>0; i--)); do\n"
              + "  sleep " + componentParams.get("intervalInSecs").asInt() + " &\n"
              + "  echo " + componentParams.get("message").asText()
              + (componentParams.get("timestamp").asBoolean() ? " ; echo `date`\n" : "\n")
              + "  wait\n"
              + "done";

        Map<String, Object> lifecycle = new HashMap<>();
        lifecycle.put("run", runScript);
        PlatformSpecificManifest manifest = PlatformSpecificManifest.builder()
                .platform(Platform.builder().os(Platform.OS.ALL).build())
                .lifecycle(lifecycle)
                .build();

        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName(paramFile.getComponentName())
                .componentVersion(paramFile.getComponentVersion())
                .componentDescription(paramFile.getComponentDescription())
                .componentType(ComponentType.GENERIC)
                .manifests(Collections.singletonList(manifest))
                .build();
    }
}
