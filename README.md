# Checker Framework Gradle Plugin

This plugin configures `JavaCompile` tasks to use the [Checker
Framework](https://checkerframework.org) for pluggable type-checking.

## Apply the plugin

Add the following to your `build.gradle` file:

```groovy
plugins {
  // Checker Framework pluggable type-checking
  id("org.checkerframework").version("1.0.0")
}
```

If you are upgrading from plugin version 0.x to 1.x, see the [migration
guide](#migrating-from-0x-to-1x).

The plugin supports Gradle versions 7.3 and above, which requires Java 11 and
above.  Although you must compile your project using at least Java 11, the
compiled classfiles can be compatible with, and can run on, any version of Java.

## Configuration

### The Checker Framework version

You must specify which
[version](https://github.com/typetools/checker-framework/releases) of the
Checker Framework to use.

* The recommended way is to modify two files.  Add this to `build.gradle`:

  ```groovy
  checkerFramework {
    version = libs.checker.get().version
  }
  ```

  and add this to `gradle/libs.versions.toml`:

  ```toml
  [libraries]
  checker = "org.checkerframework:checker:3.53.0"
  ```

* Alternately, you can edit just one file.  Add this to `build.gradle`:

  ```groovy
  checkerFramework {
    version = "3.53.0"
  }
  ```

The special value **"local"** means to use a locally-built version of the Checker
Framework, found at environment variable `$CHECKERFRAMEWORK`.

The special value **"disable"** means not to use the Checker Framework.

The command-line argument **`-PcfVersion=...`** (where "..." is a version number,
"local", or "disable") overrides settings in gradle buildfiles.

#### Checker Framework jar files

Alternately, you can directly specify which checker and checker-qual jars to
use. You must also set the Checker Framework version to the special value
**`"dependencies"`**.  Put the following in your `build.gradle` file:

```groovy
checkerFramework {
  version = "dependencies"
}

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

### Which checkers to run

You must specify which checkers to run using `checkerFramework.checkers` property.

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

#### Checker dependencies

If a checker you are running has any dependencies, use a `checkerFramework` dependency:

```groovy
dependencies {
    checkerFramework("...")
}
```

For example, if you are using the
[Subtyping Checker](https://checkerframework.org/manual/#subtyping-checker) with
custom type qualifiers, you should add a `checkerFramework` dependency referring
to the definitions of the custom qualifiers.

### Providing additional options to the compiler

You can set the `checkerFramework.extraJavacArgs` property to pass
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

## Disabling the Checker Framework

You can completely disable the Checker Framework (e.g., when testing something
unrelated) either in your build file or from the command line.

In your build file:

```groovy
checkerFramework {
  version = "disable"
}
```

From the command line, add `-PcfVersion=disable` to your gradle invocation.

### Disabling the Checker Framework for tests

By default, the plugin applies the selected checkers to all `JavaCompile` targets,
including test targets such as `testCompileJava`.

Here is how to prevent checkers from being applied to test targets:

```groovy
checkerFramework {
  excludeTests = true
}
```

A "test target" is one that contains "test" or "Test" as a word.
Words are determined using camelCase; underscores (`_`) also delimit words.

### Disabling the Checker Framework for a specific compile task

You can disable the Checker Framework for specific tasks.
This can be useful for skipping the Checker Framework on generated code:

```groovy
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

The only configuration available on a per-task basis is `enabled`.

## Multi-project builds

In a project with subprojects, you should apply the plugin to each Java
subproject (and to the top-level project, in the unlikely case that it is a Java
project).  Here are two approaches.

### Approach 1

All Checker Framework configuration (the `checkerFramework` block and any
`dependencies`) remains in the top-level `build.gradle` file.  Put it in a
`subprojects` block (or an `allprojects` block in the unlikely case that the
top-level project is a Java project).  For example, in Groovy syntax:

```groovy
plugins {
  id("org.checkerframework").version("1.0.0")
}

subprojects { subproject ->
  apply plugin: "org.checkerframework"

  checkerFramework {
    checkers = ["org.checkerframework.checker.index.IndexChecker"]
    version = "3.53.0"
  }
}
```

### Approach 2

Apply the plugin in the `build.gradle` in each subproject as if it
were a stand-alone project. You must do this if you require different configuration
for different subprojects (for instance, if you want to run different checkers).

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
    "--module-path", configurations.checkerQual.asPath
  ]
}
```

## Lombok compatibility

This plugin automatically interacts with
the [Lombok Gradle Plugin](https://plugins.gradle.org/plugin/io.freefair.lombok)
to delombok your source code before it is passed to the Checker Framework
for type-checking. This plugin does not support any other use of Lombok.

For the Checker Framework to work properly on delombok'd source code,
you must include the following key in your project's `lombok.config` file:

```config
lombok.addLombokGeneratedAnnotation = true
```

By default, Lombok suppresses all warnings in the code it generates. If you
want to typecheck the code that Lombok generates, set the `addSuppressWarnings`
to false:

```config
lombok.addSuppressWarnings = false
```

Note that doing so will cause *all* tools (including Javac itself) to begin issuing
warnings in the code that Lombok generates.

## Using a locally-built plugin

To use a locally-modified version of this plugin:

1. Publish the plugin to your local Maven repository:

   ```sh
   ./gradlew publishToMavenLocal
   ```

2. Add the following to the `settings.gradle` file in
   the Gradle project that you want to use the plugin:

   ```groovy
   pluginManagement {
       repositories {
           mavenLocal()
           gradlePluginPortal()
       }
   }
   ```

## Migrating from 0.x to 1.x

If your project uses version 0.x of the Checker Framework Gradle Plugin,
you need to make some changes in order to use version 1.x.

1. You must specify [a version number](#the-checker-framework-version).

2. You no longer need to add a `checkerFramework` dependency or add
   `checker-qual` to the `compileOnly` or `testCompileOnly` configurations.
   Remove code like the following:

   ```groovy
   dependencies {
     compileOnly("org.checkerframework:checker-qual:${checkerFrameworkVersion}")
     testCompileOnly("org.checkerframework:checker-qual:${checkerFrameworkVersion}")
     checkerFramework("org.checkerframework:checker:${checkerFrameworkVersion}")
   }
   ```

3. These options have been removed:
   * **`skipCheckerFramework`**:  Set the version to `"disable"` to skip the Checker
     Framework.  For example, change command-line argument
     `-PskipCheckerFramework` to `-PcfVersion=disable`, or change

     ```groovy
     checkerFramework {
       skipCheckerFramework = true
     }
     ```

     to

     ```groovy
     checkerFramework {
       version = "disable"
     }
     ```

     Setting the version to "disable" causes the Checker Framework not to be run
     at all.  You can also [disable the checker framework for a specific
     task](#disabling-the-checker-framework-for-a-specific-compile-task).

   * **`cfLocal`**: Set the version to `"local"` to use a locally-built version
     of the Checker Framework.  Change command-line argument
     `-PcfLocal` to `-PcfVersion=local`.

   * **`suppressLombokWarnings`**: Use [Lombok options](#lombok-compatibility)
     to configure interaction with Lombok.

   * **`skipVersionCheck`**: There is no longer a version check that might cause
     "zip file too large" error.  Remove the `-PskipVersionCheck` command-line
     argument and remove Gradle code like

     ```groovy
     checkerFramework {
       skipVersionCheck = true
     }
     ```

4. If you want to use a non-standard Checker Framework jar file (such as that of
    eisop) see [Checker Framework jar files](#checker-framework-jar-files).

## Troubleshooting

### ClassCastException for a javac class

If you encounter a crash with a `ClassCastException` referencing some internal
Javac class, disable incremental compilation in your build using the following
code in your `checkerFramework` configuration block:

```groovy
  checkerFramework {
    incrementalize = false
  }
```

Background:  By default, the plugin assumes that all checkers are ["isolating
incremental annotation
processors"](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing).
This assumption speeds up builds by enabling incremental compilation.  Gradle's
documentation warns that incremental compilation with the Checker Framework
plugin (or any other plugin that uses internal Javac APIs) may crash, because
Gradle wraps some of those APIs.

### Incompatibility with Error Prone 2.3.4 and earlier

To use both [Error Prone](https://errorprone.info/) and the Checker Framework,
you need to use Error Prone version 2.4.0 (released in May 2020) or later.

<!--
LocalWords:  JavaCompile gradle checkerframework checkerFramework toml lombok
LocalWords:  PcfVersion buildfiles qual eisopVersion eisop1 checkerQual config
LocalWords:  kotlin CheckerFrameworkExtension listOf extraJavacArgs Multi eisop
LocalWords:  Werror Astubs testCompileJava excludeTests camelCase classfiles
LocalWords:  withType configureEach compileMainGeneratedDataTemplateJava
LocalWords:  compileMainGeneratedRestJava subprojects allprojects mavenLocal
LocalWords:  delombok addLombokGeneratedAnnotation addSuppressWarnings cfLocal
LocalWords:  publishToMavenLocal pluginManagement gradlePluginPortal
LocalWords:  compileOnly testCompileOnly checkerFrameworkVersion PcfLocal
LocalWords:  skipCheckerFramework PskipCheckerFramework skipVersionCheck
LocalWords:  suppressLombokWarnings PskipVersionCheck
-->
