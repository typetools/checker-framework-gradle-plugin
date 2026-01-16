import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  `maven-publish`
  alias(libs.plugins.spotless)
}

group = "org.checkerframework"

version = "1.0.0-SNAPSHOT"

repositories { mavenCentral() }

publishing { repositories { mavenLocal() } }

dependencies { implementation(kotlin("stdlib")) }

gradlePlugin {
  // TODO: Update the URLs.
  website.set("https://github.com/kelloggm/checkerframework-gradle-plugin/blob/master/README.md")
  vcsUrl.set("https://github.com/kelloggm/checkerframework-gradle-plugin")
  plugins {
    register("checkerframework") {
      id = "org.checkerframework"
      displayName = "Checker Framework Gradle Plugin"
      description =
        "Gradle build logic for pluggable type-checking of Java via the Checker Framework"
      implementationClass = "org.checkerframework.plugin.gradle.CheckerFrameworkPlugin"
      tags.addAll(
        "checkerframework",
        "checker",
        "typechecker",
        "pluggable types",
        "formal verification",
      )
    }
  }
}

testing {
  suites {
    withType<JvmTestSuite>().configureEach {
      useJUnitJupiter(libs.versions.junitJupiter)
      dependencies { implementation(libs.truth) }
      targets.configureEach {
        testTask {
          testLogging {
            showExceptions = true
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
          }
        }
      }
    }

    val test by getting(JvmTestSuite::class) { dependencies { implementation(project()) } }
    register<JvmTestSuite>("functionalTest") {
      dependencies { implementation(gradleTestKit()) }
      // Associate with main Kotlin compilation to access internal constants.
      kotlin.target.compilations.named(name) { associateWith(kotlin.target.compilations["main"]) }
      // Make plugin-under-test-metadata.properties accessible to TestKit.
      gradlePlugin.testSourceSet(sources)
      targets.configureEach {
        testTask {
          shouldRunAfter(test)

          val testJavaToolchain = project.findProperty("test.java-toolchain")
          testJavaToolchain?.also {
            val launcher =
              project.javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(testJavaToolchain.toString()))
              }
            val metadata = launcher.get().metadata
            systemProperty("test.java-version", metadata.languageVersion.asInt())
            systemProperty("test.java-home", metadata.installationPath.asFile.canonicalPath)
            val testGradleVersion = project.findProperty("test.gradle-version")
            testGradleVersion?.also { systemProperty("test.gradle-version", testGradleVersion) }
          }
        }
      }
    }
  }
}

tasks { check { dependsOn(testing.suites) } }

spotless {
  kotlinGradle { ktfmt().googleStyle() }
  kotlin { ktfmt().googleStyle() }
}
