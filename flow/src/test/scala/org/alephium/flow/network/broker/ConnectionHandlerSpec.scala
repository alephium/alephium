package org.alephium.flow.network.broker

import akka.io.Tcp
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}

import org.alephium.flow.network.broker.ConnectionHandler.Ack
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.message.{Message, Ping}
import org.alephium.util.{AlephiumActorSpec, TimeStamp}

class ConnectionHandlerSpec
    extends AlephiumActorSpec("ConnectionHandler")
    with GroupConfigFixture.Default {
  trait Fixture {
    val remoteAddress = SocketUtil.temporaryServerAddress()
    val connection    = TestProbe()
    val brokerHandler = TestProbe()

    val connectionHandler = TestActorRef[ConnectionHandler.CliqueConnectionHandler](
      ConnectionHandler.clique(remoteAddress, connection.ref, brokerHandler.ref))
    connection.expectMsgType[Tcp.Register]
    connection.expectMsg(Tcp.ResumeReading)

    val message      = Ping(1, TimeStamp.now().millis)
    val messageBytes = Message.serialize(message)
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
    connectionHandler ! Tcp.CommandFailed(Tcp.Write(messageBytes, Ack(1)))
    connection.expectMsg(Tcp.ResumeWriting)

    connectionHandler ! ConnectionHandler.Send(messageBytes)
    connection.expectNoMessage()
    connectionHandler.underlyingActor.outMessageBuffer.contains(1)

    connectionHandler ! Tcp.WritingResumed
    connection.expectMsg(Tcp.Write(messageBytes, Ack(1)))
  }

  it should "close connection" in new Fixture {
    watch(connectionHandler)
    connectionHandler ! ConnectionHandler.CloseConnection
    connection.expectMsg(Tcp.Close)
    connectionHandler ! Tcp.Closed
    expectTerminated(connectionHandler)
  }
}
