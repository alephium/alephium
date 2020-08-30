package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import akka.actor.Props
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.{ActorRefT, Duration}

class BrokerHandlerSpec extends AlephiumFlowActorSpec("BrokerHandler") {
  it should "handshake with new connection" in {
    val connectionHandler     = TestProbe()
    val blockFlowSynchronizer = TestProbe()
    val brokerHandler =
      TestActorRef[TestBrokerHandler](
        TestBrokerHandler.props(connectionHandler.ref, blockFlowSynchronizer.ref, blockFlow))

    connectionHandler.expectMsgType[ConnectionHandler.Send]
    brokerHandler.underlyingActor.pingPongTickOpt is None

    val brokerInfo =
      BrokerInfo.unsafe(CliqueId.generate, 0, 1, new InetSocketAddress("localhost", 0))
    val hello = Hello.unsafe(brokerInfo)
    brokerHandler ! BrokerHandler.Received(hello)
    brokerHandler.underlyingActor.pingPongTickOpt is a[Some[_]]
  }
}

object TestBrokerHandler {
  def props(brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
            blockflow: BlockFlow)(implicit brokerConfig: BrokerConfig): Props = {
    Props(new TestBrokerHandler(brokerConnectionHandler, blockFlowSynchronizer, blockflow))
  }
}

class TestBrokerHandler(val brokerConnectionHandler: ActorRefT[ConnectionHandler.Command],
                        val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
                        val blockflow: BlockFlow)(implicit val brokerConfig: BrokerConfig)
    extends BrokerHandler {
  override val remoteAddress: InetSocketAddress = SocketUtil.temporaryServerAddress()

  override def handShakeDuration: Duration = Duration.ofSecondsUnsafe(2)

  override def allHandlers: AllHandlers = ???

  val brokerInfo = BrokerInfo.unsafe(CliqueId.generate, 0, 1, new InetSocketAddress("localhost", 0))

  override val handShakeMessage: Payload = Hello.unsafe(brokerInfo)

  override def exchanging: Receive = exchangingCommon

  override def dataOrigin: DataOrigin = ???

  override def pingFrequency: Duration = Duration.ofSecondsUnsafe(10)
}
