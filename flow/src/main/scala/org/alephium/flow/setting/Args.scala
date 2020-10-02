package org.alephium.flow.setting

import org.alephium.protocol.model.NetworkType

object Args {
  def parse(args: List[String]): Either[String, Option[NetworkType]] =
    args match {
      case x :: Nil => NetworkType.fromName(x) match {
        case Some(networkType)  => Right(Some(networkType))
        case None               => Left(s"Invalid network type '$x'")
      }
      case _ :: _  => Left("Too many arguments")
      case Nil => Right(None)
    }
}
