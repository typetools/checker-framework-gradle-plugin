package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LombokFunctionalTest : AbstractPluginFunctionalTest() {
  @BeforeEach
  fun setup() {
    buildFile.appendText(
      """
      plugins {
          `java-library`
          id("org.checkerframework")
          id("io.freefair.lombok").version("8.12.1")
      }
      repositories {
          mavenCentral()
      }         

      """
        .trimIndent()
    )
  }

  @Test
  fun `test lombok`() {
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
}
