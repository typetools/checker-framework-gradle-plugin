package org.checkerframework.plugin.gradle

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion

val testJavaHome = System.getProperty("test.java-home", System.getProperty("java.home"))
val testGradleVersion =
  System.getProperty("test.gradle-version")?.let(GradleVersion::version) ?: GradleVersion.current()

/**
 * The oldest Gradle version whose Kotlin DSL can use this plugin. This plugin is compiled by the
 * Kotlin compiler that the build's own Gradle embeds, and an older Gradle's Kotlin DSL cannot read
 * that compiler's metadata: it fails with "Protocol message contained an invalid tag (zero)" while
 * configuring the project. A build script written in Groovy is unaffected, so the plugin still
 * supports the Gradle version that [CheckerFrameworkPlugin.apply] requires.
 */
val minimumKotlinDslGradleVersion: GradleVersion = GradleVersion.version("8.2.1")

/**
 * The oldest Gradle version that the `io.freefair.lombok` plugin supports. Applying it to an older
 * Gradle fails with "Configuration with name 'mainSourceElements' not found", because the
 * `mainSourceElements` configuration was introduced in Gradle 7.4.
 */
val minimumLombokGradleVersion: GradleVersion = GradleVersion.version("7.4")

fun File.writeEmptyClass() {
  File(this.resolve("src/main/java/test").apply { mkdirs() }, "Success.java").apply {
    createNewFile()
    writeText(
      """
      package test;
      import org.checkerframework.checker.nullness.qual.Nullable;
      public class Success {
      }
      """
        .trimIndent()
    )
  }
}

fun File.writeTestClass() {
  File(this.resolve("src/test/java").apply { mkdirs() }, "Test.java").apply {
    createNewFile()
    writeText(
      """
      package test;
      import org.checkerframework.checker.nullness.qual.Nullable;
      public class Test {
      }
      """
        .trimIndent()
    )
  }
}

const val NULLNESS_FAILURE = "Failure.java:6: error: [dereference.of.nullable]"

fun File.writeNullnessFailure() {
  File(this.resolve("src/main/java/test").apply { mkdirs() }, "Failure.java").apply {
    createNewFile()
    writeText(
      """
      package test;
      import org.checkerframework.checker.nullness.qual.Nullable;
      public class Failure {
        void method() {
          String a = null;
          a.toString();
        }
      }
      """
        .trimIndent()
    )
  }
}

const val TAINTING_FAILURE = "Failure2Checkers.java:8: error: (argument)"

fun File.writeTaintingFailure() {
  File(this.resolve("src/main/java/test").apply { mkdirs() }, "Failure2Checkers.java").apply {
    createNewFile()
    writeText(
      """
      package test;

      import org.checkerframework.checker.tainting.qual.Untainted;

      public class Failure2Checkers {
        void untainted(@Untainted String s){}
        void test(String s){
          untainted(s);
        }
      }
      """
        .trimIndent()
    )
  }
}

fun File.writeResourceLeakTest() {
  File(this.resolve("src/main/java/test").apply { mkdirs() }, "ResourceLeakTest.java").apply {
    createNewFile()
    writeText(
      """
      package test;

      import org.checkerframework.checker.mustcall.qual.Owning;

      public class ResourceLeakTest {
        public static @Owning  java.io.FileWriter log = null;
      }
      """
        .trimIndent()
    )
  }
}

fun File.writeLombokExample() {
  File(this.resolve("src/main/java/lib").apply { mkdirs() }, "Foo.java").apply {
    createNewFile()
    writeText(
      """
      package lib;

      import lombok.Builder;
      import org.checkerframework.checker.nullness.qual.Nullable;

      @Builder
      public class Foo {
        private @Nullable Integer x;
        private Integer y;

        void demo() {
          x = null; // ok
          y = null; // error
        }
      }
      """
        .trimIndent()
    )
  }

  File(this.resolve("src/main/java/use").apply { mkdirs() }, "User.java").apply {
    createNewFile()
    writeText(
      """
      package use;

      import lib.Foo;

      public class User {
        Foo demo() {
          return Foo.builder()
              .x(null) // ok
              .y(null) // error
              .build();
        }
      }
      """
        .trimIndent()
    )
  }
}

/** Writes a class that uses lombok and that the Nullness Checker issues no warning about. */
fun File.writeCorrectLombokExample() {
  File(this.resolve("src/main/java/lib").apply { mkdirs() }, "Correct.java").apply {
    createNewFile()
    writeText(
      """
      package lib;

      import lombok.Getter;
      import org.checkerframework.checker.nullness.qual.Nullable;

      public class Correct {
        @Getter private @Nullable Integer x;

        void demo() {
          x = null;
        }
      }
      """
        .trimIndent()
    )
  }
}

fun File.writeErrorProneExample() {
  File(this.resolve("src/main/java/com/example").apply { mkdirs() }, "Demo.java").apply {
    createNewFile()
    writeText(
      """
      package com.example;

      import java.util.Set;

      public class Demo {
        void demo(Set<Short> s, short i) {
          s.remove(i - 1); // Error Prone error
          s.add(null); // Nullness Checker error
        }
      }
      """
        .trimIndent()
    )
  }
}

const val CONFIGURATION_CACHE_STORED = "Configuration cache entry stored."
const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

fun File.buildWithArgs(vararg tasks: String): BuildResult = prepareBuild(*tasks).build()

fun File.buildWithArgsAndFail(vararg tasks: String): BuildResult =
  prepareBuild(*tasks).buildAndFail()

fun File.prepareBuild(vararg tasks: String): GradleRunner =
  GradleRunner.create()
    .withGradleVersion(testGradleVersion.version)
    .withProjectDir(this)
    .withPluginClasspath()
    .withArguments(*tasks)
    .forwardOutput()
