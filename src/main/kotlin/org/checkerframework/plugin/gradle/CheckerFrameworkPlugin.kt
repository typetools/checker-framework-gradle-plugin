package org.checkerframework.plugin.gradle

import java.io.File
import java.util.function.BiFunction
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.Transformer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.CompileOptions
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
    // read each time the version is queried, rather than once while the plugin is applied, because
    // the build script may define the property. A build script may also resolve a configuration,
    // which queries the version, so it is not enough to read the property after the build script
    // has run. This provider holds a Project, so it must not be captured by a task action; it is
    // queried only while a configuration is being resolved.
    val cfVersion: Provider<String> =
      project.provider { projectProperty(project, "cfVersion") }.orElse(cfExtension.version)

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

    // Records the manifest directories that this build writes, so that a compilation can tell
    // whether the manifest it finds is this build's or an earlier build's leftover.
    val manifestService =
      project.gradle.sharedServices.registerIfAbsent(
        "checkerFrameworkManifest",
        CheckerManifestService::class.java,
      ) {}

    val writeManifestTask =
      project.tasks.register("writeCheckerManifest", WriteCheckerManifestTask::class.java) {
        group = "Checker Framework"
        checkers.set(cfExtension.checkers)
        incrementalize.set(cfExtension.incrementalize)
        cfBuildDir.set(project.layout.buildDirectory.dir("checkerframework"))
        usesService(manifestService)
        // An `onlyIf` spec, rather than a task action, records that this task is in the task graph,
        // because Gradle evaluates the spec even when the task is up to date, in which case the
        // task runs no action but its output is still the one that this build produces.
        onlyIf(MarkManifestInTaskGraph(manifestService, cfBuildDir))
      }

    // A file collection containing the manifest directory, which carries a dependency on the task
    // that creates the directory's contents.
    val cfManifestFiles = project.files(writeManifestTask.flatMap { it.cfBuildDir })

    // Whether to run the Checker Framework on a [JavaCompile] task, by task name.
    // configureJavaCompileTasks sets each value; a task whose value is never set, because
    // configureJavaCompileTasks leaves the task alone, is not compiled with the Checker Framework.
    val cfEnabled = HashMap<String, Property<Boolean>>()

    // The directory that holds the whole-program inference directories, if the "wpi2" project
    // property asks that this build perform whole-program inference, and no value otherwise.
    // configureJavaCompileTasks sets it, after the build script has run.
    val wpi2RootDir = project.objects.property(File::class.java)

    project.tasks.withType<JavaCompile>().configureEach {
      (options as ExtensionAware)
        .extensions
        .create("checkerFrameworkCompile", CheckerFrameworkCompileExtension::class.java)

      // The task action that does the configuration that has to run after all other configuration
      // of the task is registered here, while this plugin is being applied, rather than in
      // configureJavaCompileTasks below. Registering it as early as possible puts it last among the
      // task's doFirst actions, because doFirst prepends. What it does is decided by the `enabled`
      // property, whose value configureJavaCompileTasks sets after the build script has run.
      val enabled = project.objects.property(Boolean::class.java)
      cfEnabled[name] = enabled
      usesService(manifestService)
      // Run after the task that writes the manifest, if that task is in the task graph, so that the
      // manifest is written and recorded before this task checks for it. This does not put that
      // task in the graph, so a compilation on which the Checker Framework is disabled still does
      // not cause the manifest to be written.
      mustRunAfter(writeManifestTask)
      doFirst(
        ApplyCheckerFrameworkOptions(
          enabled,
          cfExtension.checkers,
          cfManifestFiles,
          manifestService,
          wpi2RootDir,
        )
      )
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
      configureJavaCompileTasks(project, cfExtension, cfManifestFiles, cfEnabled, wpi2RootDir)
    }

    // Handle Lombok
    project.pluginManager.withPlugin("io.freefair.lombok") {
      val javaPluginExtension: JavaPluginExtension =
        project.extensions.getByType(JavaPluginExtension::class.java)
      javaPluginExtension.sourceSets.configureEach {
        addCheckDelombokTask(this, project, cfEnabled)
      }
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

  /**
   * Configures every [JavaCompile] task on which the Checker Framework should be run.
   *
   * @param project current project
   * @param cfExtension the plugin's configuration options
   * @param cfManifestFiles the Checker Framework manifest directory
   * @param cfEnabled for each task, the property that says whether to run the Checker Framework on
   *   it, which this method sets
   * @param wpi2RootDir the directory that holds the whole-program inference directories, which this
   *   method sets if the "wpi2" project property asks for whole-program inference
   */
  private fun configureJavaCompileTasks(
    project: Project,
    cfExtension: CheckerFrameworkExtension,
    cfManifestFiles: FileCollection,
    cfEnabled: Map<String, Property<Boolean>>,
    wpi2RootDir: Property<File>,
  ) {
    // The "wpi2" project property is read once, for the whole project, rather than for each task,
    // because whole-program inference concerns the whole build: every task and every subproject
    // reads and writes the same inference directories. A root directory is used rather than the
    // project directory for the same reason.
    val wpi2Directory = wpi2RootDirectory(project)
    wpi2RootDir.set(wpi2Directory)

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
          cfExtension.extraJavacArgs.zip(enabled, ExtraJavacArgsIfEnabled(wpi2Directory))
        )
      )
      options.forkOptions.jvmArgumentProviders.add(CheckerFrameworkJvmArgumentProvider(enabled))

      if (wpi2Directory != null) {
        // Whole-program inference is iterative: `wpi2.sh` runs the build repeatedly, moving the
        // inference results from one directory to the other between runs. Neither directory is a
        // task input or output -- every task in the build shares them, and `wpi2.sh` rather than
        // Gradle manages them -- so nothing that Gradle watches changes between runs. Users are
        // supposed to include the "clean" target in their command. This is insurance against
        // omitting that. Without this, if the user's command does not include "clean", then every
        // run after the first would skip the compilation as up to date or restore its outputs from
        // the build cache, and no annotations would be inferred.
        val notRunningCheckerFramework = NotRunningCheckerFramework(enabled)
        outputs.upToDateWhen(notRunningCheckerFramework)
        outputs.cacheIf("whole-program inference must recompile", notRunningCheckerFramework)
      }

      requestFork(this, enabled.get())

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

      // Put the manifest directory on the annotation processor path here, rather than only in
      // ApplyCheckerFrameworkOptions, so that the Checker Framework is found even if that action's
      // changes to the path come too late.
      // If the annotationProcessorPath is null, then annotation processing is disabled, so there
      // is no need to add things to the path.
      options.annotationProcessorPath =
        options.annotationProcessorPath?.plus(manifestFilesIfEnabled)

      // The rest of the configuration is done by the task action that was registered while this
      // plugin was being applied; enabling it here is what makes that action do anything.
      cfEnabled.getValue(name).set(enabled)
    }
  }

  /**
   * Makes the given task fork, if the Checker Framework will run on it.
   *
   * Forking is necessary for the JVM arguments to be applied. It is requested at configuration
   * time, rather than only by [ApplyCheckerFrameworkOptions], because `isFork` is a task input and
   * because other configuration may read it. It is requested only if the Checker Framework is
   * enabled as of now and annotation processing is configured; a null annotationProcessorPath means
   * that annotation processing is disabled, so no checker will run. [ApplyCheckerFrameworkOptions]
   * requests forking again at execution time, so that no other configuration can undo it and so
   * that a compilation that the user enables later forks after all.
   *
   * A fork that this plugin requested is never undone, not even if the Checker Framework turns out
   * not to run on the task after all. A forked compilation uses mildly more resources but otherwise
   * produces the same results, whereas undoing a fork could interfere with what another plugin or
   * the build script wants and makes this plugin's behavior less predictable.
   *
   * @param task the task to make fork
   * @param enabled whether to run the Checker Framework on the task
   */
  private fun requestFork(task: JavaCompile, enabled: Boolean) {
    val options = task.options
    if (enabled && options.annotationProcessorPath != null) {
      options.isFork = true
    }
  }

  /**
   * Adds a checkDelombokCompileJava task, for the given source set, that copies the compileJava
   * task, but changes the source to the result of the delombok task.
   *
   * @param sourceSet the source set to add the task for
   * @param project current project
   * @param cfEnabled for each task, the property that says whether to run the Checker Framework on
   *   it
   */
  private fun addCheckDelombokTask(
    sourceSet: SourceSet,
    project: Project,
    cfEnabled: Map<String, Property<Boolean>>,
  ) {

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
      checkerTask.group = "Checker Framework"
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

      // Running the Checker Framework is the only purpose of this task, so do not run the task at
      // all if the Checker Framework is disabled on it.
      val enabled = cfEnabled.getValue(checkerTask.name)
      checkerTask.onlyIf(RunOnlyIfCheckerFrameworkEnabled(enabled))

      // Request forking now, rather than relying on configureJavaCompileTasks to have done so.
      // configureJavaCompileTasks ran when this method realized the task, above, at which time the
      // task's annotationProcessorPath was usually still null and forking was therefore usually not
      // requested.
      requestFork(checkerTask, enabled.getOrElse(false))
      project.tasks.named("build").configure { dependsOn(checkerTask) }
    }
  }

  /**
   * Runs {@code action} after {@code project} has been evaluated, or immediately if it is too late
   * to register such an action, because {@code project} has already been evaluated.
   *
   * Registration is attempted rather than predicted from [org.gradle.api.ProjectState.getExecuted],
   * which becomes true while the project's `afterEvaluate` actions are running, at which time
   * registering one more action is still permitted and still runs it later than the code that is
   * registering it. Running the action immediately in that case would read configuration, such as a
   * compile task's compiler arguments, before another `afterEvaluate` action has set it.
   *
   * @param project the project to configure
   * @param action the configuration to run
   */
  private fun afterEvaluateOrNow(project: Project, action: (Project) -> Unit) {
    try {
      project.afterEvaluate { action(this) }
    } catch (e: InvalidUserCodeException) {
      // The project has been evaluated, so the action can run now.
      action(project)
    }
  }

  /**
   * Returns the value of the given project property, or null if the property is not set. Throws an
   * exception if the property is set to a null value.
   *
   * [Project.findProperty] is used rather than
   * [org.gradle.api.provider.ProviderFactory.gradleProperty] for three reasons:
   * * gradleProperty does not see extra properties, such as those that a build script sets via
   *   `ext`.
   * * gradleProperty does not see a gradle.properties file in a subproject directory:
   *   https://github.com/gradle/gradle/issues/23572, still open as of Gradle 9.2.1.
   * * On Gradle 7.3, the oldest version that this plugin supports, obtaining a gradleProperty value
   *   at configuration time fails when the configuration cache is enabled: "Cannot obtain value
   *   from provider of Gradle property 'p' at configuration time. Use a provider returned by
   *   'forUseAtConfigurationTime()' instead." That method was deprecated in Gradle 7.4 and removed
   *   in Gradle 8.0, so this plugin cannot call it.
   *
   * The workaround in https://github.com/gradle/gradle/issues/23572#issuecomment-2563603855 makes
   * gradleProperty usable despite the second problem, but it reimplements property lookup and does
   * not address the other two problems, so findProperty remains simpler and more correct here.
   *
   * @param project the project whose property to read
   * @param propertyName the name of the property to read
   */
  private fun projectProperty(project: Project, propertyName: String): String? {
    if (!project.hasProperty(propertyName)) {
      return null
    }
    val value =
      project.findProperty(propertyName)
        ?: throw IllegalStateException("$propertyName property is set but has a null value")
    return value.toString()
  }

  /**
   * Returns the value of the "skipCheckerFramework" project property: true if the user asked that
   * the Checker Framework not be run, false if the user asked that it be run, and null if the
   * property is not set. The property, if set, overrides the `skipCheckerFramework` configuration
   * option.
   *
   * @param project the project whose property to read
   */
  private fun skipCheckerFrameworkProperty(project: Project): Boolean? =
    booleanProjectProperty(project, "skipCheckerFramework")

  /**
   * Returns the value of a project property that is interpreted as a boolean: true if it is set to
   * anything but "false", false if it is set to "false", and null if it is not set.
   *
   * @param project the project whose property to read
   * @param propertyName the name of the property to read
   */
  private fun booleanProjectProperty(project: Project, propertyName: String): Boolean? =
    projectProperty(project, propertyName)?.let { it != "false" }

  /**
   * Returns the directory that holds the whole-program inference directories, if the "wpi2" project
   * property asks that this build perform whole-program inference as the `wpi2.sh` script of the
   * Checker Framework requires, and null otherwise.
   *
   * @param project the project whose property to read
   */
  private fun wpi2RootDirectory(project: Project): File? {
    if (booleanProjectProperty(project, "wpi2") != true) {
      return null
    }
    // `Project.getRootDir()` is the root directory of the build that contains the project, which in
    // a composite build is an included build or `buildSrc` rather than the build that the user
    // invoked. Every build of a composite must use one set of directories, because `wpi2.sh` looks
    // in only one place, so use the root directory of the outermost build.
    val gradle = generateSequence(project.gradle) { it.parent }.last()
    // `Gradle.getRootProject()` throws if the outermost build's root project does not exist yet, as
    // when this plugin is applied while `buildSrc` is being configured. Then fall back to this
    // build's root directory.
    return try {
      gradle.rootProject.rootDir
    } catch (e: IllegalStateException) {
      project.rootDir
    }
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
   * Runs a task only if the Checker Framework is enabled on it.
   *
   * @param enabled whether to run the Checker Framework on the task; the task does not run if the
   *   property has no value, which means that this plugin left the task alone
   */
  internal class RunOnlyIfCheckerFrameworkEnabled(private val enabled: Provider<Boolean>) :
    Spec<Task> {
    override fun isSatisfiedBy(task: Task): Boolean {
      return enabled.getOrElse(false)
    }
  }

  /**
   * Returns true if the Checker Framework is not enabled on a task, which is the condition under
   * which whole-program inference leaves the task's up-to-date check and caching alone.
   *
   * @param enabled whether to run the Checker Framework on the task; the Checker Framework does not
   *   run if the property has no value, which means that this plugin left the task alone
   */
  internal class NotRunningCheckerFramework(private val enabled: Provider<Boolean>) : Spec<Task> {
    override fun isSatisfiedBy(task: Task): Boolean {
      return !enabled.getOrElse(false)
    }
  }

  /**
   * Records, in the build service, that the task that writes the manifest is part of the current
   * build's task graph, and always lets the task run. Gradle evaluates the spec when the task is
   * about to run, which is before any [JavaCompile] task that this plugin configures, because such
   * a task either depends on the manifest or must run after it.
   *
   * @param manifestService the service that records the manifest directories that this build writes
   * @param cfBuildDir the manifest directory that the task writes
   */
  internal class MarkManifestInTaskGraph(
    private val manifestService: Provider<CheckerManifestService>,
    private val cfBuildDir: Provider<Directory>,
  ) : Spec<Task> {
    override fun isSatisfiedBy(task: Task): Boolean {
      manifestService.get().markInTaskGraph(cfBuildDir.get().asFile)
      return true
    }
  }

  /**
   * The part of the Checker Framework configuration of a [JavaCompile] task that has to run after
   * all other configuration of the task. Because it is a task action, it runs after configuration
   * is complete. It is registered while this plugin is being applied, so it also runs after every
   * `doFirst` action that a build script or another plugin registers later, and therefore has the
   * last word about the options that it sets.
   *
   * @param enabled whether to run the Checker Framework on the task; this action does nothing if
   *   the property has no value, which means that this plugin left the task alone
   * @param checkers the checkers to run
   * @param cfManifestFiles the Checker Framework manifest directory
   * @param manifestService the service that records the manifest directories that this build writes
   * @param wpi2RootDir the directory that holds the whole-program inference directories, or no
   *   value if this build is not performing whole-program inference
   */
  internal class ApplyCheckerFrameworkOptions(
    private val enabled: Provider<Boolean>,
    private val checkers: ListProperty<String>,
    private val cfManifestFiles: FileCollection,
    private val manifestService: Provider<CheckerManifestService>,
    private val wpi2RootDir: Provider<File>,
  ) : Action<Task> {
    override fun execute(task: Task) {
      val options = (task as JavaCompile).options
      if (!enabled.getOrElse(false)) {
        return
      }
      val checkerNames = checkers.getOrElse(emptyList())
      if (checkerNames.isEmpty()) {
        throw IllegalStateException("Must specify checkers for the Checker Framework.")
      }

      // Configure whole-program inference before the test below, because ExtraJavacArgsIfEnabled
      // adds the arguments that inference requires whenever the Checker Framework is enabled, so
      // the arguments that inference forbids must be removed under the same condition.
      val wpi2Directory = wpi2RootDir.orNull
      if (wpi2Directory != null) {
        Wpi2.createAjavaDirectory(wpi2Directory)
        // The forbidden arguments are removed here, rather than only from `extraJavacArgs`,
        // because the build script or another plugin may have put them in the task's compiler
        // arguments or in one of its argument providers.
        // Removing an argument is not reported, because reporting about removing `-Pwpi2` would be
        // common, and would be noise rather than information.
        val filteredArgs = ArrayList(options.compilerArgs)
        if (filteredArgs.removeAll(Wpi2::isForbiddenArgument)) {
          options.compilerArgs = filteredArgs
        }
        filterArgumentProviders(options)
      }

      // If the annotationProcessorPath is null, then annotation processing is disabled, so no
      // checker will run and there is nothing more to configure.
      val annotationProcessorPath = options.annotationProcessorPath
      if (annotationProcessorPath == null) {
        return
      }
      requireManifest(task)

      // Must fork for the JVM arguments to be applied. Configuration time requests forking if the
      // Checker Framework was enabled then, but this ensures that no other configuration has undone
      // it and that a compilation that the user enabled later forks as well.
      options.isFork = true

      // The path already contains the manifest directory unless some other configuration replaced
      // the path.
      if (!annotationProcessorPath.files.containsAll(cfManifestFiles.files)) {
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

    /**
     * Replaces each of the task's command-line argument providers by one that omits the arguments
     * that must not be present when performing whole-program inference.
     *
     * This runs at execution time, which is too late for Gradle's up-to-date check: that check read
     * the arguments before the omission. That is harmless, because the arguments that it read are a
     * superset of the ones that javac receives, so a task whose arguments changed is still out of
     * date.
     *
     * @param options the compile options whose argument providers to replace
     */
    private fun filterArgumentProviders(options: CompileOptions) {
      val filteredProviders = options.compilerArgumentProviders.map { Wpi2ArgumentFilter(it) }
      options.compilerArgumentProviders.clear()
      options.compilerArgumentProviders.addAll(filteredProviders)
    }

    /**
     * Throws an exception if the current build does not write the manifest that makes javac
     * discover the checkers, which means that no checker would run on the given task.
     *
     * The manifest directory is an input of every task that this plugin enables, so the task that
     * writes it is in the task graph -- and has run by now -- if the Checker Framework was enabled
     * on any task when Gradle built the graph. If it was enabled only afterwards, for example by a
     * `gradle.taskGraph.whenReady` action, then adding the task that writes the manifest is no
     * longer possible. Without this check, the compilation would succeed while checking nothing,
     * because javac silently runs no annotation processor when it discovers none.
     *
     * The manifest is written once per project rather than once per task, so a task that is enabled
     * too late still finds the manifest, and is checked, if another task in the same build was
     * enabled in time. Hence this checks the manifest itself rather than when the task was enabled.
     * Every task that this plugin configures must run after the task that writes the manifest, so
     * that task, if it is in the graph, has written and recorded the manifest by the time of this
     * check even when the task being checked does not depend on it.
     *
     * A manifest that an earlier build left on disk does not count, even though javac would
     * discover the checkers in it. Nothing keeps such a manifest up to date with the configuration
     * of this build, and Gradle does not know that this compilation depends on it, so the
     * compilation would run the checkers that the earlier build wrote, or be considered up to date
     * and run none at all. The build service therefore records the manifests that this build's task
     * graph writes.
     *
     * @param task the task that the Checker Framework is enabled on
     */
    private fun requireManifest(task: Task) {
      val writtenManifests = manifestService.get()
      if (
        cfManifestFiles.files.any {
          writtenManifests.isInTaskGraph(it) &&
            File(it, WriteCheckerManifestTask.PROCESSOR_FILE_NAME).isFile
        }
      ) {
        return
      }
      throw IllegalStateException(
        "The Checker Framework was enabled on ${task.path} too late for it to run: the manifest" +
          " that makes javac discover the checkers was not written. Enable the Checker Framework" +
          " while the build is being configured, no later than when Gradle builds the task graph."
      )
    }
  }

  /**
   * Returns the extra javac arguments if the Checker Framework is enabled, and no arguments
   * otherwise. If this build performs whole-program inference, then the arguments that it requires
   * are added. The arguments that it forbids are removed by [Wpi2ArgumentFilter], which filters
   * this provider's arguments along with every other provider's.
   *
   * @param wpi2RootDir the directory that holds the whole-program inference directories, or null if
   *   this build is not performing whole-program inference
   */
  internal class ExtraJavacArgsIfEnabled(private val wpi2RootDir: File?) :
    BiFunction<List<String>, Boolean, List<String>> {
    override fun apply(extraJavacArgs: List<String>, enabled: Boolean): List<String> {
      if (!enabled) {
        return emptyList()
      }
      return if (wpi2RootDir == null) extraJavacArgs
      else extraJavacArgs + Wpi2.arguments(wpi2RootDir)
    }
  }

  /**
   * Passes on another provider's arguments, omitting the ones that must not be present when
   * performing whole-program inference.
   *
   * @param delegate the provider whose arguments to filter
   */
  internal class Wpi2ArgumentFilter(private val delegate: CommandLineArgumentProvider) :
    CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      return delegate.asArguments().filterNot { it != null && Wpi2.isForbiddenArgument(it) }
    }
  }

  /**
   * The javac arguments that whole-program inference requires and the ones that it forbids. See the
   * "Whole-program inference" section of the Checker Framework manual:
   * https://checkerframework.org/manual/#whole-program-inference
   */
  internal object Wpi2 {
    /** The directory that the compiler writes inference results to. */
    private const val NEW_DIRECTORY_NAME = "whole-program-inference-new"

    /** The directory that the compiler reads inference results from. */
    private const val OUTPUT_DIRECTORY_NAME = "whole-program-inference-output"

    /**
     * Returns the arguments that make the Checker Framework perform whole-program inference. The
     * `wpi2.sh` script requires exactly these arguments, including the names of the directories.
     *
     * @param rootDir the directory that holds the whole-program inference directories
     */
    fun arguments(rootDir: File): List<String> =
      listOf(
        "-Ainfer=ajava",
        "-AinferOutputDirectory=" + File(rootDir, NEW_DIRECTORY_NAME).absolutePath,
        "-Aajava=" + File(rootDir, OUTPUT_DIRECTORY_NAME).absolutePath,
        "-Awarns",
      )

    /**
     * Creates the directory that the compiler reads inference results from, if it does not exist.
     * The Checker Framework issues a warning that contains the whole classpath if the directory
     * that `-Aajava` names does not exist, as it does not until `wpi2.sh` has completed its first
     * round of inference.
     *
     * @param rootDir the directory that holds the whole-program inference directories
     */
    fun createAjavaDirectory(rootDir: File) {
      File(rootDir, OUTPUT_DIRECTORY_NAME).mkdirs()
    }

    /**
     * Returns true if the given javac argument must not be present when performing whole-program
     * inference. `-Werror` would turn the warnings that `-Awarns` permits, and that inference
     * issues until it converges, back into errors that halt the build. `-AinferOutputOriginal`
     * would write copies of the original source files to the inference output directory, which
     * `wpi2.sh` would treat as inference results.
     *
     * @param argument a javac argument
     */
    fun isForbiddenArgument(argument: String): Boolean =
      argument == "-Werror" ||
        argument == "-AinferOutputOriginal" ||
        argument.startsWith("-AinferOutputOriginal=")
  }

  /** Provides extraJavacArgs to the compiler, if the Checker Framework is enabled. */
  internal class CheckerFrameworkCompilerArgumentProvider(
    @get:Input @get:Optional val extraJavacArgs: Provider<List<String>>
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      return extraJavacArgs.getOrElse(emptyList())
    }
  }

  /** Provides JVM arguments, if the Checker Framework is enabled. */
  internal class CheckerFrameworkJvmArgumentProvider(@get:Input val enabled: Provider<Boolean>) :
    CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String?> {
      if (!enabled.getOrElse(true)) {
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
