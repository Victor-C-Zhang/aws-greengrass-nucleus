package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.deployment.templating.exceptions.RecipeTransformerException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterException;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

public class RecipeTransformerTest {

    @Test
    void IF_template_config_is_acceptable_THEN_it_works() {
        // no KVs generated for non-optional fields
    }

    void IF_template_config_has_invalid_schema_THEN_throw_error() {
        // schema is missing
        // schema is different
    }

    void IF_template_config_default_values_are_invalid_THEN_throw_error() {
        // missing value for optional parameter
        // value doesn't match schema
        // missing or extra values in template file
    }

    void GIVEN_template_config_read_in_WHEN_provided_invalid_parameters_THEN_throw_error() {
        // missing parameter
        // value doesn't match schema
        // extra parameter
    }

    void GIVEN_template_config_read_in_WHEN_provided_component_configs_THEN_they_are_merged_properly() {
        // generated jsonnode is good. most of the merge testing happens in WHEN_mergeParams_is_called_THEN_it_works()
    }

    void WHEN_mergeParam_is_called_THEN_it_works() {
        // nullity of one or more inputs
        // default has value that custom does not
        // custom has value that default does not
        // default and custom both have a value
        // default and custom have different capitalizations for the same field
    }

    static class FakeRecipeTransformer extends RecipeTransformer {
        public FakeRecipeTransformer(ComponentRecipe templateRecipe) throws TemplateParameterException {
            super(templateRecipe);
        }

        @Override
        protected JsonNode initTemplateSchema() throws TemplateParameterException {
            return null;
        }

        @Override
        public Pair<ComponentRecipe, List<Path>> transform(ComponentRecipe paramFile, JsonNode componentParams)
                throws RecipeTransformerException {
            return null;
        }
    }
}
