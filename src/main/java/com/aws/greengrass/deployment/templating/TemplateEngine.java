/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.MultipleTemplateDependencyException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateExecutionException;
import com.aws.greengrass.util.NucleusPaths;

import java.io.IOException;
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
    private static final String TEMPLATE_TEMP_IDENTIFIER = "Template"; // TODO: get rid of this once template type is in

    private final ComponentStore componentStore;
    private final NucleusPaths nucleusPaths;
    private final Context context;

    private Map<ComponentIdentifier, ComponentRecipe> mapOfComponentIdentifierToRecipe = null;
    private Map<String, ComponentIdentifier> mapOfTemplateNameToTemplateIdentifier = null;
    private Map<String, List<ComponentIdentifier>> mapOfTemplateToComponentsToBeBuilt = null;

    /**
     * Constructor.
     * @param componentStore a ComponentStore instance.
     * @param nucleusPaths   a NucleusPaths instance.
     * @param context        the context instance.
     */
    @Inject
    public TemplateEngine(ComponentStore componentStore, NucleusPaths nucleusPaths, Context context) {
        this.componentStore = componentStore;
        this.nucleusPaths = nucleusPaths;
        this.context = context;
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
        ensureTemplatesHaveNoLifecycle();
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
     * @throws IllegalTemplateDependencyException   if a template declares a dependency on another template or a
     *                                              parameter file declares a dependency on a template version other
     *                                              than the one provided.
     * @throws IOException                          if something funky happens with I/O or de/serialization.
     */
    void loadComponents(Path recipeDirectoryPath) throws TemplateExecutionException, IOException {
        scanComponentsIntoEngine(recipeDirectoryPath);
        populateExpansionQueue();
    }

    // populate identifier-to-recipe, template name-to-identifier maps
    void scanComponentsIntoEngine(Path recipeDirectoryPath) throws IOException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    scanComponentIntoEngine(r);
                }
            }
        }
    }

    // add component to necessary maps
    void scanComponentIntoEngine(Path recipePath) throws IOException {
        ComponentRecipe recipe = parseFile(recipePath);
        ComponentIdentifier identifier = new ComponentIdentifier(recipe.getComponentName(),
                recipe.getComponentVersion());
        mapOfComponentIdentifierToRecipe.put(identifier, recipe);
        if (recipe.getComponentName().endsWith(TEMPLATE_TEMP_IDENTIFIER)) { // TODO: same as above
            mapOfTemplateNameToTemplateIdentifier.put(recipe.getComponentName(), identifier);
        }
    }

    // scan through the recipes to find parameter files
    void populateExpansionQueue() throws TemplateExecutionException {
        for (ComponentIdentifier componentIdentifier : mapOfComponentIdentifierToRecipe.keySet()) {
            addOneToExpansionQueue(componentIdentifier);
        }
    }

    // check if component is a parameter file through its dependencies. add it to the build queue if it is.
    // throws TemplateExecutionException if there is a bad dependency requirement.
    void addOneToExpansionQueue(ComponentIdentifier identifier) throws TemplateExecutionException {
        ComponentRecipe recipe = mapOfComponentIdentifierToRecipe.get(identifier);
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

                // assume we're provided all the right components and the versions match properly.
                // TODO: allow for dependency resolution of template versions
                ComponentIdentifier templateId = mapOfTemplateNameToTemplateIdentifier.get(dependencyEntry.getKey());
                if (templateId == null) {
                    throw new IllegalTemplateDependencyException("Component " + identifier.getName() + " depends on a "
                            + "version of " + dependencyEntry.getKey() + " that can't be found locally. Requirement is "
                            + dependencyEntry.getValue().getVersionRequirement());
                }
                if (!dependencyEntry.getValue().getVersionRequirement().isSatisfiedBy(templateId.getVersion())) {
                    throw new IllegalTemplateDependencyException("Component " + identifier.getName() + " depends on a"
                            + " version of " + templateId.getName() + " that can't be found locally. Requirement is "
                            + dependencyEntry.getValue().getVersionRequirement()
                            + " but have " + templateId.getVersion());
                }

                paramFileAlreadyHasDependency = true;
                // add param file to build queue for template
                mapOfTemplateToComponentsToBeBuilt.putIfAbsent(dependencyEntry.getKey(), new ArrayList<>());
                mapOfTemplateToComponentsToBeBuilt.get(dependencyEntry.getKey()).add(identifier);
            }
        }
    }

    // assert templates have no lifecycle. TODO: do this in the nucleus, similar to provisioning plugin
    void ensureTemplatesHaveNoLifecycle() throws RecipeTransformerException {
        for (Map.Entry<ComponentIdentifier, ComponentRecipe> templateEntry :
                mapOfComponentIdentifierToRecipe.entrySet()) {
            ComponentRecipe templateRecipe = mapOfComponentIdentifierToRecipe.get(templateEntry.getKey());
            for (PlatformSpecificManifest manifest : templateRecipe.getManifests()) {
                if (manifest.getLifecycle() != null && manifest.getLifecycle().size() != 0) {
                    throw new RecipeTransformerException("Templates cannot have non-empty lifecycle. "
                            + templateEntry.getKey().getName() + " has a lifecycle map with "
                            + manifest.getLifecycle().size() + " key/value pairs.");
                }
            }
            if (templateRecipe.getLifecycle() != null && templateRecipe.getLifecycle().size() != 0) {
                throw new RecipeTransformerException("Templates cannot have non-empty lifecycle. "
                        + templateEntry.getKey().getName() + " has a lifecycle map with "
                        + templateRecipe.getLifecycle().size() + " key/value pairs.");
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
         wrapper = new TransformerWrapper(templateJarFile, mapOfComponentIdentifierToRecipe.get(template), context);
        for (ComponentIdentifier paramFile : paramFiles) {
            ComponentRecipe expandedRecipe = wrapper.expandOne(mapOfComponentIdentifierToRecipe.get(paramFile));
            componentStore.savePackageRecipe(paramFile, getRecipeSerializer().writeValueAsString(expandedRecipe));
        }
    }
}
