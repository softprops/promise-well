organization := "me.lessis"

name := "promise-well"

version := "0.1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.googlecode.concurrentlinkedhashmap" % "concurrentlinkedhashmap-lru" % "1.4",
  "org.scalatest" %% "scalatest" % "2.2.1" % "test")

crossScalaVersions := Seq("2.10.4", "2.11.2")

scalaVersion := crossScalaVersions.value.last
