package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.checkerframework.plugin.gradle.CheckerFrameworkPlugin.Companion.DEFAULT_CF_VERSION
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CFPluginFunctionalTest : AbstractPluginFunctionalTest() {
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
    assertThat(result.output).contains("Note: Checker Framework $DEFAULT_CF_VERSION")
  }

  @Test
  fun `test skipCheckerFramework property`() {
    buildFile.appendText(
        """
            
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
    val result = testProjectDir.buildWithArgs("compileJava", "-PskipCheckerFramework=true")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).doesNotContain("Note: Checker Framework $DEFAULT_CF_VERSION")
  }

  @Test
  fun `test skipCheckerFramework configure`() {
    buildFile.appendText(
        """
            
        configure<CheckerFrameworkExtension> {
            checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
            extraJavacArgs = listOf("-Aversion")
            skipCheckerFramework = true
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
    assertThat(result.output).doesNotContain("Note: Checker Framework $DEFAULT_CF_VERSION")
  }

  @Test
  fun `test checker options`() {
    buildFile.appendText(
        """
                
            configure<CheckerFrameworkExtension> {
                checkers = listOf(      "org.checkerframework.checker.regex.RegexChecker",
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
  fun `test explict processor`() {
    buildFile.appendText(
        """
            
        configure<CheckerFrameworkExtension> {
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

  @Test
  fun `test version option`() {
    val testVersion = "3.43.0"
    buildFile.appendText(
        """
                
            configure<CheckerFrameworkExtension> {
                checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
                version = "$testVersion"
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
    assertThat(result.output).contains("Note: Checker Framework $testVersion")

    val result2 = testProjectDir.buildWithArgs(":printCompileClasspath")
    assertThat(result2.output).contains("checker-qual-$testVersion.jar")
  }

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
    assertThat(result.output).doesNotContain("Note: Checker Framework $DEFAULT_CF_VERSION")

    val result2 = testProjectDir.buildWithArgs(":printCompileClasspath")
    assertThat(result2.output).contains("checker-qual.jar")
  }

  @Test
  fun `test checkerframework configuration`() {
    // This tests that the version of the Checker Framework in the checker framework configuration
    // is used instead of the version in 'version'.
    val testVersion = "3.43.0"
    buildFile.appendText(
        """
                
            dependencies {
                checkerframework("org.checkerframework:checker:$DEFAULT_CF_VERSION")
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
    assertThat(result.output).contains("Note: Checker Framework $DEFAULT_CF_VERSION")
  }

  @Test
  fun `test excludeTestsFalse`() {
    buildFile.appendText(
        """

        configure<CheckerFrameworkExtension> {
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
    assertThat(result.output)
        .doesNotContainMatch("Note: TaintingChecker is type-checking .*Test.java")
    assertThat(result.output).containsMatch("Note: TaintingChecker is type-checking .*Success.java")
  }
}
