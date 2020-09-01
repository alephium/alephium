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
