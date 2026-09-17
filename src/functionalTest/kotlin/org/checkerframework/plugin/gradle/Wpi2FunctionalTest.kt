package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The prefix of a line of build output that reports one of javac's arguments. */
private const val ARG_PREFIX = "COMPILER-ARG: "

/**
 * A build script fragment that makes the given compile task print each of javac's arguments, one
 * per line, after the task has run. The arguments are printed by a `doLast` action so that they are
 * the ones that javac received, after every `doFirst` action has had its say.
 *
 * @param taskName the name of the compile task
 */
private fun printCompilerArgs(taskName: String = "compileJava") =
  """
  tasks.named<JavaCompile>("$taskName") {
    doLast {
      options.allCompilerArgs.forEach { println("$ARG_PREFIX" + it) }
    }
  }
  """
    .trimIndent()

/**
 * The same as [printCompilerArgs], for a build script written in Groovy.
 *
 * @param taskName the name of the compile task
 */
private fun printCompilerArgsGroovy(taskName: String = "compileJava") =
  """
  tasks.named("$taskName") {
    doLast {
      options.allCompilerArgs.each { println("$ARG_PREFIX" + it) }
    }
  }
  """
    .trimIndent()

/** Returns the arguments that [printCompilerArgs] printed. */
private fun BuildResult.compilerArgs(): List<String> =
  output.lines().filter { it.startsWith(ARG_PREFIX) }.map { it.removePrefix(ARG_PREFIX) }

/**
 * Returns the value of the given argument, which must appear exactly once.
 *
 * @param name the argument's name, such as "-Aajava"
 */
private fun List<String>.argumentValue(name: String): String =
  single { it.startsWith("$name=") }.substringAfter("=")

/** Tests the `-Pwpi2` command-line argument, which requests whole-program inference. */
class Wpi2FunctionalTest : KotlinPluginFunctionalTest() {
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
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
      }

      """
        .trimIndent()
    )
  }

  @Test
  fun `test -Pwpi2 adds the required arguments`() {
    buildFile.appendText(printCompilerArgs())
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val args = result.compilerArgs()
    assertThat(args).containsAtLeast("-Ainfer=ajava", "-Awarns")

    // The directories must be absolute paths, and must have the names that wpi2.sh expects.
    val newDir = File(args.argumentValue("-AinferOutputDirectory"))
    val outputDir = File(args.argumentValue("-Aajava"))
    assertThat(newDir.isAbsolute).isTrue()
    assertThat(outputDir.isAbsolute).isTrue()
    assertThat(newDir.name).isEqualTo("whole-program-inference-new")
    assertThat(outputDir.name).isEqualTo("whole-program-inference-output")
    assertThat(newDir.parentFile).isEqualTo(testProjectDir.canonicalFile)
    assertThat(outputDir.parentFile).isEqualTo(testProjectDir.canonicalFile)
  }

  @Test
  fun `test -Pwpi2 removes the forbidden arguments`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        extraJavacArgs = listOf("-Afilenames", "-Werror", "-AinferOutputOriginal")
      }
      tasks.named<JavaCompile>("compileJava") {
        options.compilerArgs.add("-Werror")
        options.compilerArgs.add("-AinferOutputOriginal")
        options.compilerArgumentProviders.add(CommandLineArgumentProvider { listOf("-Werror") })
      }

      """
        .trimIndent() + printCompilerArgs()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val args = result.compilerArgs()
    assertThat(args).containsNoneOf("-Werror", "-AinferOutputOriginal")
    // The arguments that are not forbidden are still passed.
    assertThat(args).contains("-Afilenames")
  }

  @Test
  fun `test no -Pwpi2`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        extraJavacArgs = listOf("-AinferOutputOriginal")
      }
      tasks.named<JavaCompile>("compileJava") {
        options.compilerArgs.add("-Werror")
      }

      """
        .trimIndent() + printCompilerArgs()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val args = result.compilerArgs()
    assertThat(args).containsAtLeast("-Werror", "-AinferOutputOriginal")
    assertThat(args).containsNoneOf("-Ainfer=ajava", "-Awarns")
    assertThat(args.none { it.startsWith("-Aajava=") }).isTrue()
    assertThat(args.none { it.startsWith("-AinferOutputDirectory=") }).isTrue()
  }

  @Test
  fun `test -Pwpi2=false`() {
    buildFile.appendText(printCompilerArgs())
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2=false")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.compilerArgs()).containsNoneOf("-Ainfer=ajava", "-Awarns")
  }

  @Test
  fun `test -Pwpi2 infers annotations`() {
    buildFile.appendText(
      """
      tasks.named<JavaCompile>("compileJava") {
        options.compilerArgs.add("-Werror")
      }
      """
        .trimIndent()
    )
    // given: code that the Nullness Checker issues a warning about and infers an annotation for
    testProjectDir.writeInferenceExample()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then: the build succeeds, because "-Awarns" was added and "-Werror" was removed
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    // The inferred annotations are written where wpi2.sh looks for them.
    val newDir = testProjectDir.resolve("whole-program-inference-new")
    val ajavaFiles = newDir.walkTopDown().filter { it.extension == "ajava" }.toList()
    assertThat(ajavaFiles).isNotEmpty()
    // The Nullness Checker is a compound checker, so it writes one file per subchecker, in an
    // order that differs from one file system to another. Only one of the files, whichever it is,
    // contains the inferred annotation.
    assertThat(ajavaFiles.joinToString("\n") { "${it.path}:\n" + it.readText() })
      .contains("@org.checkerframework.checker.nullness.qual.Nullable")
  }

  @Test
  fun `test -Pwpi2 with the configuration cache`() {
    buildFile.appendText(printCompilerArgs())
    // given
    testProjectDir.writeEmptyClass()

    // when
    val firstResult = testProjectDir.buildWithArgs("compileJava", "-Pwpi2", "--configuration-cache")

    // then
    assertThat(firstResult.output).contains(CONFIGURATION_CACHE_STORED)
    assertThat(firstResult.compilerArgs()).containsAtLeast("-Ainfer=ajava", "-Awarns")

    // when the configuration is reused rather than recomputed
    val secondResult =
      testProjectDir.buildWithArgs(
        "compileJava",
        "--rerun-tasks",
        "-Pwpi2",
        "--configuration-cache",
      )

    // then
    assertThat(secondResult.output).contains(CONFIGURATION_CACHE_REUSED)
    assertThat(secondResult.compilerArgs()).containsAtLeast("-Ainfer=ajava", "-Awarns")
  }

  @Test
  fun `test -Pwpi2 succeeds when annotation processing is disabled`() {
    buildFile.appendText(
      """
      configure<CheckerFrameworkExtension> {
        extraJavacArgs = listOf("-AinferOutputOriginal")
      }
      tasks.named<JavaCompile>("compileJava") {
        options.compilerArgs.add("-Werror")
        options.annotationProcessorPath = null
      }

      """
        .trimIndent()
    )
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then: the build succeeds. No checker runs on this task, so which javac arguments it receives
    // is unspecified.
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `test -Pwpi2 compiles on every invocation`() {
    // given
    testProjectDir.writeInferenceExample()
    val newDir = testProjectDir.resolve("whole-program-inference-new")
    val outputDir = testProjectDir.resolve("whole-program-inference-output")

    // when: the build is run twice, as wpi2.sh does, moving the inference results from one
    // directory to the other in between, which is all that changes from one run to the next
    val firstResult = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")
    assertThat(firstResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(outputDir.deleteRecursively()).isTrue()
    assertThat(newDir.renameTo(outputDir)).isTrue()
    val secondResult = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then: the compilation runs again and writes its inference results, rather than being skipped
    // as up to date and writing nothing at all
    assertThat(secondResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val ajavaFiles = newDir.walkTopDown().filter { it.extension == "ajava" }.toList()
    assertThat(ajavaFiles).isNotEmpty()
  }

  @Test
  fun `test no -Pwpi2 leaves the up-to-date check alone`() {
    // given
    testProjectDir.writeEmptyClass()

    // when
    testProjectDir.buildWithArgs("compileJava")
    val secondResult = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(secondResult.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  @Test
  fun `test -Pwpi2 creates the directory that it reads inference results from`() {
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then: the Checker Framework warns, printing the entire classpath, if the directory that
    // "-Aajava" names does not exist, as it does not before the first round of inference
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(testProjectDir.resolve("whole-program-inference-output").isDirectory).isTrue()
    assertThat(result.output).doesNotContain("did not find annotation file or directory")
  }

  @Test
  fun `test -Pwpi2 uses the same directories in every subproject`() {
    settingsFile.appendText(
      """
      include(":sub")
      """
        .trimIndent()
    )
    buildFile.appendText(printCompilerArgs())
    testProjectDir
      .resolve("sub")
      .apply { mkdirs() }
      .resolve("build.gradle.kts")
      .writeText(
        """
        import org.checkerframework.plugin.gradle.*

        plugins {
            `java-library`
            id("org.checkerframework")
        }
        repositories {
            mavenCentral()
        }
        configure<CheckerFrameworkExtension> {
          version = "$TEST_CF_VERSION"
          checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        }

        """
          .trimIndent() + printCompilerArgs()
      )
    // given
    testProjectDir.writeEmptyClass()
    testProjectDir.resolve("sub").writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then: both projects use the root project's directories, as wpi2.sh requires
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":sub:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val args = result.compilerArgs()
    val ajavaArgs = args.filter { it.startsWith("-Aajava=") }
    val inferOutputArgs = args.filter { it.startsWith("-AinferOutputDirectory=") }
    assertThat(ajavaArgs).hasSize(2)
    assertThat(inferOutputArgs).hasSize(2)
    assertThat(ajavaArgs.distinct()).hasSize(1)
    assertThat(inferOutputArgs.distinct()).hasSize(1)
    assertThat(File(ajavaArgs.first().substringAfter("=")).parentFile)
      .isEqualTo(testProjectDir.canonicalFile)
  }
}

/**
 * Tests the `-Pwpi2` command-line argument with a build script written in Groovy, which is the only
 * kind of build script that the Gradle versions below [minimumKotlinDslGradleVersion] can use.
 */
class Wpi2GroovyFunctionalTest : GroovyPluginFunctionalTest() {
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
      checkerFramework {
        version = "$TEST_CF_VERSION"
        checkers = ["org.checkerframework.checker.nullness.NullnessChecker"]
        extraJavacArgs = ["-Afilenames", "-Werror", "-AinferOutputOriginal"]
      }
      compileJava {
        options.compilerArgs.add("-Werror")
        options.compilerArgumentProviders.add({ ["-AinferOutputOriginal"] } as CommandLineArgumentProvider)
      }

      """
        .trimIndent() + printCompilerArgsGroovy()
    )
  }

  @Test
  fun `test -Pwpi2`() {
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "-Pwpi2")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val args = result.compilerArgs()
    assertThat(args).containsAtLeast("-Ainfer=ajava", "-Awarns", "-Afilenames")
    assertThat(args).containsNoneOf("-Werror", "-AinferOutputOriginal")
    assertThat(File(args.argumentValue("-AinferOutputDirectory")).name)
      .isEqualTo("whole-program-inference-new")
    assertThat(File(args.argumentValue("-Aajava")).name).isEqualTo("whole-program-inference-output")
  }

  @Test
  fun `test no -Pwpi2`() {
    // given
    testProjectDir.writeEmptyClass()

    // when
    val result = testProjectDir.buildWithArgs("compileJava")

    // then
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val args = result.compilerArgs()
    assertThat(args).containsAtLeast("-Werror", "-AinferOutputOriginal")
    assertThat(args).containsNoneOf("-Ainfer=ajava", "-Awarns")
  }
}
