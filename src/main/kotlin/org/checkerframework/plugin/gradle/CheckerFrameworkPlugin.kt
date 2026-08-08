package org.checkerframework.plugin.gradle

import java.io.File
import javax.inject.Inject
import org.gradle.api.NamedDomainObjectProvider
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
import org.gradle.api.provider.Property
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

    val cfManifestDir = project.layout.buildDirectory.dir("checkerframework")

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
          options.annotationProcessorPath =
            options.annotationProcessorPath?.plus(project.files(cfManifestDir))

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
            project.logger.warn(
              "Found -processor argument without a value; no checkers will be used."
            )
          }
          // Must fork for the JVM arguments to be applied.
          options.isFork = true
        } else {
          throw IllegalStateException("Must specify checkers for the Checker Framework.")
        }
      }
    }

    // Read the project property again after the build script has run, because the build script may
    // define it.
    project.afterEvaluate { setCFVersionFromProjectProperty(project, cfVersion) }

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
