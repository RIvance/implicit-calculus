ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version := "0.2.2"

lazy val root = (project in file("."))
  .settings(SmtDependency.settings)
  .settings(
    name := "implicit-calculus",
    libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    Compile / run / fork := true,
    Test / fork := true,
    Test / test := (Test / runMain).toTask(" implicitlang.CalculusTests").value
  )
