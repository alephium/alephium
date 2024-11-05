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

package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.io.Tcp
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}
import akka.util.ByteString

import org.alephium.flow.network.broker.ConnectionHandler.Ack
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.{SignatureSchema, WireVersion}
import org.alephium.protocol.message.{Header, Hello, Message, Ping, RequestId}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.{AlephiumActorSpec, TimeStamp}

class ConnectionHandlerSpec extends AlephiumActorSpec {
  trait Fixture extends AlephiumConfigFixture {
    val remoteAddress = SocketUtil.temporaryServerAddress()
    val connection    = TestProbe()
    val brokerHandler = TestProbe()

    lazy val connectionHandler = {
      val handler = TestActorRef[ConnectionHandler.CliqueConnectionHandler](
        ConnectionHandler.clique(remoteAddress, connection.ref, brokerHandler.ref)
      )
      connection.expectMsgType[Tcp.Register]
      connection.expectMsg(Tcp.ResumeReading)
      handler
    }

    lazy val connectionHandlerActor = connectionHandler.underlyingActor
    lazy val message                = Ping(RequestId.unsafe(1), TimeStamp.now())
    lazy val messageBytes           = Message.serialize(message)

    def switchToBufferedCommunicating() = {
      connectionHandler ! ConnectionHandler.Send(messageBytes)
      connection.expectMsg(Tcp.Write(messageBytes, Ack(1)))
      connectionHandler ! Tcp.CommandFailed(Tcp.Write(messageBytes, Ack(1)))
      connection.expectMsg(Tcp.ResumeWriting)
    }
  }

  it should "publish misbehavior when receive invalid message" in new Fixture {
    val invalidVersion   = WireVersion(WireVersion.currentWireVersion.value + 1)
    val (priKey, pubKey) = SignatureSchema.secureGeneratePriPub()
    val brokerInfo =
      BrokerInfo.unsafe(CliqueId(pubKey), 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val handshakeMessage =
      Message(Header(invalidVersion), Hello.unsafe(brokerInfo.interBrokerInfo, priKey))
    val handshakeMessageBytes = Message.serialize(handshakeMessage)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    connectionHandler ! Tcp.Received(handshakeMessageBytes)
    listener.expectMsg(MisbehaviorManager.SerdeError(remoteAddress))
  }

  it should "read data from connection" in new Fixture {
    connectionHandler ! Tcp.Received(messageBytes)
    brokerHandler.expectMsg(BrokerHandler.Received(message))

    connectionHandler ! Tcp.Received(messageBytes ++ messageBytes)
    brokerHandler.expectMsg(BrokerHandler.Received(message))
    brokerHandler.expectMsg(BrokerHandler.Received(message))
  }

  it should "write data to connection" in new Fixture {
    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectMsg(Tcp.Write(messageBytes, Ack(1)))

    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectMsg(Tcp.Write(messageBytes, Ack(2)))
  }

  it should "buffer data when writing is failing" in new Fixture {
    connectionHandler ! Tcp.CommandFailed(Tcp.Write(messageBytes, Ack(-1)))
    connection.expectMsg(Tcp.ResumeWriting)
    connectionHandlerActor.outMessageBuffer.size is 1
    connectionHandlerActor.outMessageCount is 0

    connectionHandler ! Tcp.CommandFailed(Tcp.Write(messageBytes, Ack(0)))
    connection.expectMsg(Tcp.ResumeWriting)
    connectionHandlerActor.outMessageBuffer.size is 2
    connectionHandlerActor.outMessageCount is 0

    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectNoMessage()
    connectionHandlerActor.outMessageBuffer.size is 3
    connectionHandlerActor.outMessageCount is 1

    connectionHandler ! Tcp.WritingResumed
    connection.expectMsg(Tcp.Write(messageBytes, Ack(-1)))
  }

  it should "close connection" in new Fixture {
    watch(connectionHandler)
    connectionHandler ! ConnectionHandler.CloseConnection
    connection.expectMsg(Tcp.Close)
    connectionHandler ! Tcp.Closed
    expectTerminated(connectionHandler)
  }

  it should "close connection when write buffer overrun" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      "alephium.network.connection-buffer-capacity-in-byte" -> 100
    )

    watch(connectionHandler)
    switchToBufferedCommunicating()
    val data = Array.fill[Byte](100)(0x01)
    connectionHandler ! ConnectionHandler.Send(ByteString.fromArrayUnsafe(data))
    expectTerminated(connectionHandler)
  }

  it should "write all buffered data to remote when half closing" in new Fixture {
    switchToBufferedCommunicating()
    val datas = (2 until 6).map { idx =>
      val data = ByteString.fromArrayUnsafe(Array.fill[Byte](100)(idx.toByte))
      connectionHandler ! ConnectionHandler.Send(data)
      connectionHandlerActor.outMessageBuffer(idx.toLong) is data
      data
    }

    connectionHandler ! Tcp.PeerClosed
    connection.expectMsg(Tcp.Write(messageBytes, Ack(1)))
    datas.zipWithIndex.foreach { case (data, idx) =>
      connection.expectMsg(Tcp.Write(data, Ack(idx.toLong + 2)))
    }
    connectionHandler ! Ack(1)
    connectionHandlerActor.outMessageBuffer.contains(1) is false

    connectionHandler ! Tcp.CommandFailed(Tcp.Write(datas.head, Ack(2)))
    connection.expectMsg(Tcp.ResumeWriting)
    connectionHandler ! Tcp.WritingResumed
    datas.zipWithIndex.foreach { case (data, idx) =>
      val id = idx.toLong + 2
      connection.expectMsg(Tcp.Write(data, Ack(id)))
      connectionHandler ! Ack(id)
    }
    connectionHandlerActor.outMessageBuffer.isEmpty is true
    connection.expectMsg(Tcp.Close)
  }

  it should "switch to `communicating` when all buffered data write succeed" in new Fixture {
    switchToBufferedCommunicating()
    connectionHandlerActor.outMessageBuffer.size is 1
    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connectionHandlerActor.outMessageBuffer.size is 2
    connectionHandler ! Tcp.WritingResumed
    connection.expectMsg(Tcp.Write(messageBytes, Ack(1)))
    connectionHandler ! Ack(1)
    connection.expectMsg(Tcp.Write(messageBytes, Ack(2)))
    connectionHandler ! Ack(2)
    connectionHandlerActor.outMessageBuffer.size is 0

    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectMsg(Tcp.Write(messageBytes, Ack(3)))
    connectionHandlerActor.outMessageBuffer.size is 0
  }
}
