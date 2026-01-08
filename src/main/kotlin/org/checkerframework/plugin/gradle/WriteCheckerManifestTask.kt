package org.checkerframework.plugin.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Create META-INF/services/javax.annotation.processing.Processor and
 * META-INF/gradle/incremental.annotation.processors files so that processor autodiscovery works.
 */
abstract class WriteCheckerManifestTask : DefaultTask() {
  @get:Input abstract val checkers: ListProperty<String>

  @get:Input @get:Optional abstract val incrementalize: Property<Boolean>

  @get:OutputDirectory abstract val cfBuildDir: DirectoryProperty

  @TaskAction
  fun run() {
    if (checkers.get().isEmpty()) {
      // No need to write the file if no checkers are specified.
      return
    }
    val cfBuildDirAsFile = cfBuildDir.get().asFile
    cfBuildDirAsFile.mkdirs()
    // https://checkerframework.org/manual/#checker-auto-discovery
    writeManifestFile(
      cfBuildDirAsFile,
      checkers.get(),
      "META-INF/services/javax.annotation.processing.Processor",
      "\n",
    )
    if (incrementalize.getOrElse(true)) {
      // https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing
      writeManifestFile(
        cfBuildDirAsFile,
        checkers.get(),
        "META-INF/gradle/incremental.annotation.processors",
        ",isolating\n",
      )
    }
  }

  private fun writeManifestFile(
    cfBuildDir: File,
    checkers: List<String>,
    fileName: String,
    separator: String,
  ) {
    val processorFile = File(cfBuildDir, fileName)
    processorFile.parentFile.mkdirs()
    // Overwrites the contents of fileName if it exists or creates a new file if fileName does not
    // exist.
    processorFile.writeText(checkers.joinToString(separator = separator, postfix = separator))
  }
}
