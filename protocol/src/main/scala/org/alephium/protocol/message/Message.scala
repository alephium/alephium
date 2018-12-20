package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.protocol.Protocol
import org.alephium.serde._

import scala.util.{Failure, Success, Try}

case class Message(header: Header, payload: Payload)

object Message {
  def apply[T <: Payload](payload: T): Message = {
    val header = Header(Protocol.version)
    Message(header, payload)
  }

  def serialize(message: Message): ByteString = {
    Serde[Header].serialize(message.header) ++ Payload.serialize(message.payload)
  }

  def _deserialize(input: ByteString): Try[(Message, ByteString)] = {
    for {
      (header, rest0)  <- Serde[Header]._deserialize(input)
      (payload, rest1) <- Payload._deserialize(rest0)
    } yield (Message(header, payload), rest1)
  }

  def deserialize(input: ByteString): Try[Message] = {
    _deserialize(input).flatMap {
      case (message, rest) =>
        if (rest.isEmpty) Success(message)
        else Failure(WrongFormatException(s"Too many bytes: #${rest.length} left"))
    }
  }
}
