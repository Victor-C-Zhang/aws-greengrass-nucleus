/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

public class TransformerWrapper {
    RecipeTransformer transformer = null;

    /**
     * Constructor. Generates an execution wrapper for a particular template.
     * @param pathToExecutable              the jar to run.
     * @param template                      the template recipe file.
     * @param ezPlugins                     EZPlugins instance.
     * @throws RecipeTransformerException   if something goes wrong instantiating the transformer.
     */
    @SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.AvoidCatchingGenericException"})
    public TransformerWrapper(Path pathToExecutable, ComponentRecipe template, EZPlugins ezPlugins)
            throws RecipeTransformerException {
        if (!Files.exists(pathToExecutable)) {
            throw new RecipeTransformerException("Could not find template parsing jar to execute");
        }
        try {
            ezPlugins.loadPlugin(pathToExecutable, sc -> sc.matchSubclassesOf(RecipeTransformer.class, c -> {
                try {
                    transformer = c.getDeclaredConstructor(ComponentRecipe.class).newInstance(template);
                } catch (InstantiationException | IllegalAccessException | NoSuchMethodException
                        | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }));
        } catch (IOException | RuntimeException e) {
            throw new RecipeTransformerException("Could not instantiate the transformer for "
                    + template.getComponentName(), e);
        }

        if (transformer == null) {
            throw new RecipeTransformerException("Could not instantiate the transformer for "
                    + template.getComponentName());
        }
    }

    ComponentRecipe expandOne(ComponentRecipe paramFile)
            throws RecipeTransformerException {
        return transformer.execute(paramFile);
    }
}
