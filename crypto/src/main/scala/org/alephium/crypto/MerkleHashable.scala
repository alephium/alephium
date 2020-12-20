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

package org.alephium.crypto

import scala.annotation.tailrec
import scala.reflect.ClassTag

import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

trait MerkleHashable[Hash <: RandomBytes] {
  def merkleHash: Hash
}

object MerkleHashable {
  def rootHash[Hash <: RandomBytes: ClassTag, T <: MerkleHashable[Hash]](
      hashAlgo: HashSchema[Hash],
      nodes: AVector[T]): Hash = {
    if (nodes.isEmpty) {
      hashAlgo.zero
    } else {
      val buffer = Array.tabulate(nodes.length)(nodes(_).merkleHash)
      rootHash(hashAlgo, buffer)
    }
  }

  private[crypto] def rootHash[Hash <: RandomBytes](hashAlgo: HashSchema[Hash],
                                                    buffer: Array[Hash]): Hash = {
    assume(buffer.nonEmpty)

    @inline def updateDoubleLeaves(index: Int): Unit = {
      buffer(index) = hashAlgo.hash(buffer(2 * index).bytes ++ buffer(2 * index + 1).bytes)
    }

    @inline def updateSingleLeaf(index: Int): Unit = {
      val leaf = buffer(2 * index)
      buffer(index) = hashAlgo.hash(leaf.bytes ++ leaf.bytes)
    }

    @tailrec
    def iter(size: Int): Unit = {
      if (size > 1) {
        val halfSize = size / 2
        (0 until halfSize).foreach(index => updateDoubleLeaves(index))
        if (size % 2 != 0) {
          updateSingleLeaf(halfSize)
        }
        iter((size + 1) / 2)
      }
    }

    if (buffer.length > 1) iter(buffer.length) else updateSingleLeaf(0)
    buffer(0)
  }
}
