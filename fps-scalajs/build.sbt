
ThisBuild / organization := "org.app"
ThisBuild / scalaVersion := "3.3.0"
ThisBuild / version := "0.1.0-SNAPSHOT"

val laminarVersion = "15.0.1"
val frontrouteVersion = "0.17.1"  
val nativeConverterVersion = "0.9.0"  
val javaSecurerandomVersion = "1.0.0"
//val momentVersion = "0.10.9"
//val jqueryVersion = "2.1"

lazy val root = (project in file("."))
  .enablePlugins(ScalaJSPlugin, LiveReloadJSPlugin)
  .settings(
    name := "fps",
    livereloadPublic := Some(baseDirectory.value / "public"),
    livereloadServerPort := Some(3000),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo" %%% "laminar" % laminarVersion,
      "io.frontroute" %%% "frontroute" % frontrouteVersion,
      "org.getshaka" %%% "native-converter" % nativeConverterVersion,
      ("org.scala-js" %%% "scalajs-java-securerandom" % javaSecurerandomVersion) cross CrossVersion.for3Use2_13,
      //("ru.pavkin" %%% "scala-js-momentjs" % momentVersion) cross CrossVersion.for3Use2_13,
      //("org.querki" %%% "jquery-facade" % jqueryVersion) cross CrossVersion.for3Use2_13 excludeAll (
      //  ExclusionRule(organization = "org.scala-js")
      //),
    ),
    (artifactPath / compile / fastOptJS) := Attributed.blank(livereloadPublic.value.get / "assets" / "js" / "main.js"),
    (artifactPath / compile / fullOptJS) := Attributed.blank(livereloadPublic.value.get / "assets" / "js" / "main.js")
  )
