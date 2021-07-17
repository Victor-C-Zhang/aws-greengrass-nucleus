/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.MissingTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterTypeMismatchException;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class RecipeTransformerTest {
    private RecipeTransformer recipeTransformer;

    static String TEMPLATE_SCHEMA = "stringParam:\n" + "  type: string\n" + "  required: true\n" + "booleanParam:\n"
            + "  type: boolean\n" + "  required: true\n" + "numberParam:\n" + "  type: number\n" + "  required: false\n"
            + "objectParam:\n" + "  type: object\n" + "  required: false\n" + "arrayParam:\n" + "  type: array\n"
            + "  required: false\n";
    static String VALID_INPUT_DEFAULT_PARAMS = "stringParam: unnecessary string\n" + "numberParam: 42069\n"
            + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: subval2\n" + "    subkey2: subval2\n"
            + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";
    static String VALID_EFFECTIVE_DEFAULT_PARAMS = "numberParam: 42069\n" + "objectParam:\n" + "  key1: val1\n"
            + "  key2:\n" + "    subkey1: subval2\n" + "    subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n"
            + "  - 2\n" + "  - red\n" + "  - blue";

    @Test
    void IF_template_config_is_acceptable_THEN_it_works() throws IOException, TemplateParameterException {
        // no KVs generated for non-optional fields
        recipeTransformer = new FakeRecipeTransformer(getRecipeResource("fakeTransformerTemplate.yaml"));

        JsonNode effectiveDefaultParams = getRecipeSerializer().readTree(VALID_EFFECTIVE_DEFAULT_PARAMS);
        assertEquals(effectiveDefaultParams, recipeTransformer.getEffectiveDefaultConfig());
    }

    @Test
    void IF_template_config_has_invalid_schema_THEN_throw_error() {
        // schema is missing
        assertThrows(TemplateParameterException.class, () -> new FakeRecipeTransformer(getRecipeResource(
                "missingFakeTransformerTemplate.yaml")));

        // schema is different
        assertThrows(TemplateParameterException.class, () -> new FakeRecipeTransformer(getRecipeResource(
                "differentFakeTransformerTemplate.yaml")));
    }

    @Test
    void IF_template_config_default_values_are_invalid_THEN_throw_error() {
        // missing value for optional parameter
        assertThrows(MissingTemplateParameterException.class, () -> new FakeRecipeTransformer(getRecipeResource(
                "missingOptionalFakeTransformerTemplate.yaml")));

        // value doesn't match schema
        assertThrows(TemplateParameterTypeMismatchException.class, () -> new FakeRecipeTransformer(getRecipeResource(
                "schemaMismatchFakeTransformerTemplate.yaml")));

        // extra values in template file
        assertThrows(IllegalTemplateParameterException.class, () -> new FakeRecipeTransformer(getRecipeResource(
                "extraParamFakeTransformerTemplate.yaml")));
    }

    @Test
    void GIVEN_template_config_read_in_WHEN_provided_invalid_parameters_THEN_throw_error()
            throws IOException, TemplateParameterException {
        recipeTransformer = new FakeRecipeTransformer(getRecipeResource("fakeTransformerTemplate.yaml"));

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
        recipeTransformer = new FakeRecipeTransformer(getRecipeResource("fakeTransformerTemplate.yaml"));
        String goodConfig = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068";
        ComponentRecipe goodRecipe = getParameterFileWithParams(goodConfig);
        JsonNode actual = extractDefaultConfig(recipeTransformer.execute(goodRecipe).getLeft());

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
                .componentConfiguration(null)
                .build();
        RecipeTransformer recipeTransformer = new FakeRecipeTransformer(getRecipeResource("fakeTransformerTemplate"
                + ".yaml"));
        // TODO: break out schema violations into a separate exception from RecipeTransformerException
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(nullConfig));

        // default has value that custom does not, custom has value that default does not
        String missingOptional = "stringParam: a string\n" + "booleanParam: true";
        ComponentRecipe missingOptionalRecipe = getParameterFileWithParams(missingOptional);
        JsonNode actual = extractDefaultConfig(recipeTransformer.execute(missingOptionalRecipe).getLeft());
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
        actual = extractDefaultConfig(recipeTransformer.execute(differentValueRecipe).getLeft());
        expected = getRecipeSerializer().readTree(differentValue);
        assertEquals(expected, actual);

        // default and custom have different capitalizations for the same field
        String differentCap = "stringParam: a string\n" + "booleanParam: true\n" + "NumberParam: 42068\n"
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    Subkey1: newSubval2\n"
                + "    Subkey2: subval2\n" + "arrayParam:\n" + "  - 1\n" + "  - 2\n" + "  - red\n" + "  - blue";
        ComponentRecipe differentCapRecipe = getParameterFileWithParams(differentCap);
        actual = extractDefaultConfig(recipeTransformer.execute(differentCapRecipe).getLeft());
        expected = getRecipeSerializer().readTree(differentCap);
        assertEquals(expected, actual);
    }

    ComponentRecipe getRecipeResource(String fileName) throws IOException {
        return getRecipeSerializer().readValue(getClass().getResource(fileName), ComponentRecipe.class);
    }

    // parameter file with minimal boilerplate
    ComponentRecipe getParameterFileWithParams(String params) throws JsonProcessingException {
        JsonNode paramObj = getRecipeSerializer().readTree(params);
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("A")
                .componentVersion(new Semver("1.0.0"))
                .componentConfiguration(new ComponentConfiguration(paramObj))
                .build();
    }

    JsonNode extractDefaultConfig(ComponentRecipe wrapperRecipe) {
        return wrapperRecipe.getComponentConfiguration().getDefaultConfiguration();
    }

    static class FakeRecipeTransformer extends RecipeTransformer {
        public FakeRecipeTransformer(ComponentRecipe templateRecipe) throws TemplateParameterException {
            super(templateRecipe);
        }

        @Override
        protected JsonNode initTemplateSchema() throws TemplateParameterException {
            try {
                return getRecipeSerializer().readTree(TEMPLATE_SCHEMA);
            } catch (JsonProcessingException e) {
                throw new TemplateParameterException(e);
            }
        }

        @Override
        public Pair<ComponentRecipe, List<Path>> transform(ComponentRecipe paramFile, JsonNode componentParams)
                throws RecipeTransformerException {
            // just wrap the componentParams for checking
            ComponentRecipe wrapperRecipe = ComponentRecipe.builder().componentConfiguration(
                    ComponentConfiguration.builder().defaultConfiguration(componentParams).build()).build();
            return new Pair<>(wrapperRecipe, null);
        }
    }
}
