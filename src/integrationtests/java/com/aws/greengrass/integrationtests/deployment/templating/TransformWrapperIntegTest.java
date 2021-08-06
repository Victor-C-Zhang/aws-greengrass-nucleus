/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.templating.TransformerWrapper;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TransformWrapperIntegTest extends BaseITCase {
    private Context context;
    private ComponentRecipe templateRecipe;

    @BeforeEach
    void beforeEach() {
        context = new Context();
        ExecutorService executorService = Executors.newCachedThreadPool();
        context.put(ExecutorService.class, executorService);
        templateRecipe = ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName("A")
                .componentVersion(new Semver("1.0.0"))
                .build();
    }

    @AfterEach
    void afterEach() {
        if (context != null) {
            context.shutdown();
        }
    }

    @Test
    void GIVEN_jar_with_no_transformer_WHEN_try_instantiation_THEN_throw_transformer_exception()
            throws URISyntaxException {
        Path noImplementedTransformer =
                Paths.get(getClass().getResource("no-implemented-transformer-tests.jar").toURI());
        RecipeTransformerException ex = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(noImplementedTransformer, templateRecipe, context));
        assertThat(ex.getMessage(), containsString("Could not find a candidate transformer class for template"));
    }

    @Test
    void GIVEN_jar_with_more_than_one_transformer_WHEN_try_instantiation_THEN_throw_exception()
            throws URISyntaxException {
        Path multipleTransformer = Paths.get(getClass().getResource("multiple-transformer-tests.jar").toURI());
        RecipeTransformerException ex2 = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(multipleTransformer, templateRecipe, context));
        assertThat(ex2.getMessage(), containsString("Found more than one candidate transformer class"));
    }
    @Test
    void GIVEN_jar_with_faulty_transformer_WHEN_try_instantiation_THEN_throw_exception()
            throws URISyntaxException {
        // something goes wrong with transformer init
        Path errorTransformer = Paths.get(getClass().getResource("error-transformer-tests.jar").toURI());
        RecipeTransformerException ex3 = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(errorTransformer, templateRecipe, context));
        assertThat(ex3.getMessage(), containsString("Could not instantiate the transformer"));
    }
}
