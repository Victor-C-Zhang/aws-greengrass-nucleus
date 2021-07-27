/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class TransformWrapperTest {
    @Mock
    Context mockContext;
    @Mock
    ComponentRecipe mockComponentRecipe;

    @Test
    void WHEN_given_bad_path_THEN_throw_error() throws URISyntaxException {
        Path goodPath = Paths.get(getClass().getResource("no-implemented-transformer.jar").toURI());
        Path badPath = goodPath.resolveSibling("nonexistent_file.txt");
        RecipeTransformerException ex = assertThrows(RecipeTransformerException.class,
                () -> new TransformerWrapper(badPath, mockComponentRecipe, mockContext));
        assertThat(ex.getMessage(), containsString("Could not find template parsing jar to execute"));
    }
}
