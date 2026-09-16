# Checker Framework Gradle Plugin changelog

## Unreleased

The plugin is compatible with Gradle's [isolated
projects](https://docs.gradle.org/current/userguide/isolated_projects.html)
feature.  Previously, enabling that feature failed every multi-project build
that applied the plugin to a subproject, with "Project ':a' cannot dynamically
look up a property in the parent project ':'", even if the build set none of the
plugin's project properties.

Incompatible change: a subproject no longer reads the `cfVersion` or
`skipCheckerFramework` project property from a parent project's `ext`.  A build
that sets one of them like this:

```groovy
// in the top-level build.gradle
ext.cfVersion = "3.53.1"
```

must instead set it in a `gradle.properties` file or on the command line, either
of which works in every subproject.  Setting it via `ext` in the subproject's
own `build.gradle` file also works.  Every other way of setting the two
properties is unaffected.
