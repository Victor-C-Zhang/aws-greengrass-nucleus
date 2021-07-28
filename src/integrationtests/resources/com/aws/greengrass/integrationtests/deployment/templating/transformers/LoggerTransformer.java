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

import java.util.Arrays;
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
                "sleep " + componentParams.get("intervalInSecs").asInt() + " &&\n"
              + "echo " + componentParams.get("message").asText()
              + (componentParams.get("timestamp").asBoolean() ? " ; echo `date`\n" : "\n");
        String runScriptWindows =
                "timeout " + componentParams.get("intervalInSecs").asInt() + " && "
              + "echo " + componentParams.get("message").asText()
              + (componentParams.get("timestamp").asBoolean() ? " && echo %DATE% %TIME%\n" : "\n");

        Map<String, Object> lifecycle = new HashMap<>();
        lifecycle.put("run", runScript);
        Map<String, Object> lifecycleWindows = new HashMap<>();
        lifecycleWindows.put("run", runScriptWindows);

        PlatformSpecificManifest manifest = PlatformSpecificManifest.builder()
                .platform(Platform.builder().os(Platform.OS.ALL).build())
                .lifecycle(lifecycle)
                .build();
        PlatformSpecificManifest manifestWindows = PlatformSpecificManifest.builder()
                .platform(Platform.builder().os(Platform.OS.WINDOWS).build())
                .lifecycle(lifecycleWindows)
                .build();

        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName(paramFile.getComponentName())
                .componentVersion(paramFile.getComponentVersion())
                .componentDescription(paramFile.getComponentDescription())
                .componentType(ComponentType.GENERIC)
                .manifests(Arrays.asList(manifestWindows, manifest))
                .build();
    }
}
