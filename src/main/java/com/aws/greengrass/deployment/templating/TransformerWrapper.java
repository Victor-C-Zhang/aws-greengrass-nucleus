/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public class TransformerWrapper {
    RecipeTransformer transformer;

    /**
     * Constructor. Generates an execution wrapper for a particular template.
     * @param pathToExecutable              the jar to run.
     * @param template                      the template recipe file.
     * @param context                       the Context instance.
     * @throws RecipeTransformerException   if something goes wrong instantiating the transformer.
     */
    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    public TransformerWrapper(Path pathToExecutable, ComponentRecipe template, Context context)
            throws RecipeTransformerException {
        if (!Files.exists(pathToExecutable)) {
            throw new RecipeTransformerException("Could not find template parsing jar to execute. Looked for a jar at "
                    + pathToExecutable);
        }
        EZPlugins ezPlugins = context.get(EZPlugins.class);
        AtomicReference<Class<RecipeTransformer>> transformerClass = new AtomicReference<>();
        try {
            ezPlugins.loadPlugin(pathToExecutable, sc -> sc.matchSubclassesOf(RecipeTransformer.class, c -> {
                if (transformerClass.get() != null) {
                    throw new RuntimeException("Found more than one candidate transformer class.");
                }
                transformerClass.set((Class<RecipeTransformer>) c);
            }));
        } catch (IOException | RuntimeException e) {
            throw new RecipeTransformerException(e);
        }

        if (transformerClass.get() == null) {
            throw new RecipeTransformerException("Could not find a candidate transformer class for template "
                    + template.getComponentName());
        }

        try {
            transformer = context.get(transformerClass.get());
            transformer.initTemplateRecipe(template);
        } catch (Throwable e) {
            throw new RecipeTransformerException("Could not instantiate the transformer for "
                    + template.getComponentName(), e);
        }
    }

    ComponentRecipe expandOne(ComponentRecipe paramFile)
            throws RecipeTransformerException {
        return transformer.execute(paramFile);
    }
}
