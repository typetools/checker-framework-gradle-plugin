# Checker Framework Gradle Plugin changelog

## Unreleased

The plugin is compatible with Gradle's [isolated
projects](https://docs.gradle.org/current/userguide/isolated_projects.html)
feature.  Previously, enabling that feature failed every multi-project build
that applied the plugin to a subproject, with "Project ':a' cannot dynamically
look up a property in the parent project ':'", even if the build set none of the
plugin's project properties.

Incompatible change: a subproject no longer inherits the `cfVersion` or
`skipCheckerFramework` project property from an ancestor project, whether the
ancestor sets it via `ext` or in a `gradle.properties` file in the ancestor's
own directory.  A build that sets one of them like this:

```groovy
// in the top-level build.gradle
ext.cfVersion = "3.53.1"
```

must instead set it in the *root* project's `gradle.properties` file or on the
command line, either of which works in every subproject.  Setting it via `ext`
in the subproject's own `build.gradle` file, or in a `gradle.properties` file in
the subproject's own directory, also works for that subproject, but such a
setting does not reach that subproject's own subprojects.  Every other way of
setting the two properties is unaffected: a `gradle.properties` file in
`$GRADLE_USER_HOME`, a `-Dorg.gradle.project.*` system property, and an
`ORG_GRADLE_PROJECT_*` environment variable all still work in every subproject.
