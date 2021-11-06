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

import org.alephium.crypto.Blake3
import org.alephium.protocol._
import org.alephium.util.{AVector, Hex, TimeStamp}

trait BlockSnapshotsFixture extends TransactionSnapshotsFixture {
  def blockHeader(txsHash: Hash) = {
    import Hex._

    BlockHeader(
      nonce = Nonce.unsafe(hex"bb557f744763ca4f5ef8079b4b76c2dbb26a4cd845fbc84d"),
      version = DefaultBlockVersion,
      blockDeps = BlockDeps.build(
        deps = AVector(
          Blake3.unsafe(hex"f4e21b0811b4d1a56d016d4980cdcb34708de0d96050e077ac6a28bc3831be97"),
          Blake3.unsafe(hex"abb46756a535f6912c90f9f06f503eed53748697f4fad672da1557e2126fa760"),
          Blake3.unsafe(hex"aecea2ddb52f00109726408bb1eb86bbde953fe696c57e6517c93b27973cc805"),
          Blake3.unsafe(hex"6725874ac2a55cd70b1ffec51b2afb46eeaf098052e5352582f2ff0135da127e"),
          Blake3.unsafe(hex"4325ecfd044d88e58c3537275381d1c3a1f410812a2847382058e5686dccfd7a")
        )
      ),
      depStateHash =
        Hash.unsafe(hex"a670c675a926606f1f01fe28660c50621fe31719414f43eccfa871432fe8ce8a"),
      txsHash = txsHash,
      // Must be later than org.alephium.protocol.ALPH.LaunchTimestamp
      timestamp = TimeStamp.unsafe(1630167995025L),
      target = Target(hex"20ffffff")
    )
  }

  def block(transactions: Transaction*): Block = {
    val coinbaseTx = coinbaseTransaction(transactions: _*)
    val allTxs     = AVector.from(transactions) :+ coinbaseTx

    val header = blockHeader(Block.calTxsHash(allTxs))
    Block(header, allTxs)
  }
}
