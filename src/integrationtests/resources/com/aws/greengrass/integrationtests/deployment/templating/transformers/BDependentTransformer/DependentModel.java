/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment.templating.transformers.BDependentTransformer;

public class DependentModel {
    private final CustomString field;
    private final Integer integer;

    public String getField() {
        return field.get();
    }

    public Integer getInteger() {
        return integer;
    }

    public DependentModel(String field, Integer integer) {
        this.field = CustomString.of(field);
        this.integer = integer;
    }
}
