/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TransformWrapperTest {
    @Mock
    Context mockContext;
    @Mock
    ComponentRecipe mockComponentRecipe;

    static ExecutorService executorService;

    @BeforeAll
    static void beforeAll() {
        executorService = Executors.newCachedThreadPool();
    }

    @AfterAll
    static void afterAll() {
        executorService.shutdown();
    }

    @Test
    void WHEN_given_bad_path_THEN_throws_error() throws URISyntaxException {
        Path goodPath = Paths.get(getClass().getResource("no-implemented-transformer.jar").toURI());
        Path badPath = goodPath.resolveSibling("nonexistent_file.txt");
        RecipeTransformerException ex = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(badPath, mockComponentRecipe, mockContext));
        assertThat(ex.getMessage(), containsString("Could not find template parsing jar to execute"));
    }

    @Test
    void WHEN_given_bad_jar_THEN_throws_error() throws URISyntaxException {
        when(mockContext.get(EZPlugins.class)).thenReturn(new EZPlugins(executorService));

        // no transformer class found
        Path noImplemented = Paths.get(getClass().getResource("no-implemented-transformer.jar").toURI());
        RecipeTransformerException ex = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(noImplemented, mockComponentRecipe, mockContext));
        System.out.println(ex.getMessage());
        assertThat(ex.getMessage(), containsString("Could not find a candidate transformer class for template"));

        // more than one transformer class found
        Path multipleTransformer = Paths.get(getClass().getResource("multiple-transformer.jar").toURI());
        RecipeTransformerException ex2 = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(multipleTransformer, mockComponentRecipe, mockContext));
        assertThat(ex2.getMessage(), containsString("Found more than one candidate transformer class"));

        // something goes wrong with transformer init
        Path errorTransformer = Paths.get(getClass().getResource("error-transformer.jar").toURI());
        RecipeTransformerException ex3 = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(errorTransformer, mockComponentRecipe, mockContext));
        assertThat(ex3.getMessage(), containsString("Could not instantiate the transformer"));
    }
}
