/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.MissingTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterTypeMismatchException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Optional;
import javax.annotation.Nullable;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;

/**
 * Interface representing a runnable that takes as input(s) minimized recipe(s) and generates full recipe(s) and
 * artifacts. Only maintains state for the template.
 */
public abstract class RecipeTransformer {
    // valid json data types
    private static final String STRING_TYPE = "string";
    private static final String NUMBER_TYPE = "number";
    private static final String OBJECT_TYPE = "object";
    private static final String ARRAY_TYPE = "array";
    private static final String BOOLEAN_TYPE = "boolean";
    private static final String NULL_TYPE = "null";

    // TODO: should this be declared in an extension class to ComponentRecipe?
    static final String TEMPLATE_PARAMETER_SCHEMA_KEY = "parameterSchema";
    static final String TEMPLATE_DEFAULT_PARAMETER_KEY = "parameters";
    static final String TEMPLATE_FIELD_REQUIRED_KEY = "required";
    static final String TEMPLATE_FIELD_TYPE_KEY = "type";

    protected static final ObjectMapper RECIPE_SERIALIZER = getRecipeSerializer();

    private final JsonNode templateSchema;
    private final JsonNode effectiveDefaultConfig;

    /**
     * Constructor. One class instance for each template; instances are shared between parameter files for the same
     * template.
     * @param templateRecipe to extract default params, param schema.
     * @throws TemplateParameterException if the template recipe or custom config is malformed.
     */
    public RecipeTransformer(ComponentRecipe templateRecipe)
            throws TemplateParameterException {
        templateSchema = initTemplateSchema();
        effectiveDefaultConfig = getAndValidateTemplateComponentConfig(
                templateRecipe.getComponentConfiguration().getDefaultConfiguration());
    }

    /**
     * Workaround to declaring an "abstract" template schema field.
     * @return a JsonNode representing the desired template schema. Can be a node with no fields, representing a pure
     *     substitution template.
     */
    protected abstract JsonNode initTemplateSchema() throws TemplateParameterException;

    /**
     * Stateless expansion for one component.
     * @param parameterFile the parameter file for the component.
     * @return a recipe. See the declaration for {@link #transform(ComponentRecipe, JsonNode) transform}.
     * @throws RecipeTransformerException if the provided parameters violate the template schema.
     */
    public ComponentRecipe execute(ComponentRecipe parameterFile) throws RecipeTransformerException {
        JsonNode effectiveComponentParams = mergeAndValidateComponentParams(parameterFile);
        return transform(parameterFile, effectiveComponentParams);
    }

    /**
     * Transforms the parameter file into a full recipe.
     * @param paramFile the parameter file object.
     * @param componentParams the effective component parameters to use during expansion.
     * @return the expanded recipe.
     * @throws RecipeTransformerException if there is any error with the transformation.
     */
    public abstract ComponentRecipe transform(ComponentRecipe paramFile, JsonNode componentParams)
            throws RecipeTransformerException;

    /**
     * Note the configuration of the template itself. Validates provided defaults.
     * @param defaultConfig the DefaultConfiguration recipe key.
     * @throws TemplateParameterException if the template recipe file or given configuration is malformed.
     */
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    protected JsonNode getAndValidateTemplateComponentConfig(JsonNode defaultConfig) throws TemplateParameterException {
        // validate schema in template matches internal schema, just for good measure
        JsonNode recipeProvidedSchema = defaultConfig.get(TEMPLATE_PARAMETER_SCHEMA_KEY);
        if (recipeProvidedSchema == null && templateSchema.size() != 0) {
            throw new TemplateParameterException("Template recipe did not provide a schema but transformer requires "
                    + "schema:\n" + templateSchema);
        }
        if (!templateSchema.equals(defaultConfig.get(TEMPLATE_PARAMETER_SCHEMA_KEY))) {
            throw new TemplateParameterException("Template recipe provided schema different from template transformer"
                    + " binary. Transformer needs schema:\n" + templateSchema.toString() + "\nTemplate provided "
                    + "schema:\n" + defaultConfig.get(TEMPLATE_PARAMETER_SCHEMA_KEY).toString());
        }

        // validate hard-coded/user-provided "default configs"
        JsonNode defaultNode = defaultConfig.get(TEMPLATE_DEFAULT_PARAMETER_KEY);
        // check both ways
        for (Iterator<String> it = templateSchema.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!defaultNode.has(field)
                    && !(getTitleInsensitive(templateSchema.get(field), TEMPLATE_FIELD_REQUIRED_KEY)).asBoolean()) {
                throw new MissingTemplateParameterException("Template does not provide default for optional "
                        + "parameter: " + field);
            }
            if (getTitleInsensitive(templateSchema.get(field), TEMPLATE_FIELD_REQUIRED_KEY).asBoolean()) {
                ((ObjectNode)defaultNode).remove(field);
                continue;
            }
            JsonNode defaultVal = defaultNode.get(field);
            if (nodeType(templateSchema.get(field).get(TEMPLATE_FIELD_TYPE_KEY).asText()) != defaultVal.getNodeType()) {
                throw new TemplateParameterTypeMismatchException("Template default value does not match schema. "
                        + "Expected " + nodeType(templateSchema.get(field).get(TEMPLATE_FIELD_TYPE_KEY).asText())
                        + " but got " + defaultNode.getNodeType());
            }
        }
        for (Iterator<String> it = defaultNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!templateSchema.has(field)) {
                throw new IllegalTemplateParameterException("Template declared parameter not found in schema: "
                        + field);
            }
        }

        return defaultNode;
    }

    // merges the template-provided default parameters and parameters provided by the parameter file. validates the
    // resulting parameter set satisfies the schema declared by the template.
    // returns the resulting parameter set.
    protected JsonNode mergeAndValidateComponentParams(ComponentRecipe paramFile)
            throws RecipeTransformerException {
        JsonNode paramNode = paramFile.getComponentConfiguration().getDefaultConfiguration();
        JsonNode mergedParams = mergeParams(effectiveDefaultConfig, paramNode).get();
        try {
            validateParams(mergedParams);
        } catch (TemplateParameterException e) {
            throw new RecipeTransformerException("Configuration does not match required schema", e);
        }
        return mergedParams;
    }


    /* Utility functions */

    // checks the provided parameter map satisfies the schema
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    void validateParams(JsonNode params) throws TemplateParameterException {
        // check both ways
        for (Iterator<String> it = templateSchema.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!params.has(field)) { // we validate template defaults, so this can only happen if a required param
                // is not given
                throw new MissingTemplateParameterException("Configuration does not specify required parameter: "
                        + field);
            }
            JsonNodeType paramType = params.get(field).getNodeType();
            if (nodeType(templateSchema.get(field).get(TEMPLATE_FIELD_TYPE_KEY).asText()) != paramType) {
                throw new TemplateParameterTypeMismatchException("Provided parameter does not satisfy template schema. "
                        + "Expected " + nodeType(templateSchema.get(field).get(TEMPLATE_FIELD_TYPE_KEY).asText())
                        + " but got " + paramType);
            }
        }
        for (Iterator<String> it = params.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!templateSchema.has(field)) {
                throw new IllegalTemplateParameterException("Configuration declared parameter not found in schema: "
                        + field);
            }
        }
    }

    // merge the defaultVal parameter map into the customVal parameter map by set addition. if both declare a value
    // for the same parameter, the one in customVal takes precedence.
    protected static Optional<JsonNode> mergeParams(@Nullable JsonNode defaultVal, @Nullable JsonNode customVal) {
        if (defaultVal == null) {
            return Optional.ofNullable(customVal);
        }
        if (customVal == null) {
            return Optional.of(defaultVal);
        }

        JsonNode retval = customVal.deepCopy();
        Iterator<String> fieldNames = defaultVal.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (hasTitleInsensitive(customVal, fieldName)) { // TODO: how do we resolve field capitalization???
                ((ObjectNode)retval).set(fieldName, customVal.get(fieldName)); // non-recursive
            } else {
                ((ObjectNode)retval).set(fieldName, defaultVal.get(fieldName));
            }
        }
        return Optional.of(retval);
    }

    // equivalent to asking for both "fieldName" and "FieldName".
    // If one is declared but the other isn't, return the one declared.
    // If neither are declared, return null.
    // If both are declared, return the one asked for.
    // does no error checking either. good luck!
    protected static JsonNode getTitleInsensitive(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            return node.get(fieldName);
        }
        return node.get(invertCase(fieldName));
    }

    // similar, but for has instead of get
    protected static boolean hasTitleInsensitive(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            return true;
        }
        return node.has(invertCase(fieldName));
    }

    // invert the case of the first character in a string
    protected static String invertCase(String camelCaseString) {
        char inverseCaseFirstChar;
        if (Character.isUpperCase(camelCaseString.charAt(0))) {
            inverseCaseFirstChar = Character.toLowerCase(camelCaseString.charAt(0));
        } else {
            inverseCaseFirstChar = Character.toUpperCase(camelCaseString.charAt(0));
        }
        return inverseCaseFirstChar + camelCaseString.substring(1);
    }

    // Utility to get the node type from a string. Useful when parsing type from JSON.
    protected static JsonNodeType nodeType(String typeString) {
        String lowercased = typeString.toLowerCase();
        switch (lowercased) {
            case STRING_TYPE: return JsonNodeType.STRING;
            case NUMBER_TYPE: return JsonNodeType.NUMBER;
            case OBJECT_TYPE: return JsonNodeType.OBJECT;
            case ARRAY_TYPE: return JsonNodeType.ARRAY;
            case BOOLEAN_TYPE: return JsonNodeType.BOOLEAN;
            case NULL_TYPE: return JsonNodeType.NULL;
            default: return JsonNodeType.MISSING;
        }
    }
}
