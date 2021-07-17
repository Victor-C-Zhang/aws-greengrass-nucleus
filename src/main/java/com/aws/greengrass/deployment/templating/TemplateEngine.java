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
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateExecutionException;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.deployment.DeploymentService.parseFile;
import static com.aws.greengrass.deployment.templating.RecipeTransformer.TEMPLATE_TRANSFORMER_CLASS_KEY;

/**
 * Template expansion workflow. Assumes the deployment is local and has all the required components/dependencies
 * necessary without appealing to the cloud. That is, if there is an unsatisfied template dependency, the deployment
 * will fail.
 */
public class TemplateEngine {
    public static final String PARSER_JAR = "transformer.jar";

    @Inject
    private ComponentStore componentStore;
    @Inject
    private NucleusPaths nucleusPaths;

    private Map<ComponentIdentifier, ComponentRecipe> recipes = null;
    private Map<String, ComponentIdentifier> templates = null;
    private Map<String, List<ComponentIdentifier>> needsToBeBuilt = null;

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
        // init state
        recipes = new HashMap<>();
        templates = new HashMap<>();
        needsToBeBuilt = new HashMap<>();

        loadComponents(recipeDirectoryPath);
        // TODO: resolve versioning, download dependencies if necessary
        expandAll(artifactsDirectoryPath);

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
    void loadComponents(Path recipeDirectoryPath) throws TemplateExecutionException, IOException {
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

    /**
     * Process all templates and parameter files.
     * @param artifactsDirectoryPath        the artifacts path in which to find template transformer jars.
     * @throws PackageLoadingException      if the template isn't present on the device.
     * @throws RecipeTransformerException   if something goes wrong with template expansion.
     * @throws IOException                  if something goes wrong with IO/serialization.
     */
    void expandAll(Path artifactsDirectoryPath)
            throws PackageLoadingException, RecipeTransformerException, IOException {
        for (Map.Entry<String, List<ComponentIdentifier>> entry : needsToBeBuilt.entrySet()) {
            ComponentIdentifier template = templates.get(entry.getKey());
            if (template == null) {
                throw new PackageLoadingException("Could not get template component " + entry.getKey());
            }
            Path templateJarPath = artifactsDirectoryPath.resolve(template.getName())
                    .resolve(template.getVersion().toString()).resolve(PARSER_JAR);
            expandAllForTemplate(template, templateJarPath, entry.getValue());
        }
    }

    void expandAllForTemplate(ComponentIdentifier template, Path templateJarFile, List<ComponentIdentifier> paramFiles)
            throws IOException, PackageLoadingException, RecipeTransformerException {
        TransformerWrapper wrapper;
        try {
            JsonNode transformerClassNode = recipes.get(template).getComponentConfiguration().getDefaultConfiguration()
                    .get(TEMPLATE_TRANSFORMER_CLASS_KEY);
            if (transformerClassNode == null) {
                throw new RecipeTransformerException("Template recipe did not specify a transformer class");
            }
            wrapper = new TransformerWrapper(templateJarFile, transformerClassNode.asText(), recipes.get(template));
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
        if (!Files.exists(newArtifact)) {
            Files.copy(artifactPath, newArtifact);
        }
    }

    /**
     * Scan through list of packages, removing templates from package list and keeping track of which packages were
     * removed.
     * @param desiredPackages the list of packages to scan through.
     * @return a Pair where the first value is the resulting package list, less templates; and the second value is
     *     the list of removed packages.
     */
    public static Pair<List<ComponentIdentifier>, List<ComponentIdentifier>> separateTemplatesFromPackageList(
            List<ComponentIdentifier> desiredPackages) {
        List<ComponentIdentifier> resultant = new ArrayList<>();
        List<ComponentIdentifier> removed = new ArrayList<>();
        desiredPackages.forEach(componentIdentifier -> {
            // TODO: can we dig through the recipe store to check template status?
            if (componentIdentifier.getName().endsWith("Template")) {
                removed.add(componentIdentifier);
            } else {
                resultant.add(componentIdentifier);
            }
        });
        return new Pair<>(resultant, removed);
    }

    /**
     * Utility function to remove templates from the list of deployment package configurations.
     * @param deploymentPackageConfigurationList the list to scan through.
     * @param templates a collection of components known to be templates.
     * @return a new list with deployment packages, excluding templates.
     */
    public static List<DeploymentPackageConfiguration> deploymentPackageConfigurationListLessTemplates(
            @Nullable List<DeploymentPackageConfiguration> deploymentPackageConfigurationList,
            Set<ComponentIdentifier> templates) {
        if (deploymentPackageConfigurationList == null) {
            return new ArrayList<>();
        }
        return deploymentPackageConfigurationList.stream().filter(packageConfig ->
                !templates.contains(new ComponentIdentifier(packageConfig.getPackageName(),
                new Semver(packageConfig.getResolvedVersion()))))
        .collect(Collectors.toList());
    }
}
