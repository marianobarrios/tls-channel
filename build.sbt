scalaVersion := "2.11.8"

organization := "com.github.marianobarrios"
version := "0.1-SNAPSHOT"
description := "TLS Socket Channel"
homepage := Some(url("https://github.com/marianobarrios/tls-socket-channel"))
licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php"))

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

scalacOptions := Seq(
	"-language:implicitConversions", 
	"-Ywarn-dead-code",
	"-Ywarn-inaccessible",
	"-Ywarn-nullary-unit",
	"-Ywarn-nullary-override",
	"-Ywarn-infer-any")

name := "tls-socket-channel"

fork in run := true
fork in Test := true
connectInput in run := true
testOptions in Test += Tests.Argument("-oF")

// to only use scala-library for testing
autoScalaLibrary := false

parallelExecution in Test := false

libraryDependencies ++=
  "org.slf4j" % "slf4j-api" % "1.7.22" ::
  "org.scala-lang" % "scala-library" % "2.11.8" % "test" ::
  "ch.qos.logback" % "logback-classic" % "1.1.9" % "test" ::
  "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2" % "test" ::
  "org.scalatest" %% "scalatest" % "2.2.6" % "test" ::
  "com.jsuereth" %% "scala-arm" % "1.4" % "test" ::
  Nil

// Do not put Scala version in the artifact, since Scala is only used for tests.
crossPaths := false

autoAPIMappings := true
publishMavenStyle := true

pomExtra := (
  <scm>
    <url>git@github.com:marianobarrios/tls-socket-channel.git</url>
    <connection>scm:git:git@github.com:marianobarrios/tls-socket-channel.git</connection>
    <developerConnection>scm:git:git@github.com:marianobarrios/tls-socket-channel.git</developerConnection>
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