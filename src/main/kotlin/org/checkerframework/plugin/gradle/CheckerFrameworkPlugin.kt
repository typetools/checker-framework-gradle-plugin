package org.checkerframework.plugin.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
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

    // The Checker Framework version to use: the value of the "cfVersion" project property if it is
    // set, and the value of the `version` configuration option otherwise. The project property is
    // read each time the version is queried, rather than once while the plugin is applied, because
    // the build script may define the property. A build script may also resolve a configuration,
    // which queries the version, so it is not enough to read the property after the build script
    // has run. This provider holds a Project, so it must not be captured by a task action; it is
    // queried only while a configuration is being resolved.
    val cfVersion: Provider<String> =
      project.provider { cfVersionProjectProperty(project) }.orElse(cfExtension.version)

    val cfConfiguration =
      project.configurations.register(CONFIGURATION_NAME) {
        description =
          "Checker Framework dependencies, will be extended by all source sets' annotationProcessor configurations"
        addDefaultCFDependencies(cfVersion, project, "checker")
      }

    val checkerQualConfiguration =
      project.configurations.register("checkerQual") {
        description =
          "Pluggable type-checker qualifier dependencies, will be extended by all source sets' implementation configuration"
        addDefaultCFDependencies(cfVersion, project, "checker-qual")
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

    val cfManifestDir = project.layout.buildDirectory.dir("checkerframework")
    // A FileCollection, unlike a Project, can be captured by a task action: the configuration cache
    // can serialize it.
    val cfManifestFiles = project.files(cfManifestDir)

    project.tasks.register("writeCheckerManifest", WriteCheckerManifestTask::class.java) {
      group = "Checker Framework tasks"
      checkers.set(cfExtension.checkers)
      incrementalize.set(cfExtension.incrementalize)
      cfBuildDir.set(cfManifestDir)
    }

    project.tasks.withType<JavaCompile>().configureEach {
      val cfCompileOptions =
        (options as ExtensionAware)
          .extensions
          .create("checkerFrameworkCompile", CheckerFrameworkCompileExtension::class.java)

      // If the user passes -PskipCheckerFramework, then use that value rather than the value from
      // the configuration.
      val skipCf =
        skipCheckerFrameworkProperty(project) ?: cfExtension.skipCheckerFramework.getOrElse(false)

      if (
        skipCf ||
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
      doFirst {
        if (
          skipCf ||
            !cfCompileOptions.enabled.getOrElse(true) ||
            (cfExtension.excludeTests.getOrElse(false) && isTestName(name))
        ) {
          return@doFirst
        }
        if (cfExtension.checkers.isPresent) {
          val checkers = cfExtension.checkers.get()
          if (checkers.isEmpty()) {
            throw IllegalStateException("Must specify checkers for the Checker Framework.")
          }
          // If the annotationProcessorPath is null, then annotation processing is disabled, so no
          // need to add things to the path.
          options.annotationProcessorPath = options.annotationProcessorPath?.plus(cfManifestFiles)

          val processorArgIndex = options.compilerArgs.indexOf("-processor")
          if (processorArgIndex != -1 && processorArgIndex + 1 < options.compilerArgs.size) {
            // Because the user already passed -processor as a compiler arg, auto discovery will
            // not work, so add the checkers to the list of processors.
            // This can't be done in CheckerFrameworkCompilerArgumentProvider because it modifies
            // existing arguments rather than adding a new one.
            val oldProcessors = options.compilerArgs[processorArgIndex + 1]
            val cfProcessors = checkers.joinToString(separator = ",")
            options.compilerArgs[processorArgIndex + 1] = "$oldProcessors,$cfProcessors"
          } else if (processorArgIndex != -1) {
            logger.warn("Found -processor argument without a value; no checkers will be used.")
          }
          // Must fork for the JVM arguments to be applied.
          options.isFork = true
        } else {
          throw IllegalStateException("Must specify checkers for the Checker Framework.")
        }
      }
    }

    // Handle Lombok
    project.pluginManager.withPlugin("io.freefair.lombok") {
      val javaPluginExtension: JavaPluginExtension =
        project.extensions.getByType(JavaPluginExtension::class.java)
      javaPluginExtension.sourceSets.configureEach { addCheckDelombokTask(this, project) }
    }
  }

  /**
   * Adds a checkDelombokCompileJava task, for the given source set, that copies the compileJava
   * task, but changes the source to the result of the delombok task.
   */
  private fun addCheckDelombokTask(sourceSet: SourceSet, project: Project) {

    val checkerTaskProvider: TaskProvider<JavaCompile> =
      project.tasks.register(
        sourceSet.getTaskName("checkDelombok", "CompileJava"),
        JavaCompile::class.java,
      )

    sourceSet.extensions.add("checkerTask", checkerTaskProvider)
    val compileTaskProvider: TaskProvider<JavaCompile> =
      project.tasks.named(sourceSet.compileJavaTaskName, JavaCompile::class.java)
    val delombokTaskProvider: TaskProvider<Task> =
      project.tasks.named(sourceSet.getTaskName("delombok", ""), Task::class.java)

    project.afterEvaluate {
      val delombokTask = delombokTaskProvider.get()
      val checkerTask = checkerTaskProvider.get()
      val compileTask = compileTaskProvider.get()
      checkerTask.group = "Checker Framework tasks"
      checkerTask.description =
        "Runs the Checker Framework on the result of delomboking the source code"
      // The lombok plugin's default formatting is pretty-printing, without the @Generated
      // annotations that we need to recognize lombok'd code.
      delombokTask.extensions.add("generated", "generate")

      // Set the sources to the delomboked code.
      checkerTask.source(delombokTask.outputs.files.asFileTree)
      checkerTask.dependsOn(delombokTask)

      // Copy properties from the original task
      checkerTask.classpath = compileTask.classpath
      checkerTask.destinationDirectory.set(
        project.layout.buildDirectory.dir(sourceSet.getTaskName("checkerFramework", "Classes"))
      )
      checkerTask.options.compilerArgs = ArrayList(compileTask.options.compilerArgs)
      checkerTask.options.annotationProcessorPath = compileTask.options.annotationProcessorPath
      project.tasks.named("build").configure { dependsOn(checkerTask) }
    }
  }

  /**
   * Returns the value of the "cfVersion" project property, or null if the property is not set.
   *
   * @param project the project whose property to read
   */
  private fun cfVersionProjectProperty(project: Project): String? {
    // Project.findProperty is used rather than ProviderFactory.gradleProperty because the latter
    // does not see extra properties and cannot be queried at configuration time on Gradle 7.3.
    if (!project.hasProperty("cfVersion")) {
      return null
    }
    val version =
      project.findProperty("cfVersion")
        ?: throw IllegalStateException("cfVersion property is set but has a null value")
    return version.toString()
  }

  /**
   * Returns the value of the "skipCheckerFramework" project property: true if the user asked that
   * the Checker Framework not be run, false if the user asked that it be run, and null if the
   * property is not set. The property, if set, overrides the `skipCheckerFramework` configuration
   * option.
   *
   * @param project the project whose property to read
   */
  private fun skipCheckerFrameworkProperty(project: Project): Boolean? {
    // Project.findProperty is used rather than ProviderFactory.gradleProperty because the latter
    // does not see extra properties and cannot be queried at configuration time on Gradle 7.3.
    if (!project.hasProperty("skipCheckerFramework")) {
      return null
    }
    // A property that is set to a null value means the same as one that is set to "false".
    val skipCfProperty = project.findProperty("skipCheckerFramework") ?: return false
    return skipCfProperty.toString() != "false"
  }

  /**
   * Add the default dependencies for the given {@code jarName}.
   *
   * @param cfVersion a provider of the Checker Framework version, "local", or "dependencies"; the
   *   provider may have no value
   * @param project current project
   * @param jarName name of the jar which is added as a dependency
   */
  private fun Configuration.addDefaultCFDependencies(
    cfVersion: Provider<String>,
    project: Project,
    jarName: String,
  ) {
    isCanBeConsumed = false
    isCanBeResolved = false
    val dependencies = project.dependencies
    val objects = project.objects
    defaultDependencies {
      defaultCFDependency(cfVersion, dependencies, objects, jarName)?.let { add(it) }
    }
  }

  /**
   * Returns the default dependency on {@code jarName}, or null if the user asked that no dependency
   * be added. Throws an exception if {@code cfVersion} has no value.
   *
   * @param cfVersion a provider of the Checker Framework version, "local", or "dependencies"; the
   *   provider may have no value
   * @param dependencies creates the dependency
   * @param objects creates a file collection for a local jar
   * @param jarName name of the jar to depend on
   */
  private fun defaultCFDependency(
    cfVersion: Provider<String>,
    dependencies: DependencyHandler,
    objects: ObjectFactory,
    jarName: String,
  ): Dependency? {
    return when (
      val version =
        cfVersion.orNull ?: throw IllegalStateException("Checker Framework version must be set.")
    ) {
      "local" -> {
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
        dependencies.create(objects.fileCollection().from(jarFile))
      }
      // The user asked that no dependency be added.
      "dependencies" -> null
      else -> dependencies.create("org.checkerframework:$jarName:$version")
    }
  }

  /** Return true if the Name is a test name. */
  private fun isTestName(taskName: String): Boolean {
    return taskName.matches(Regex(".*(T|(^|[A-Z_])t)est.*"))
  }

  /** Provides extraJavacArgs to the compiler. */
  internal class CheckerFrameworkCompilerArgumentProvider(
    private val cfOptions: CheckerFrameworkExtension
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      return cfOptions.extraJavacArgs.getOrElse(emptyList())
    }
  }

  /** Provides JVM arguments. */
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
