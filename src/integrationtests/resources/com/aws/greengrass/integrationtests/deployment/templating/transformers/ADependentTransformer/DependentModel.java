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
