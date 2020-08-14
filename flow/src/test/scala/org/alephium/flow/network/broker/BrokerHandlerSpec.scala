//package org.alephium.flow.network.broker
//
//import java.net.InetSocketAddress
//
//import scala.io.StdIn
//
//import akka.actor.{ActorRef, Props}
//import akka.io.{IO, Tcp}
//import akka.testkit.TestProbe
//
//import org.alephium.flow.AlephiumFlowActorSpec
//import org.alephium.flow.core.BlockFlow
//import org.alephium.protocol.config.GroupConfig
//import org.alephium.protocol.message.{Hello, Payload}
//import org.alephium.protocol.model.{BrokerInfo, CliqueId}
//import org.alephium.util.{ActorRefT, BaseActor, Duration}
//
//class BrokerHandlerSpec extends AlephiumFlowActorSpec("BrokerHandler") {
//  it should "handshake" in {
//    val probe = TestProbe()
//    system.actorOf(Props(classOf[Listener], probe.ref, blockFlow), "listener")
//    val listenAddress = probe.expectMsgType[InetSocketAddress]
//
//    IO(Tcp) ! Tcp.Connect(listenAddress, pullMode = true)
//    expectMsgType[Tcp.Connected]
//    system.actorOf(Props(classOf[TestBrokerHandler], lastSender, blockFlow), "broker")
//
//    println("Press enter to exit...")
//    StdIn.readLine()
//    system.terminate()
//  }
//}
//
//class Listener(monitor: ActorRef, blockflow: BlockFlow) extends BaseActor {
//  import Tcp._
//  import context.system
//
//  override def preStart(): Unit = {
//    super.preStart()
//    IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 0), pullMode = true)
//  }
//
//  def receive: Receive = {
//    case Bound(localAddress) =>
//      sender() ! Tcp.ResumeAccepting(batchSize = 1)
//      monitor ! localAddress
//      context become listening
//  }
//
//  def listening: Receive = {
//    case Connected(remote, local) =>
//      log.info(s"Connected: $remote, $local")
//      context.actorOf(Props(classOf[TestBrokerHandler], sender(), blockflow))
//      ()
//  }
//}
//
//class TestBrokerHandler(connection: ActorRef, blockflow: BlockFlow) extends BrokerHandler {
//  override def brokerAlias: String = "brokerAlias"
//
//  override def handShakeDuration: Duration = Duration.ofSecondsUnsafe(20)
//
//  override val brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] =
//    ActorRefT(
//      context.actorOf(Props(classOf[TestBrokerConnectionHandler], connection, new GroupConfig {
//        override def groups: Int = 3
//      })))
//
//  val brokerInfo = BrokerInfo.unsafe(0, 1, new InetSocketAddress("localhost", 0))
//
//  override val handShakeMessage: Payload = Hello.unsafe(CliqueId.generate, brokerInfo)
//
//  override def pingFrequency: Duration = Duration.ofSecondsUnsafe(10)
//
//  override def blockflowView: BlockFlow = blockflow
//}
//
//class TestBrokerConnectionHandler(_connection: ActorRef, implicit val groupConfig: GroupConfig)
//    extends BrokerConnectionHandler {
//  override def connection: ActorRefT[Tcp.Command] = ActorRefT(_connection)
//}
