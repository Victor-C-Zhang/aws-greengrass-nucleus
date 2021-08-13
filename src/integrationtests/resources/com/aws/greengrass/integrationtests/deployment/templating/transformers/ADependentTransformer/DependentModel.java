/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating.transformers.ADependentTransformer;

public class DependentModel {
    private final String field;
    private final Integer integer;

    public String getField() {
        return field;
    }

    public Integer getInteger() {
        return integer;
    }

    public DependentModel(String field, Integer integer) {
        this.field = field;
        this.integer = integer;
    }
}
