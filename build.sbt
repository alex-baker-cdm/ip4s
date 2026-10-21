import com.typesafe.tools.mima.core._

ThisBuild / tlBaseVersion := "3.7"

ThisBuild / organization := "com.comcast"
ThisBuild / organizationName := "Comcast Cable Communications Management, LLC"

ThisBuild / startYear := Some(2018)

ThisBuild / developers ++= List(
  tlGitHubDev("mpilquist", "Michael Pilquist"),
  tlGitHubDev("matthughes", "Matt Hughes"),
  tlGitHubDev("nequissimus", "Tim Steinbach")
)

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("8"))

ThisBuild / crossScalaVersions := List("2.12.20", "2.13.16", "3.3.6")

ThisBuild / tlVersionIntroduced := Map("3" -> "3.0.3")

ThisBuild / doctestTestFramework := DoctestTestFramework.ScalaCheck

ThisBuild / initialCommands := "import com.comcast.ip4s._"

ThisBuild / mimaBinaryIssueFilters ++= Seq(
  ProblemFilters.exclude[ReversedMissingMethodProblem]("com.comcast.ip4s.IpAddress.toDefaultString"), // #553
  ProblemFilters.exclude[DirectMissingMethodProblem]("com.comcast.ip4s.Ipv6Address.toInetAddress"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("com.comcast.ip4s.Dns.*"), // sealed trait
  // Scala 3 (erroneously?) considered Multicast/SourceSpecificMulticast as sum types
  ProblemFilters.exclude[DirectMissingMethodProblem]("com.comcast.ip4s.Multicast.ordinal"),
  ProblemFilters.exclude[MissingTypesProblem]("com.comcast.ip4s.Multicast$"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("com.comcast.ip4s.SourceSpecificMulticast.ordinal"),
  ProblemFilters.exclude[MissingTypesProblem]("com.comcast.ip4s.SourceSpecificMulticast$"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("com.comcast.ip4s.IpAddress.isPrivate"), // #562
  ProblemFilters.exclude[ReversedMissingMethodProblem]("com.comcast.ip4s.IpAddress.isLoopback"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("com.comcast.ip4s.IpAddress.isLinkLocal")
)

// GitHub Actions supply-chain hardening: pin third-party actions to commit SHAs
// (tag kept as a trailing comment) and run with least-privilege GITHUB_TOKEN permissions.
import org.typelevel.sbt.gha.{PermissionValue, Permissions}

lazy val pinnedActions: Map[(String, String), String] = Map(
  ("actions", "checkout") -> "11d5960a326750d5838078e36cf38b85af677262 # v4.4.0",
  ("actions", "setup-java") -> "cf277c60eb25467037889841efdb72551f06f6c3 # v4.9.1",
  ("actions", "upload-artifact") -> "ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2",
  ("actions", "download-artifact") -> "d3f86a106a0bac45b974a628896c90dbdf5c8093 # v4.3.0",
  ("sbt", "setup-sbt") -> "ecad189dd245444b9ae0a0cd5922fd938b88906f # v1.5.12",
  ("scalacenter", "sbt-dependency-submission") -> "f3c0455a87097de07b66c3dc1b8619b5976c1c89 # v2.3.1"
)

def pinAction(step: WorkflowStep): WorkflowStep = step match {
  case use: WorkflowStep.Use =>
    use.ref match {
      case UseRef.Public(owner, repo, _) if pinnedActions.contains((owner, repo)) =>
        use.withRef(UseRef.Public(owner, repo, pinnedActions((owner, repo))))
      case _ => use
    }
  case other => other
}

lazy val contentsOnly: Permissions.Specify = Permissions.Specify(
  actions = PermissionValue.None,
  checks = PermissionValue.None,
  contents = PermissionValue.Read,
  deployments = PermissionValue.None,
  idToken = PermissionValue.None,
  issues = PermissionValue.None,
  packages = PermissionValue.None,
  pages = PermissionValue.None,
  pullRequests = PermissionValue.None,
  repositoryProjects = PermissionValue.None,
  securityEvents = PermissionValue.None,
  statuses = PermissionValue.None
)

ThisBuild / githubWorkflowPermissions := Some(contentsOnly)
// clean.yml is a static template shipped with sbt-typelevel; it is maintained by hand to carry a permissions block.
ThisBuild / githubWorkflowIncludeClean := false
ThisBuild / githubWorkflowGeneratedCI ~= { jobs =>
  jobs.map { job =>
    val pinned = job.withSteps(job.steps.map(pinAction))
    // sbt-dependency-submission writes to the dependency graph API
    if (job.id == "dependency-submission")
      pinned.withPermissions(Some(contentsOnly.withContents(PermissionValue.Write)))
    else pinned
  }
}

lazy val root = tlCrossRootProject.aggregate(core, testKit)

lazy val testKit = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("./test-kit"))
  .settings(commonSettings)
  .settings(
    name := "ip4s-test-kit"
  )
  .settings(mimaPreviousArtifacts := Set.empty)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalacheck" %%% "scalacheck" % "1.18.1",
      "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0-RC1" % Test
    )
  )
  .jvmSettings(
    libraryDependencies += "com.google.guava" % "guava" % "33.4.8-jre" % "test"
  )
  .dependsOn(core % "compile->compile")

lazy val testKitJVM = testKit.jvm
lazy val testKitJS = testKit.js
  .disablePlugins(DoctestPlugin)
  .enablePlugins(ScalaJSBundlerPlugin)
lazy val testKitNative = testKit.js
  .disablePlugins(DoctestPlugin)
  .settings(commonNativeSettings)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name := "ip4s-core",
    libraryDependencies ++= {
      if (tlIsScala3.value) Nil
      else List("org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided")
    },
    scalacOptions := scalacOptions.value.filterNot(_ == "-source:3.0-migration"),
    Compile / doc / scalacOptions ++= (if (scalaVersion.value.startsWith("2.")) Seq("-nowarn")
                                       else Nil)
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "literally" % "1.2.0",
      "org.typelevel" %%% "cats-core" % "2.13.0",
      "org.typelevel" %%% "cats-effect" % "3.7.0-RC1",
      "org.scalacheck" %%% "scalacheck" % "1.18.1" % Test
    )
  )
  .nativeSettings(
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "idna4s-core" % "0.1.0"
    )
  )

lazy val coreJVM = core.jvm.settings(
  doctestIgnoreRegex := Some(".*Literals.scala")
)

lazy val coreJS = core.js
  .disablePlugins(DoctestPlugin)
  .enablePlugins(ScalaJSBundlerPlugin)
  .settings(
    Compile / npmDependencies += "punycode" -> "2.1.1",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val coreNative = core.native
  .disablePlugins(DoctestPlugin)
  .settings(commonNativeSettings)

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(MdocPlugin)
  .dependsOn(coreJVM)
  .settings(
    mdocIn := baseDirectory.value / "src",
    mdocOut := baseDirectory.value / "../docs",
    githubWorkflowArtifactUpload := false,
    libraryDependencies += "org.typelevel" %%% "cats-effect" % "3.6.3"
  )

lazy val commonSettings = Seq(
  Compile / unmanagedResources ++= {
    val base = baseDirectory.value / ".."
    (base / "NOTICE") +: (base / "LICENSE") +: (base / "CONTRIBUTING") +: ((base / "licenses") * "LICENSE_*").get
  }
)

lazy val commonNativeSettings = Seq(
  tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.8.0").toMap
)
