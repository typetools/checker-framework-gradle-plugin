package org.checkerframework.plugin.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.Transformer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
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
    // read after the build script has run, because the build script may define it.
    val cfVersion: Property<String> = project.objects.property(String::class.java)
    cfVersion.convention(cfExtension.version)

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

    val writeManifestTask =
      project.tasks.register("writeCheckerManifest", WriteCheckerManifestTask::class.java) {
        group = "Checker Framework tasks"
        checkers.set(cfExtension.checkers)
        incrementalize.set(cfExtension.incrementalize)
        cfBuildDir.set(project.layout.buildDirectory.dir("checkerframework"))
      }

    // A file collection containing the manifest directory, which carries a dependency on the task
    // that creates the directory's contents.
    val cfManifestFiles = project.files(writeManifestTask.flatMap { it.cfBuildDir })

    project.tasks.withType<JavaCompile>().configureEach {
      (options as ExtensionAware)
        .extensions
        .create("checkerFrameworkCompile", CheckerFrameworkCompileExtension::class.java)
    }

    // Configure after the build script has run, so that the values of the extensions and of the
    // project properties are the ones the user requested, no matter when a task is realized.
    afterEvaluateOrNow(project) {
      project.findProperty("cfVersion")?.let { cfVersion.set(it.toString()) }
      addCFDependenciesToSourceSets(project, cfExtension, cfConfiguration, checkerQualConfiguration)
      configureJavaCompileTasks(project, cfExtension, cfManifestFiles, writeManifestTask)
    }

    // Handle Lombok
    project.pluginManager.withPlugin("io.freefair.lombok") {
      val javaPluginExtension: JavaPluginExtension =
        project.extensions.getByType(JavaPluginExtension::class.java)
      javaPluginExtension.sourceSets.configureEach { addCheckDelombokTask(this, project) }
    }
  }

  /**
   * Adds checker.jar to all annotationProcessor configurations and checker-qual.jar to all
   * implementation configurations. If the user set `excludeTests` to true, then the jars are not
   * added to test configurations.
   */
  private fun addCFDependenciesToSourceSets(
    project: Project,
    cfExtension: CheckerFrameworkExtension,
    cfConfiguration: NamedDomainObjectProvider<Configuration>,
    checkerQualConfiguration: NamedDomainObjectProvider<Configuration>,
  ) {
    val excludeTests = cfExtension.excludeTests.getOrElse(false)
    project.plugins.withType<JavaBasePlugin> {
      project.extensions.getByName<SourceSetContainer>("sourceSets").configureEach {
        if (!excludeTests || !isTestName(name)) {
          project.configurations.named(annotationProcessorConfigurationName) {
            extendsFrom(cfConfiguration.get())
          }
          project.configurations.named(implementationConfigurationName) {
            extendsFrom(checkerQualConfiguration.get())
          }
        }
      }
    }
  }

  /** Configures every [JavaCompile] task on which the Checker Framework should be run. */
  private fun configureJavaCompileTasks(
    project: Project,
    cfExtension: CheckerFrameworkExtension,
    cfManifestFiles: FileCollection,
    writeManifestTask: TaskProvider<WriteCheckerManifestTask>,
  ) {
    // If the user passes -PskipCheckerFramework, then use that value rather than the value from
    // the configuration. Project.findProperty is used rather than
    // ProviderFactory.gradleProperty because the latter does not see extra properties and cannot
    // be queried at configuration time on Gradle 7.3.
    val skipCfProperty = project.findProperty("skipCheckerFramework")
    val skipCf =
      if (skipCfProperty != null) {
        skipCfProperty.toString() != "false"
      } else {
        cfExtension.skipCheckerFramework.getOrElse(false)
      }
    if (skipCf) {
      return
    }

    project.tasks.withType<JavaCompile>().configureEach {
      val cfCompileOptions =
        (options as ExtensionAware).extensions.getByType<CheckerFrameworkCompileExtension>()

      if (
        !cfCompileOptions.enabled.getOrElse(true) ||
          (cfExtension.excludeTests.getOrElse(false) && isTestName(name))
      ) {
        return@configureEach
      }
      dependsOn(writeManifestTask)

      // Add argument providers so that a user cannot accidentally overwrite the Checker
      // Framework options, i.e. options.compilerArgs = [...].
      options.compilerArgumentProviders.add(
        CheckerFrameworkCompilerArgumentProvider(cfExtension.extraJavacArgs)
      )
      options.forkOptions.jvmArgumentProviders.add(CheckerFrameworkJvmArgumentProvider())

      // Put the manifest directory on the annotation processor path here, rather than only in the
      // task action below, so that its contents are part of the task's inputs. The directory is
      // added lazily, so that it is absent if the task's `enabled` option is set to false after
      // this plugin has configured the task.
      // If the annotationProcessorPath is null, then annotation processing is disabled, so there
      // is no need to add things to the path.
      options.annotationProcessorPath =
        options.annotationProcessorPath?.plus(
          project.files(
            cfCompileOptions.enabled.orElse(true).map(ManifestFilesIfEnabled(cfManifestFiles))
          )
        )

      // The rest of the configuration must be done after every other configuration of the task,
      // so that neither the user nor another plugin can accidentally undo it. A task action runs
      // after all configuration, no matter in what order the configuration was registered.
      doFirst(
        ApplyCheckerFrameworkOptions(
          cfCompileOptions.enabled,
          cfExtension.checkers,
          cfManifestFiles,
        )
      )
      // Must fork for the JVM arguments to be applied.
      options.isFork = true
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

    afterEvaluateOrNow(project) {
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
      // This discards whatever this plugin put on the checker task's annotation processor path,
      // but ApplyCheckerFrameworkOptions restores the manifest directory at execution time.
      checkerTask.options.annotationProcessorPath = compileTask.options.annotationProcessorPath
      project.tasks.named("build").configure { dependsOn(checkerTask) }
    }
  }

  /**
   * Runs {@code action} after {@code project} has been evaluated, or immediately if {@code project}
   * has already been evaluated. Calling [Project.afterEvaluate] on an already-evaluated project is
   * an error.
   *
   * @param project the project to configure
   * @param action the configuration to run
   */
  private fun afterEvaluateOrNow(project: Project, action: (Project) -> Unit) {
    if (project.state.executed) {
      action(project)
    } else {
      project.afterEvaluate { action(this) }
    }
  }

  /**
   * Add the default dependencies for the given {@code jarName}.
   *
   * @param cfVersion the Checker Framework version, "local", or "dependencies"
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
      when (
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
          add(dependencies.create(objects.fileCollection().from(jarFile)))
        }
        "dependencies" -> {
          // Don't add dependencies.
        }
        else -> {
          add(dependencies.create("org.checkerframework:$jarName:$version"))
        }
      }
    }
  }

  /** Return true if the Name is a test name. */
  private fun isTestName(taskName: String): Boolean {
    return taskName.matches(Regex(".*(T|(^|[A-Z_])t)est.*"))
  }

  /**
   * Returns the Checker Framework manifest files if the Checker Framework is enabled, and no files
   * otherwise.
   */
  internal class ManifestFilesIfEnabled(private val cfManifestFiles: FileCollection) :
    Transformer<Any, Boolean> {
    override fun transform(enabled: Boolean): Any {
      return if (enabled) cfManifestFiles else emptyList<Any>()
    }
  }

  /**
   * The part of the Checker Framework configuration of a [JavaCompile] task that has to run after
   * all other configuration of the task. Because it is a task action, it runs after configuration
   * is complete, and neither the user nor another plugin can undo its effect.
   */
  internal class ApplyCheckerFrameworkOptions(
    private val enabled: Provider<Boolean>,
    private val checkers: ListProperty<String>,
    private val cfManifestFiles: FileCollection,
  ) : Action<Task> {
    override fun execute(task: Task) {
      if (!enabled.getOrElse(true)) {
        return
      }
      val checkerNames = checkers.getOrElse(emptyList())
      if (checkerNames.isEmpty()) {
        throw IllegalStateException("Must specify checkers for the Checker Framework.")
      }
      val options = (task as JavaCompile).options

      // If the annotationProcessorPath is null, then annotation processing is disabled, so there
      // is no need to add things to the path. The path already contains the manifest directory
      // unless some other configuration replaced the path.
      val annotationProcessorPath = options.annotationProcessorPath
      if (
        annotationProcessorPath != null &&
          !annotationProcessorPath.files.containsAll(cfManifestFiles.files)
      ) {
        options.annotationProcessorPath = annotationProcessorPath.plus(cfManifestFiles)
      }

      val compilerArgs = ArrayList(options.compilerArgs)
      val processorArgIndex = compilerArgs.indexOf("-processor")
      if (processorArgIndex != -1) {
        if (processorArgIndex + 1 < compilerArgs.size) {
          // Because the user already passed -processor as a compiler arg, auto discovery will
          // not work, so add the checkers to the list of processors.
          // This can't be done in CheckerFrameworkCompilerArgumentProvider because it modifies
          // existing arguments rather than adding a new one.
          val oldProcessors = compilerArgs[processorArgIndex + 1]
          val cfProcessors = checkerNames.joinToString(separator = ",")
          compilerArgs[processorArgIndex + 1] = "$oldProcessors,$cfProcessors"
          options.compilerArgs = compilerArgs
        } else {
          task.logger.warn("Found -processor argument without a value; no checkers will be used.")
        }
      }
    }
  }

  /** Provides extraJavacArgs to the compiler. */
  internal class CheckerFrameworkCompilerArgumentProvider(
    @get:Input val extraJavacArgs: ListProperty<String>
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      return extraJavacArgs.getOrElse(emptyList())
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
