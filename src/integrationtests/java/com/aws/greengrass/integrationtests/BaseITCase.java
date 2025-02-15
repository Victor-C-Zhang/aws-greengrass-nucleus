/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests;


import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.UniqueRootPathExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

/**
 * This class is a base IT case to simplify the setup for integration tests.
 *
 * It creates a temp directory and sets it to "root" before each @Test.
 *
 * However, individual integration test could override the setup or just set up without extending this.
 */
@ExtendWith({GGExtension.class, UniqueRootPathExtension.class})
public class BaseITCase {

    protected Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        tempRootDir = Paths.get(System.getProperty("root"));
        LogConfig.getRootLogConfig().reset();
    }

    public static void setDeviceConfig(Kernel kernel, String key, Number value) {
        kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                        key).withValue(value);
    }

}
