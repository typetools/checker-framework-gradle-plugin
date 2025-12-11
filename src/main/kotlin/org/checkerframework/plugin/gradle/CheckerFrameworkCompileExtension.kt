package org.checkerframework.plugin.gradle

import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.*

abstract class CheckerFrameworkCompileExtension {
  abstract val enabled: Property<Boolean>
}
