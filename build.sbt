scalaVersion := "2.13.1"

organization := "com.github.marianobarrios"
version := "0.2.0-SNAPSHOT"
description := "TLS Channel"
homepage := Some(url("https://github.com/marianobarrios/tls-channel"))
licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php"))

publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)

scalacOptions := Seq(
	"-language:implicitConversions",
  "-Xlint",
  "-deprecation",
  "-Xfatal-warnings")

name := "tls-channel"

fork in run := true
fork in Test := true
connectInput in run := true
testOptions in Test += Tests.Argument("-oF")

// to only use scala-library for testing
autoScalaLibrary := false

parallelExecution in Test := false

libraryDependencies ++=
  "org.slf4j" % "slf4j-api" % "1.7.28" ::
  "org.scala-lang" % "scala-library" % "2.13.1" % "test" ::
  "ch.qos.logback" % "logback-classic" % "1.2.3" % "test" ::
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2" % "test" ::
  "org.scalatest" %% "scalatest" % "3.0.8" % "test" ::
  Nil

// Do not put Scala version in the artifact, since Scala is only used for tests.
crossPaths := false

javacOptions in (Compile,doc) ++= Seq(
  "-link", "https://docs.oracle.com/javase/8/docs/api/",
  "-subpackages", "tlschannel",
  "-exclude", "tlschannel.impl:tlschannel.util",
  "-sourcepath", "src/main/java"
)

publishMavenStyle := true

pomExtra := (
  <scm>
    <url>git@github.com:marianobarrios/tls-channel.git</url>
    <connection>scm:git:git@github.com:marianobarrios/tls-channel.git</connection>
    <developerConnection>scm:git:git@github.com:marianobarrios/tls-channel.git</developerConnection>
  </scm>
  <developers>
    <developer>
      <name>Mariano Barrios</name>
      <url>https://github.com/marianobarrios/</url>
    </developer>
    <developer>
      <name>Claudio Martinez</name>
      <url>https://github.com/cldmartinez/</url>
    </developer>    
  </developers>)
