package com.aws.greengrass.integrationtests.deployment.templating.transformers.BDependentTransformer;

public class CustomString {
    private final String val;

    public CustomString(String val) {
        this.val = val + (new StringBuilder()).append(val).reverse();
    }

    static CustomString of(String s) {
        return new CustomString(s);
    }

    public String get() {
        return val;
    }
}
