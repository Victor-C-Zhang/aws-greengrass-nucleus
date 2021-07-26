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
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
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
        // pre-load contents to package store
        Path localStoreContentPath = Paths.get(TemplateEngineIntegTest.class.getResource(".").toURI());
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
    void WHEN_a_deployment_has_templates_THEN_they_are_expanded_properly() throws Exception {
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

        String recipeDir = getClass().getResource("recipes").getPath();
        String artifactsDir = getClass().getResource("artifacts").getPath();

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("LoggerA", "1.0.0");
        componentsToMerge.put("LoggerB", "1.0.0");
        componentsToMerge.put("LoggerC", "1.0.0");
        //        componentsToMerge.put("LoggerTemplate", "1.0.0");

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
                            assertEquals(
                                    "for ((i=30; i>0; i--)); do\n  sleep 5 &\n  echo Logger A says hi\n  wait\ndone",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            break;
                        }
                        case "LoggerB": {
                            assertEquals(
                                    "for ((i=30; i>0; i--)); do\n  sleep 3 &\n  echo Ping pong its a default "
                                            + "message ; echo `date`\n  wait\ndone",
                                    recipe.getManifests().get(0).getLifecycle().get("run"));
                            break;
                        }
                        case "LoggerC": {
                            assertEquals(
                                    "for ((i=30; i>0; i--)); do\n  sleep 10 &\n  echo Hello from Logger C ; echo "
                                            + "`date`\n  wait\ndone",
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

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), Deployment.DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }
}
