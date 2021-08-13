/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.Platform;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.amazon.aws.iot.greengrass.component.common.TemplateParameter;
import com.amazon.aws.iot.greengrass.component.common.TemplateParameterSchema;
import com.amazon.aws.iot.greengrass.component.common.TemplateParameterType;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
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
        defaultValue: 42069
      objectParam:
        type: object
        required: false
        defaultValue:
          key1: val1
          key2:
            subkey1: subval2
            subkey2: subval2
      arrayParam:
        type: array
        required: false
        defaultValue:
          - 1
          - 2
          - red
          - blue
     */
    static String TEMPLATE_SCHEMA = "stringParam:\n" + "  type: string\n" + "  required: true\n" + "booleanParam:\n"
            + "  type: boolean\n" + "  required: true\n" + "numberParam:\n" + "  type: number\n" + "  required: false\n"
            + "  defaultValue: 42069\n" + "objectParam:\n" + "  type: object\n" + "  required: false\n"
            + "  defaultValue:\n" + "    key1: val1\n" + "    key2:\n" + "      subkey1: subval2\n"
            + "      subkey2: subval2\n" + "arrayParam:\n" + "  type: array\n" + "  required: false\n"
            + "  defaultValue:\n" + "    - 1\n" + "    - 2\n" + "    - red\n" + "    - blue";

    /*
      stringParam:
        type: string
        required: true
      booleanParam:
        type: boolean
        required: true
        defaultValue: false # should not have default value for required param
      numberParam:
        type: number
        required: false # should have default value
      objectParam:
        type: object
        required: false
        defaultValue: 12 # mismatch with required type
      arrayParam:
        type: array
        required: false
        defaultValue:
          - 1
          - 2
          - red
          - blue
     */
    static String BAD_TEMPLATE_SCHEMA = "stringParam:\n" + "  type: string\n" + "  required: true\n" + "booleanParam:\n"
            + "  type: boolean\n" + "  required: true\n" + "  defaultValue: false\n" + "numberParam:\n"
            + "  type: number\n" + "  required: false\n" + "objectParam:\n" + "  type: object\n" + "  required: false\n"
            + "  defaultValue: 12\n" + "arrayParam:\n" + "  type: array\n" + "  required: false\n" + "  defaultValue:\n"
            + "    - 1\n" + "    - 2\n" + "    - red\n" + "    - blue";

    static TemplateParameterSchema defaultTemplateSchema;

    @BeforeAll
    static void beforeAll() throws JsonProcessingException {
        defaultTemplateSchema = getRecipeSerializer().readValue(TEMPLATE_SCHEMA, TemplateParameterSchema.class);
    }

    @Test
    void IF_transformer_config_has_invalid_schema_THEN_throw_error() {
        recipeTransformer = new BadRecipeTransformer();
        final TemplateParameterException ex = assertThrows(TemplateParameterException.class,
                () -> recipeTransformer.initTemplateRecipe(null));

        // default value for required field
        assertThat(ex.getMessage(), containsString("Provided default value for required field: booleanParam"));
        // no default for optional field
        assertThat(ex.getMessage(), containsString("Did not provide default value for optional field: numberParam"));
        // different type than required
        assertThat(ex.getMessage(), containsString("Template value for \"objectParam\" does not match schema"));
    }

    @Test
    void IF_both_transformer_and_template_recipe_provide_acceptable_schema_THEN_it_works() throws TemplateParameterException {
        recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema));
    }

    @Test
    void GIVEN_empty_schema_WHEN_transform_called_THEN_it_works()
            throws TemplateParameterException, RecipeTransformerException {
        ComponentRecipe emptyTemplate = ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("EmptyTemplate")
                .componentVersion(new Semver("1.0.0"))
                .componentType(ComponentType.TEMPLATE)
                .build();
        ComponentRecipe paramFile = ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("Random")
                .componentVersion(new Semver("0.1.0"))
                .build();

        recipeTransformer = new EmptyRecipeTransformer();
        recipeTransformer.initTemplateRecipe(emptyTemplate);

        ComponentRecipe generated = recipeTransformer.execute(paramFile);

        assertEquals(generated.getComponentName(), "A");
        assertEquals(generated.getComponentVersion(), new Semver("1.0.0"));
    }

    @Test
    void IF_template_recipe_provides_invalid_schema_THEN_throw_error() {
        TemplateParameterSchema badSchema = new TemplateParameterSchema(defaultTemplateSchema);
        badSchema.get("stringParam").setRequired(false);
        badSchema.remove("arrayParam");
        badSchema.put("extraParam",
                TemplateParameter.builder().type(TemplateParameterType.STRING).defaultValue("uh oh!").build());
        badSchema.get("numberParam").setDefaultValue(false);
        final TemplateParameterException exception = assertThrows(TemplateParameterException.class, () ->
                new FakeRecipeTransformer().initTemplateRecipe(getTemplate(badSchema)));

        // schema value is different
        assertThat(exception.getMessage(), containsString("Template value for \"stringParam\" does not match schema"));
        // missing value
        assertThat(exception.getMessage(), containsString("Missing parameter: arrayParam"));
        // extra value
        assertThat(exception.getMessage(), containsString("Template declared parameter not found in schema: "
                + "extraParam"));
    }

    @Test
    void GIVEN_template_recipe_schema_read_in_WHEN_provided_invalid_parameters_THEN_throw_error()
            throws IOException, TemplateParameterException {
        recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema));

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

        // parameter with different capitalization
        String wrongCap = "stringParam: a string\n" + "booleanParam: haha im a string\n" + "NumberParam: 42068\n";
        ComponentRecipe wrongCapParam = getParameterFileWithParams(wrongCap);
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(wrongCapParam));
    }

    @Test
    void GIVEN_template_config_read_in_WHEN_provided_component_configs_THEN_they_are_merged()
            throws IOException, TemplateParameterException, RecipeTransformerException {
        recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema));
        String goodConfig = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068";
        ComponentRecipe goodRecipe = getParameterFileWithParams(goodConfig);
        JsonNode actual = extractDefaultConfig(recipeTransformer.execute(goodRecipe));

        String expectedString = "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068\n"
                + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: subval2\n"
                + "    subkey2: subval2\n" + "arrayParam:\n" + "  - '1'\n" + "  - '2'\n" + "  - red\n" + "  - blue\n";
        JsonNode expected = getRecipeSerializer().readTree(expectedString);
        assertEquals(expected, actual);
    }

    @Test
    void WHEN_mergeParam_is_called_THEN_it_works()
            throws IOException, TemplateParameterException, RecipeTransformerException {
        // nullity of config
        ComponentRecipe nullConfig =
                ComponentRecipe.builder().recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).componentName("A").componentVersion(new Semver("1.0.0"))
                        //                .componentConfiguration(null)
                        .build();
        RecipeTransformer recipeTransformer = new FakeRecipeTransformer();
        recipeTransformer.initTemplateRecipe(getTemplate(defaultTemplateSchema));
        // TODO: break out schema violations into a separate exception from RecipeTransformerException
        assertThrows(RecipeTransformerException.class, () -> recipeTransformer.execute(nullConfig));

        // default has value that custom does not, custom has value that default does not
        String missingOptional = "stringParam: a string\n" + "booleanParam: true";
        ComponentRecipe missingOptionalRecipe = getParameterFileWithParams(missingOptional);
        JsonNode actual = extractDefaultConfig(recipeTransformer.execute(missingOptionalRecipe));
        String expectedString =
                "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42069\n" + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: subval2\n"
                        + "    subkey2: subval2\n" + "arrayParam:\n" + "  - '1'\n" + "  - '2'\n" + "  - red\n" + "  - blue";
        JsonNode expected = getRecipeSerializer().readTree(expectedString);
        assertEquals(expected, actual);

        // default and custom both have a value
        String differentValue =
                "stringParam: a string\n" + "booleanParam: true\n" + "numberParam: 42068\n" + "objectParam:\n" + "  key1: val1\n" + "  key2:\n" + "    subkey1: newSubval2\n"
                        + "    subkey2: subval2\n" + "arrayParam:\n" + "  - '1'\n" + "  - '2'\n" + "  - red\n" + "  - blue";
        ComponentRecipe differentValueRecipe = getParameterFileWithParams(differentValue);
        actual = extractDefaultConfig(recipeTransformer.execute(differentValueRecipe));
        expected = getRecipeSerializer().readTree(differentValue);
        assertEquals(expected, actual);
    }

    ComponentRecipe getTemplate(TemplateParameterSchema templateParameterMap) {
        PlatformSpecificManifest manifest =
                PlatformSpecificManifest.builder().platform(Platform.builder().os(Platform.OS.ALL).build()).build();
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("FakeTransformerTemplate")
                .componentVersion(new Semver("1.0.0"))
                .componentType(ComponentType.TEMPLATE)
                .templateParameterSchema(templateParameterMap)
                .manifests(Collections.singletonList(manifest))
                .build();
    }

    // parameter file with minimal boilerplate
    ComponentRecipe getParameterFileWithParams(String params) throws JsonProcessingException {
        TemplateParameters paramObj = getRecipeSerializer().readValue(params, TemplateParameters.class);
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("A")
                .componentVersion(new Semver("1.0.0"))
                .templateParameters(paramObj)
                .build();
    }

    JsonNode extractDefaultConfig(ComponentRecipe wrapperRecipe) {
        return wrapperRecipe.getComponentConfiguration().getDefaultConfiguration();
    }

    private static class FakeRecipeTransformer extends RecipeTransformer {
        private final ObjectMapper noCapMapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        @Override
        protected String initTemplateSchema() {
            return TEMPLATE_SCHEMA;
        }

        @Override
        protected Class<?> initRecievingClass() {
            return TemplateParamShape.class;
        }

        @Override
        public ComponentRecipe transform(ComponentRecipe paramFile, Object componentParams)
                throws RecipeTransformerException {
            // just wrap the componentParams for checking
            JsonNode stringifiedParams;
            try {
                stringifiedParams =
                        getRecipeSerializer().readTree(noCapMapper.writeValueAsString(componentParams));
            } catch (JsonProcessingException e) {
                throw new RecipeTransformerException(e);
            }

            return ComponentRecipe.builder()
                    .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                    .componentName("A")
                    .componentVersion(new Semver("1.0.0"))
                    .componentConfiguration(ComponentConfiguration.builder().defaultConfiguration(stringifiedParams).build())
                    .build();
        }
    }

    private static class BadRecipeTransformer extends RecipeTransformer {

        @Override
        protected String initTemplateSchema() {
            return BAD_TEMPLATE_SCHEMA;
        }

        @Override
        protected Class<?> initRecievingClass() {
            return TemplateParamShape.class;
        }

        @Override
        public ComponentRecipe transform(ComponentRecipe paramFile, Object componentParamsObj) {
            return null;
        }
    }

    private static class EmptyRecipeTransformer extends RecipeTransformer {

        @Override
        protected String initTemplateSchema() {
            return "{ }";
        }

        @Override
        protected Class<?> initRecievingClass() {
            return Object.class;
        }

        @Override
        public ComponentRecipe transform(ComponentRecipe paramFile, Object componentParamsObj) {
            return ComponentRecipe.builder()
                    .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                    .componentName("A")
                    .componentVersion(new Semver("1.0.0"))
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TemplateParamShape {
        String stringParam;
        Boolean booleanParam;
        Integer numberParam;
        CustomObject objectParam;
        List<String> arrayParam;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class CustomObject {
        String key1;
        CustomInnerObject key2;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class CustomInnerObject {
        String subkey1;
        String subkey2;
    }

    private static class TemplateParameters extends HashMap<String, Object> {
        private static final long serialVersionUID = 8385439320479083191L;

        public TemplateParameters() {
            super();
        }
    }
}
