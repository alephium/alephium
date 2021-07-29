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

package org.alephium.protocol.model

import org.alephium.protocol.BuildInfo
import org.alephium.serde.Serde

final case class Version(major: Int, minor: Int, patch: Int) extends Ordered[Version] {
  def compatible(version: Version): Boolean = major == version.major

  override def compare(that: Version): Int = {
    major.compare(that.major) match {
      case 0 =>
        minor.compare(that.minor) match {
          case 0   => patch.compare(that.patch)
          case res => res
        }
      case res => res
    }
  }

  override def toString: String = s"v$major.$minor.$patch"
}

object Version {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  val release: Version          = fromReleaseVersion(BuildInfo.version).get
  val dbMinimalVersion: Version = release

  val clientId: String = s"scala-alephium/$release/${System.getProperty("os.name")}"

  def fromReleaseVersion(release: String): Option[Version] = {
    val regex = """^(\d+)\.(\d+)\.(\d+)(\+.+)?""".r
    release match {
      case regex(major, minor, patch, _) =>
        Option(Version(major.toInt, minor.toInt, patch.toInt))
      case _ => None
    }
  }

  implicit val serde: Serde[Version] =
    Serde.forProduct3(Version.apply, v => (v.major, v.minor, v.patch))
}
