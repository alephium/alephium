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

import org.scalatest.Assertion

import org.alephium.util.{AlephiumSpec, AVector}

class MerkleHashableSpec extends AlephiumSpec {
  val hashAlgo = Blake3
  type Hash = Blake3

  def hash(hash0: Hash): Hash = hash(hash0, hash0)

  def hash(hash0: Hash, hash1: Hash): Hash = {
    hashAlgo.hash(hash0.bytes ++ hash1.bytes)
  }

  def test(hashes: AVector[Hash], expected: Hash): Assertion = {
    MerkleHashable.rootHash(hashAlgo, hashes.toArray) is expected
    MerkleHashable.rootHash(
      hashAlgo,
      hashes.map[MerkleHashable[Hash]](h =>
        new MerkleHashable[Hash] {
          override def merkleHash: Hash = h
        }
      )
    ) is expected
  }

  it should "compute rootHash for few hashes" in {
    val hashes1   = AVector.fill(1)(hashAlgo.random)
    val expected1 = hash(hashes1(0))
    test(hashes1, expected1)

    val hashes2   = AVector.fill(2)(hashAlgo.random)
    val expected2 = hash(hashes2(0), hashes2(1))
    test(hashes2, expected2)

    val hashes3   = AVector.fill(3)(hashAlgo.random)
    val expected3 = hash(hash(hashes3(0), hashes3(1)), hash(hashes3(2)))
    test(hashes3, expected3)

    val hashes4   = AVector.fill(4)(hashAlgo.random)
    val expected4 = hash(hash(hashes4(0), hashes4(1)), hash(hashes4(2), hashes4(3)))
    test(hashes4, expected4)

    val hashes5 = AVector.fill(5)(hashAlgo.random)
    val expected5 =
      hash(hash(hash(hashes5(0), hashes5(1)), hash(hashes5(2), hashes5(3))), hash(hash(hashes5(4))))
    test(hashes5, expected5)

    val hashes6 = AVector.fill(6)(hashAlgo.random)
    val expected6 =
      hash(
        hash(hash(hashes6(0), hashes6(1)), hash(hashes6(2), hashes6(3))),
        hash(hash(hashes6(4), hashes6(5)))
      )
    test(hashes6, expected6)

    val hashes7 = AVector.fill(7)(hashAlgo.random)
    val expected7 =
      hash(
        hash(hash(hashes7(0), hashes7(1)), hash(hashes7(2), hashes7(3))),
        hash(hash(hashes7(4), hashes7(5)), hash(hashes7(6)))
      )
    test(hashes7, expected7)
  }
}
