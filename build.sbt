name := "maze"
organization := "com.vsct.dt"

scalaVersion := "2.12.0-RC1"

startYear := Some(2015)
description := """Maze helps you automate your technical tests"""

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.10",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.5",
  ("org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4").exclude("org.scala-lang", "scala-library"),
  "org.apache.httpcomponents" % "httpclient" % "4.5.2",
  "com.github.docker-java" % "docker-java" % "3.0.6",
  "com.google.code.findbugs" % "jsr305" % "3.0.1",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.8.3",
  "org.scalatest" %% "scalatest" % "3.0.0",
  "net.sf.saxon" % "Saxon-HE" % "9.6.0-5"
)

publishTo := {
  if (version.value.trim.endsWith("SNAPSHOT"))
    Some("Sonatype Nexus Repository Manager" at "http://nexus/content/repositories/dt-snapshots/")
  else
    Some("Sonatype Nexus Repository Manager" at "http://nexus/content/repositories/dt-releases/")
}

