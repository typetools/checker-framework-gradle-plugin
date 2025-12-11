import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// MDE: If " works as well as ` here , I would prefer ".
plugins {
  `java-gradle-plugin`
  `kotlin-dsl`
  id("maven-publish")
  alias(libs.plugins.spotless)
}

group = "org.checkerframework"

// MDE: I would start at a slightly lower number for the first release, even if 0.9.0.
version = "1.0.0-SNAPSHOT"

repositories { mavenCentral() }

publishing { repositories { mavenLocal() } }

dependencies { implementation(kotlin("stdlib-jdk8")) }

gradlePlugin {
  // TODO: Update the URLs.
  website.set("https://github.com/kelloggm/checkerframework-gradle-plugin/blob/master/README.md")
  vcsUrl.set("https://github.com/kelloggm/checkerframework-gradle-plugin")
  plugins {
    register("checkerframework") {
      id = "org.checkerframework"
      displayName = "Checker Framework Gradle Plugin"
      description =
          "Re-usable build logic for extending the Java type system via the Checker Framework," +
              " for Gradle builds"
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
      dependencies {
        implementation(project()) { because("Tests use constants") }
        implementation(gradleTestKit())
      }
      // associate with main Kotlin compilation to access internal constants
      kotlin.target.compilations.named(name) { associateWith(kotlin.target.compilations["main"]) }
      // make plugin-under-test-metadata.properties accessible to TestKit
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
          }
        }
      }
    }
  }
}

tasks { check { dependsOn(testing.suites) } }

spotless {
  kotlinGradle {
    ktfmt().googleStyle().configure {
      it.setMaxWidth(100)
      it.setBlockIndent(2)
      it.setContinuationIndent(4)
    }
  }

  kotlin {
    ktfmt().googleStyle().configure {
      it.setMaxWidth(100)
      it.setBlockIndent(2)
      it.setContinuationIndent(4)
    }
  }
}
