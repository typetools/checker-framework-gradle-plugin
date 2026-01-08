package org.checkerframework.plugin.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.util.GradleVersion

/**
 * A [Plugin] that configures [JavaCompile] tasks to use the
 * [Checker Framework](https://checkerframework.org/).
 */
class CheckerFrameworkPlugin @Inject constructor() : Plugin<Project> {
  companion object {
    const val PLUGIN_ID = "org.checkerframework"
    const val CONFIGURATION_NAME = "checkerFramework"
  }

  override fun apply(project: Project) {
    if (GradleVersion.current() < GradleVersion.version("7.3")) {
      throw UnsupportedOperationException("$PLUGIN_ID requires at least Gradle 7.3")
    }

    val cfExtension =
      project.extensions.create("checkerFramework", CheckerFrameworkExtension::class.java)

    val cfConfiguration =
      project.configurations.register(CONFIGURATION_NAME) {
        description =
          "Checker Framework dependencies, will be extended by all source sets' annotationProcessor configurations"
        addDefaultCFDependencies(cfExtension, project, "checker")
      }

    val checkerQualConfiguration =
      project.configurations.register("checkerQual") {
        description =
          "Pluggable type-checker qualifier dependencies, will be extended by all source sets' implementation configuration"
        addDefaultCFDependencies(cfExtension, project, "checker-qual")
      }

    // Add checker.jar to all annotationProcessor configurations and checker-qual.jar to all
    // compileOnly configurations.
    // If the user set `excludeTests` to true, then the jars are not added to test configurations.
    project.plugins.withType<JavaBasePlugin> {
      project.extensions.getByName<SourceSetContainer>("sourceSets").configureEach {
        if (!cfExtension.excludeTests.getOrElse(false) || !isTestName(name)) {
          project.configurations.named(annotationProcessorConfigurationName) {
            extendsFrom(cfConfiguration.get())
          }
          project.configurations.named(implementationConfigurationName) {
            extendsFrom(checkerQualConfiguration.get())
          }
        }
      }
    }

    val cfManifestDir = project.layout.buildDirectory.dir("checkerframework").get().asFile

    project.tasks.register("writeCheckerManifest", WriteCheckerManifestTask::class.java) {
      group = "CheckerFramework"
      checkers.set(cfExtension.checkers)
      incrementalize.set(cfExtension.incrementalize)
      cfBuildDir.set(cfManifestDir)
    }

    project.tasks.withType<JavaCompile>().configureEach {
      val cfCompileOptions =
        (options as ExtensionAware)
          .extensions
          .create("checkerFrameworkCompile", CheckerFrameworkCompileExtension::class.java)

      if (
        getCFVersion(cfExtension, project) == "disable" ||
          !cfCompileOptions.enabled.getOrElse(true) ||
          (cfExtension.excludeTests.getOrElse(false) && isTestName(name))
      ) {
        return@configureEach
      }
      dependsOn("writeCheckerManifest")

      // Add argument providers so that a user cannot accidentally overwrite the Checker
      // Framework options, i.e. options.compilerArgs = [...].
      options.compilerArgumentProviders.add(CheckerFrameworkCompilerArgumentProvider(cfExtension))
      options.forkOptions.jvmArgumentProviders.add(CheckerFrameworkJvmArgumentProvider())

      if (cfExtension.checkers.isPresent) {
        // If the annotationProcessorPath is null, then annotation processing is disabled, so no
        // need to add things to the path.
        options.annotationProcessorPath =
          options.annotationProcessorPath?.plus(project.files(cfManifestDir.toPath().toString()))

        doFirst {
          val processorArgIndex = options.compilerArgs.indexOf("-processor")
          if (processorArgIndex != -1 && processorArgIndex + 1 < options.compilerArgs.size) {
            // Because the user already passed -processor as a compiler arg, auto discovery will
            // not work, so add the checkers to the list of processors.
            // This can't be done in CheckerFrameworkCompilerArgumentProvider because it modifies
            // existing arguments rather than adding a new one.
            val oldProcessors = options.compilerArgs.get(processorArgIndex + 1)
            val cfProcessors = cfExtension.checkers.get().joinToString(separator = ",")
            options.compilerArgs.set(processorArgIndex + 1, "$oldProcessors,$cfProcessors")
          } else if (processorArgIndex != -1) {
            project.logger.warn(
              "Found -processor argument without a value; checkers will not be appended"
            )
          }
          // Must fork for the JVM arguments to be applied.
          options.isFork = true
        }
      }

      val compileTaskName = name

      // Handle Lombok
      project.pluginManager.withPlugin("io.freefair.lombok") {
        // Find the delombok task that delomboks the code for this JavaCompile task.
        val delombokTaskProvider: TaskProvider<Task> =
          if (compileTaskName == "compileJava") {
            project.tasks.named("delombok")
          } else {
            val sourceSetName =
              compileTaskName.substring("compile".length, compileTaskName.length - "Java".length)
            project.tasks.named("delombok$sourceSetName")
          }

        if (delombokTaskProvider.isPresent) {
          val delombokTask = delombokTaskProvider.get()
          dependsOn.add(delombokTask)
          // The lombok plugin's default formatting is pretty-printing, without the @Generated
          // annotations that we need to recognize lombok'd code.
          delombokTask.extensions.add("generated", "generate")
          if (cfExtension.suppressLombokWarnings.getOrElse(false)) {
            // Also re-add @SuppressWarnings annotations so that we don't get warnings from
            // generated code.
            delombokTask.extensions.add("suppressWarnings", "generate")
          }
          // Set the sources to the delomboked code.
          source = delombokTask.outputs.files.asFileTree
        }
      }
    }
  }

  private fun Configuration.addDefaultCFDependencies(
    cfExtension: CheckerFrameworkExtension,
    project: Project,
    jarName: String,
  ) {
    isCanBeConsumed = false
    isCanBeResolved = false
    defaultDependencies {
      val version = getCFVersion(cfExtension, project)
      if (version == "local") {
        val cfHome =
          System.getenv("CHECKERFRAMEWORK")
            ?: throw IllegalStateException(
              "CHECKERFRAMEWORK environment variable must be set when using local version"
            )
        val jarFile = File("$cfHome/checker/dist/$jarName.jar")
        if (!jarFile.exists()) {
          throw IllegalStateException(
            "Could not find $jarName at ${jarFile.absolutePath}. " +
              "Please ensure the Checker Framework is built."
          )
        }
        add(project.dependencies.create(project.files(jarFile)))
      } else if (version == "dependencies" || version == "disable") {
        // Don't add dependencies.
      } else {
        add(project.dependencies.create("org.checkerframework:$jarName:$version"))
      }
    }
  }

  private fun getCFVersion(cfExtension: CheckerFrameworkExtension, project: Project): String {
    if (!cfExtension.version.isPresent) {
      throw IllegalStateException("Checker Framework version must be set.")
    }

    if (project.hasProperty("cfVersion")) {
      return project.properties.get("cfVersion") as String
    }
    return cfExtension.version.get()
  }

  private fun isTestName(string: String): Boolean {
    return string.contains("test") || string.contains("Test")
  }

  internal class CheckerFrameworkCompilerArgumentProvider(
    private val cfOptions: CheckerFrameworkExtension
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      if (cfOptions.extraJavacArgs.isPresent) {
        return cfOptions.extraJavacArgs.get()
      }
      return emptyList()
    }
  }

  internal class CheckerFrameworkJvmArgumentProvider : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      return listOf(
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
      )
    }
  }
}
