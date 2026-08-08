package org.checkerframework.plugin.gradle

import java.io.File
import java.util.function.BiFunction
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.Transformer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitivity
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
    // read both now and after the build script has run: now, so that the version is correct even if
    // a configuration is resolved while the build script runs, and again afterwards because the
    // build script may define the property.
    val cfVersion: Property<String> = project.objects.property(String::class.java)
    cfVersion.convention(cfExtension.version)
    setCFVersionFromProjectProperty(project, cfVersion)

    val cfConfiguration =
      project.configurations.register(CONFIGURATION_NAME) {
        description =
          "Checker Framework dependencies, which are added to all source sets' annotationProcessor configurations"
        addDefaultCFDependencies(cfVersion, project, "checker")
      }

    val checkerQualConfiguration =
      project.configurations.register("checkerQual") {
        description =
          "Pluggable type-checker qualifier dependencies, which are added to all source sets' implementation configurations"
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

    // Register the actions that add dependencies now, rather than after the build script has run,
    // because Gradle forbids adding a dependency action to a configuration that has already been
    // resolved, and a build script may resolve a configuration while it runs. Registering the
    // action does not fix what it will add: the action reads the extension's options when a
    // configuration is resolved, by which time the options have their final values.
    addCFDependenciesToSourceSets(
      project,
      cfExtension,
      cfVersion,
      cfConfiguration,
      checkerQualConfiguration,
    )

    // Configure after the build script has run, so that the values of the extensions and of the
    // project properties are the ones the user requested, no matter when a task is realized.
    afterEvaluateOrNow(project) {
      setCFVersionFromProjectProperty(project, cfVersion)
      configureJavaCompileTasks(project, cfExtension, cfManifestFiles)
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
    cfVersion: Provider<String>,
    cfConfiguration: NamedDomainObjectProvider<Configuration>,
    checkerQualConfiguration: NamedDomainObjectProvider<Configuration>,
  ) {
    project.plugins.withType<JavaBasePlugin> {
      project.extensions.getByName<SourceSetContainer>("sourceSets").configureEach {
        val annotationProcessorConfiguration =
          project.configurations.getByName(annotationProcessorConfigurationName)
        val implementationConfiguration =
          project.configurations.getByName(implementationConfigurationName)
        val isTest = isTestName(name)
        addCFDependencies(
          annotationProcessorConfiguration,
          cfConfiguration,
          cfExtension,
          cfVersion,
          project,
          "checker",
          isTest,
        )
        addCFDependencies(
          implementationConfiguration,
          checkerQualConfiguration,
          cfExtension,
          cfVersion,
          project,
          "checker-qual",
          isTest,
        )
      }
    }
  }

  /**
   * Adds the dependencies of {@code cfConfiguration} to {@code targetConfiguration}. Adds nothing
   * if {@code targetConfiguration} belongs to a test source set and the user set `excludeTests` to
   * true.
   *
   * The dependencies are copied rather than inherited via [Configuration.extendsFrom], because the
   * values that determine what to add are not necessarily known when extendsFrom would have to be
   * called: when this plugin is applied to an already-evaluated project, the user configures the
   * extension afterwards, and Gradle forbids changing a configuration's hierarchy once the
   * configuration has been observed. [Configuration.withDependencies] runs when the dependencies
   * are queried, by which time the extension's options have their final values.
   *
   * This must be called while this plugin is being applied, not after the project has been
   * evaluated, because Gradle forbids calling [Configuration.withDependencies] on a configuration
   * that has already been resolved, and a build script may resolve a source set's classpath while
   * the build script runs.
   *
   * @param targetConfiguration the configuration of a source set to add the dependencies to
   * @param cfConfiguration the configuration whose dependencies to copy
   * @param cfExtension the configuration that says whether to exclude tests
   * @param cfVersion the Checker Framework version, "local", or "dependencies"
   * @param project current project
   * @param jarName name of the jar to depend on if {@code cfConfiguration} declares no dependencies
   * @param isTest true if {@code targetConfiguration} belongs to a test source set
   */
  private fun addCFDependencies(
    targetConfiguration: Configuration,
    cfConfiguration: NamedDomainObjectProvider<Configuration>,
    cfExtension: CheckerFrameworkExtension,
    cfVersion: Provider<String>,
    project: Project,
    jarName: String,
    isTest: Boolean,
  ) {
    val dependencies = project.dependencies
    val objects = project.objects
    targetConfiguration.withDependencies {
      if (isTest && cfExtension.excludeTests.getOrElse(false)) {
        return@withDependencies
      }
      val cfConfigurationValue = cfConfiguration.get()
      // This replicates what defaultDependencies does for cfConfiguration: the default dependency
      // is used only if the user declared no dependency on cfConfiguration itself. It cannot simply
      // be copied from cfConfiguration, because cfConfiguration's defaultDependencies action has
      // not necessarily run yet; that action runs only when cfConfiguration itself is being
      // resolved.
      if (cfConfigurationValue.dependencies.isEmpty()) {
        defaultCFDependency(cfVersion, dependencies, objects, jarName)?.let { add(it) }
      }
      // Add every dependency, constraint, and exclude rule that extendsFrom would have made
      // targetConfiguration inherit, including those that cfConfiguration itself inherits.
      // A dependency is copied rather than shared, because a dependency is mutable and belongs to
      // one configuration: configuring targetConfiguration must not change cfConfiguration.
      cfConfigurationValue.allDependencies.forEach { add(it.copy()) }
      // A constraint is shared rather than copied, which is what extendsFrom does as well. A
      // constraint cannot be copied faithfully: DependencyConstraint has no `copy` method, and
      // recreating a constraint from its group and name turns a constraint on a project into a
      // constraint on an external module, which no longer selects the project.
      cfConfigurationValue.allDependencyConstraints.forEach {
        targetConfiguration.dependencyConstraints.add(it)
      }
      // The exclude rules are read from the whole hierarchy, because Configuration.getExcludeRules
      // returns only a configuration's own rules, whereas resolution applies the exclude rules of
      // every configuration in the hierarchy.
      cfConfigurationValue.hierarchy.forEach { configuration ->
        configuration.excludeRules.forEach {
          val excludeRule = HashMap<String, String>()
          it.group?.let { group -> excludeRule["group"] = group }
          it.module?.let { module -> excludeRule["module"] = module }
          if (excludeRule.isNotEmpty()) {
            targetConfiguration.exclude(excludeRule)
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
  ) {
    project.tasks.withType<JavaCompile>().configureEach {
      // The "skipCheckerFramework" project property is read here, rather than once outside this
      // block, so that its value is the one the user requested even when this plugin is applied
      // after the build script has run and this method therefore runs before the user sets the
      // property. Unlike the extension's options, a project property cannot change after the task
      // has been configured, so it is safe to do nothing at all when it says to skip.
      val skipCfProperty = skipCheckerFrameworkProperty(project)
      if (skipCfProperty == true) {
        return@configureEach
      }

      val cfCompileOptions =
        (options as ExtensionAware).extensions.getByType<CheckerFrameworkCompileExtension>()

      // Whether to run the Checker Framework on this task. Every option that this depends on can be
      // set after this plugin has configured the task, so every effect of this plugin either is
      // undone by ApplyCheckerFrameworkOptions or is computed lazily from this provider.
      val enabled: Provider<Boolean> =
        cfCompileOptions.enabled
          .orElse(true)
          .map(
            RunCheckerFramework(
              skipCfProperty,
              cfExtension.skipCheckerFramework,
              cfExtension.excludeTests,
              isTestName(name),
            )
          )

      // Add argument providers so that a user cannot accidentally overwrite the Checker
      // Framework options, i.e. options.compilerArgs = [...].
      // The provider's input is the arguments that this task will actually use, rather than
      // `extraJavacArgs` itself, so that changing `extraJavacArgs` does not make a task on which
      // the Checker Framework is disabled out of date.
      options.compilerArgumentProviders.add(
        CheckerFrameworkCompilerArgumentProvider(
          cfExtension.extraJavacArgs.zip(enabled, ExtraJavacArgsIfEnabled())
        )
      )
      options.forkOptions.jvmArgumentProviders.add(CheckerFrameworkJvmArgumentProvider(enabled))

      // Must fork for the JVM arguments to be applied. Forking is requested here, at configuration
      // time, because `isFork` is a task input and because other configuration may read it. It is
      // requested only if the Checker Framework is enabled as of now, so that a compilation that
      // does not run the Checker Framework does not fork needlessly. ApplyCheckerFrameworkOptions
      // requests forking again at execution time, so that no other configuration can undo it and so
      // that a compilation that the user enables later forks after all; and it undoes this request
      // if the user disables the Checker Framework after this configuration has run.
      val requestedFork = enabled.get() && !options.isFork
      if (requestedFork) {
        options.isFork = true
      }

      // The manifest directory, or no files if the Checker Framework is disabled. The manifest
      // directory carries a dependency on the task that writes it, so that task runs only if some
      // compilation uses the Checker Framework.
      val manifestFilesIfEnabled =
        project.files(enabled.map(ManifestFilesIfEnabled(cfManifestFiles)))

      // Declare the manifest directory as an input of the task, in addition to putting it on the
      // annotation processor path below. Other configuration may replace the annotation processor
      // path, in which case ApplyCheckerFrameworkOptions puts the manifest directory back, but too
      // late for Gradle to treat it as an input.
      inputs
        .files(manifestFilesIfEnabled)
        .withPropertyName("checkerFrameworkManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

      // Put the manifest directory on the annotation processor path here, rather than only in the
      // task action below, so that the Checker Framework is found even if the task action's
      // changes to the path come too late.
      // If the annotationProcessorPath is null, then annotation processing is disabled, so there
      // is no need to add things to the path.
      options.annotationProcessorPath =
        options.annotationProcessorPath?.plus(manifestFilesIfEnabled)

      // The rest of the configuration must be done after every other configuration of the task,
      // so that neither the user nor another plugin can accidentally undo it. A task action runs
      // after all configuration, no matter in what order the configuration was registered.
      doFirst(
        ApplyCheckerFrameworkOptions(enabled, cfExtension.checkers, cfManifestFiles, requestedFork)
      )
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
      // but ApplyCheckerFrameworkOptions restores the manifest directory at execution time, and
      // the manifest directory is a declared input of the task in any case.
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
   * Sets {@code cfVersion} from the "cfVersion" project property, if that property is set. Does
   * nothing if the property is not set, so that a caller that runs after the build script does not
   * undo a value that an earlier call established.
   *
   * @param project the project whose property to read
   * @param cfVersion the Checker Framework version to set
   */
  private fun setCFVersionFromProjectProperty(project: Project, cfVersion: Property<String>) {
    // Project.findProperty is used rather than ProviderFactory.gradleProperty because the latter
    // does not see extra properties and cannot be queried at configuration time on Gradle 7.3.
    if (project.hasProperty("cfVersion")) {
      val version =
        project.findProperty("cfVersion")
          ?: throw IllegalStateException("cfVersion property is set but has a null value")
      cfVersion.set(version.toString())
    }
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
    val skipCfProperty = project.findProperty("skipCheckerFramework") ?: return null
    return skipCfProperty.toString() != "false"
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
      defaultCFDependency(cfVersion, dependencies, objects, jarName)?.let { add(it) }
    }
  }

  /**
   * Returns the default dependency on {@code jarName}, or null if the user asked that no dependency
   * be added.
   *
   * @param cfVersion the Checker Framework version, "local", or "dependencies"
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

  /**
   * Returns true if the Checker Framework should be run on a [JavaCompile] task whose
   * `checkerFrameworkCompile.enabled` option has the given value.
   *
   * The extension's options are read when this runs, rather than when the task was configured, so
   * that their values are the ones the user requested even if the user sets them after this plugin
   * has configured the task.
   *
   * @param skipCfProperty the value of the "skipCheckerFramework" project property, or null if the
   *   property is not set
   * @param skipCheckerFramework the `skipCheckerFramework` configuration option
   * @param excludeTests the `excludeTests` configuration option
   * @param isTestTask true if the task compiles a test source set
   */
  internal class RunCheckerFramework(
    private val skipCfProperty: Boolean?,
    private val skipCheckerFramework: Provider<Boolean>,
    private val excludeTests: Provider<Boolean>,
    private val isTestTask: Boolean,
  ) : Transformer<Boolean, Boolean> {
    override fun transform(enabled: Boolean): Boolean {
      if (!enabled) {
        return false
      }
      if (skipCfProperty ?: skipCheckerFramework.getOrElse(false)) {
        return false
      }
      return !(isTestTask && excludeTests.getOrElse(false))
    }
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
    private val requestedFork: Boolean,
  ) : Action<Task> {
    override fun execute(task: Task) {
      val options = (task as JavaCompile).options
      if (!enabled.getOrElse(true)) {
        // Undo the forking that configuration time requested when the Checker Framework was still
        // enabled, so that this compilation does not fork needlessly. Forking that this plugin did
        // not request is left alone; a request that the user makes after this plugin's cannot be
        // distinguished from this plugin's, and is undone as well. The undoing is logged because
        // it discards a fork that the user may have asked for, along with its fork options.
        if (requestedFork) {
          task.logger.info(
            "The Checker Framework is disabled for ${task.path}, so ${task.path} will not fork."
          )
          options.isFork = false
        }
        return
      }
      val checkerNames = checkers.getOrElse(emptyList())
      if (checkerNames.isEmpty()) {
        throw IllegalStateException("Must specify checkers for the Checker Framework.")
      }

      // Must fork for the JVM arguments to be applied. Configuration time requests forking if the
      // Checker Framework was enabled then, but this ensures that no other configuration has undone
      // it and that a compilation that the user enabled later forks as well.
      options.isFork = true

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

  /**
   * Returns the extra javac arguments if the Checker Framework is enabled, and no arguments
   * otherwise.
   */
  internal class ExtraJavacArgsIfEnabled : BiFunction<List<String>, Boolean, List<String>> {
    override fun apply(extraJavacArgs: List<String>, enabled: Boolean): List<String> {
      return if (enabled) extraJavacArgs else emptyList()
    }
  }

  /** Provides extraJavacArgs to the compiler, if the Checker Framework is enabled. */
  internal class CheckerFrameworkCompilerArgumentProvider(
    @get:Input val extraJavacArgs: Provider<List<String>>
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      return extraJavacArgs.getOrElse(emptyList())
    }
  }

  /** Provides JVM arguments, if the Checker Framework is enabled. */
  internal class CheckerFrameworkJvmArgumentProvider(@get:Input val enabled: Provider<Boolean>) :
    CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      if (!enabled.get()) {
        return emptyList()
      }
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
