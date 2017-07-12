scalaVersion := "2.12.2"

organization := "com.github.marianobarrios"
version := "0.1-SNAPSHOT"
description := "TLS Channel"
homepage := Some(url("https://github.com/marianobarrios/tls-channel"))
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

name := "tls-channel"

fork in run := true
fork in Test := true
connectInput in run := true
testOptions in Test += Tests.Argument("-oF")

// to only use scala-library for testing
autoScalaLibrary := false

parallelExecution in Test := false

libraryDependencies ++=
  "org.slf4j" % "slf4j-api" % "1.7.25" ::
  "org.scala-lang" % "scala-library" % "2.12.2" % "test" ::
  "ch.qos.logback" % "logback-classic" % "1.2.3" % "test" ::
  "com.typesafe.scala-logging" %% "scala-logging" % "3.7.1" % "test" ::
  "org.scalatest" %% "scalatest" % "3.0.2" % "test" ::
  "com.jsuereth" %% "scala-arm" % "2.0" % "test" ::
  Nil

// Do not put Scala version in the artifact, since Scala is only used for tests.
crossPaths := false

javacOptions in (Compile,doc) ++= Seq("-link", "http://docs.oracle.com/javase/8/docs/api/")

sources in (Compile, doc) ~= (_ filter { file =>
	val parent = file.getParent
	!parent.endsWith("/engine/misc") && !parent.endsWith("/tlschannel/impl") && !parent.endsWith("/tlschannel/util")  
})

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