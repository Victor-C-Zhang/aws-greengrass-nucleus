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
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateExecutionException;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Pair;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
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

/**
 * Template expansion workflow. Assumes the deployment is local and has all the required components/dependencies
 * necessary without appealing to the cloud. That is, if there is an unsatisfied template dependency, the deployment
 * will fail.
 */
public class TemplateEngine {
    public static final String PARSER_JAR = "transformer.jar";
    private static final String TEMPLATE_TEMP_IDENTIFIER = "Template"; // TODO: get rid of this once template type is in

    @Inject
    private ComponentStore componentStore;
    @Inject
    private NucleusPaths nucleusPaths;
    @Inject
    EZPlugins ezPlugins;

    private Map<ComponentIdentifier, ComponentRecipe> mapOfComponentIdentifierToRecipe = null;
    private Map<String, ComponentIdentifier> mapOfTemplateNameToTemplateIdentifier = null;
    private Map<String, List<ComponentIdentifier>> mapOfTemplateToComponentsToBeBuilt = null;

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
        mapOfComponentIdentifierToRecipe = new HashMap<>();
        mapOfTemplateNameToTemplateIdentifier = new HashMap<>();
        mapOfTemplateToComponentsToBeBuilt = new HashMap<>();

        loadComponents(recipeDirectoryPath);
        // TODO: resolve versioning, download dependencies if necessary
        expandAll(artifactsDirectoryPath);

        // cleanup state
        mapOfComponentIdentifierToRecipe = null;
        mapOfTemplateNameToTemplateIdentifier = null;
        mapOfTemplateToComponentsToBeBuilt = null;
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

    void loadComponent(Path recipePath) throws TemplateExecutionException, IOException {
        // add component to necessary maps
        ComponentRecipe recipe = parseFile(recipePath);
        ComponentIdentifier identifier = new ComponentIdentifier(recipe.getComponentName(),
                recipe.getComponentVersion());
        mapOfComponentIdentifierToRecipe.put(identifier, recipe);
        if (recipe.getComponentName().endsWith(TEMPLATE_TEMP_IDENTIFIER)) { // TODO: same as above
            mapOfTemplateNameToTemplateIdentifier.put(recipe.getComponentName(), identifier);
        }

        // check if template is a parameter file through its dependencies. add it to the build queue if it is.
        Map<String, DependencyProperties> deps = recipe.getComponentDependencies();
        if (deps == null) {
            return;
        }
        boolean paramFileAlreadyHasDependency = false; // a parameter file can only have one template dependency
        for (Map.Entry<String, DependencyProperties> dependencyEntry : deps.entrySet()) {
            if (dependencyEntry.getKey().endsWith(TEMPLATE_TEMP_IDENTIFIER)) { // TODO: same as above
                if (identifier.getName().endsWith(TEMPLATE_TEMP_IDENTIFIER)) { // TODO: here too
                    throw new IllegalTemplateDependencyException("Illegal dependency for template "
                            + identifier.getName() + ". Templates cannot depend on other templates");
                }
                if (paramFileAlreadyHasDependency) {
                    throw new MultipleTemplateDependencyException("Parameter file " + identifier.getName()
                            + " has multiple template dependencies");
                }
                paramFileAlreadyHasDependency = true;
                // add param file to build queue for template
                mapOfTemplateToComponentsToBeBuilt.putIfAbsent(dependencyEntry.getKey(), new ArrayList<>());
                mapOfTemplateToComponentsToBeBuilt.get(dependencyEntry.getKey()).add(identifier);
            }
        }
    }

    /**
     * Process all templates and parameter files.
     * @param artifactsDirectoryPath        the artifacts path in which to find template transformer jars.
     * @throws PackageLoadingException      if the template isn't present on the device.
     * @throws RecipeTransformerException   if something goes wrong with template expansion.
     * @throws IOException                  if something goes wrong with IO/serialization.
     */
    void expandAll(Path artifactsDirectoryPath)
            throws PackageLoadingException, RecipeTransformerException, IOException {
        for (Map.Entry<String, List<ComponentIdentifier>> entry : mapOfTemplateToComponentsToBeBuilt.entrySet()) {
            ComponentIdentifier template = mapOfTemplateNameToTemplateIdentifier.get(entry.getKey());
            if (template == null) {
                throw new PackageLoadingException("Could not get template component " + entry.getKey());
            }
            Path templateJarPath = artifactsDirectoryPath.resolve(template.getName())
                    .resolve(template.getVersion().toString()).resolve(PARSER_JAR);
            expandAllForTemplate(template, templateJarPath, entry.getValue());
        }
    }

    // expand all the recipes that depend on a specific template. save updated recipes to componentStore
    void expandAllForTemplate(ComponentIdentifier template, Path templateJarFile, List<ComponentIdentifier> paramFiles)
            throws IOException, PackageLoadingException, RecipeTransformerException {
        TransformerWrapper wrapper;
         wrapper = new TransformerWrapper(templateJarFile, mapOfComponentIdentifierToRecipe.get(template), ezPlugins);
        for (ComponentIdentifier paramFile : paramFiles) {
            ComponentRecipe expandedRecipe = wrapper.expandOne(mapOfComponentIdentifierToRecipe.get(paramFile));
            componentStore.savePackageRecipe(paramFile, getRecipeSerializer().writeValueAsString(expandedRecipe));
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
            if (componentIdentifier.getName().endsWith(TEMPLATE_TEMP_IDENTIFIER)) {
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
