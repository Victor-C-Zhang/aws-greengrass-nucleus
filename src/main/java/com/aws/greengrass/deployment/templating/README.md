# Quick Start Guide
## Creating the template component
If you have a pre-built template component, you can skip this step.

Every template must include an artifact that does the actual template expansion.
This will be a java class extends the [`RecipeTransformer`](RecipeTransformer.java) class.
1. Create and implement your custom transformer class by extending `RecipeTransformer`. There are two functions to 
   implement, and both are documented in [***RecipeTransformer***](RecipeTransformer.java).
2. Jar your class in a file named `transformer.jar`. It is *VERY IMPORTANT* that your jar be named such.
3. Create a recipe file for your template. For now, make sure your component name ends in `Template`. This 
   requirement will change in the future once some more work is done in the `EvergreenComponentsCommon` repo. Add the 
   name of your custom transformer class to the configuration section:
```yaml
RecipeFormatVersion: 2020-01-25
ComponentName: MyCustomTemplate # <- make sure this ends in "Template"
ComponentVersion: '0.0.0'
ComponentConfiguration:
   DefaultConfiguration:
      transformerClass: MyCustomTransformerClass # <- insert path here
```
4. Declare a _parameter schema_ if necessary. Each parameter can be declared as one of the standard JSON types: 
   `string`, `number`, `object`, `array`, `boolean`, or `null`. Parameters can also be declared as either _required_ or
   _optional_. If you declare a parameter optional, you must also provide a default value for that parameter!
```yaml
ComponentConfiguration:
   DefaultConfiguration:
      parameterSchema:
         stringParam:
            type: string
            required: true
         booleanParam:
            type: boolean
            required: true
         optionalParam:
            type: string
            required: false
      parameters:
         optionalParam: Here is a default value for an optional param
```
5. Finally, fill in a `manfiests` section with dummy information. It doesn't matter what you put, since the template 
   will never be deployed, so doesn't have a lifecycle.
```yaml
Manifests:
   - Platform:
        os: all
     Lifecycle:
        Run: echo This will never be run
```
6. Add this created recipe and `transformer.jar` artifact to your local deployment directory.

## Creating a Parameter File
This involves filling in the parameter schema declared in the template and declaring a dependency on your template. The 
following is an example of a parameter file:
```yaml
RecipeFormatVersion: '2020-01-25'
ComponentName: MyTemplatedComponent
ComponentVersion: '1.0.0'
ComponentDependencies:
  MyCustomTemplate:
    VersionRequirement: '0.0.0'
ComponentConfiguration:
  DefaultConfiguration:
    stringParam: Foo
    booleanParam: true
    optionalParam: not needed, but if you provide a value it will override the template default

```
Add it to your local recipe directory. When deployment runs, this will expand into a `MyTemplatedComponent` component.