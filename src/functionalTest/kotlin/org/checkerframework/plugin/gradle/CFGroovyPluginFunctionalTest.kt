package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        extraJavacArgs = ["-Aversion", "-Afilenames"]
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
    // Shows that type-checking ran, given that no error is issued.
    assertThat(result.output).contains("Note: TaintingChecker is type-checking")
    assertThat(result.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test explicit processor added in afterEvaluate, checkers exchanged`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.tainting.TaintingChecker"]
        extraJavacArgs = ["-Aversion", "-Afilenames"]
      }
      afterEvaluate {
        compileJava {
          options.compilerArgs.add("-processor")
          options.compilerArgs.add("org.checkerframework.checker.nullness.NullnessChecker")
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeTaintingFailure()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then, as in `test explicit processor added in afterEvaluate` but with the roles of the two
    // checkers exchanged, both checkers run.
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains("Note: NullnessChecker is type-checking")
    assertThat(result.output).contains(TAINTING_FAILURE_MESSAGE)
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
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
    assertThat(result.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test annotationProcessorPath replaced in afterEvaluate is retained`() {
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
          doLast {
            println("annotationProcessorPath = " + options.annotationProcessorPath.files)
          }
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava")

    // then this plugin's contribution to the annotation processor path is add-only: the path that
    // the user set in afterEvaluate is still present, and the manifest directory has been added to
    // that path.
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val pathLine = result.output.lines().single { it.startsWith("annotationProcessorPath = ") }
    val normalizedPathLine = pathLine.replace('\\', '/')
    assertThat(normalizedPathLine).contains("/checker/$TEST_CF_VERSION/")
    assertThat(normalizedPathLine).contains("build/checkerframework")
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
  fun `test skipCheckerFramework set to false after compileJava has been configured`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        skipCheckerFramework = true
      }
      // Configuring the task realizes it, which runs the plugin's configuration of the task while
      // skipCheckerFramework is still true.
      compileJava{}
      checkerFramework {
        skipCheckerFramework = false
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
  fun `test excludeTests set to false after compileTestJava has been configured`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.tainting.TaintingChecker"]
        extraJavacArgs = ["-Afilenames"]
        excludeTests = true
      }
      // Configuring the task realizes it, which runs the plugin's configuration of the task while
      // excludeTests is still true.
      compileTestJava{}
      checkerFramework {
        excludeTests = false
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
    assertThat(result.output).containsMatch("Note: TaintingChecker is type-checking .*Test.java")
  }

  @Test
  fun `test checkerFrameworkCompile enabled set to true after being set to false`() {
    buildFile.appendText(
      """
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
      }
      compileJava {
        options.checkerFrameworkCompile.enabled = false
      }
      compileJava.options.checkerFrameworkCompile.enabled = true
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
  fun `test running the Checker Framework after a build that skipped it`() {
    buildFile.appendText(
      """
      // Configuring the task realizes it, which runs the plugin's configuration of the task before
      // the checkerFramework block below has run, so the plugin does not know until the task runs
      // whether the Checker Framework should be skipped.
      compileJava{}
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        skipCheckerFramework = project.hasProperty("skipCf")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeNullnessFailure()

    // when the Checker Framework is skipped
    val skipped = testProjectDir.buildWithArgs("compileJava", "-PskipCf")

    // then
    assertThat(skipped.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(skipped.output).doesNotContain(NULLNESS_FAILURE)

    // when the Checker Framework is no longer skipped, the task must not be up to date
    val notSkipped = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(notSkipped.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(notSkipped.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test lombok when applying the plugin after the project is evaluated`() {
    assumeTrue(
      testGradleVersion >= minimumLombokGradleVersion,
      "The io.freefair.lombok plugin does not support Gradle ${testGradleVersion.version}.",
    )
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
  fun `test lombok when applying the plugin from afterEvaluate`() {
    assumeTrue(
      testGradleVersion >= minimumLombokGradleVersion,
      "The io.freefair.lombok plugin does not support Gradle ${testGradleVersion.version}.",
    )
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
      afterEvaluate {
        apply plugin: "org.checkerframework"
        checkerFramework {
          version = "$TEST_CF_VERSION"
          checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        }
      }
      // This block runs after the one above, so the checkDelombokCompileJava task must not copy
      // compileJava's compiler arguments until this block has run.
      afterEvaluate {
        compileJava.options.compilerArgs << "-Amarker"
      }
      tasks.register("printDelombokArgs") {
        def args = provider { tasks.checkDelombokCompileJava.options.compilerArgs }
        doLast {
          println "DELOMBOK_ARGS=" + args.get()
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeCorrectLombokExample()

    // when
    val result = testProjectDir.buildWithArgs("printDelombokArgs")

    // then
    assertThat(result.output).containsMatch("DELOMBOK_ARGS=\\[.*-Amarker.*]")
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
        def hasChecker = { c -> c.files.any { it.name.startsWith("checker-$TEST_CF_VERSION") } }
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
