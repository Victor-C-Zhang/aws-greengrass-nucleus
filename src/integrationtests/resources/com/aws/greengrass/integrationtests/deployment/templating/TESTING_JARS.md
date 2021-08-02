## Summary of testing jars

### `error-transformer.jar`
- [ErrorTransformer](transformers/ErrorTransformer.java)

### `multiple-transformer.jar`
- [EchoTransformer](transformers/EchoTransformer.java)
- [ErrorTransformer](transformers/ErrorTransformer.java)

### `no-implemented-transformer.jar`
- [ErrorTransformer](transformers/ErrorTransformer.java) modified to not inherit from
  [RecipeTransformer](../../../../../../../../main/java/com/aws/greengrass/deployment/templating/RecipeTransformer.java)

### `LoggerTemplate` transformer jar
- [LoggerTransformer](transformers/LoggerTransformer.java)

### `ATemplate` transformer jar
- [EchoTransformer](transformers/EchoTransformer.java)
