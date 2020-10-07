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

package org.alephium.flow.network.bootstrap

import akka.util.ByteString

import org.alephium.serde.{Serde, SerdeError, SerdeResult}

trait SerdeUtils {
  implicit val peerInfoSerde: Serde[PeerInfo]          = PeerInfo._serde
  implicit val intraCliqueInfo: Serde[IntraCliqueInfo] = IntraCliqueInfo._serde
}

object SerdeUtils {
  def unwrap[T](deserResult: SerdeResult[(T, ByteString)]): SerdeResult[Option[(T, ByteString)]] = {
    deserResult match {
      case Right(pair)                        => Right(Some(pair))
      case Left(_: SerdeError.NotEnoughBytes) => Right(None)
      case Left(e)                            => Left(e)
    }
  }
}
