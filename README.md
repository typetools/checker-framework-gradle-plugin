# Checker Framework Gradle Plugin

This plugin configures `JavaCompile` tasks to use the [Checker
Framework](https://checkerframework.org) for pluggable type-checking.

## Apply the plugin

Add the following to your `build.gradle` file:

```groovy
plugins {
  // Checker Framework pluggable type-checking
  id("org.checkerframework").version("0.6.60")
}
```

The `org.checkerframework` plugin modifies existing Java
compilation tasks. You should apply it *after*
whatever plugins introduce your Java compilation tasks (usually the `java`
or `java-library` plugin for non-Android builds).

## Configuration

### Configuring which checkers to use

The `checkerFramework.checkers` property lists which checkers will be run.

For example, using Groovy syntax in a `build.gradle` file:

```groovy
checkerFramework {
  checkers = [
    "org.checkerframework.checker.nullness.NullnessChecker",
    "org.checkerframework.checker.units.UnitsChecker"
  ]
}
```

The same example, using Kotlin syntax in a `build.gradle.kts` file:

```kotlin
// In Kotlin, you need to import CheckerFrameworkExtension explicitly:
import org.checkerframework.plugin.gradle.CheckerFrameworkExtension

configure<CheckerFrameworkExtension> {
    checkers = listOf(
        "org.checkerframework.checker.nullness.NullnessChecker",
        "org.checkerframework.checker.units.UnitsChecker"
    )
}
```

For a list of checkers, see the [Checker Framework Manual](https://checkerframework.org/manual/#introduction).

### Providing additional options to the compiler

You can set the `checkerFramework.extraJavacArgs` property in order to pass
additional options to the compiler when running a pluggable type-checker.

For example, to treat all warnings as errors and to use a stub file:

```groovy
checkerFramework {
  extraJavacArgs = [
    "-Werror",
    "-Astubs=/path/to/my/stub/file.astub"
  ]
}
```

### Specifying a Checker Framework version

Version 0.6.60 of this plugin uses Checker Framework version 3.51.1 by default.
Anytime you upgrade to a newer version of this plugin,
it might use a different version of the Checker Framework.

You can use a Checker Framework
[version](https://github.com/typetools/checker-framework/releases) that is
different from this plugin's default.  For example, if you want to use Checker
Framework version 3.52.0, then you should add the following text to the Checker Framework configuration block
`build.gradle`, after `apply plugin: "org.checkerframework"`:

```groovy
  version = "3.52.0"
```

You may also set the version to `"local"` which will use a locally-built version of the Checker Framework specified by the `CHECKERFRAMEWORK` environment variable.

```groovy
  version = "local"
```

Or you can run a Gradle task with and pass `"-PcfLocal"` so that the local version will be used for that task.

You can also directly specify which checker and checker-qual jars to use:

```groovy
ext {
    versions = [
        eisopVersion: "3.42.0-eisop1",
    ]
}

dependencies {
    checkerQual("io.github.eisop:checker-qual:${versions.eisopVersion}")
    checkerFramework("io.github.eisop:checker:${versions.eisopVersion}")
}
```

You should also use a `checkerFramework` dependency for anything needed by a
checker you are running. For example, if you are using the [Subtyping
Checker](https://checkerframework.org/manual/#subtyping-checker) with custom
type qualifiers, you should add a `checkerFramework` dependency referring to the
definitions of the custom qualifiers.


### Incremental compilation

By default, the plugin assumes that all checkers are ["isolating incremental
annotation
processors"](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing).
This assumption speeds up builds by enabling incremental compilation.

Gradle's documentation warns that incremental compilation with the Checker
Framework plugin (or any other plugin that uses internal Javac APIs) may crash,
because Gradle wraps some of those APIs.  Such a crash would appear as a
`ClassCastException` referencing some internal Javac class. If you encounter
such a crash, you can disable incremental compilation in your build using the
following code in your `checkerFramework` configuration block:

```groovy
  checkerFramework {
    incrementalize = false
  }
```

### Disabling the Checker Framework for a specific compile task

You can disable the Checker Framework for specific tasks.
This can be useful for skipping the Checker Framework on generated code:

```build.gradle
tasks.withType(JavaCompile).configureEach {
  // Don't run the checker on generated code.
  if (name.equals("compileMainGeneratedDataTemplateJava")
      || name.equals("compileMainGeneratedRestJava")) {
    checkerFramework {
      enabled = false
    }
  }
}
```

Currently, the only supported option is `enabled`.

Also see the `excludeTests` configuration variable, described below.

### Other options

* You can disable the Checker Framework temporarily (e.g. when testing something
  unrelated) either in your build file or from the command line. In your build
  file:

  ```groovy
  checkerFramework {
    skipCheckerFramework = true
  }
  ```

  From the command line, add `-PskipCheckerFramework` to your gradle invocation.
  This property can also take an argument:
  anything other than `false` results in the Checker Framework being skipped.

* By default, the plugin applies the selected checkers to all `JavaCompile` targets,
  including test targets such as `testCompileJava`.

  Here is how to prevent checkers from being applied to test targets:

  ```groovy
  checkerFramework {
    excludeTests = true
  }
  ```

  The check for test targets is entirely syntactic: this option will not apply
  the checkers to any task whose name includes "test" or "Test".

* If you encounter errors of the form `zip file name too long` when configuring your
  Gradle project, you can use the following code to skip this plugin's version check,
  which reads the manifest file of the version of the Checker Framework you are actually
  using:

  ```groovy
  checkerFramework {
    skipVersionCheck = true
  }
  ```

### Multi-project builds

In a project with subprojects, you should apply the project to each Java
subproject (and to the top-level project, in the unlikely case that it is a Java
project).  Here are two approaches.

**Approach 1:**
All Checker Framework configuration (the `checkerFramework` block and any
`dependencies`) remains in the top-level `build.gradle` file.  Put it in a
`subprojects` block (or an `allprojects` block in the unlikely case that the
top-level project is a Java project).  For example, in Groovy syntax:

```groovy
plugins {
  id("org.checkerframework").version("0.6.60")
}

subprojects { subproject ->
  apply plugin: "org.checkerframework"

  checkerFramework {
    checkers = ["org.checkerframework.checker.index.IndexChecker"]
    version = "3.52.2"
  }
}
```

**Approach 2:**
Apply the plugin in the `build.gradle` in each subproject as if it
were a stand-alone project. You must do this if you require different configuration
for different subprojects (for instance, if you want to run different checkers).

### Incompatibility with Error Prone 2.3.4 and earlier

To use both [Error Prone](https://errorprone.info/) and the Checker Framework,
you need to use Error Prone version 2.4.0 (released in May 2020) or later.

## Modules

The Checker Framework inserts inferred annotations into bytecode even if none
appear in source code, so you must make them known to the compiler even if you
write no annotations in your code.  When running the plugin on a Java project
that uses modules, you need to add annotations to the module path.

Add following to your `module-info.java`:

```java
requires org.checkerframework.checker.qual;
```

The addition of `requires` is typically enough.

If it does not fix your compilation issues, you can additionally add the `checker-qual.jar`
artifact (which only contains annotations) to the module path:

```groovy
checkerFramework {
  configurations.compileOnly.setCanBeResolved(true)
  extraJavacArgs = [
    "--module-path", configurations.compileOnly.asPath
  ]
}
```

## Lombok compatibility

This plugin automatically interacts with
the [Lombok Gradle Plugin](https://plugins.gradle.org/plugin/io.freefair.lombok)
to delombok your source code before it is passed to the Checker Framework
for typechecking. This plugin does not support any other use of Lombok.

For the Checker Framework to work properly on delombok'd source code,
you must include the following key in your project's `lombok.config` file:

```config
lombok.addLombokGeneratedAnnotation = true
```

By default, Lombok suppresses all warnings in the code it generates. If you
want to typecheck the code that Lombok generates, use the `suppressLombokWarnings`
configuration key:

```gradle
checkerFramework {
  suppressLombokWarnings = false
}
```

Note that doing so will cause *all* tools (including Javac itself) to begin issuing
warnings in the code that Lombok generates.

## Using a locally-built plugin

To use a locally-modified version of the plugin:

1. Publish the plugin to your local Maven repository:

   ```sh
   ./gradlew publishToMavenLocal
   ```

2. Add the following to the `settings.gradle` file in
   the Gradle project that you want to use the plugin:

   ```gradle
   pluginManagement {
       repositories {
           mavenLocal()
           gradlePluginPortal()
       }
   }
   ```
