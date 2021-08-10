/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating.transformers;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.deployment.templating.RecipeTransformer;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;

public class ErrorTransformer extends RecipeTransformer {
    @Override
    protected String initTemplateSchema() {
        return null; // should actually be non-null in production
    }

    @Override
    protected Class<?> initRecievingClass() {
        return null; // should actually be non-null in production
    }

    @Override
    public ComponentRecipe transform(ComponentRecipe paramFile, Object componentParamsObj)
            throws RecipeTransformerException {
        return null;
    }
}
