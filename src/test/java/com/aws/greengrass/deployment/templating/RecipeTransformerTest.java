/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.Platform;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.MissingTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterTypeMismatchException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.deployment.templating.RecipeTransformer.TEMPLATE_DEFAULT_PARAMETER_KEY;
import static com.aws.greengrass.deployment.templating.RecipeTransformer.TEMPLATE_PARAMETER_SCHEMA_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RecipeTransformerTest {
    private RecipeTransformer recipeTransformer;

    /*
      stringParam:
        type: string
        required: true
      booleanParam:
        type: boolean
        required: true
      numberParam:
        type: number
        required: false
      objectParam:
        type: object
        required: false
      arrayParam:
        type: array
        required: false
     */
    static String TEMPLATE_SCHEMA = "stringParam:\n" + "  type: string\n" + "  required: true\n" + "booleanParam:\n"
            + "  type: boolean\n" + "  required: true\n" + "numberParam:\n" + "  type: number\n" + "  required: false\n"
            + "objectParam:\n" + "  type: object\n" + "  required: false\n" + "arrayParam:\n" + "  type: array\n"
            + "  required: false\n";

    /*
      stringParam: unnecessary string
      numberParam: 42069
      objectParam:
        key1: val1
        key2:
          subkey1: subval2
          subkey2: subval2
      arrayParam:
        - 1
        - 2
        - red
        - blue
     */
    static String TEMPLATE_DEFAULT_PARAMS = "stringParam: unnecessary string\n" + "numberParam: 42069\n"
            + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: subval2\n" + "    subkey2: subval2\n"
            + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";

    static String VALID_EFFECTIVE_DEFAULT_PARAMS = "numberParam: 42069\n" + "objectParam:\n" + "  key1: val1\n"
            + "  key2:\n" + "    subkey1: subval2\n" + "    subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n"
            + "  - 2\n" + "  - red\n" + "  - blue";

    static JsonNode defaultTemplateSchema;
    static JsonNode defaultTemplateDefaultParams;

    @BeforeAll
    static void beforeAll() throws JsonProcessingException {
        defaultTemplateSchema = getRecipeSerializer().readTree(TEMPLATE_SCHEMA);
        defaultTemplateDefaultParams = getRecipeSerializer().readTree(TEMPLATE_DEFAULT_PARAMS);
    }

    @Test
    void IF_template_config_is_acceptable_THEN_it_works() throws IOException, TemplateParameterException {
        // no KVs generated for non-optional fields
        recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema, defaultTemplateDefaultParams));

        JsonNode effectiveDefaultParams = getRecipeSerializer().readTree(VALID_EFFECTIVE_DEFAULT_PARAMS);
        assertEquals(effectiveDefaultParams, recipeTransformer.getEffectiveDefaultConfig());
    }

    @Test
    void IF_template_config_has_invalid_schema_THEN_throw_error() throws JsonProcessingException {
        // schema is missing
        assertThrows(TemplateParameterException.class, () ->
                new FakeRecipeTransformer().initTemplateRecipe(getTemplate(null, defaultTemplateDefaultParams)));

        // schema is different
        ObjectNode stringParamNotRequired = defaultTemplateSchema.deepCopy();
        stringParamNotRequired.replace("stringParam",
                getRecipeSerializer().readTree("type: string\nrequired: false"));
        assertThrows(TemplateParameterException.class, () ->
                new FakeRecipeTransformer()
                        .initTemplateRecipe(getTemplate(stringParamNotRequired, defaultTemplateDefaultParams)));
    }

    @Test
    void IF_template_config_default_values_are_invalid_THEN_throw_error() {
        // missing value for optional parameter
        ObjectNode missingArrayDefault = defaultTemplateDefaultParams.deepCopy();
        missingArrayDefault.remove("arrayParam");
        assertThrows(MissingTemplateParameterException.class, () ->
                new FakeRecipeTransformer().initTemplateRecipe(getTemplate(defaultTemplateSchema, missingArrayDefault)));

        // value doesn't match schema
        ObjectNode arrayisBoolean = defaultTemplateDefaultParams.deepCopy();
        arrayisBoolean.replace("arrayParam", BooleanNode.valueOf(false));
        assertThrows(TemplateParameterTypeMismatchException.class, () ->
                new FakeRecipeTransformer().initTemplateRecipe(getTemplate(defaultTemplateSchema, arrayisBoolean)));

        // extra values in template file
        ObjectNode extraParam = defaultTemplateDefaultParams.deepCopy();
        extraParam.set("extraParam", new TextNode("uh oh!"));
        assertThrows(IllegalTemplateParameterException.class, () ->
                new FakeRecipeTransformer().initTemplateRecipe(getTemplate(defaultTemplateSchema, extraParam)));
    }

    @Test
    void GIVEN_template_config_read_in_WHEN_provided_invalid_parameters_THEN_throw_error()
            throws IOException, TemplateParameterException {
        recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema, defaultTemplateDefaultParams));

        // missing parameter
        String missingBoolean = "stringParam: a string\n" + "numberParam: 42068";
        ComponentRecipe missingParam = getParameterFileWithParams(missingBoolean);
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(missingParam));

        // value doesn't match schema
        String badParam = "stringParam: a string\n" + "booleanParam: haha im a string\n" + "numberParam: 42068";
        ComponentRecipe badParamParam = getParameterFileWithParams(badParam);
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(badParamParam));

        // extra parameter
        String extraNumber = "stringParam: a string\n" + "booleanParam: haha im a string\n" + "numberParam: 42068\n" +
                        "extraNumberParam: 42069";
        ComponentRecipe extraParam = getParameterFileWithParams(extraNumber);
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(extraParam));
    }

    @Test
    void GIVEN_template_config_read_in_WHEN_provided_component_configs_THEN_they_are_merged()
            throws IOException, TemplateParameterException, RecipeTransformerException {
        recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema, defaultTemplateDefaultParams));
        String goodConfig = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068";
        ComponentRecipe goodRecipe = getParameterFileWithParams(goodConfig);
        JsonNode actual = extractDefaultConfig(recipeTransformer.execute(goodRecipe));

        String expectedString = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068\n"
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: subval2\n"
                + "    subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue\n";
        JsonNode expected = getRecipeSerializer().readTree(expectedString);
        assertEquals(expected, actual);
    }

    @Test
    void WHEN_mergeParam_is_called_THEN_it_works()
            throws IOException, TemplateParameterException, RecipeTransformerException {
        // nullity of config
        ComponentRecipe nullConfig = ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("A")
                .componentVersion(new Semver("1.0.0"))
//                .componentConfiguration(null)
                .build();
        RecipeTransformer recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema, defaultTemplateDefaultParams));
        // TODO: break out schema violations into a separate exception from RecipeTransformerException
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(nullConfig));

        // default has value that custom does not, custom has value that default does not
        String missingOptional = "stringParam: a string\n" + "booleanParam: true";
        ComponentRecipe missingOptionalRecipe = getParameterFileWithParams(missingOptional);
        JsonNode actual = extractDefaultConfig(recipeTransformer.execute(missingOptionalRecipe));
        String expectedString = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42069\n"
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: subval2\n"
                + "    subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";
        JsonNode expected = getRecipeSerializer().readTree(expectedString);
        assertEquals(expected, actual);

        // default and custom both have a value
        String differentValue = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068\n"
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: newSubval2\n"
                + "    subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";
        ComponentRecipe differentValueRecipe = getParameterFileWithParams(differentValue);
        actual = extractDefaultConfig(recipeTransformer.execute(differentValueRecipe));
        expected = getRecipeSerializer().readTree(differentValue);
        assertEquals(expected, actual);

        // default and custom have different capitalizations for the same field
        String differentCap = "stringParam: a string\n" + "booleanParam: true\n" + "NumberParam: 42068\n"
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    Subkey1: newSubval2\n"
                + "    Subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";
        ComponentRecipe differentCapRecipe = getParameterFileWithParams(differentCap);
        actual = extractDefaultConfig(recipeTransformer.execute(differentCapRecipe));
        expectedString = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068\n" // numberParam diff
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    Subkey1: newSubval2\n"
                + "    Subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";
        expected = getRecipeSerializer().readTree(expectedString);
        assertEquals(expected, actual);
    }

    ComponentRecipe getTemplate(JsonNode schema, JsonNode parameters) {
        ObjectNode defaultConfig = getRecipeSerializer().createObjectNode();
        if (schema != null) {
            defaultConfig.set(TEMPLATE_PARAMETER_SCHEMA_KEY, schema);
        }
        if (parameters != null) {
            defaultConfig.set(TEMPLATE_DEFAULT_PARAMETER_KEY, parameters);
        }
        PlatformSpecificManifest manifest =
                PlatformSpecificManifest.builder().platform(Platform.builder().os(Platform.OS.ALL).build()).build();
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("FakeTransformerTemplate")
                .componentVersion(new Semver("1.0.0"))
                .componentConfiguration(ComponentConfiguration.builder().defaultConfiguration(defaultConfig).build())
                .manifests(Collections.singletonList(manifest))
                .build();
    }

    // parameter file with minimal boilerplate
    ComponentRecipe getParameterFileWithParams(String params) throws JsonProcessingException {
        JsonNode paramObj = getRecipeSerializer().readTree(params);
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("A")
                .componentVersion(new Semver("1.0.0"))
                .componentConfiguration(ComponentConfiguration.builder().defaultConfiguration(paramObj).build())
                .build();
    }

    JsonNode extractDefaultConfig(ComponentRecipe wrapperRecipe) {
        return wrapperRecipe.getComponentConfiguration().getDefaultConfiguration();
    }

    private static class FakeRecipeTransformer extends RecipeTransformer {
        @Override
        protected JsonNode initTemplateSchema() throws TemplateParameterException {
            try {
                return getRecipeSerializer().readTree(TEMPLATE_SCHEMA);
            } catch (JsonProcessingException e) {
                throw new TemplateParameterException(e);
            }
        }

        @Override
        public ComponentRecipe transform(ComponentRecipe paramFile, JsonNode componentParams)
                throws RecipeTransformerException {
            // just wrap the componentParams for checking
            return ComponentRecipe.builder()
                    .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                    .componentName("A")
                    .componentVersion(new Semver("1.0.0"))
                    .componentConfiguration(ComponentConfiguration.builder().defaultConfiguration(componentParams).build())
                    .build();
        }
    }
}
