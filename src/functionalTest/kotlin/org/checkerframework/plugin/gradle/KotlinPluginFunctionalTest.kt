package org.checkerframework.plugin.gradle

import java.io.File
import java.util.Properties
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

/** Abstract class that sets up a project using Kotlin. */
abstract class KotlinPluginFunctionalTest {
  @TempDir lateinit var testProjectDir: File
  lateinit var settingsFile: File
  lateinit var buildFile: File

  @BeforeEach
  open fun setupProject() {
    assumeTrue(
      testGradleVersion >= minimumKotlinDslGradleVersion,
      "Gradle ${testGradleVersion.version}'s Kotlin DSL cannot read this plugin's Kotlin metadata;" +
        " the Groovy tests cover this Gradle version.",
    )
    testProjectDir.resolve("gradle.properties").outputStream().use {
      Properties().apply {
        setProperty("org.gradle.java.home", testJavaHome)
        store(it, null)
      }
    }
    settingsFile = testProjectDir.resolve("settings.gradle.kts").apply { createNewFile() }
    buildFile =
      testProjectDir.resolve("build.gradle.kts").apply {
        writeText(
          """
          import org.checkerframework.plugin.gradle.*
          """
            .trimIndent()
        )
      }
  }
}
