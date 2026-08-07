package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Tests that the plugin works with Gradle's configuration cache. */
class ConfigurationCacheFunctionalTest : KotlinPluginFunctionalTest() {
  @BeforeEach
  fun setup() {
    buildFile.appendText(
      """
      plugins {
          `java-library`
          id("org.checkerframework")
      }
      repositories {
          mavenCentral()
      }

      """
        .trimIndent()
    )
  }

  @Test
  fun `test configuration cache is stored and reused`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val firstResult = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then
    assertThat(firstResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(firstResult.output).contains("Note: Checker Framework $TEST_CF_VERSION")
    assertThat(firstResult.output).contains(CONFIGURATION_CACHE_STORED)

    // when the build is run again from a clean output directory
    testProjectDir.resolve("build/classes").deleteRecursively()
    val secondResult = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then
    assertThat(secondResult.output).contains(CONFIGURATION_CACHE_REUSED)
    assertThat(secondResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondResult.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test configuration cache with a failing checker`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeNullnessFailure()

    // when
    val firstResult = testProjectDir.buildWithArgsAndFail("compileJava", "--configuration-cache")

    // then
    assertThat(firstResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(firstResult.output).contains(NULLNESS_FAILURE)
    assertThat(firstResult.output).contains(CONFIGURATION_CACHE_STORED)

    // when
    val secondResult = testProjectDir.buildWithArgsAndFail("compileJava", "--configuration-cache")

    // then
    assertThat(secondResult.output).contains(CONFIGURATION_CACHE_REUSED)
    assertThat(secondResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(secondResult.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test configuration cache with an explicit processor`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        extraJavacArgs = listOf("-Anomsgtext","-Afilenames")
        checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
      }
      tasks.named<JavaCompile>("compileJava") {
        options.compilerArgs.add("-processor")
        options.compilerArgs.add("org.checkerframework.checker.nullness.NullnessChecker")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeTaintingFailure()

    // when
    testProjectDir.buildWithArgsAndFail("compileJava", "--configuration-cache")
    val result = testProjectDir.buildWithArgsAndFail("compileJava", "--configuration-cache")

    // then
    assertThat(result.output).contains(CONFIGURATION_CACHE_REUSED)
    assertThat(result.output).contains("Note: NullnessChecker is type-checking")
    assertThat(result.output).contains(TAINTING_FAILURE)
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
  }

  @Test
  fun `test configuration cache is invalidated by -PskipCheckerFramework`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val firstResult = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then
    assertThat(firstResult.output).contains("Note: Checker Framework $TEST_CF_VERSION")

    // when the property is added, the configuration cache entry must not be reused
    testProjectDir.resolve("build/classes").deleteRecursively()
    val secondResult =
      testProjectDir.buildWithArgs("compileJava", "--configuration-cache", "-PskipCheckerFramework")

    // then
    assertThat(secondResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondResult.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test configuration cache is invalidated when the checkers change`() {
    val buildFileText = buildFile.readText()
    buildFile.writeText(
      buildFileText +
        """
        configure<CheckerFrameworkExtension> {
          version = "$TEST_CF_VERSION"
          extraJavacArgs = listOf("-Afilenames")
          checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
        }
        """
          .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val firstResult = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then
    assertThat(firstResult.output).containsMatch("Note: TaintingChecker is type-checking")

    // when the set of checkers changes
    buildFile.writeText(
      buildFileText +
        """
        configure<CheckerFrameworkExtension> {
          version = "$TEST_CF_VERSION"
          extraJavacArgs = listOf("-Afilenames")
          checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        }
        """
          .trimIndent()
    )
    val secondResult = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then
    assertThat(secondResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(secondResult.output).containsMatch("Note: NullnessChecker is type-checking")
  }

  @Test
  fun `test configuration cache with lombok`() {
    buildFile.delete()
    buildFile =
      testProjectDir.resolve("build.gradle.kts").apply {
        writeText(
          """
          import org.checkerframework.plugin.gradle.*

          plugins {
              `java-library`
              id("org.checkerframework")
              id("io.freefair.lombok").version("9.2.0")
          }
          repositories {
              mavenCentral()
          }

          configure<CheckerFrameworkExtension> {
            version = "$TEST_CF_VERSION"
            checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
            extraJavacArgs = listOf("-Aversion")
          }
          """
            .trimIndent()
        )
      }
    // given
    testProjectDir.writeLombokExample()

    // when
    testProjectDir.buildWithArgsAndFail("build", "--configuration-cache")
    val result = testProjectDir.buildWithArgsAndFail("build", "--configuration-cache")

    // then
    assertThat(result.output).contains(CONFIGURATION_CACHE_REUSED)
    assertThat(result.output)
      .contains(
        "User.java:9: error: [argument] incompatible argument for parameter y of FooBuilder.y."
      )
    assertThat(result.output)
      .contains("Foo.java:12: error: [assignment] incompatible types in assignment.")
  }

  @Test
  fun `test configuration cache with missing checkers`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava", "--configuration-cache")

    // then
    assertThat(result.output).contains("Must specify checkers for the Checker Framework.")
  }
}
