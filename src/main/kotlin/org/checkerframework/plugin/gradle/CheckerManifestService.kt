package org.checkerframework.plugin.gradle

import java.io.File
import java.util.Collections
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Records which manifest directories the current build writes; that is, for which of them the task
 * that writes the manifest is part of the current build's task graph.
 *
 * A build service is used because its state, unlike that of an ordinary object that tasks share, is
 * the same state for every task of the build even when the configuration cache serializes the
 * tasks. The state is that of one build: a build service is created anew for each build, so a
 * manifest that an earlier build wrote is not recorded in this one.
 *
 * One instance is shared by every project of the build, so each manifest directory, rather than
 * each project, is recorded separately.
 */
internal abstract class CheckerManifestService : BuildService<BuildServiceParameters.None> {
  /**
   * The manifest directories that a task of the current build's task graph writes. It is a
   * synchronized set because tasks of different projects may run in parallel.
   */
  private val directories: MutableSet<File> = Collections.synchronizedSet(HashSet())

  /**
   * Records that the task that writes the given manifest directory is part of the current build's
   * task graph.
   *
   * @param cfBuildDir the manifest directory
   */
  fun markInTaskGraph(cfBuildDir: File) {
    directories.add(cfBuildDir)
  }

  /**
   * Returns true if the task that writes the given manifest directory is part of the current
   * build's task graph, so that the manifest on disk is the one that the current build produces.
   *
   * @param cfBuildDir the manifest directory
   * @return true if the current build writes the given manifest directory
   */
  fun isInTaskGraph(cfBuildDir: File): Boolean = directories.contains(cfBuildDir)
}
