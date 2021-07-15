/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateExecutionException;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.deployment.DeploymentService.parseFile;

/**
 * Template expansion workflow. Assumes the deployment is local and has all the required components/dependencies
 * necessary without appealing to the cloud. That is, if there is an unsatisfied template dependency, the deployment
 * will fail.
 */
public class TemplateEngine {
    public static final String PARSER_JAR = "transformer.jar";

    private Path recipeDirectoryPath;
    private Path artifactsDirectoryPath;
    @Inject
    private ComponentStore componentStore;
    @Inject
    private NucleusPaths nucleusPaths;

    private Map<ComponentIdentifier, ComponentRecipe> recipes;
    private Map<String, ComponentIdentifier> templates;
    private Map<String, List<ComponentIdentifier>> needsToBeBuilt;

    /**
     * Constructor.
     */
    @Inject
    public TemplateEngine() {
    }

    /**
     * Constructor for testing only.
     * @param componentStore a ComponentStore instance.
     */
    public TemplateEngine(ComponentStore componentStore) {
        this.componentStore = componentStore;
    }

    /**
     * Call to do templating. This call assumes we do not need to resolve component versions or fetch dependencies.
     * @throws TemplateExecutionException   if pre-processing throws an error.
     * @throws IOException                  for most things.
     * @throws PackageLoadingException      if we can't load a dependency.
     * @throws RecipeTransformerException   if individual templating runs into an issue.
     */
    public void process() throws TemplateExecutionException, IOException, PackageLoadingException,
            RecipeTransformerException {
        process(nucleusPaths.recipePath(), nucleusPaths.artifactPath());
    }

    /**
     * Processing method for unit testing.
     * @param recipeDirectoryPath           the component store recipe root.
     * @param artifactsDirectoryPath        the component store artifacts root.
     * @throws TemplateExecutionException   if pre-processing throws an error.
     * @throws IOException                  for most things.
     * @throws PackageLoadingException      if we can't load a dependency.
     * @throws RecipeTransformerException   if individual templating runs into an issue.
     */
    @SuppressWarnings("PMD.NullAssignment")
    public void process(Path recipeDirectoryPath, Path artifactsDirectoryPath) throws TemplateExecutionException,
            IOException, PackageLoadingException, RecipeTransformerException {
        this.recipeDirectoryPath = recipeDirectoryPath;
        this.artifactsDirectoryPath = artifactsDirectoryPath;

        // init state
        recipes = new HashMap<>();
        templates = new HashMap<>();
        needsToBeBuilt = new HashMap<>();

        loadComponents();
        // TODO: resolve versioning, download dependencies if necessary
        expandAll();
        removeTemplatesFromStore();

        // cleanup state
        recipes = null;
        templates = null;
        needsToBeBuilt = null;
    }

    /**
     * Read the parameter files and templates from store. Note which parameters files need to be expanded by which
     * template.
     * @throws MultipleTemplateDependencyException  if a parameter file declares a dependency on more than one template.
     * @throws IllegalTemplateDependencyException   if a template declares a dependency on another template.
     * @throws IOException                          if something funky happens with I/O or de/serialization.
     */
    void loadComponents() throws TemplateExecutionException, IOException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    loadComponent(r);
                }
            }
        }
    }

    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    void loadComponent(Path recipePath) throws TemplateExecutionException, IOException {
        ComponentRecipe recipe = parseFile(recipePath);
        ComponentIdentifier identifier = new ComponentIdentifier(recipe.getComponentName(),
                recipe.getComponentVersion());
        recipes.put(identifier, recipe);
        if (recipe.getComponentName().endsWith("Template")) { // TODO: same as above
            templates.put(recipe.getComponentName(), identifier);
        }
        Map<String, DependencyProperties> deps = recipe.getComponentDependencies();
        if (deps == null) {
            return;
        }
        boolean paramFileAlreadyHasDependency = false;
        for (Map.Entry<String, DependencyProperties> me : deps.entrySet()) {
            if (me.getKey().endsWith("Template")) { // TODO: same as above
                if (identifier.getName().endsWith("Template")) { // TODO: here too
                    throw new IllegalTemplateDependencyException("Illegal dependency for template "
                            + identifier.getName() + ". Templates cannot depend on other templates");
                }
                if (paramFileAlreadyHasDependency) {
                    throw new MultipleTemplateDependencyException("Parameter file " + identifier.getName()
                            + " has multiple template dependencies");
                }
                paramFileAlreadyHasDependency = true;
                needsToBeBuilt.putIfAbsent(me.getKey(), new ArrayList<>());
                needsToBeBuilt.get(me.getKey()).add(identifier);
            }
        }
    }

    // process all templates and parameter files
    void expandAll() throws PackageLoadingException, RecipeTransformerException,
            IOException {
        for (Map.Entry<String, List<ComponentIdentifier>> entry : needsToBeBuilt.entrySet()) {
            ComponentIdentifier template = templates.get(entry.getKey());
            if (template == null) {
                throw new PackageLoadingException("Could not get template component " + entry.getKey());
            }
            expandAllForTemplate(template, entry.getValue());
        }
    }

    void expandAllForTemplate(ComponentIdentifier template, List<ComponentIdentifier> paramFiles)
            throws IOException, PackageLoadingException, RecipeTransformerException {
        Path templateExecutablePath =
                artifactsDirectoryPath.resolve(template.getName()).resolve(template.getVersion().toString()).resolve(
                        PARSER_JAR);
        TransformerWrapper wrapper;
        try {
            wrapper = new TransformerWrapper(templateExecutablePath,
                    "com.aws.greengrass.deployment.templating.transformers.EchoTransformer",
                    recipes.get(template));
        } catch (ClassNotFoundException | IllegalTransformerException | NoSuchMethodException
                | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new RecipeTransformerException("Could not instantiate the transformer for template "
                    + template.getName(), e);
        }
        for (ComponentIdentifier paramFile : paramFiles) {
            Pair<ComponentRecipe, List<Path>> rt =
                    wrapper.expandOne(recipes.get(paramFile));
            componentStore.savePackageRecipe(paramFile, getRecipeSerializer().writeValueAsString(rt.getLeft()));
            Path componentArtifactsDirectory = componentStore.resolveArtifactDirectoryPath(paramFile);
            for (Path artifactPath : rt.getRight()) {
                copyArtifactToStoreIfMissing(artifactPath, componentArtifactsDirectory);
            }
        }
    }

    // copies the artifact to the artifacts directory, if one with the same name does not already exist
    void copyArtifactToStoreIfMissing(Path artifactPath, Path componentArtifactsDirectory) throws IOException {
        Path newArtifact = componentArtifactsDirectory.resolve(artifactPath.getFileName());
        if (Files.exists(newArtifact)) {
            return;
        }
        Files.copy(artifactPath, newArtifact);
    }

    void removeTemplatesFromStore() throws IOException, TemplateExecutionException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    ComponentRecipe recipe = parseFile(r);
                    if (recipe.getComponentName().endsWith("Template")) { // TODO: remove templates by component type
                        componentStore.deleteComponent(new ComponentIdentifier(recipe.getComponentName(),
                                recipe.getComponentVersion()));
                    }
                }
            }
        } catch (PackageLoadingException e) {
            throw new TemplateExecutionException("Could not delete template component", e);
        }
    }
}
