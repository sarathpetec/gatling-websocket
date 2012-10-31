name := "gatling-websocket"

organization := "com.giltgroupe.util"

version := "0.0.1"

scalaVersion := "2.9.2"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javacOptions ++= Seq("-Xlint:deprecation", "-encoding", "utf8")

crossPaths := false

libraryDependencies ++= Seq(
    "org.eclipse.jetty" % "jetty-websocket" % "8.1.7.v20120910",
    "com.excilys.ebi.gatling" % "gatling-core" % "1.3.+",
    "com.typesafe" % "config" % "0.6.+"
)

libraryDependencies ++= Seq(
    "junit" % "junit" % "4.10" % "test",
    "org.mockito" % "mockito-core" % "1.9.+" % "test",
    "org.specs2" %% "specs2" % "1.12.+" % "test",
    "com.typesafe.akka" % "akka-testkit" % "2.0.+" % "test"
)

resolvers ++= Seq(
    "excilys" at "http://repository.excilys.com/content/groups/public"
)
