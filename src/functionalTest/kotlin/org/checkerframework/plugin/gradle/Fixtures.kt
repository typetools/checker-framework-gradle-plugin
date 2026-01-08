package org.checkerframework.plugin.gradle

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion

val testJavaHome = System.getProperty("test.java-home", System.getProperty("java.home"))
val testGradleVersion =
  System.getProperty("test.gradle-version")?.let(GradleVersion::version) ?: GradleVersion.current()

fun File.writeEmptyClass() {
  File(this.resolve("src/main/java/test").apply { mkdirs() }, "Success.java").apply {
    createNewFile()
    writeText(
      """
      package test;

      public class Success {
      }
      """
        .trimIndent()
    )
  }
}

fun File.writeTestClass() {
  File(this.resolve("src/test/java/test").apply { mkdirs() }, "Test.java").apply {
    createNewFile()
    writeText(
      """
      package test;

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
