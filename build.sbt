scalaVersion := "2.11.6"

organization := "com.despegar"
version := "0.1-SNAPSHOT"
description := "TLS Socket Channel"
homepage := Some(url("https://github.com/despegar/tls-socket-channel"))
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

autoScalaLibrary := false

parallelExecution in Test := false

libraryDependencies ++=
  "org.scala-lang" % "scala-library" % "2.11.6" % "test" ::
  "ch.qos.logback" % "logback-classic" % "1.1.2" % "test" ::
  "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2" % "test" ::
  "org.easymock" % "easymock" % "3.3" % "test" ::
  "org.scalatest" %% "scalatest" % "2.2.2" % "test" ::
  "com.jsuereth" %% "scala-arm" % "1.4" % "test" ::
  Nil

autoAPIMappings := true
publishMavenStyle := true

pomExtra := (
  <scm>
    <url>git@github.com:despegar/tls-socket-channel.git</url>
    <connection>scm:git:git@github.com:despegar/tls-socket-channel.git</connection>
    <developerConnection>scm:git:git@github.com:despegar/tls-socket-channel.git</developerConnection>
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