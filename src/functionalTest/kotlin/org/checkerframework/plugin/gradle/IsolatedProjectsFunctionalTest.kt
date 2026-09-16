package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests that the plugin works with Gradle's isolated projects feature, which forbids a project from
 * reading another project's model.
 *
 * Every test uses a multi-project build, because that is the only kind of build in which the
 * feature can be violated: a single-project build has no other project to read. In particular, a
 * subproject that reads a project property must not fall back to its parent project when the
 * property is not set, which is what [Project.findProperty] does and what
 * [org.gradle.api.plugins.ExtraPropertiesExtension] does not.
 */
class IsolatedProjectsFunctionalTest {
  @TempDir lateinit var testProjectDir: File

  @BeforeEach
  fun setupProject() {
    assumeTrue(
      testGradleVersion >= minimumKotlinDslGradleVersion,
      "Gradle ${testGradleVersion.version}'s Kotlin DSL cannot read this plugin's Kotlin metadata.",
    )
    testProjectDir.resolve("gradle.properties").outputStream().use {
      Properties().apply {
        setProperty("org.gradle.java.home", testJavaHome)
        setProperty("org.gradle.unsafe.isolated-projects", "true")
        store(it, null)
      }
    }
    testProjectDir
      .resolve("settings.gradle.kts")
      .writeText("""rootProject.name = "isolated"${"\n"}include("a", "b")${"\n"}""")
    // The root project does not apply the plugin, so that the plugin is exercised only in
    // subprojects, which are the projects that the feature constrains.
    testProjectDir.resolve("build.gradle.kts").writeText("")
    SUBPROJECTS.forEach { writeSubproject(it) }
  }

  /**
   * Writes a subproject that applies the plugin and whose code the Nullness Checker accepts.
   *
   * @param name the subproject's name
   */
  private fun writeSubproject(name: String) {
    val dir = testProjectDir.resolve(name).apply { mkdirs() }
    dir
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
        extraJavacArgs = listOf("-Aversion")
      }
      """
          .trimIndent()
      )
    dir.writeEmptyClass()
  }

  @Test
  fun `test isolated projects in a multi-project build`() {
    // when
    val result = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then the build does not violate the isolated projects feature
    assertThat(result.output).doesNotContain(CANNOT_LOOK_UP_IN_PARENT)
    SUBPROJECTS.forEach {
      assertThat(result.task(":$it:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
    assertThat(result.output).contains(CONFIGURATION_CACHE_STORED)

    // when the build is run again from a clean output directory
    SUBPROJECTS.forEach { testProjectDir.resolve("$it/build/classes").deleteRecursively() }
    val secondResult = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then
    assertThat(secondResult.output).contains(CONFIGURATION_CACHE_REUSED)
    SUBPROJECTS.forEach {
      assertThat(secondResult.task(":$it:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
  }

  @Test
  fun `test isolated projects with -PcfVersion`() {
    // when
    val result =
      testProjectDir.buildWithArgs(
        "compileJava",
        "--configuration-cache",
        "-PcfVersion=$OTHER_TEST_CF_VERSION",
      )

    // then the command-line property overrides the version, without a cross-project lookup
    assertThat(result.output).doesNotContain(CANNOT_LOOK_UP_IN_PARENT)
    assertThat(result.output).contains("Note: Checker Framework $OTHER_TEST_CF_VERSION")
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test isolated projects with -PskipCheckerFramework`() {
    // when
    val result =
      testProjectDir.buildWithArgs("compileJava", "--configuration-cache", "-PskipCheckerFramework")

    // then no checker runs, and reading the property does not look in the parent project
    assertThat(result.output).doesNotContain(CANNOT_LOOK_UP_IN_PARENT)
    assertThat(result.output).doesNotContain("Note: Checker Framework")
    SUBPROJECTS.forEach {
      assertThat(result.task(":$it:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
  }

  @Test
  fun `test isolated projects with a subproject gradle_properties file`() {
    // given a property set in a subproject's own gradle.properties file, which is the file that
    // org.gradle.api.provider.ProviderFactory.gradleProperty does not read
    testProjectDir
      .resolve("a/gradle.properties")
      .writeText("cfVersion=$OTHER_TEST_CF_VERSION${"\n"}")

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then only that subproject uses the overriding version
    assertThat(result.output).doesNotContain(CANNOT_LOOK_UP_IN_PARENT)
    assertThat(result.output).contains("Note: Checker Framework $OTHER_TEST_CF_VERSION")
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test isolated projects with an extra property in the subproject`() {
    // given a property that the subproject's own build script sets via `ext`, which is the kind of
    // property that org.gradle.api.provider.ProviderFactory.gradleProperty does not read
    testProjectDir
      .resolve("a/build.gradle.kts")
      .appendText("${"\n"}extra[\"cfVersion\"] = \"$OTHER_TEST_CF_VERSION\"${"\n"}")

    // when
    val result = testProjectDir.buildWithArgs("compileJava", "--configuration-cache")

    // then the extra property is read, without a cross-project lookup
    assertThat(result.output).doesNotContain(CANNOT_LOOK_UP_IN_PARENT)
    assertThat(result.output).contains("Note: Checker Framework $OTHER_TEST_CF_VERSION")
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  companion object {
    /** The subprojects that apply the plugin. */
    private val SUBPROJECTS = listOf("a", "b")

    /**
     * A Checker Framework version other than [TEST_CF_VERSION], used to check that a project
     * property overrides the version that the extension sets.
     */
    private const val OTHER_TEST_CF_VERSION = "3.42.0"

    /**
     * The message that Gradle issues when a project reads a property of its parent project, which
     * is the violation that using [Project.findProperty] to read a project property causes.
     */
    private const val CANNOT_LOOK_UP_IN_PARENT = "cannot dynamically look up a property"
  }
}
