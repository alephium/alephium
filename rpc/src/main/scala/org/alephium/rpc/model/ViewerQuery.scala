package org.alephium.rpc.model

import io.circe._
import io.circe.generic.semiauto._

case class ViewerQuery(from: Option[Long])

object ViewerQuery {
  implicit val decoder: Decoder[ViewerQuery] = deriveDecoder[ViewerQuery]
  implicit val encoder: Encoder[ViewerQuery] = deriveEncoder[ViewerQuery]
}
