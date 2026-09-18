package org.checkerframework.plugin.gradle

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.gradle.testkit.runner.BuildResult
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
 * property is not set, which is what [org.gradle.api.Project.findProperty] does and what
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
    // The build resolves the plugin from a Maven repository, as a user would, rather than from
    // TestKit's injected plugin classpath, which cannot be resolved from two projects at once.
    // A Windows path's backslashes would be escape sequences in the generated Kotlin source, so
    // the path is written with forward slashes, which Windows and every other platform accept.
    val pluginRepoUrl = testPluginRepo.replace('\\', '/')
    testProjectDir
      .resolve("settings.gradle.kts")
      .writeText(
        """
      pluginManagement {
          repositories {
              maven { url = uri("$pluginRepoUrl") }
              gradlePluginPortal()
          }
      }
      rootProject.name = "isolated"
      include("a", "b")
      """
          .trimIndent()
      )
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
          id("org.checkerframework") version "$testPluginVersion"
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

  /**
   * Asserts that the isolated projects feature was enabled during the build and that the build did
   * not violate it.
   *
   * Without the first assertion, the second one would be vacuous in a Gradle version that does not
   * recognize the incubating `org.gradle.unsafe.isolated-projects` property, because Gradle
   * silently ignores an unrecognized `org.gradle.*` property: every test would run an ordinary
   * multi-project build, in which no violation can occur, and would keep passing even if the plugin
   * regressed.
   *
   * @param result the result of a build of [testProjectDir]
   */
  private fun assertIsolatedProjects(result: BuildResult) {
    assertThat(result.output).containsMatch(ISOLATED_PROJECTS_ENABLED)
    assertThat(result.output).doesNotContain(CANNOT_LOOK_UP_IN_PARENT)
  }

  /**
   * Runs a build of [testProjectDir] that resolves the plugin from a Maven repository.
   *
   * @param tasks the build's command-line arguments
   * @return the build's result
   */
  private fun File.buildIsolated(vararg tasks: String): BuildResult =
    prepareBuildWithoutPluginClasspath(*tasks).build()

  @Test
  fun `test isolated projects in a multi-project build`() {
    // when
    val result = testProjectDir.buildIsolated("compileJava", "--configuration-cache")

    // then the build does not violate the isolated projects feature
    assertIsolatedProjects(result)
    SUBPROJECTS.forEach {
      assertThat(result.task(":$it:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
    assertThat(result.output).contains(CONFIGURATION_CACHE_STORED)

    // when the build is run again from a clean output directory
    SUBPROJECTS.forEach { testProjectDir.resolve("$it/build/classes").deleteRecursively() }
    val secondResult = testProjectDir.buildIsolated("compileJava", "--configuration-cache")

    // then
    assertIsolatedProjects(secondResult)
    assertThat(secondResult.output).contains(CONFIGURATION_CACHE_REUSED)
    SUBPROJECTS.forEach {
      assertThat(secondResult.task(":$it:compileJava")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
  }

  @Test
  fun `test isolated projects with -PcfVersion`() {
    // when
    val result =
      testProjectDir.buildIsolated(
        "compileJava",
        "--configuration-cache",
        "-PcfVersion=$OTHER_TEST_CF_VERSION",
      )

    // then the command-line property overrides the version, without a cross-project lookup
    assertIsolatedProjects(result)
    assertThat(result.output).contains("Note: Checker Framework $OTHER_TEST_CF_VERSION")
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test isolated projects with -PskipCheckerFramework`() {
    // when
    val result =
      testProjectDir.buildIsolated("compileJava", "--configuration-cache", "-PskipCheckerFramework")

    // then no checker runs, and reading the property does not look in the parent project
    assertIsolatedProjects(result)
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
    val result = testProjectDir.buildIsolated("compileJava", "--configuration-cache")

    // then only that subproject uses the overriding version
    assertIsolatedProjects(result)
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
    val result = testProjectDir.buildIsolated("compileJava", "--configuration-cache")

    // then the extra property is read, without a cross-project lookup
    assertIsolatedProjects(result)
    assertThat(result.output).contains("Note: Checker Framework $OTHER_TEST_CF_VERSION")
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test isolated projects with a root gradle_properties file`() {
    // given a property set in the root project's gradle.properties file, which is the setting that
    // the documentation tells users to migrate to
    testProjectDir
      .resolve("gradle.properties")
      .appendText("${"\n"}cfVersion=$OTHER_TEST_CF_VERSION${"\n"}")

    // when
    val result = testProjectDir.buildIsolated("compileJava", "--configuration-cache")

    // then every subproject uses the overriding version, without a cross-project lookup: Gradle
    // merges the root project's gradle.properties file into every project's extra properties
    assertIsolatedProjects(result)
    assertThat(result.output).contains("Note: Checker Framework $OTHER_TEST_CF_VERSION")
    assertThat(result.output).doesNotContain("Note: Checker Framework $TEST_CF_VERSION")
  }

  @Test
  fun `test isolated projects does not inherit an extra property from an ancestor`() {
    // given a property that only the root project sees, because its build script sets it via `ext`
    testProjectDir
      .resolve("build.gradle.kts")
      .appendText("${"\n"}extra[\"cfVersion\"] = \"$OTHER_TEST_CF_VERSION\"${"\n"}")

    // when
    val result = testProjectDir.buildIsolated("compileJava", "--configuration-cache")

    // then no subproject inherits the setting, so each one falls back to the extension's version.
    // This is the incompatible change that the changelog documents; reading the ancestor's
    // property is the cross-project access that the feature forbids.
    assertIsolatedProjects(result)
    assertThat(result.output).contains("Note: Checker Framework $TEST_CF_VERSION")
    assertThat(result.output).doesNotContain("Note: Checker Framework $OTHER_TEST_CF_VERSION")
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
     * is the violation that using [org.gradle.api.Project.findProperty] to read a project property
     * causes.
     */
    private const val CANNOT_LOOK_UP_IN_PARENT = "cannot dynamically look up a property"

    /**
     * A regex matching the message that Gradle issues when the isolated projects feature is
     * enabled. Gradle 9.2.1 and earlier write the feature's name as "Isolated projects", and a
     * later version capitalizes it as "Isolated Projects".
     */
    private const val ISOLATED_PROJECTS_ENABLED =
      """Isolated [Pp]rojects is an incubating feature\."""
  }
}
