/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating.transformers.BDependentTransformer;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.Platform;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.deployment.templating.RecipeTransformer;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class DependentTransformer extends RecipeTransformer {
    @Override
    protected String initTemplateSchema() {
        return "{ }";
    }

    @Override
    protected Class<?> initRecievingClass() {
        return Void.class;
    }

    @Override
    public ComponentRecipe transform(ComponentRecipe paramFile, Object componentParamsObj)
            throws RecipeTransformerException {
        DependentModel dep = new DependentModel("fold", 42);
        Map<String, Object> newLifecyle = new HashMap<>();
        String runString = String.format("echo Field: %s Integer: %s",
                dep.getField(), dep.getInteger());
        newLifecyle.put("run", runString);

        PlatformSpecificManifest manifest =
                PlatformSpecificManifest.builder().lifecycle(newLifecyle).platform(
                        Platform.builder().os(Platform.OS.ALL).build()).build();
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName(paramFile.getComponentName())
                .componentVersion(paramFile.getComponentVersion())
                .componentType(ComponentType.GENERIC)
                .manifests(Collections.singletonList(manifest))
                .build();
    }
}
