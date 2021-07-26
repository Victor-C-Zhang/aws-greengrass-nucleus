/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentConfiguration;
import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateExecutionException;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class TemplateEngineTest extends BaseITCase {
    @Mock
    private ComponentStore mockComponentStore;
    @Mock
    private NucleusPaths mockNucleusPaths;
    @Mock
    private Context mockContext;

    @Test
    void WHEN_a_deployment_contains_multiple_templates_and_param_files_THEN_all_of_them_are_expanded()
            throws PackageLoadingException, TemplateExecutionException, RecipeTransformerException, IOException,
            URISyntaxException {
        JsonNode firstTracker = getRecipeSerializer().createObjectNode().set("tracker1", TextNode.valueOf("tracked1"));
        JsonNode secondTracker = getRecipeSerializer().createObjectNode().set("tracker2", TextNode.valueOf("tracked2"));
        JsonNode thirdTracker = getRecipeSerializer().createObjectNode().set("tracker3", TextNode.valueOf("tracked3"));

        try (MockedConstruction<TransformerWrapper> mocked = mockConstruction(TransformerWrapper.class,
                (mock, context) -> {
                    ComponentRecipe template = (ComponentRecipe) context.arguments().get(1);
                    ComponentIdentifier templateIdentifier = new ComponentIdentifier(template.getComponentName(),
                            template.getComponentVersion());
                    switch (template.getComponentName()) {
                        case "FirstTemplate": {
                            when(mock.expandOne(any())).thenReturn(mockTemplateExpansion(templateIdentifier, firstTracker));
                            break;
                        }
                        case "SecondTemplate": {
                            when(mock.expandOne(any())).thenReturn(mockTemplateExpansion(templateIdentifier, secondTracker));
                            break;
                        }
                        case "ThirdTemplate": {
                            when(mock.expandOne(any())).thenReturn(mockTemplateExpansion(templateIdentifier, thirdTracker));
                            break;
                        }
                        default: {
                            fail();
                            break;
                        }
                    }
                })) {
            // validate different templates transform differently
            Answer<Void> ans = invocation -> {
                Object[] args = invocation.getArguments();
                ComponentIdentifier identifier = (ComponentIdentifier) args[0];
                ComponentRecipe generatedRecipe = getRecipeSerializer().readValue((String) args[1], ComponentRecipe.class);
                if (identifier.getName().contains("First")) {
                    assertEquals(generatedRecipe.getComponentConfiguration().getDefaultConfiguration(), firstTracker);
                } else if (identifier.getName().contains("Second")) {
                    assertEquals(generatedRecipe.getComponentConfiguration().getDefaultConfiguration(), secondTracker);
                } else if (identifier.getName().contains("Third")) {
                    assertEquals(generatedRecipe.getComponentConfiguration().getDefaultConfiguration(), thirdTracker);
                } else {
                    fail();
                }
                return null;
            };
            doAnswer(ans).when(mockComponentStore).savePackageRecipe(any(), anyString());
            TemplateEngine templateEngine = new TemplateEngine(mockComponentStore, mockNucleusPaths, mockContext);
            Path rootDir = Paths.get(getClass().getResource("multiple_templates_and_parameter_files").toURI());
            Path recipeDir = rootDir.resolve("recipes");
            Path artifactsDir = rootDir.resolve("artifacts");

            templateEngine.process(recipeDir, artifactsDir);
        }
    }

    ComponentRecipe mockTemplateExpansion(ComponentIdentifier identifier, JsonNode trackerNode) {
        return ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName(identifier.getName())
                .componentVersion(identifier.getVersion())
                .componentConfiguration(ComponentConfiguration.builder().defaultConfiguration(trackerNode).build())
                .build();
    }

    @Test
    void WHEN_a_deployment_contains_non_templated_components_THEN_nothing_happens_to_them()
            throws PackageLoadingException, TemplateExecutionException, RecipeTransformerException, IOException,
            URISyntaxException {
        ComponentRecipe mockedReturn = ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("Expanded")
                .componentVersion(new Semver("1.0.0"))
                .build();
        try (MockedConstruction<TransformerWrapper> mocked = mockConstruction(TransformerWrapper.class,
                (mock, context) -> when(mock.expandOne(any())).thenReturn(mockedReturn))) {
            // validate only parameter files are transformed
            Answer<Void> ans = invocation -> {
                Object[] args = invocation.getArguments();
                ComponentIdentifier identifier = (ComponentIdentifier) args[0];
                if ("RegularRecipe".equals(identifier.getName())) {
                    fail("A new version of a non-templated component was saved to component store");
                }
                return null;
            };
            doAnswer(ans).when(mockComponentStore).savePackageRecipe(any(), anyString());
            TemplateEngine templateEngine = new TemplateEngine(mockComponentStore, mockNucleusPaths, mockContext);
            Path rootDir = Paths.get(getClass().getResource("multiple_templates_and_parameter_files").toURI());
            Path recipeDir = rootDir.resolve("recipes");
            Path artifactsDir = rootDir.resolve("artifacts");

            assertTrue(Files.exists(recipeDir.resolve("RegularRecipe-1.0.0.yaml")));

            templateEngine.process(recipeDir, artifactsDir);
        }
    }

    @Test
    void WHEN_provided_with_bad_load_dependencies_THEN_throw_error() throws URISyntaxException{
        // multiple dependency
        TemplateEngine templateEngine = new TemplateEngine(mockComponentStore, mockNucleusPaths, mockContext);
        Path rootDir = Paths.get(getClass().getResource("multiple_dependency").toURI());
        Path multipleDepRecipeDir = rootDir.resolve("recipes");
        Path multipleDepArtifactsDir = rootDir.resolve("artifacts");
        MultipleTemplateDependencyException ex = assertThrows(MultipleTemplateDependencyException.class,
                () -> templateEngine.process(multipleDepRecipeDir, multipleDepArtifactsDir));
        assertThat(ex.getMessage(), containsString("has multiple template dependencies"));

        // templates depending on templates
        rootDir = Paths.get(getClass().getResource("template_with_dependency").toURI());
        Path templateDepRecipeDir = rootDir.resolve("recipes");
        Path templateDepArtifactsDir = rootDir.resolve("artifacts");
        IllegalTemplateDependencyException ex2 = assertThrows(IllegalTemplateDependencyException.class,
                () -> templateEngine.process(templateDepRecipeDir, templateDepArtifactsDir));
        assertThat(ex2.getMessage(), containsString("Illegal dependency for template"));
    }

    @Test
    void GIVEN_provided_templates_IF_templates_have_lifecycle_THEN_throw_exception() throws URISyntaxException {
        TemplateEngine templateEngine = new TemplateEngine(mockComponentStore, mockNucleusPaths, mockContext);
        Path rootDir = Paths.get(getClass().getResource("template_with_lifecycle").toURI());
        Path artifactsDir = rootDir.resolve("artifacts");

        // manifests::lifecycle
        Path manifestRecipeDir = rootDir.resolve("recipes_manifests");
        RecipeTransformerException ex = assertThrows(RecipeTransformerException.class,
                () -> templateEngine.process(manifestRecipeDir, artifactsDir));
        assertThat(ex.getMessage(), containsString("Templates cannot have non-empty lifecycle"));

        // lifecycle
        Path lifecycleRecipeDir = rootDir.resolve("recipes_lifecycle");
        RecipeTransformerException ex2 = assertThrows(RecipeTransformerException.class,
                () -> templateEngine.process(lifecycleRecipeDir, artifactsDir));
        assertThat(ex2.getMessage(), containsString("Templates cannot have non-empty lifecycle"));
    }

    @Test
    void GIVEN_param_files_loaded_IF_desired_template_doesnt_exist_THEN_throw_package_loading_exception()
            throws URISyntaxException {
        TemplateEngine templateEngine = new TemplateEngine(mockComponentStore, mockNucleusPaths, mockContext);
        Path rootDir = Paths.get(getClass().getResource("desired_template_doesnt_exist").toURI());
        Path artifactsDir = rootDir.resolve("artifacts");

        // version is incompatible
        Path incompatibleRecipeDir = rootDir.resolve("recipes_incompatible");
        IllegalTemplateDependencyException ex = assertThrows(IllegalTemplateDependencyException.class,
                () -> templateEngine.process(incompatibleRecipeDir, artifactsDir));
        assertThat(ex.getMessage(), containsString("can't be found locally. Requirement is"));

        // template is missing
        Path missingRecipeDir = rootDir.resolve("recipes_missing");
        IllegalTemplateDependencyException ex2 = assertThrows(IllegalTemplateDependencyException.class,
                () -> templateEngine.process(missingRecipeDir, artifactsDir));
        assertThat(ex2.getMessage(), containsString("can't be found locally. Requirement is"));
    }
}
