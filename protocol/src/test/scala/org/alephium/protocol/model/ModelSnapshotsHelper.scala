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

package org.alephium.protocol

import java.nio.file.{Files, Path, Paths}

import akka.util.ByteString

import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Hex}

trait ModelSnapshotsHelper extends AlephiumSpec {
  implicit class SnapshotVerifier[T: Serde](model: T)(implicit
      baseDir: String
  ) {
    def verify(name: String): ByteString = {
      val filePath = Paths.get(s"$baseDir/$name.serialized.txt")
      if (!Files.exists(filePath)) {
        info(s"New file ${filePath.toString} is created.")
        serializeAndWrite(filePath)
      }

      val serialized = Hex.from(Files.readString(filePath)).value

      serialize(model) is serialized
      deserialize[T](serialized) isE model

      serialized
    }

    // Use this when the serialized format on disk *should* be updated.
    def update(name: String): Path = {
      val filePath = Paths.get(s"$baseDir/$name.serialized.txt")
      serializeAndWrite(filePath)
    }

    private def serializeAndWrite(filePath: Path): Path = {
      val serializedHex = Hex.toHexString(serialize(model).toIndexedSeq)
      Files.createDirectories(filePath.getParent())
      Files.write(filePath, serializedHex.getBytes())
    }
  }
}
