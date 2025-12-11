package org.checkerframework.plugin.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class CheckerFrameworkExtension {
  /**
   * Which checkers will be run. Each element is a fully-qualified class name, such as
   * "org.checkerframework.checker.nullness.NullnessChecker".
   */
  abstract val checkers: ListProperty<String>

  /** A list of extra options to pass directly to javac when running typecheckers. */
  abstract val extraJavacArgs: ListProperty<String>

  /**
   * Which version of the Checker Framework to use. Or if set to "local", then the Checker Framework
   * at the CHECKERFRAMEWORK environment variable is use.
   */
  abstract val version: Property<String>

  /** Whether to avoid running the Checker Framework on tests. */
  abstract val excludeTests: Property<Boolean>

  /**
   * If true, generate @SuppressWarnings("all") annotations on Lombok-generated code, which is
   * Lombok's default but could permit unsoundness from the Checker Framework. For an example, see
   * https://github.com/kelloggm/checkerframework-gradle-plugin/issues/85. Defaults to false.
   */
  abstract val suppressLombokWarnings: Property<Boolean>

  /**
   *      Flag to disable automatic incremental compilation. By default, the Checker Framework
   *      assumes that all checkers are incremental with type "isolating". Gradle's documentation
   *      suggests that annotation processors that interact with Javac APIs might crash because
   *      Gradle wraps some Javac APIs, so if you encounter such a crash you can disable incremental
   *      compilation using this flag. (Defaults to true.)
   */
  abstract val incrementalize: Property<Boolean>

  /** Disables the Checker Framework. */
  abstract val skipCheckerFramework: Property<Boolean>

  // If you encounter "zip file too large" errors, you can set this flag to avoid
  // the standard version check which unzips a jar to look at its manifest.
  @Deprecated(message = "This option is no longer needed.") var skipVersionCheck: Boolean = false
}
