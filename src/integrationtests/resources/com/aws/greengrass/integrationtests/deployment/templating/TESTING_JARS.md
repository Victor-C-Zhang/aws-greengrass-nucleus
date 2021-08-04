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

### `ADependentTemplate` transformer jar
- [DependentTransformer](transformers/ADependentTransformer/DependentTransformer.java)
- [DependentModel](transformers/ADependentTransformer/DependentModel.java)

### `BDependentTemplate` transformer jar
- [DependentTransformer](transformers/BDependentTransformer/DependentTransformer.java) (different from the file in 
  `ADependentTemplate`)
- [DependentModel](transformers/BDependentTransformer/DependentModel.java) (different from the file in 
  `ADependentTemplate`)
- [CustomString](transformers/BDependentTransformer/CustomString.java)
