# Checker Framework Gradle Plugin changelog

## Unreleased

The plugin is compatible with Gradle's [isolated
projects](https://docs.gradle.org/current/userguide/isolated_projects.html)
feature.

Incompatible change: a subproject no longer inherits the `cfVersion` or
`skipCheckerFramework` project property from an ancestor project.  Instead, you
should set it in the *root* project's `gradle.properties` file or on the command
line, either of which works in every subproject.  If you set it via `ext` in the
subproject's own `build.gradle` file, or in a `gradle.properties` file in the
subproject's own directory, then that setting applies only to the subproject.
Other ways of setting the two properties are unaffected: a `gradle.properties`
file in `$GRADLE_USER_HOME`, a `-Dorg.gradle.project.*` system property, and an
`ORG_GRADLE_PROJECT_*` environment variable all still work in every subproject.
