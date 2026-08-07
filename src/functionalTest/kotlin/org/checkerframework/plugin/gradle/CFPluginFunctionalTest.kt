package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

const val TEST_CF_VERSION = "3.53.0"

class CfPluginFunctionalTest : KotlinPluginFunctionalTest() {
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
  fun `test default version is used`() {
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
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test -PskipCheckerFramework`() {
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
    val result = testProjectDir.buildWithArgs("compileJava", "-PskipCheckerFramework")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test skipCheckerFramework configure`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        skipCheckerFramework = true
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
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
  fun `test -PskipCheckerFramework=false`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        skipCheckerFramework = true
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-PskipCheckerFramework=false")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test checker options`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.regex.RegexChecker",
          "org.checkerframework.checker.resourceleak.ResourceLeakChecker",
          "org.checkerframework.checker.signedness.SignednessChecker",
          "org.checkerframework.checker.signature.SignatureChecker",)

        extraJavacArgs = listOf("-ArequirePrefixInWarningSuppressions",
          "-AwarnUnneededSuppressions",
          "-AwarnRedundantAnnotations",
          "-ApermitStaticOwning",
        )
      }
      tasks.named<JavaCompile>("compileJava") {
          options.compilerArgs = listOf(
          "-g",
          "-nowarn",
          "-Xlint:-classfile,-options"
        )
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeResourceLeakTest()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "--stacktrace")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `check for expected failure`() {
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
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains(NULLNESS_FAILURE)
  }

  @Test
  fun `test running two checkers`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        extraJavacArgs = listOf("-Anomsgtext","-Afilenames")
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker",
        "org.checkerframework.checker.tainting.TaintingChecker")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeTaintingFailure()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains("Note: NullnessChecker is type-checking")
    assertThat(result.output).contains(TAINTING_FAILURE)
  }

  @Test
  fun `test explicit processor`() {
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
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.output).contains("Note: NullnessChecker is type-checking")
    assertThat(result.output).contains(TAINTING_FAILURE)
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
  }

  @Disabled("This works with Groovy but not Kotlin.")
  @Test
  fun `test disabling CF for some task`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        extraJavacArgs = listOf("-Anomsgtext","-Afilenames")
        checkers =listOf("org.checkerframework.checker.tainting.TaintingChecker")
      }

      tasks {
        compileJava{
          options.checkerFrameworkCompile.enabled = false
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeTaintingFailure()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.output).contains("Note: NullnessChecker is type-checking")
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Disabled("Need to install a Checker Framework for CI.")
  @Test
  fun `test version local option`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        version = "local"
        extraJavacArgs = listOf("-Aversion")
      }
      tasks.register("printCompileClasspath") {
        doLast {
          println("Compile Classpath:")
          sourceSets.main.get().compileClasspath.forEach { file ->
            println(file.absolutePath)
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

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")

    val result2 = testProjectDir.buildWithArgs(":printCompileClasspath")
    assertThat(result2.output).contains("checker-qual.jar")
  }

  @Disabled("Need to install a Checker Framework for CI.")
  @Test
  fun `test property local`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      tasks.named<JavaCompile>("compileJava") {
        doLast{
          println(classpath.asPath)
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-PcfVersion=local")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")

    assertThat(result.output).contains("checker-qual.jar")
  }

  @Test
  fun `test version=dependencies`() {
    buildFile.appendText(
      """
      dependencies {
        checkerFramework("org.checkerframework:checker:$TEST_CF_VERSION")
        checkerQual("org.checkerframework:checker-qual:$TEST_CF_VERSION")
      }
      configure<CheckerFrameworkExtension> {
        checkers = listOf("org.checkerframework.checker.index.IndexChecker")
        version = "dependencies"
        extraJavacArgs = listOf("-Aversion")
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
  fun `test missing version`() {
    buildFile.appendText(
      """
      dependencies {
        checkerFramework("org.checkerframework:checker:$TEST_CF_VERSION")
      }
      configure<CheckerFrameworkExtension> {
        checkers = listOf("org.checkerframework.checker.index.IndexChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.output).contains("Checker Framework version must be set.")
  }

  @Test
  fun `test missing checkers`() {
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
    val result = testProjectDir.buildWithArgsAndFail("compileJava")

    // then
    assertThat(result.output).contains("Must specify checkers for the Checker Framework.")
  }

  @Test
  fun `test checkerFramework configuration`() {
    // This tests that the version of the Checker Framework in the checker framework configuration
    // is used instead of the version in 'version'.
    val testVersion = "3.43.0"
    buildFile.appendText(
      """
      dependencies {
        checkerFramework("org.checkerframework:checker:$TEST_CF_VERSION")
      }
      configure<CheckerFrameworkExtension> {
        checkers = listOf("org.checkerframework.checker.index.IndexChecker")
        version = "$testVersion"
        extraJavacArgs = listOf("-Aversion")
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
  fun `test excludeTestsFalse`() {
    buildFile.appendText(
      """
       configure<CheckerFrameworkExtension> {
         version = "$TEST_CF_VERSION"
         checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
         excludeTests = false
         extraJavacArgs = listOf("-Afilenames")
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
    assertThat(result.output).containsMatch("Note: TaintingChecker is type-checking .*Success.java")
  }

  @Test
  fun `test excludeTestsTrue`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
        excludeTests = true
        extraJavacArgs = listOf("-Afilenames")
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
  fun `test excludeTests omits the dependencies from the test configurations`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
        excludeTests = true
      }
      tasks.register("printCFConfigurations") {
        // Only the annotation processor path is examined. checker-qual is on the test compile
        // classpath no matter what `excludeTests` says, because testImplementation extends the
        // main source set's implementation configuration.
        val mainProcessorPath = configurations["annotationProcessor"]
        val testProcessorPath = configurations["testAnnotationProcessor"]
        val hasChecker = { c: Configuration -> c.files.any { it.name.startsWith("checker-3") } }
        doLast {
          println("MAIN_HAS_CF=" + hasChecker(mainProcessorPath))
          println("TEST_HAS_CF=" + hasChecker(testProcessorPath))
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("printCFConfigurations")

    // then
    assertThat(result.output).contains("MAIN_HAS_CF=true")
    assertThat(result.output).contains("TEST_HAS_CF=false")
  }

  @Test
  fun `test dependencies that the checkerFramework configuration inherits are used for tests`() {
    buildFile.appendText(
      """
      val cfParent by configurations.creating
      configurations["checkerFramework"].extendsFrom(cfParent)
      dependencies {
        cfParent("org.checkerframework:checker:$TEST_CF_VERSION")
        checkerQual("org.checkerframework:checker-qual:$TEST_CF_VERSION")
      }
      configure<CheckerFrameworkExtension> {
        // No dependency is added by default, so the only checker.jar is the inherited one.
        version = "dependencies"
        checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
        extraJavacArgs = listOf("-Afilenames")
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
    assertThat(result.output).containsMatch("Note: TaintingChecker is type-checking .*Test.java")
  }

  @Test
  fun `test dependency constraints on the checkerFramework configuration are used`() {
    buildFile.appendText(
      """
      dependencies {
        // The version comes from the constraint below, not from this declaration.
        checkerFramework("org.checkerframework:checker")
        checkerQual("org.checkerframework:checker-qual:$TEST_CF_VERSION")
        constraints {
          checkerFramework("org.checkerframework:checker") {
            version { require("$TEST_CF_VERSION") }
            because("the test pins the Checker Framework version")
          }
        }
      }
      configure<CheckerFrameworkExtension> {
        version = "dependencies"
        checkers = listOf("org.checkerframework.checker.tainting.TaintingChecker")
        extraJavacArgs = listOf("-Afilenames")
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
    assertThat(result.output).containsMatch("Note: TaintingChecker is type-checking .*Test.java")
  }

  @Test
  fun `test exclude rules that the checkerFramework configuration inherits are used`() {
    buildFile.appendText(
      """
      val cfParent by configurations.creating {
        exclude(mapOf("group" to "org.checkerframework", "module" to "checker-qual"))
      }
      configurations["checkerFramework"].extendsFrom(cfParent)
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
      }
      tasks.register("printCFConfigurations") {
        val mainProcessorPath = configurations["annotationProcessor"]
        val testProcessorPath = configurations["testAnnotationProcessor"]
        val hasCheckerQual = { c: Configuration ->
          c.files.any { it.name.startsWith("checker-qual") }
        }
        doLast {
          println("MAIN_HAS_CHECKER_QUAL=" + hasCheckerQual(mainProcessorPath))
          println("TEST_HAS_CHECKER_QUAL=" + hasCheckerQual(testProcessorPath))
        }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("printCFConfigurations")

    // then
    // checker.jar depends on checker-qual.jar, so the exclude rule is observable only if the
    // annotation processor paths honor the rule that the checkerFramework configuration inherits.
    assertThat(result.output).contains("MAIN_HAS_CHECKER_QUAL=false")
    assertThat(result.output).contains("TEST_HAS_CHECKER_QUAL=false")
  }

  @Test
  fun `test cfVersion extra property`() {
    buildFile.appendText(
      """
      extra["cfVersion"] = "$TEST_CF_VERSION"
      configure<CheckerFrameworkExtension> {
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
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
  fun `test skipCheckerFramework extra property`() {
    buildFile.appendText(
      """
      extra["skipCheckerFramework"] = "true"
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
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test writeCheckerManifest with no checkers`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("writeCheckerManifest")

    // then
    assertThat(result.task(":writeCheckerManifest")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `test incrementalize false removes the incremental manifest`() {
    val buildFileText = buildFile.readText()
    buildFile.writeText(
      buildFileText +
        """
        configure<CheckerFrameworkExtension> {
          version = "$TEST_CF_VERSION"
          checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        }
        """
          .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()
    val incrementalManifest =
      testProjectDir.resolve(
        "build/checkerframework/META-INF/gradle/incremental.annotation.processors"
      )

    // when
    testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(incrementalManifest.exists()).isTrue()

    // when incremental annotation processing is turned off
    buildFile.writeText(
      buildFileText +
        """
        configure<CheckerFrameworkExtension> {
          version = "$TEST_CF_VERSION"
          checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
          incrementalize = false
        }
        """
          .trimIndent()
    )
    testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(incrementalManifest.exists()).isFalse()
  }
}
