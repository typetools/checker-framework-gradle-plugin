package org.checkerframework.plugin.gradle

import org.gradle.api.provider.Property

abstract class CheckerFrameworkCompileExtension {
  abstract val enabled: Property<Boolean>
}
