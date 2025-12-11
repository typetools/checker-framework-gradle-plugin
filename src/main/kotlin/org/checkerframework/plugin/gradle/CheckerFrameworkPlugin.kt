package org.checkerframework.plugin.gradle

import java.io.File
import java.util.Locale.getDefault
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/**
 * A [Plugin] that configures [JavaCompile] tasks to use the
 * [Checker Framework](https://checkerframework.org/).
 */
class CheckerFrameworkPlugin @Inject constructor(private val providers: ProviderFactory) :
    Plugin<Project> {
  companion object {
    const val PLUGIN_ID = "org.checkerframework"
    const val CONFIGURATION_NAME = "checkerframework"
    const val DEFAULT_CF_VERSION = "3.52.1"
  }

  override fun apply(project: Project) {
    // TODO: What versions of Gradle works with this plugin?
    //    if (GradleVersion.current() < GradleVersion.version("6.8")) {
    //      throw UnsupportedOperationException("$PLUGIN_ID requires at least Gradle 6.8")
    //    }

    val cfOptions =
        project.extensions.create("checkerframework", CheckerFrameworkExtension::class.java)

    val cfConfiguration =
        project.configurations.register(CONFIGURATION_NAME) {
          description =
              "Checker Framework dependencies, will be extended by all source sets' annotationProcessor configurations"
          isVisible = false
          isCanBeConsumed = false
          isCanBeResolved = false
          defaultDependencies {
            val version = findCFVersion(cfOptions)
            if (version != "local") {
              add(project.dependencies.create("org.checkerframework:checker:$version"))
            } else {
              val cfHome = System.getenv("CHECKERFRAMEWORK")
              add(project.dependencies.create(project.files("${cfHome}/checker/dist/checker.jar")))
            }
          }
        }

    val checkerQualConfiguration =
        project.configurations.register("checkerQual") {
          description =
              "Checker qual dependencies, will be extended by all source sets' implementation configuration"
          isVisible = false
          isCanBeConsumed = false
          isCanBeResolved = false
          defaultDependencies {
            val version = findCFVersion(cfOptions)
            if (version != "local") {
              add(project.dependencies.create("org.checkerframework:checker-qual:$version"))
            } else {
              val cfHome = System.getenv("CHECKERFRAMEWORK")
              add(
                  project.dependencies.create(
                      project.files("${cfHome}/checker/dist/checker-qual.jar")
                  )
              )
            }
          }
        }

    // Add checker.jar to all annotationProcessor configurations and checker-qual.jar to all
    // compileOnly configurations.
    // If the user set `excludeTests` to true, then the jars are not added to test configurations.
    project.plugins.withType<JavaBasePlugin> {
      project.extensions.getByName<SourceSetContainer>("sourceSets").configureEach {
        if (
            !cfOptions.excludeTests.getOrElse(false) ||
                !name.lowercase(getDefault()).contains("test")
        ) {
          project.configurations.named(annotationProcessorConfigurationName) {
            extendsFrom(cfConfiguration.get())
          }
          project.configurations.named(implementationConfigurationName) {
            extendsFrom(checkerQualConfiguration.get())
          }
        }
      }
    }

    project.tasks.withType<JavaCompile>().configureEach {
      println("skip Prop: " + project.properties.getOrElse("skipCheckerFramework", { false }))
      println("skip Cong: ${cfOptions.skipCheckerFramework.getOrElse(false)}")
      if (
          cfOptions.skipCheckerFramework.getOrElse(false) ||
              project.properties.getOrElse("skipCheckerFramework", { false }) != false ||
              (cfOptions.excludeTests.getOrElse(false) &&
                  name.lowercase(getDefault()).contains("test"))
      ) {
        println("Skipped")
        return@configureEach
      }

      // Add argument providers so that a user cannot accidentally override the Checker
      // Framework options.
      options.compilerArgumentProviders.add(CheckerFrameworkCompilerArgumentProvider(cfOptions))
      options.forkOptions.jvmArgumentProviders.add(CheckerFrameworkJVMArgumentProvider())

      if (cfOptions.checkers.isPresent) {
        // TODO: This should be in a task so that it happens once rather than for each JavaCompile
        // task.
        // Create META-INF/services/javax.annotation.processing.Processor and
        // META-INF/gradle/incremental.annotation.processors
        // files so that processor autodiscovery works.
        val cfBuildDir = project.layout.buildDirectory.file("checkerframework").get().asFile
        cfBuildDir.mkdirs()
        // https://checkerframework.org/manual/#checker-auto-discovery
        val processorFile =
            File(cfBuildDir, "META-INF/services/javax.annotation.processing.Processor")
        processorFile.parentFile.mkdirs()
        processorFile.createNewFile()
        processorFile.writeText(cfOptions.checkers.get().joinToString(separator = "\n") + "\n")
        if (cfOptions.incrementalize) {
          // https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing
          val gradleProcessorFile =
              File(cfBuildDir, "META-INF/gradle/incremental.annotation.processors")
          gradleProcessorFile.parentFile.mkdirs()
          gradleProcessorFile.createNewFile()

          gradleProcessorFile.writeText(
              cfOptions.checkers.get().joinToString(separator = ",isolating\n") + ",isolating\n"
          )
        }

        options.annotationProcessorPath =
            options.annotationProcessorPath?.plus(project.files(cfBuildDir.toPath().toString()))

        doFirst {
          val processorArgIndex = options.compilerArgs.indexOf("-processor")
          if (processorArgIndex != -1) {
            // Because the user already passed -processor as a compiler arg, auto discovery will
            // not work, so add the checkers to the list of processors.
            // This can't be done in CheckerFrameworkCompilerArgumentProvider because it modifies
            // existing arguments rather than adding a new one.
            val oldProcessors = options.compilerArgs.get(processorArgIndex + 1)
            val cfProcessors = cfOptions.checkers.get().joinToString(separator = ",")
            options.compilerArgs.set(processorArgIndex + 1, "$oldProcessors,$cfProcessors")
          }
          // Must fork for the JVM arguments to be applied.
          options.isFork = true
        }
      }

      val compileTaskName = name

      // Handle Lombok
      project.pluginManager.withPlugin("io.freefair.lombok") {
        // Find the delombok task that delomboks the code for this JavaCompile task.
        var delombokTaskProvider: TaskProvider<Task>
        if (compileTaskName == "compileJava") {
          delombokTaskProvider = project.tasks.named("delombok")
        } else {
          val sourceSetName =
              compileTaskName.substring("compile".length, compileTaskName.length - "Java".length)
          delombokTaskProvider = project.tasks.named("delombok$sourceSetName")
        }

        if (delombokTaskProvider.isPresent) {
          val delombokTask = delombokTaskProvider.get()
          dependsOn.add(delombokTask)
          // The lombok plugin's default formatting is pretty-printing, without the @Generated
          // annotations that we need to recognize lombok'd code.
          delombokTask.extensions.add("generated", "generate")
          if (cfOptions.suppressLombokWarnings) {
            // Also re-add suppress warnings annotations so that we don't get warnings from
            // generated code.
            delombokTask.extensions.add("suppressWarnings", "generate")
          }
          // Set the sources to the delomboked code.
          source = delombokTask.outputs.files.asFileTree
        }
      }
    }
  }

  private fun findCFVersion(cfOptions: CheckerFrameworkExtension): String =
      cfOptions.version.getOrElse(DEFAULT_CF_VERSION)

  internal class CheckerFrameworkCompilerArgumentProvider(
      private val cfOptions: CheckerFrameworkExtension
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      if (cfOptions.extraJavacArgs.isPresent) {
        return cfOptions.extraJavacArgs.get()
      }
      return listOf()
    }
  }

  internal class CheckerFrameworkJVMArgumentProvider() : CommandLineArgumentProvider {
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
