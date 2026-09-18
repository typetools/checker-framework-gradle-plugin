package org.checkerframework.plugin.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class CheckerFrameworkExtension {
  /**
   * Which checkers will be run. Each element is a fully qualified class name, such as
   * "org.checkerframework.checker.nullness.NullnessChecker".
   */
  abstract val checkers: ListProperty<String>

  /** A list of extra command-line options to pass to javac when running a typechecker. */
  abstract val extraJavacArgs: ListProperty<String>

  /**
   * Which version of the Checker Framework to use. If set to "local", then use the Checker
   * Framework at the `$CHECKERFRAMEWORK` environment variable. If set to "dependencies", then use
   * the Checker Framework jars provided in the checkerFramework and checkerQual configurations.
   */
  abstract val version: Property<String>

  /** If true, do not run the Checker Framework on tests. */
  abstract val excludeTests: Property<Boolean>

  /**
   * If true, enable automatic incremental compilation. By default, this plugin assumes that all
   * checkers are incremental annotation processors of type "isolating". Gradle's documentation
   * warns that annotation processors that use internal javac APIs might crash, because Gradle wraps
   * some of those APIs. If you encounter such a crash, use this flag to disable incremental
   * compilation. (Defaults to true.)
   */
  abstract val incrementalize: Property<Boolean>

  /** If true, do not run the Checker Framework. (Defaults to false.) */
  abstract val skipCheckerFramework: Property<Boolean>
}
