/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.TemplateParameter;
import com.amazon.aws.iot.greengrass.component.common.TemplateParameterSchema;
import com.amazon.aws.iot.greengrass.component.common.TemplateParameterType;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;

/**
 * Interface representing a runnable that takes as input(s) minimized recipe(s) and generates full recipe(s) and
 * artifacts. Only maintains state for the template.
 */
public abstract class RecipeTransformer {
    private TemplateParameterSchema templateSchema;
    private Class<?> templateParametersShape;

    /**
     * Post-construction and injection, initialize the transformer with a template.
     * @param templateRecipe to extract default params, param schema.
     * @throws TemplateParameterException if the template recipe schema or transformer-provided schema is malformed.
     */
    void initTemplateRecipe(ComponentRecipe templateRecipe) throws TemplateParameterException {
        try {
            // init transformer-specific values
            templateSchema = getRecipeSerializer().readValue(initTemplateSchema(), TemplateParameterSchema.class);
            templateParametersShape = initRecievingClass();

            // validate transformer-provided schema
            boolean shouldError = false;
            StringBuilder errorBuilder = new StringBuilder("Template transformer binary provided invalid schema:");
            for (Map.Entry<String, TemplateParameter> entry : templateSchema.entrySet()) {
                if (entry.getValue().getRequired() && entry.getValue().getDefaultValue() != null) {
                    shouldError = true;
                    errorBuilder.append("\nProvided default value for required field: ").append(entry.getKey());
                    continue;
                }
                if (!entry.getValue().getRequired() && entry.getValue().getDefaultValue() == null) {
                    shouldError = true;
                    errorBuilder.append("\nDid not provide default value for optional field: ").append(entry.getKey());
                    continue;
                }
                if (!entry.getValue().getRequired()) {
                    TemplateParameterType actualType = extractType(entry.getValue().getDefaultValue());
                    if (!entry.getValue().getType().equals(actualType)) {
                        shouldError = true;
                        errorBuilder.append("\nTemplate value for \"").append(entry.getKey())
                                .append("\" does not match schema. Expected ").append(entry.getValue())
                                .append(" but got ").append(actualType);
                    }
                }
            }
            if (shouldError) {
                throw new TemplateParameterException(errorBuilder.toString());
            }
        } catch (JsonProcessingException e) {
            throw new TemplateParameterException(e);
        }

        // validate template recipe schema
        Map<String, TemplateParameter> recipeSchema = templateRecipe.getTemplateParameterSchema();
        if (recipeSchema == null) {
            validateTemplateComponentConfig(new TemplateParameterSchema());
        } else {
            validateTemplateComponentConfig(new TemplateParameterSchema(recipeSchema));
        }
    }

    /**
     * Workaround to declaring an "abstract" template schema field.
     * @return a stringified JsonNode (in YAML format) representing the desired template schema. Can be a node with no
     *     fields, representing a pure substitution template.
     */
    protected abstract String initTemplateSchema();

    /**
     * Method to declare the shape of the parameters the transformer uses.
     * @return the user-defined data class representing the parameter structure to use for transformation.
     */
    protected abstract Class<?> initRecievingClass();

    /**
     * Stateless expansion for one component.
     * @param parameterFile the parameter file for the component.
     * @return a recipe. See the declaration for {@link #transform(ComponentRecipe, Object) transform}.
     * @throws RecipeTransformerException if the provided parameters violate the template schema.
     */
    public ComponentRecipe execute(ComponentRecipe parameterFile) throws RecipeTransformerException {
        Object effectiveComponentParams = mergeAndValidateComponentParams(parameterFile);
        return transform(parameterFile, effectiveComponentParams);
    }

    /**
     * Transforms the parameter file into a full recipe.
     * @param paramFile the parameter file object.
     * @param componentParamsObj the effective component parameters to use during expansion, expressed as an object of
     *                           type given by {@link #initRecievingClass()}.
     * @return the expanded recipe.
     * @throws RecipeTransformerException if there is any error with the transformation.
     */
    public abstract ComponentRecipe transform(ComponentRecipe paramFile, Object componentParamsObj)
            throws RecipeTransformerException;

    /**
     * Validate the schema provided by the template recipe.
     * @param recipeProvidedSchema the TemplateParameterSchema recipe key.
     * @throws TemplateParameterException if the template recipe file or given configuration is malformed.
     */
    protected void validateTemplateComponentConfig(TemplateParameterSchema recipeProvidedSchema)
            throws TemplateParameterException {
        if (recipeProvidedSchema == null) {
            recipeProvidedSchema = new TemplateParameterSchema();
        }

        // tell user about all schema errors in one message
        boolean shouldError = false;
        StringBuilder errorBuilder =
                new StringBuilder("Template recipe provided schema different from template transformer binary:");
        // check both ways
        for (Map.Entry<String, TemplateParameter> e : templateSchema.entrySet()) {
            if (recipeProvidedSchema.get(e.getKey()) == null) {
                shouldError = true;
                errorBuilder.append("\nMissing parameter: ").append(e.getKey());
                continue;
            }
            TemplateParameter recipeProvidedParam = recipeProvidedSchema.get(e.getKey());
            if (!e.getValue().equals(recipeProvidedParam)) {
                shouldError = true;
                errorBuilder.append("\nTemplate value for \"").append(e.getKey())
                        .append("\" does not match schema. Expected ").append(e.getValue()).append(" but got ")
                        .append(recipeProvidedParam);
            }
        }
        for (String key : recipeProvidedSchema.keySet()) {
            if (!templateSchema.containsKey(key)) {
                shouldError = true;
                errorBuilder.append("\nTemplate declared parameter not found in schema: ").append(key);
            }
        }

        if (shouldError) {
            throw new TemplateParameterException(errorBuilder.toString());
        }
    }

    // merges the template-provided default parameters and parameters provided by the parameter file. validates the
    // resulting parameter set satisfies the schema declared by the template.
    // returns the resulting parameter set.
    protected Object mergeAndValidateComponentParams(ComponentRecipe paramFile)
            throws RecipeTransformerException {
        Map<String, Object> params = paramFile.getTemplateParameters();
        if (params == null) {
            params = new HashMap<>();
        }
        Map<String, Object> mergedParams = mergeParams(templateSchema, params);
        try {
            validateParams(mergedParams);
        } catch (TemplateParameterException e) {
            throw new RecipeTransformerException("Configuration for component " + paramFile.getComponentName()
                    + " does not match required schema", e);
        }
        try {
             return getRecipeSerializer().readValue(getRecipeSerializer().writeValueAsString(mergedParams),
                    templateParametersShape);
        } catch (JsonProcessingException e) {
            throw new RecipeTransformerException(e);
        }
    }


    /* Utility functions */

    // checks the provided parameter map satisfies the schema
    void validateParams(Map<String, Object> params) throws TemplateParameterException {
        boolean shouldError = false;
        StringBuilder errorBuilder = new StringBuilder("Provided parameters do not satisfy template schema:");
        for (Map.Entry<String, TemplateParameter> e : templateSchema.entrySet()) {
            if (!params.containsKey(e.getKey())) { // we validate template defaults, so this can only happen if a
                // required param is not given
                shouldError = true;
                errorBuilder.append("\nConfiguration does not specify required parameter: ").append(e.getKey());
                continue;
            }
            TemplateParameterType actualType = extractType(params.get(e.getKey()));
            if (!e.getValue().getType().equals(actualType)) {
                shouldError = true;
                errorBuilder.append("\nProvided parameter \"").append(e.getKey())
                        .append("\" does not specify required schema. Expected ").append(e.getValue().getType())
                        .append(" but got ").append(actualType);
            }
        }
        for (String key : params.keySet()) {
            if (!templateSchema.containsKey(key)) {
                shouldError = true;
                errorBuilder.append("\nConfiguration declared parameter not found in schema: ").append(key);
            }
        }

        if (shouldError) {
            throw new TemplateParameterException(errorBuilder.toString());
        }
    }

    // merge the defaultVal parameter map into the customVal parameter map by set addition. if both declare a value
    // for the same parameter, the one in customVal takes precedence.
    static Map<String, Object> mergeParams(TemplateParameterSchema defaultVal, Map<String, Object> customVal) {
        Map<String, Object> retval = new HashMap<>(customVal);
        for (Map.Entry<String, TemplateParameter> e : defaultVal.entrySet()) {
            if (!retval.containsKey(e.getKey())) {
                retval.put(e.getKey(), e.getValue().getDefaultValue());
            }
        }
        return retval;
    }

    // Utility to get the JSON node type from an object. Returns null if the object is not a known type.
    @Nullable
    protected static TemplateParameterType extractType(Object object) {
        try {
            switch (getRecipeSerializer().readTree(getRecipeSerializer().writeValueAsString(object)).getNodeType()) {
                case STRING:
                    return TemplateParameterType.STRING;
                case NUMBER:
                    return TemplateParameterType.NUMBER;
                case OBJECT:
                    return TemplateParameterType.OBJECT;
                case ARRAY:
                    return TemplateParameterType.ARRAY;
                case BOOLEAN:
                    return TemplateParameterType.BOOLEAN;
                default:
                    return null;
            }
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
