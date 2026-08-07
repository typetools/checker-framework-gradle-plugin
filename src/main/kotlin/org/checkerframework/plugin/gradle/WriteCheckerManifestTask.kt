package org.checkerframework.plugin.gradle

import java.io.File
import java.io.IOException
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
    val cfBuildDirAsFile = cfBuildDir.get().asFile
    // Discard files written by a previous run, which might no longer be desired.
    // deleteRecursively() is best-effort: it returns false, rather than throwing, if it does not
    // delete everything. A failure is not reported here, because every file that this run should
    // produce is overwritten below and every file that this run must not leave behind is deleted
    // by deleteManifestFile, which does report a failure.
    cfBuildDirAsFile.deleteRecursively()
    if (!cfBuildDirAsFile.isDirectory && !cfBuildDirAsFile.mkdirs()) {
      throw IOException("Could not create directory $cfBuildDirAsFile")
    }
    // https://checkerframework.org/manual/#checker-auto-discovery
    val processorFileName = "META-INF/services/javax.annotation.processing.Processor"
    // https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing
    val incrementalFileName = "META-INF/gradle/incremental.annotation.processors"
    val checkerNames = checkers.get()
    if (checkerNames.isEmpty()) {
      // No need to write the files if no checkers are specified.
      deleteManifestFile(cfBuildDirAsFile, processorFileName)
      deleteManifestFile(cfBuildDirAsFile, incrementalFileName)
      return
    }
    writeManifestFile(cfBuildDirAsFile, checkerNames, processorFileName, "\n")
    if (incrementalize.getOrElse(true)) {
      writeManifestFile(cfBuildDirAsFile, checkerNames, incrementalFileName, ",isolating\n")
    } else {
      deleteManifestFile(cfBuildDirAsFile, incrementalFileName)
    }
  }

  /**
   * Deletes a manifest file that must not exist when this task completes; for example,
   * incremental.annotation.processors must not exist when `incrementalize` is false. Throws an
   * exception if the file exists and cannot be deleted, because leaving the file in place would
   * make javac behave as if a previous run's configuration were still in effect.
   */
  private fun deleteManifestFile(cfBuildDir: File, fileName: String) {
    val manifestFile = File(cfBuildDir, fileName)
    if (manifestFile.exists() && !manifestFile.delete()) {
      throw IOException("Could not delete $manifestFile")
    }
  }

  private fun writeManifestFile(
    cfBuildDir: File,
    checkers: List<String>,
    fileName: String,
    separator: String,
  ) {
    val processorFile = File(cfBuildDir, fileName)
    val parentDir = processorFile.parentFile
    if (!parentDir.isDirectory && !parentDir.mkdirs()) {
      throw IOException("Could not create directory $parentDir")
    }
    // Overwrites the contents of fileName if it exists or creates a new file if fileName does not
    // exist.
    processorFile.writeText(checkers.joinToString(separator = separator, postfix = separator))
  }
}
