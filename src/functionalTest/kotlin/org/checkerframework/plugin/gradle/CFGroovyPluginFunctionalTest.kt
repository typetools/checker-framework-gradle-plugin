package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CFGroovyPluginFunctionalTest : GroovyPluginFunctionalTest() {
  @BeforeEach
  fun setup() {
    buildFile.appendText(
      """
      plugins {
          id("java")
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
  fun `test compileJava before checkerFramework`() {
    buildFile.appendText(
      """
      compileJava{}
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Aversion"]
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test disabling CF for one task`() {
    buildFile.appendText(
      """
      compileJava {
        options.checkerFrameworkCompile.enabled = false
      }
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Aversion"]
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeNullnessFailure()

    // when
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
    assertThat(result.output).doesNotContain(NULLNESS_FAILURE)
  }

  @Test
  fun `test excludeTestsTrue`() {
    buildFile.appendText(
      """
       compileTestJava{}

      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.tainting.TaintingChecker"]
        excludeTests = true
        extraJavacArgs = ["-Afilenames"]
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()
    testProjectDir.writeTestClass()

    // when
    val result = testProjectDir.buildWithArgs("compileTestJava")

    // then

    assertThat(result.task(":compileTestJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).containsMatch("Note: TaintingChecker is type-checking .*Success.java")
    assertThat(result.output)
      .doesNotContainMatch("Note: TaintingChecker is type-checking .*Test.java")
  }
}
