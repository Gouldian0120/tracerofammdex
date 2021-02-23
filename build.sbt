lazy val commonSettings = Seq(
  scalacOptions ++= commonScalacOptions,
  scalaVersion := "2.12.13",
  organization := "org.ergoplatform",
  version := "0.1.2",
  resolvers += Resolver.sonatypeRepo("public"),
  resolvers += Resolver.sonatypeRepo("snapshots"),
  test in assembly := {},
  assemblyMergeStrategy in assembly := {
    case "logback.xml"                                => MergeStrategy.first
    case "module-info.class"                          => MergeStrategy.discard
    case other if other.contains("io.netty.versions") => MergeStrategy.first
    case other if other.contains("scala")             => MergeStrategy.first
    case other                                        => (assemblyMergeStrategy in assembly).value(other)
  },
  libraryDependencies ++= dependencies.Testing ++ dependencies.CompilerPlugins
)

lazy val commonScalacOptions = List(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-language:higherKinds",
  "-language:postfixOps",
  "-language:implicitConversions",
  "-feature",
  "-unchecked",
  "-Xfuture",
  "-Yno-adapted-args",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Ypartial-unification"
)

lazy val allConfigDependency = "compile->compile;test->test"

lazy val dexBackend = project
  .in(file("."))
  .withId("ergo-dex-backend")
  .settings(commonSettings)
  .settings(moduleName := "ergo-dex-backend", name := "ErgoDexBackend")
  .aggregate(core, tracker, matcher, executor, marketsApi)

lazy val core = utils
  .mkModule("dex-core", "DexCore")
  .settings(commonSettings)
  .settings(libraryDependencies ++= dependencies.core)

lazy val tracker = utils
  .mkModule("dex-tracker", "DexTracker")
  .settings(commonSettings)
  .settings(
    mainClass in assembly := Some(
      "org.ergoplatform.dex.tracker.App"
    ),
    libraryDependencies ++= dependencies.tracker
  )
  .dependsOn(core % allConfigDependency)

lazy val matcher = utils
  .mkModule("dex-matcher", "DexMatcher")
  .settings(commonSettings)
  .settings(
    mainClass in assembly := Some(
      "org.ergoplatform.dex.matcher.App"
    ),
    libraryDependencies ++= dependencies.matcher
  )
  .dependsOn(core % allConfigDependency)

lazy val executor = utils
  .mkModule("dex-executor", "DexExecutor")
  .settings(commonSettings)
  .settings(
    mainClass in assembly := Some(
      "org.ergoplatform.dex.executor.App"
    ),
    libraryDependencies ++= dependencies.executor
  )
  .dependsOn(core % allConfigDependency)

lazy val marketsApi = utils
  .mkModule("markets-api", "MarketsApi")
  .settings(commonSettings)
  .settings(
    mainClass in assembly := Some(
      "org.ergoplatform.dex.api.App"
    ),
    libraryDependencies ++= dependencies.api
  )
  .dependsOn(core % allConfigDependency)
