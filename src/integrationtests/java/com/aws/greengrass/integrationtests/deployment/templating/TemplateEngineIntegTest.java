/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentDocumentDownloader;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class TemplateEngineIntegTest extends BaseITCase {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;

    @BeforeEach
    void beforeEach(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        kernel = new Kernel();
        kernel.getContext().put(DeploymentDocumentDownloader.class, deploymentDocumentDownloader);
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                TemplateEngineIntegTest.class.getResource("../onlyMain.yaml"));

        // ensure deployment service starts
        CountDownLatch deploymentServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(DEPLOYMENT_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                deploymentServiceLatch.countDown();

            }
        });
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);

        kernel.launch();
        assertTrue(deploymentServiceLatch.await(10, TimeUnit.SECONDS));
        deploymentQueue =  kernel.getContext().get(DeploymentQueue.class);

        FleetStatusService fleetStatusService = (FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS);
        fleetStatusService.getIsConnected().set(false);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void WHEN_a_local_deployment_has_templates_THEN_they_are_expanded_properly() throws Exception {
        // pre-load contents to package store
        localStoreContentPath =
                Paths.get(TemplateEngineIntegTest.class.getResource("nondependent").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
        renameTestTransformerJarsToTransformerJars(localStoreContentPath.resolve("artifacts"));

        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (status) -> {
            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("templatingDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                verifyLoggerDeployment();
                firstDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineIntegTest");

        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("LoggerA", "1.0.0");
        componentsToMerge.put("LoggerB", "1.0.0");
        componentsToMerge.put("LoggerC", "1.0.0");
        componentsToMerge.put("A", "1.0.0");

        Map<String, ConfigurationUpdateOperation> updateConfig = new HashMap<>();

        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("templatingDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .configurationUpdate(updateConfig)
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        assertTrue(firstDeploymentCDL.await(10, TimeUnit.SECONDS), "Templating deployment did not succeed");
    }

    @Test
    void WHEN_multiple_local_deployments_are_requested_THEN_templating_works_for_all_deployments() throws Exception {
        // pre-load contents to package store
        localStoreContentPath =
                Paths.get(TemplateEngineIntegTest.class.getResource("nondependent").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
        renameTestTransformerJarsToTransformerJars(localStoreContentPath.resolve("artifacts"));

        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        CountDownLatch secondDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("templatingDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                verifyLoggerDeployment();
                firstDeploymentCDL.countDown();
            } else if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondTemplatingDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                verifyLoggerDeployment();
                secondDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineIntegTest");

        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();
        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("LoggerA", "1.0.0");
        componentsToMerge.put("LoggerB", "1.0.0");
        componentsToMerge.put("LoggerC", "1.0.0");

        Map<String, ConfigurationUpdateOperation> updateConfig = new HashMap<>();

        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("templatingDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .configurationUpdate(updateConfig)
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        assertTrue(firstDeploymentCDL.await(10, TimeUnit.SECONDS), "Templating deployment did not succeed");


        Map<String, String> newComponentsToMerge = new HashMap<>();
        componentsToMerge.put("A", "1.0.0");

        LocalOverrideRequest secondRequest = LocalOverrideRequest.builder().requestId("secondTemplatingDeployment")
                .componentsToMerge(newComponentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .configurationUpdate(updateConfig)
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(secondRequest);
        assertTrue(secondDeploymentCDL.await(10, TimeUnit.SECONDS), "Second templating deployment did not succeed");
    }

    @Test
    void WHEN_multiple_expansions_require_auxiliary_classes_THEN_identically_named_classes_dont_clash() throws Exception {
        // pre-load contents to package store
        localStoreContentPath =
                Paths.get(TemplateEngineIntegTest.class.getResource("dependent").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
        renameTestTransformerJarsToTransformerJars(localStoreContentPath.resolve("artifacts"));

        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("templatingDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                verifyDependentDeployment();
                firstDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineIntegTest");

        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();
        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("ADependent", "1.0.0");
        componentsToMerge.put("BDependent", "1.0.0");

        Map<String, ConfigurationUpdateOperation> updateConfig = new HashMap<>();

        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("templatingDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .configurationUpdate(updateConfig)
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        assertTrue(firstDeploymentCDL.await(10, TimeUnit.SECONDS), "Templating deployment did not succeed");
    }

    // reach into component store to verify
    private void verifyLoggerDeployment() {
        try (Stream<Path> files = Files.walk(kernel.getNucleusPaths().recipePath())) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!Files.isDirectory(r)) {
                    ComponentRecipe recipe = getRecipeSerializer().readValue(r.toFile(), ComponentRecipe.class);
                    switch (recipe.getComponentName()) {
                        case "LoggerA": {
                            assertEquals("echo Logger A says hi",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            assertEquals("sleep 5 && echo Logger A says hi",
                                    recipe.getManifests().get(1).getLifecycle().get("run"));
                            break;
                        }
                        case "LoggerB": {
                            assertEquals("echo Ping pong its a default message && echo %DATE% %TIME%",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            assertEquals("sleep 3 && echo Ping pong its a default message ; echo `date`",
                                    recipe.getManifests().get(1).getLifecycle().get("run"));
                            break;
                        }
                        case "LoggerC": {
                            assertEquals("echo Hello from Logger C && echo %DATE% %TIME%",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            assertEquals("sleep 10 && echo Hello from Logger C ; echo `date`",
                                    recipe.getManifests().get(1).getLifecycle().get("run"));
                            break;
                        }
                        case "A": {
                            assertEquals("echo Param1: hello Param2: world",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            break;
                        }
                        default: {
                            fail("Found recipe file other than loggers A,B,C");
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    private void verifyDependentDeployment() {
        try (Stream<Path> files = Files.walk(kernel.getNucleusPaths().recipePath())) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!Files.isDirectory(r)) {
                    ComponentRecipe recipe = getRecipeSerializer().readValue(r.toFile(), ComponentRecipe.class);
                    switch (recipe.getComponentName()) {
                        case "ADependent": {
                            assertEquals("echo Field: field Integer: 14",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            break;
                        }
                        case "BDependent": {
                            assertEquals("echo Field: folddlof Integer: 42",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            break;
                        }
                        default: {
                            fail("Found recipe file other than ADependent, BDependent");
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            fail(e);
        }
    }

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), Deployment.DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }

    private void renameTestTransformerJarsToTransformerJars(Path artifactsDir) throws IOException {
        try (Stream<Path> files = Files.walk(artifactsDir)) {
            for (Path r : files.collect(Collectors.toList())) {
                if (!r.toFile().isDirectory() && "transformer-tests.jar".equals(r.getFileName().toString())) {
                    Files.move(r, r.resolveSibling("transformer.jar"), REPLACE_EXISTING);
                }
            }
        }
    }
}
