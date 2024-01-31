// https://github.com/sbt/sbt/issues/6997#issuecomment-1310637232
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("com.eed3si9n"       % "sbt-assembly"          % "2.0.0")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"          % "2.5.0")
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"        % "1.5.11")
addSbtPlugin("org.scalastyle"    %% "scalastyle-sbt-plugin" % "1.0.0")
addSbtPlugin("org.scoverage"      % "sbt-scoverage"         % "2.0.6")
addSbtPlugin("com.typesafe.sbt"   % "sbt-native-packager"   % "1.8.1")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"               % "0.4.3")
addSbtPlugin("org.wartremover"    % "sbt-wartremover"       % "3.0.6")
addSbtPlugin("se.marcuslonnberg"  % "sbt-docker"            % "1.11.0")
addSbtPlugin("com.eed3si9n"       % "sbt-buildinfo"         % "0.11.0")
