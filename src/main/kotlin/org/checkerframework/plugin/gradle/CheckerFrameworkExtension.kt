package org.checkerframework.plugin.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class CheckerFrameworkExtension {
  /**
   * Which checkers will be run. Each element is a fully-qualified class name, such as
   * "org.checkerframework.checker.nullness.NullnessChecker".
   */
  abstract val checkers: ListProperty<String>

  /** A list of extra command-line options to pass to javac when running a typechecker. */
  abstract val extraJavacArgs: ListProperty<String>

  /**
   * Which version of the Checker Framework to use. If set to "local", then use the Checker
   * Framework at the `$CHECKERFRAMEWORK` environment variable. If set to "disable", then don't use
   * the Checker Framework. If set to "dependencies", then use the Checker Framework jars provided
   * in the checkerFramework and checkerQual configurations.
   */
  abstract val version: Property<String>

  /** If true, don't run the Checker Framework on tests. */
  abstract val excludeTests: Property<Boolean>

  /**
   * If true, enable automatic incremental compilation. By default, the Checker Framework assumes
   * that all checkers are incremental with type "isolating". Gradle's documentation suggests that
   * annotation processors that interact with Javac APIs might crash because Gradle wraps some Javac
   * APIs. If you encounter such a crash, you can disable incremental compilation using this flag.
   * (Defaults to true.)
   */
  abstract val incrementalize: Property<Boolean>
}
