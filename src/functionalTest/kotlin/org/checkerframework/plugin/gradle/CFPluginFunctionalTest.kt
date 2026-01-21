package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

const val TEST_CF_VERSION = "3.53.0"

class CfPluginFunctionalTest : AbstractPluginFunctionalTest() {
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
  fun `test -PcfVersion=disable`() {
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
    val result = testProjectDir.buildWithArgs("compileJava", "-PcfVersion=disable")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test disable configure`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        version = "disable"
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
  }
}
