/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Pair;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import javax.inject.Inject;

public class TransformerWrapper {
    @Inject
    Logger logger;
    RecipeTransformer transformer;

    /**
     * Constructor. Generates an execution wrapper for a particular template.
     * @param pathToExecutable  the jar to run.
     * @param className         the name of the parser class.
     * @param template          the template recipe file.
     * @throws ClassNotFoundException       if the jar does not contain the parser class.
     * @throws IllegalTransformerException  if the class does not implement the RecipeTransformer interface.
     * @throws NoSuchMethodException        similarly.
     * @throws InvocationTargetException    if the called template throws an exception.
     * @throws InstantiationException       if the constructor is not public.
     * @throws IllegalAccessException       similarly.
     * @throws RecipeTransformerException   for everything else.
     */
    @SuppressWarnings("PMD.CloseResource")
    public TransformerWrapper(Path pathToExecutable, String className, ComponentRecipe template)
            throws ClassNotFoundException, IllegalTransformerException, NoSuchMethodException,
            InvocationTargetException, InstantiationException, IllegalAccessException, RecipeTransformerException {
        if (!pathToExecutable.toFile().exists()) {
            throw new RecipeTransformerException("Could not find template parsing jar to execute");
        }
        try {
            URLClassLoader loader = AccessController.doPrivileged(new PrivilegedExceptionAction<URLClassLoader>() {
                @Override
                public URLClassLoader run() throws MalformedURLException {
                    return new URLClassLoader(new URL[] {pathToExecutable.toFile().toURI().toURL()},
                            TransformerWrapper.class.getClassLoader());
                }
            });
            Class<?> recipeTransformerClass = Class.forName(className, true, loader);
            if (!RecipeTransformer.class.isAssignableFrom(recipeTransformerClass)) {
                throw new IllegalTransformerException(className + " does not implement the RecipeTransformer "
                        + "interface");
            }
            transformer = (RecipeTransformer) recipeTransformerClass
                    .getConstructor(ComponentRecipe.class)
                    .newInstance(template);
            loader.close();
        } catch (PrivilegedActionException | MalformedURLException e) {
            throw new RecipeTransformerException("Could not find template parsing jar to execute", e);
        } catch (IOException e) {
            logger.atWarn().setCause(e).log("Could not close URLClassLoader for template "
                    + template.getComponentName());
        }
    }

    Pair<ComponentRecipe, List<Path>> expandOne(ComponentRecipe paramFile)
            throws RecipeTransformerException {
        return transformer.execute(paramFile);
    }
}
