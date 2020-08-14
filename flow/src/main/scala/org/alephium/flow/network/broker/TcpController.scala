package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import scala.collection.mutable

import akka.actor.Terminated
import akka.io.{IO, Tcp}

import org.alephium.util.{ActorRefT, AVector, BaseActor, Duration}

object TcpController {
  sealed trait Command
  case object ConnectBrokers                                   extends Command
  final case class ConnectTo(brokerAddress: InetSocketAddress) extends Command
  final case class ConnectionConfirmed(remote: InetSocketAddress,
                                       connection: ActorRefT[Tcp.Command])
      extends Command
  final case class ConnectionDenied(remote: InetSocketAddress, connection: ActorRefT[Tcp.Command])
      extends Command
}

trait TcpController extends BaseActor {
  import Tcp._

  import TcpController._

  val tcpManager: ActorRefT[Tcp.Command] = ActorRefT(IO(Tcp)(context.system))
  def brokerManager: ActorRefT[BrokerManager.Command]

  def maxConnections: Int
  def bindAddress: InetSocketAddress

  def randomBrokers: AVector[InetSocketAddress]

  val pendingConnections: mutable.Set[InetSocketAddress] = mutable.Set.empty
  val confirmedConnections: mutable.Map[InetSocketAddress, ActorRefT[BrokerHandler.Command]] =
    mutable.Map.empty

  override def preStart(): Unit = {
    super.preStart()
    tcpManager ! Bind(self, bindAddress, pullMode = true)
  }

  override def receive: Receive = binding

  def binding: Receive = {
    case Bound(localAddress) =>
      log.debug(s"Server bound to $localAddress")
      resetNewConnectionSize()
      schedule(self, ConnectBrokers, Duration.ofSecondsUnsafe(5))
      context become connection
    case CommandFailed(_: Bind) =>
      log.error(s"Binding to $bindAddress failed")
      System.exit(1)
  }

  def resetNewConnectionSize(): Unit = {
    sender() ! ResumeAccepting(maxConnections - pendingConnections.size - confirmedConnections.size)
  }

  def connection: Receive = {
    case Connected(remoteAddress, _) =>
      if (isOutgoing(remoteAddress)) {
        createBrokerHandler(remoteAddress, ActorRefT(sender()))
      } else {
        brokerManager ! BrokerManager.ConfirmConnection(remoteAddress, ActorRefT(sender()))
      }
    case failure @ CommandFailed(c: Connect) =>
      pendingConnections -= c.remoteAddress
      log.info(s"Failed to connect to ${c.remoteAddress} - $failure")
      brokerManager ! BrokerManager.Remove(c.remoteAddress)
    case ConnectionConfirmed(remote, connection) =>
      createBrokerHandler(remote, connection)
    case ConnectionDenied(remote, connection) =>
      pendingConnections -= remote
      connection ! Close
      resetNewConnectionSize()
    case Terminated(brokerHandler) =>
      val toRemove = confirmedConnections.view.filter(_._2.ref == brokerHandler).keys
      toRemove.foreach(confirmedConnections.subtractOne)
      resetNewConnectionSize()
    case ConnectBrokers =>
      randomBrokers.filter(isAvailable).foreach(connectTo)
    case ConnectTo(brokerAddress) =>
      connectTo(brokerAddress)
  }

  def isOutgoing(remoteAddress: InetSocketAddress): Boolean = {
    pendingConnections.contains(remoteAddress)
  }

  def createBrokerHandler(remote: InetSocketAddress, connection: ActorRefT[Tcp.Command]): Unit
//  def createBrokerHandler(remote: InetSocketAddress, connection: ActorRefT[Tcp.Command]): Unit = {
//    pendingConnections -= remote
//    val brokerHandler: ActorRefT[BrokerHandler.Command] = ???
//    confirmedConnections += remote -> brokerHandler
//  }

  def isAvailable(address: InetSocketAddress): Boolean = {
    !(pendingConnections.contains(address) || confirmedConnections.contains(address))
  }

  def connectTo(broker: InetSocketAddress): Unit = {
    tcpManager ! Connect(broker, pullMode = true)
    pendingConnections.addOne(broker)
  }
}
