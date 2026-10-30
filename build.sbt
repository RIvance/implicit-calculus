ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version := "0.2.2"

val compilerOptions = Seq("-deprecation", "-feature", "-unchecked")

lazy val root = (project in file("."))
  .settings(SmtDependency.settings)
  .settings(
    name := "implicit-calculus",
    Compile / unmanagedSourceDirectories += baseDirectory.value / "src/main/scala-jvm",
    libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
    scalacOptions ++= compilerOptions,
    Compile / run / fork := true,
    Test / fork := true,
    Test / test := (Test / runMain).toTask(" implicitlang.CalculusTests").value
  )

lazy val compilerJS = (project in file("target/compiler-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "implicit-calculus-js",
    Compile / unmanagedSourceDirectories := Seq(
      (LocalRootProject / baseDirectory).value / "src/main/scala",
      (LocalRootProject / baseDirectory).value / "src/main/scala-js"
    ),
    libraryDependencies += "org.scala-lang.modules" %%% "scala-parser-combinators" % "2.4.0",
    scalacOptions ++= compilerOptions
  )

lazy val playground = (project in file("playground/bridge"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(compilerJS)
  .settings(
    name := "implicit-playground",
    scalacOptions ++= compilerOptions,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withESFeatures(_.withESVersion(org.scalajs.linker.interface.ESVersion.ES2022))),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value.getParentFile / ".compiler/development",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value.getParentFile / ".compiler/production"
  )
