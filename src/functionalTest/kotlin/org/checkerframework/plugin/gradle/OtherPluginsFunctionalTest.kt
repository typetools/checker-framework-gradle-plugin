package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OtherPluginsFunctionalTest : KotlinPluginFunctionalTest() {
  @BeforeEach
  fun setup() {
    buildFile.appendText(
      """
      repositories {
          mavenCentral()
      }         
      """
        .trimIndent()
    )
  }

  @Test
  fun `test lombok 8 12 1`() {
    val majorVersion = Runtime.version().feature()
    if (majorVersion >= 25) {
      return
    }
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("8.12.1")
      }
      
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeLombokExample()

    // when
    val result = testProjectDir.buildWithArgsAndFail("build")

    if (majorVersion >= 25) {

      // then
      assertThat(result.output)
        .contains(
          "User.java:9: error: [argument] incompatible argument for parameter y of FooBuilder.y."
        )
      assertThat(result.output)
        .contains("Foo.java:12: error: [assignment] incompatible types in assignment.")
    }
  }

  @Test
  fun `test lombok latest`() {
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("9.2.0")
      }
      
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeLombokExample()

    // when
    val result = testProjectDir.buildWithArgsAndFail("build")

    // then
    assertThat(result.output)
      .contains(
        "User.java:9: error: [argument] incompatible argument for parameter y of FooBuilder.y."
      )
    assertThat(result.output)
      .contains("Foo.java:12: error: [assignment] incompatible types in assignment.")
  }

  @Test
  fun `test forking is visible at configuration time with lombok`() {
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("9.2.0")
      }

      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
      }
      gradle.taskGraph.whenReady {
        val checkerTask = tasks.named<JavaCompile>("checkDelombokCompileJava").get()
        println("CHECK_DELOMBOK_FORK=" + checkerTask.options.isFork)
      }
      """
        .trimIndent()
    )

    // when
    val result = testProjectDir.buildWithArgs("help")

    // then
    // The checkDelombokCompileJava task's annotationProcessorPath is copied from the compileJava
    // task after this plugin has configured the task, so requesting the fork while configuring
    // every JavaCompile task is not enough for this task.
    assertThat(result.output).contains("CHECK_DELOMBOK_FORK=true")
  }

  @Test
  fun `test forking is undone with lombok when annotation processing is disabled`() {
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("9.2.0")
      }

      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
      }
      tasks.withType<JavaCompile>().configureEach {
        options.annotationProcessorPath = configurations.getByName("annotationProcessor")
      }
      gradle.taskGraph.whenReady {
        tasks.named<JavaCompile>("checkDelombokCompileJava").get().options.annotationProcessorPath =
          null
      }
      tasks.named<JavaCompile>("checkDelombokCompileJava") {
        doLast { logger.lifecycle("CHECK_DELOMBOK_FORK=" + options.isFork) }
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeCorrectLombokExample()

    // when
    val result = testProjectDir.buildWithArgs("checkDelombokCompileJava")

    // then
    // The build script's own configureEach gives the checkDelombokCompileJava task an
    // annotationProcessorPath before this plugin copies one onto it, so this plugin requests the
    // fork the first of the two times that it tries to. That request must still be recorded when
    // the task turns out not to run the Checker Framework, so that the fork is undone.
    assertThat(result.task(":checkDelombokCompileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output).contains("CHECK_DELOMBOK_FORK=false")
  }

  @Test
  fun `test disabling CF with lombok `() {
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("9.2.0")
      }
      
      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeLombokExample()

    // when
    val result = testProjectDir.buildWithArgs("build", "-PskipCheckerFramework")

    // then
    assertThat(result.task(":build")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.output)
      .doesNotContain(
        "User.java:9: error: [argument] incompatible argument for parameter y of FooBuilder.y."
      )
    assertThat(result.output)
      .doesNotContain("Foo.java:12: error: [assignment] incompatible types in assignment.")
  }

  @Test
  fun `test disabling CF for compileJava only, with lombok`() {
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("9.2.0")
      }

      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      tasks.named<JavaCompile>("compileJava") {
        val cfOptions =
          (options as ExtensionAware).extensions.getByName("checkerFrameworkCompile")
            as CheckerFrameworkCompileExtension
        cfOptions.enabled.set(false)
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeLombokExample()

    // when
    val result = testProjectDir.buildWithArgsAndFail("build")

    // then the Checker Framework does not run on compileJava, as the user asked. The Checker
    // Framework still runs on the delomboked source code, which a separate checkDelombokCompileJava
    // task compiles; that task has its own checkerFrameworkCompile.enabled option, which the user
    // did not set.
    assertThat(result.task(":compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":checkDelombokCompileJava")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains(
        "User.java:9: error: [argument] incompatible argument for parameter y of FooBuilder.y."
      )
    assertThat(result.output)
      .contains("Foo.java:12: error: [assignment] incompatible types in assignment.")
  }

  @Test
  fun `test disabling CF for the delombok task only`() {
    buildFile.appendText(
      """
       plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("9.2.0")
      }

      configure<CheckerFrameworkExtension> {
        version = "$TEST_CF_VERSION"
        checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
        extraJavacArgs = listOf("-Aversion")
      }
      tasks.named<JavaCompile>("checkDelombokCompileJava") {
        val cfOptions =
          (options as ExtensionAware).extensions.getByName("checkerFrameworkCompile")
            as CheckerFrameworkCompileExtension
        cfOptions.enabled.set(false)
      }
      """
        .trimIndent()
    )
    // given
    testProjectDir.writeLombokExample()

    // when
    val result = testProjectDir.buildWithArgs("checkDelombokCompileJava")

    // then the task does not run at all, as the user asked: running the Checker Framework on the
    // delomboked source code is its only purpose.
    assertThat(result.task(":checkDelombokCompileJava")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
    assertThat(result.output).doesNotContain("error:")
  }

  @Test
  fun `test errorprone latest`() {
    val majorVersion = Runtime.version().feature()
    if (majorVersion < 21) {
      return
    }
    buildFile.delete()
    buildFile =
      testProjectDir.resolve("build.gradle.kts").apply {
        writeText(
          """
          import net.ltgt.gradle.errorprone.errorprone
          import org.checkerframework.plugin.gradle.*

          plugins {
              id("java-library")
              id("net.ltgt.errorprone") version "4.0.1"
              id("org.checkerframework")
          }

          dependencies {
              errorprone("com.google.errorprone:error_prone_core:2.46.0")
          }

          repositories {
              mavenCentral()
          }
          tasks.withType<JavaCompile>().configureEach {
              options.errorprone.warn("CollectionIncompatibleType")
          }

          configure<CheckerFrameworkExtension> {
              version = "$TEST_CF_VERSION"
              checkers = listOf("org.checkerframework.checker.nullness.NullnessChecker")
          }
          """
            .trimIndent()
        )
      }
    // given
    testProjectDir.writeErrorProneExample()

    // when
    val result = testProjectDir.buildWithArgsAndFail("build")

    if (majorVersion < 21) {
      // then
      assertThat(result.output)
        .contains(
          "Demo.java:7: warning: [CollectionIncompatibleType] Argument 'i - 1' should not be passed to this method; its type int is not compatible with its collection's type argument Short"
        )
      assertThat(result.output)
        .contains(
          "Demo.java:8: error: [argument] incompatible argument for parameter arg0 of Set.add."
        )
    }
  }
}
