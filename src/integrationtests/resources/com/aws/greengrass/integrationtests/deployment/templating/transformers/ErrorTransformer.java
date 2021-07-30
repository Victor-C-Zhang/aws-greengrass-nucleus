/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.deployment.templating.RecipeTransformer;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.fasterxml.jackson.databind.JsonNode;

public class ErrorTransformer extends RecipeTransformer {
    @Override
    protected String initTemplateSchema() {
        return null; // should actually be non-null in production
    }

    @Override
    public ComponentRecipe transform(ComponentRecipe paramFile, JsonNode componentParams)
            throws RecipeTransformerException {
        return null;
    }
}
