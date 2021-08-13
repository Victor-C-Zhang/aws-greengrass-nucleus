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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class LoggerTransformer extends RecipeTransformer {

    /*
      intervalInSecs:
        type: number
        required: true
      timestamp:
        type: boolean
        required: false
        defaultValue: false
      message:
        type: string
        required: false
        defaultValue: Ping pong its a default message
     */
    private static final String PARAMETER_SCHEMA = "intervalInSecs:\n" + "  type: number\n" + "  required: true\n"
            + "timestamp:\n" + "  type: boolean\n" + "  required: false\n" + "  defaultValue: false\n" + "message:\n"
            + "  type: string\n" + "  required: false\n" + "  defaultValue: Ping pong its a default message";

    @Override
    protected String initTemplateSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Class<?> initRecievingClass() {
        return TransformerParams.class;
    }

    @Override
    public ComponentRecipe transform(ComponentRecipe paramFile, Object componentParamsObj)
            throws RecipeTransformerException {
        TransformerParams componentParams = (TransformerParams) componentParamsObj;
        String getTimestampString = componentParams.getTimestamp() ? " ; echo `date`" : "";
        String getTimestampStringWindows = componentParams.getTimestamp() ? " && echo %DATE% %TIME%" : "";

        String runScript = String.format("sleep %d && echo %s", componentParams.getIntervalInSecs(),
                componentParams.getMessage())
                + getTimestampString;
        String runScriptWindows = String.format("echo %s", componentParams.getMessage())
                + getTimestampStringWindows;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TransformerParams {
        Integer intervalInSecs;
        Boolean timestamp;
        String message;
    }
}
