/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializerJson;

public class TemplateEngine {
    public static final String PARSER_JAR = "parser.jar";

    private final Path recipeDirectoryPath;
    private final Path artifactsDirectoryPath;

    private final Map<ComponentIdentifier, ComponentRecipe> recipes = new HashMap<>();
    private final List<ComponentIdentifier> templates = new ArrayList<>();
    private final Map<String, Queue<ComponentIdentifier>> needsToBeBuilt = new HashMap<>();

    /**
     * Constructor.
     * @param recipeDirectoryPath the directory in which to expand and clean up templates.
     * @param artifactsDirectoryPath the directory in which to prepare artifacts.
     */
    public TemplateEngine(Path recipeDirectoryPath, Path artifactsDirectoryPath) {
        this.recipeDirectoryPath = recipeDirectoryPath;
        this.artifactsDirectoryPath = artifactsDirectoryPath;
    }

    public void process() throws MultipleTemplateDependencyException, IllegalDependencyException, IOException,
            PackageLoadingException, TemplateExecutionException {
        loadComponents();
        // TODO: resolve versioning, download dependencies if necessary
        expandAll();
        removeTemplatesFromStore();
    }

    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.AvoidDeeplyNestedIfStmts"})
    void loadComponents() throws IOException, MultipleTemplateDependencyException, IllegalDependencyException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    ComponentRecipe recipe = parseFile(r);
                    ComponentIdentifier identifier = new ComponentIdentifier(recipe.getComponentName(), recipe.getVersion());
                    recipes.put(identifier, recipe);
                    if (recipe.getComponentName().endsWith("Template")) { // TODO: create and use separate type for templates
                        templates.add(identifier);
                    }
                    Map<String, DependencyProperties> deps = recipe.getDependencies();
                    if (deps == null) {
                        continue;
                    }
                    boolean paramFileAlreadyHasDependency = false;
                    for (Map.Entry<String, DependencyProperties> me : deps.entrySet()) {
                        if (me.getKey().endsWith("Template")) { // TODO: same as above
                            if (identifier.getName().endsWith("Template")) { // TODO: here too
                                throw new IllegalDependencyException("Illegal dependency for template "
                                        + identifier.getName() + ". Templates cannot depend on other templates");
                            }
                            if (paramFileAlreadyHasDependency) {
                                throw new MultipleTemplateDependencyException("Parameter file " + identifier.getName() + " has multiple template dependencies");
                            }
                            paramFileAlreadyHasDependency = true;
                            needsToBeBuilt.putIfAbsent(me.getKey(), new LinkedList<>());
                            needsToBeBuilt.get(me.getKey()).add(identifier);
                        }
                    }
                }
            }
        }
    }

    void expandAll() throws PackageLoadingException, TemplateExecutionException {
        for (Map.Entry<String, Queue<ComponentIdentifier>> entry : needsToBeBuilt.entrySet()) {
            // TODO: get resolved component from existing map
            ComponentIdentifier template = null;
            for (ComponentIdentifier potentialTemplate : templates) {
                if (potentialTemplate.getName().equals(entry.getKey())) {
                    template = potentialTemplate; // find first lol
                    break;
                }
            }
            if (template == null) {
                throw new PackageLoadingException("Could not find template: " + entry.getKey());
            }
            // END TODO
            for (ComponentIdentifier paramFile : entry.getValue()) {
                try {
                    expandOne(template, paramFile);
                } catch (IOException e) {
                    throw new PackageLoadingException("Could not find templating executable for template " + template.getName(), e);
                }
            }
        }
    }

    void expandOne(ComponentIdentifier template, ComponentIdentifier paramFile)
            throws IOException, TemplateExecutionException, PackageLoadingException {
        System.out.println(template.getVersion().toString());
        Path templateExecutablePath =
                artifactsDirectoryPath.resolve(template.getName()).resolve(template.getVersion().toString()).resolve(
                        PARSER_JAR);
//        ExecutableWrapper executableWrapper = new ExecutableWrapper(templateExecutablePath,
//                recipes.get(paramFile).toString());
//        recipes.replace(paramFile, getRecipeSerializer().readValue(executableWrapper.transform(), ComponentRecipe.class));

        try {
            Pair<ComponentRecipe, List<Path>> rt = TransformerWrapper.execute(templateExecutablePath, "Parser",
                    recipes.get(paramFile));
            updateRecipeInStore(rt.getLeft());
            Path componentArtifactsDirectory =
                    artifactsDirectoryPath.resolve(paramFile.getName()).resolve(paramFile.getVersion().toString());
            // TODO: actually get the right to-string for semver version
            for (Path artifactPath : rt.getRight()) {
                copyArtifactToStoreIfMissing(artifactPath, componentArtifactsDirectory);
            }
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalTransformerException e) {
            throw new TemplateExecutionException("Could not execute template " + template.getName(), e);
        }
    }

    // replaces the old component recipe file with the new one, written as a .yaml file
    void updateRecipeInStore(ComponentRecipe componentRecipe) throws IOException, PackageLoadingException {
        String componentName = componentRecipe.getComponentName();
        Path newRecipePath = null;
        // find old recipe and remove it, in case it is a different extension than we want
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    String fileName = FilenameUtils.removeExtension(String.valueOf(r.getFileName()));
                    if (componentName.equals(fileName)) {
                        newRecipePath = r.resolveSibling(fileName + ".yaml");
                        if(!r.toFile().delete()) throw new IOException("Could not delete old parameter file " + componentName);
                    }
                }
            }
        }

        // write new file
        if (newRecipePath == null) {
            throw new PackageLoadingException("Component " + componentName + " does not have a parameter file to "
                    + "replace");
        }
        FileUtils.writeStringToFile(newRecipePath.toFile(), getRecipeSerializer().writeValueAsString(componentRecipe));
    }

    // copies the artifact to the artifacts directory, if one with the same name does not already exist
    void copyArtifactToStoreIfMissing(Path artifactPath, Path componentArtifactsDirectory) throws IOException {
        Path newArtifact = componentArtifactsDirectory.resolve(artifactPath.getFileName());
        if (Files.exists(newArtifact)) {
            return;
        }
        Files.copy(artifactPath, newArtifact);
    }

    void removeTemplatesFromStore() throws IOException {
        try (Stream<Path> files = Files.walk(recipeDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory()) {
                    ComponentRecipe recipe = parseFile(r);
                    if (recipe.getComponentName().endsWith("Template")) { // TODO: remove templates by component type
                        if(!r.toFile().delete()) throw new IOException("Could not delete template file " + recipe.getComponentName());
                        removeCorrespondingArtifactsFromStore(recipe.getComponentName());
                    }
                }
            }
        }
    }

    void removeCorrespondingArtifactsFromStore(String templateName) throws IOException {
        try (Stream<Path> files = Files.walk(artifactsDirectoryPath)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (r.toFile().isDirectory() && r.toFile().getName().equals(templateName)) {
                    Files.walk(r).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        }
    }

    // copied from DeploymentService.copyRecipeFileToComponentStore()
    ComponentRecipe parseFile(Path recipePath) throws IOException {
        String ext = Utils.extension(recipePath.toString());
        ComponentRecipe recipe = null;
        try {
            if (recipePath.toFile().length() > 0) {
                switch (ext.toLowerCase()) {
                    case "yaml":
                    case "yml":
                        recipe = getRecipeSerializer().readValue(recipePath.toFile(), ComponentRecipe.class);
                        break;
                    case "json":
                        recipe = getRecipeSerializerJson().readValue(recipePath.toFile(), ComponentRecipe.class);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            // Throw on error so that the user will receive this message and we will stop the deployment.
            // This is to fail fast while providing actionable feedback.
            throw new IOException(
                    String.format("Unable to parse %s as a recipe due to: %s", recipePath.toString(), e.getMessage()),
                    e);
        }
        if (recipe == null) {
            //            logger.atError().log("Skipping file {} because it was not recognized as a recipe", recipePath);
            return null;
        }

        return recipe;
    }
}
