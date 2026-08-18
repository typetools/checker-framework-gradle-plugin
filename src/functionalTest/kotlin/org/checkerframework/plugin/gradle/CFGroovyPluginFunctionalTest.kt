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
  fun `test applying the plugin after the project is evaluated`() {
    buildFile.writeText(
      """
      plugins {
          id("java")
          id("org.checkerframework") apply false
      }
      repositories {
          mavenCentral()
      }
      gradle.projectsEvaluated {
        apply plugin: "org.checkerframework"
        checkerFramework {
          version = "$TEST_CF_VERSION"
          checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
          extraJavacArgs = ["-Aversion"]
        }
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
  fun `test skipCheckerFramework when applying the plugin after the project is evaluated`() {
    buildFile.writeText(
      """
      plugins {
          id("java")
          id("org.checkerframework") apply false
      }
      repositories {
          mavenCentral()
      }
      gradle.projectsEvaluated {
        apply plugin: "org.checkerframework"
        checkerFramework {
          version = "$TEST_CF_VERSION"
          checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
          extraJavacArgs = ["-Aversion"]
          skipCheckerFramework = true
        }
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
  fun `test skipCheckerFramework after compileJava has been configured`() {
    buildFile.appendText(
      """
      // Configuring the task realizes it, which runs the plugin's configuration of the task before
      // the checkerFramework block below has run.
      compileJava{}
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        skipCheckerFramework = true
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
    assertThat(result.output).doesNotContain(NULLNESS_FAILURE)
  }

  @Test
  fun `test lombok when applying the plugin after the project is evaluated`() {
    buildFile.writeText(
      """
      plugins {
          id("java-library")
          id("io.freefair.lombok") version "9.2.0"
          id("org.checkerframework") apply false
      }
      repositories {
          mavenCentral()
      }
      gradle.projectsEvaluated {
        apply plugin: "org.checkerframework"
        checkerFramework {
          version = "$TEST_CF_VERSION"
          checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
          extraJavacArgs = ["-Aversion"]
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeCorrectLombokExample()

    // when
    val result = testProjectDir.buildWithArgs("build")

    // then
    assertThat(result.task(":checkDelombokCompileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test excludeTests when applying the plugin after the project is evaluated`() {
    buildFile.writeText(
      """
      plugins {
          id("java")
          id("org.checkerframework") apply false
      }
      repositories {
          mavenCentral()
      }
      gradle.projectsEvaluated {
        apply plugin: "org.checkerframework"
        checkerFramework {
          version = "$TEST_CF_VERSION"
          checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
          excludeTests = true
        }
      }
      tasks.register("printCFConfigurations") {
        // Only the annotation processor path is examined. checker-qual is on the test compile
        // classpath no matter what `excludeTests` says, because testImplementation extends the
        // main source set's implementation configuration.
        def mainProcessorPath = configurations.annotationProcessor
        def testProcessorPath = configurations.testAnnotationProcessor
        def hasChecker = { c -> c.files.any { it.name.startsWith("checker-3") } }
        doLast {
          println "MAIN_HAS_CF=" + hasChecker(mainProcessorPath)
          println "TEST_HAS_CF=" + hasChecker(testProcessorPath)
        }
      }
      """
        .trimIndent()
    )

    // when
    val result = testProjectDir.buildWithArgs("printCFConfigurations")

    // then
    assertThat(result.output).contains("MAIN_HAS_CF=true")
    assertThat(result.output).contains("TEST_HAS_CF=false")
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
