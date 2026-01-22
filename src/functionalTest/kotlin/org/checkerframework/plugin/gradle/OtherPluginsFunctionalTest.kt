package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class OtherPluginsFunctionalTest : AbstractPluginFunctionalTest() {
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

  @Disabled // Crashes see https://github.com/kelloggm/checkerframework-gradle-plugin/issues/316.
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
