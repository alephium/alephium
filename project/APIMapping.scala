// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

import Dependencies._
import sbt._
import sbt.Keys._

object APIMapping {

  def scalaDocs(classPath: Classpath, scalaVersion: String): (sbt.File, sbt.URL) = {
    val scalaLibJar =
      findDependencyJar(
        classPath = classPath,
        moduleId = "org.scala-lang" % "scala-library" % scalaVersion
      )

    val scalaDocsURL = url(s"http://www.scala-lang.org/api/$scalaVersion/")

    scalaLibJar -> scalaDocsURL
  }

  def fastParseDocs(classPath: Classpath, scalaVersion: String): (sbt.File, sbt.URL) = {
    val fastParseJar =
      findDependencyJar(
        classPath = classPath,
        moduleId = fastparse
      )

    val scalaMajorMinor = getScalaMajorMinor(scalaVersion)

    val fastParseDocsURL =
      url(
        s"https://www.javadoc.io/doc/com.lihaoyi/fastparse_$scalaMajorMinor/${fastparse.revision}/index.html"
      )

    fastParseJar -> fastParseDocsURL
  }

  private def getScalaMajorMinor(scalaVersion: String): String =
    scalaVersion.split("\\.").take(2).mkString(".")

  /** Finds the jar file for an external library.
    *
    * @param classPath
    *   The classpath to search
    * @param moduleId
    *   Target external library to find within the classpath
    * @return
    *   The jar file of external library or [[sys.error]] if not found.
    */
  private def findDependencyJar(classPath: Classpath, moduleId: ModuleID): File = {
    val jarFileOption =
      classPath.find { file =>
        file.get(moduleID.key).exists { module =>
          module.organization == moduleId.organization && module.name.startsWith(moduleId.name)
        }
      }

    jarFileOption match {
      case Some(jarFile) =>
        jarFile.data

      case None =>
        sys.error(
          s"Dependency not found: ${moduleId.organization}:${moduleId.name}:${moduleId.revision}"
        )
    }
  }
}
