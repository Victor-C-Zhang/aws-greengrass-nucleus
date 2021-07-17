/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentDocumentDownloader;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.deployment.templating.exceptions.TemplateExecutionException;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class TemplateEngineTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(TemplateEngineTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;
    @Mock
    private ComponentStore mockComponentStore;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        kernel = new Kernel();
        kernel.getContext().put(DeploymentDocumentDownloader.class, deploymentDocumentDownloader);
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                TemplateEngineTest.class.getResource("../onlyMain.yaml"));

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
        // pre-load contents to package store
        localStoreContentPath =
                Paths.get(TemplateEngineTest.class.getResource(".").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void integTest() throws Exception {

        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {
            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                firstDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineTest" );

        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("A", "1.0.0");
        componentsToMerge.put("ATemplate", "1.0.0");

        String dependencyUpdateConfigString =
                "{" +
                "  \"MERGE\": {" +
                "    \"param1\" : \"New param 1\"," +
                "    \"param2\" : \"New param2\"" +
                "  }," +
                "  \"RESET\": [" +
                "    \"/resetParam1\", \"/resetParam2\"" +
                "  ]" +
                "}";
        Map<String, ConfigurationUpdateOperation> updateConfig = new HashMap<>();
        updateConfig.put("A",
                OBJECT_MAPPER.readValue(dependencyUpdateConfigString, ConfigurationUpdateOperation.class));

        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("firstDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .configurationUpdate(updateConfig)
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        assertTrue(firstDeploymentCDL.await(10, TimeUnit.SECONDS), "First deployment did not succeed");
    }

    @Test
    @SuppressWarnings("PMD.PrematureDeclaration")
    void unitTest() throws PackagingException, TemplateExecutionException, IOException, RecipeTransformerException {
        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {
            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                firstDeploymentCDL.countDown();
            }
            return true;
        },"TemplateEngineTest" );

        Path recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath();
        Path recipeWorkDir = localStoreContentPath.resolve("_recipes_out");
        try {
            Files.createDirectory(recipeWorkDir);
        } catch (FileAlreadyExistsException e) {}

        Path artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath();
        Path artifactsWorkDir = localStoreContentPath.resolve("_artifacts_out");
        try {
            Files.createDirectory(artifactsWorkDir);
        } catch (FileAlreadyExistsException e) {}

        // if there are files, delete them first
        for (File file : Objects.requireNonNull(recipeWorkDir.toFile().listFiles())) {
            if (!file.delete()) {
                throw new IOException("Could not delete work file " + file.getAbsolutePath());
            }
        }
        Files.walk(artifactsWorkDir).collect(Collectors.toList()).forEach(source -> source.toFile().delete());

        // copy files to work directories
        for (File file : Objects.requireNonNull(recipeDir.toFile().listFiles())) {
            System.out.println(file.getPath());
            System.out.println(recipeWorkDir.resolve(file.getName()));
            Files.copy(Paths.get(file.getPath()), recipeWorkDir.resolve(file.getName()));
        }
        Files.walk(artifactsDir).collect(Collectors.toList()).forEach(source -> {
            try {
                Files.copy(source,
                        artifactsWorkDir.resolve(artifactsDir.relativize(source)), REPLACE_EXISTING);
            } catch (IOException e) {
                logger.atWarn().setCause(e).log();
            }
        });

        TemplateEngine templateEngine = new TemplateEngine(mockComponentStore);
        templateEngine.init(recipeWorkDir, artifactsWorkDir);
        templateEngine.process();
    }

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }

    void WHEN_components_are_loaded_THEN_build_queue_is_populated() {

    }

    void WHEN_provided_with_bad_load_dependencies_THEN_throw_error() {
        // multiple dependency
        // templates depending on templates
    }

    void WHEN_expansion_called_then_component_store_is_involved() {

    }

    void GIVEN_expanded_templates_THEN_template_components_are_deleted() {

    }
}

