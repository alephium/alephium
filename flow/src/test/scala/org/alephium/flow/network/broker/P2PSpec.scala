package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import scala.collection.mutable
import scala.io.StdIn

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import akka.testkit.{SocketUtil, TestProbe}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, TestUtils}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.{BrokerInfo, CliqueId}
import org.alephium.util.{ActorRefT, AVector, Duration}

class P2PSpec extends AlephiumFlowActorSpec("P2P") {
  def createNode(): (InetSocketAddress, ActorRef) = {
    val (allHandlers, _) = TestUtils.createBlockHandlersProbe
    val blockflowSynchronize =
      system.actorOf(TestBlockFlowSynchronizer.props(blockFlow, allHandlers))

    val bindAddress        = SocketUtil.temporaryServerAddress()
    val misBehavingHandler = TestProbe()
    val tcpController =
      system.actorOf(
        TestTcpController
          .props(bindAddress, blockFlow, blockflowSynchronize, misBehavingHandler.ref))
    (bindAddress, tcpController)
  }

  val (address0, tcpHandler0) = createNode()
  val (address1, tcpHandler1) = createNode()

  Thread.sleep(1000)

  tcpHandler0 ! TcpController.ConnectTo(address1)

  println("Press enter to exit...")
  StdIn.readLine()
  system.terminate()
}

object TestTcpController {
  def props(bindAddress: InetSocketAddress,
            blockFlow: BlockFlow,
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
            misBehavingHandler: ActorRefT[MisBehavingHandler.Command]): Props =
    Props(new TestTcpController(bindAddress, blockFlow, blockFlowSynchronizer, misBehavingHandler))
}

class TestTcpController(val bindAddress: InetSocketAddress,
                        blockFlow: BlockFlow,
                        blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
                        misBehavingHandler: ActorRefT[MisBehavingHandler.Command])
    extends TcpController {
  override def brokerManager: ActorRefT[BrokerManager.Command] =
    ActorRefT(context.actorOf(TestBrokerManager.props()))

  override def maxConnections: Int = 5

  override def randomBrokers: AVector[InetSocketAddress] = AVector.empty

  override def createBrokerHandler(remote: InetSocketAddress,
                                   connection: ActorRefT[Tcp.Command]): Unit = {
    val handler = context.actorOf(
      TestBrokerHandler.props(connection, blockFlow, blockFlowSynchronizer, misBehavingHandler))
    pendingConnections -= remote
    confirmedConnections += remote -> ActorRefT(handler)
  }
}

object TestBrokerManager {
  def props(): Props = Props(new TestBrokerManager)
}

class TestBrokerManager() extends BrokerManager {
  override def isBanned(remote: InetSocketAddress): Boolean = false

  override def remove(remote: InetSocketAddress): Unit = ()
}

object TestBrokerHandler {
  def props(connection: ActorRefT[Tcp.Command],
            blockflow: BlockFlow,
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
            misBehavingHandler: ActorRefT[MisBehavingHandler.Command]): Props =
    Props(new TestBrokerHandler(connection, blockflow, blockFlowSynchronizer, misBehavingHandler))
}

class TestBrokerHandler(connection: ActorRefT[Tcp.Command],
                        val blockflow: BlockFlow,
                        val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command],
                        val misBehavingHandler: ActorRefT[MisBehavingHandler.Command])
    extends BrokerHandler {
  override def connectionInfo: BrokerHandler.ConnectionInfo = ???

  val selfBrokerInfo = BrokerInfo.unsafe(0, 1, new InetSocketAddress("localhost", 0))

  override def brokerAlias: String = selfBrokerInfo.address.toString

  override def handShakeDuration: Duration = Duration.ofSecondsUnsafe(2)

  override val brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] =
    context.actorOf(TestBrokerConnectionHandler.props(connection))

  override val handShakeMessage: Payload = Hello.unsafe(CliqueId.generate, selfBrokerInfo)

  override def pingFrequency: Duration = Duration.ofSecondsUnsafe(30)
}

object TestBrokerConnectionHandler {
  def props(connection: ActorRefT[Tcp.Command]): Props =
    Props(new TestBrokerConnectionHandler(connection))
}

class TestBrokerConnectionHandler(val connection: ActorRefT[Tcp.Command])
    extends BrokerConnectionHandler {
  override implicit def groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = 3
  }
}

object TestBlockFlowSynchronizer {
  def props(blockFlow: BlockFlow, allHandlers: AllHandlers): Props =
    Props(new TestBlockFlowSynchronizer(blockFlow, allHandlers))
}

class TestBlockFlowSynchronizer(val blockflow: BlockFlow, val allHandlers: AllHandlers)
    extends BlockFlowSynchronizer {
  override implicit def groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = 3
  }

  override def downloading: mutable.HashSet[Hash] = mutable.HashSet.empty

  override def samplePeers: AVector[ActorRefT[BrokerHandler.Command]] =
    AVector.from(brokerInfos.keys)
}
