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
  fun `test disabling CF for one task in afterEvaluate`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Aversion"]
      }
      afterEvaluate {
        compileJava {
          options.checkerFrameworkCompile.enabled = false
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
  fun `test explicit processor added in afterEvaluate`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Aversion"]
      }
      afterEvaluate {
        compileJava {
          options.compilerArgs.add("-processor")
          options.compilerArgs.add("org.checkerframework.checker.tainting.TaintingChecker")
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeNullnessFailure()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test annotationProcessorPath replaced in afterEvaluate`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Aversion"]
      }
      afterEvaluate {
        compileJava {
          options.annotationProcessorPath = configurations.annotationProcessor
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeNullnessFailure()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test configuration cache is stored and reused`() {
    buildFile.appendText(
      """
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
  fun `test excludeTests set after the plugin has configured the tasks`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.tainting.TaintingChecker"]
        extraJavacArgs = ["-Afilenames"]
      }
      gradle.projectsEvaluated {
        checkerFramework {
          excludeTests = true
        }
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

  @Test
  fun `test skipCheckerFramework set after the plugin has configured the tasks`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Aversion"]
      }
      gradle.projectsEvaluated {
        checkerFramework {
          skipCheckerFramework = true
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
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test forking is visible at configuration time`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
      }
      tasks.register("printFork") {
        def fork = tasks.compileJava.options.fork
        doLast {
          println "COMPILE_JAVA_FORK=" + fork
        }
      }
      """
        .trimIndent()
    )

    // when
    val result = testProjectDir.buildWithArgs("printFork")

    // then
    // ApplyCheckerFrameworkOptions also sets `fork`, at execution time, so that no other
    // configuration can undo it. But `fork` is a task input and other configuration may read it,
    // so it must be set at configuration time as well.
    assertThat(result.output).contains("COMPILE_JAVA_FORK=true")
  }

  @Test
  fun `test forking is undone if the Checker Framework is disabled after configuration`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
      }
      gradle.taskGraph.whenReady {
        tasks.compileJava.options.checkerFrameworkCompile.enabled = false
      }
      tasks.compileJava.doLast {
        println "COMPILE_JAVA_FORK=" + options.fork
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    // Forking was requested at configuration time, while the Checker Framework was still enabled.
    // Because this compilation does not run the Checker Framework after all, it does not fork.
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("COMPILE_JAVA_FORK=false")
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
