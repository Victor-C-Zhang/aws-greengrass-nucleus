/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
        AtomicReference<Class<RecipeTransformer>> transformerClass = new AtomicReference<>();

        Consumer<FastClasspathScanner> matcher = sc -> sc.matchAllClasses(c -> {
            if (RecipeTransformer.class.isAssignableFrom(c) && !c.equals(RecipeTransformer.class)) {
                if (transformerClass.get() != null) {
                    throw new RuntimeException("Found more than one candidate transformer class.");
                }
                transformerClass.set((Class<RecipeTransformer>) c);
            }
        });
        try {
            URL[] urls = {pathToExecutable.toUri().toURL()};
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                URLClassLoader cl = new URLClassLoader(urls);
                FastClasspathScanner sc = new FastClasspathScanner();
                sc.ignoreParentClassLoaders();
                sc.overrideClassLoaders(cl);
                matcher.accept(sc);
                sc.scan(context.get(ExecutorService.class), 1);

                try {
                    cl.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
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
