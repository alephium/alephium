package org.alephium.flow.network.nat

import java.net.InetAddress

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters.MapHasAsScala

import com.typesafe.scalalogging.StrictLogging
import org.bitlet.weupnp.{GatewayDevice, GatewayDiscover}

import org.alephium.flow.setting.UpnpSettings

object Upnp extends StrictLogging {
  val tcp                         = "TCP"
  val udp                         = "UDP"
  val protocols: ArraySeq[String] = ArraySeq(tcp, udp)
  val description                 = "Alephium"

  def getUpnpClient(setting: UpnpSettings): Option[UpnpClient] =
    try {
      setting.httpTimeout.foreach(duration =>
        GatewayDevice.setHttpReadTimeout(duration.millis.toInt))
      val discovery = new GatewayDiscover()
      setting.discoveryTimeout.foreach(duration => discovery.setTimeout(duration.millis.toInt))

      val gatewayMapOpt = Option(discovery.discover()).map(_.asScala).map(_.toMap)
      gatewayMapOpt.flatMap { gatewayMap =>
        gatewayMap.foreach {
          case (address, _) =>
            logger.debug(s"UPnP gateway device found on ${address.getHostAddress}")
        }
        Option(discovery.getValidGateway).map(new UpnpClient(_))
      }
    } catch {
      case t: Throwable =>
        logger.error(s"Unable to discovery valid upnp gateway, please shutdown it manually", t)
        None
    }
}

class UpnpClient(gateway: GatewayDevice) extends StrictLogging {
  val localAddress: InetAddress    = gateway.getLocalAddress
  val externalAddress: InetAddress = InetAddress.getByName(gateway.getExternalIPAddress)

  def addPortMapping(externalPort: Int, internalPort: Int): Boolean = {
    try {
      Upnp.protocols.forall {
        gateway.addPortMapping(externalPort,
                               internalPort,
                               localAddress.getHostAddress,
                               _,
                               Upnp.description)
      }
    } catch {
      case t: Throwable =>
        logger.error(
          s"Unable to map external [${externalAddress.getHostAddress}]:$externalPort to" +
            s" internal [${localAddress.getHostAddress}]:$internalPort",
          t)
        false
    }
  }

  def deletePortMapping(externalPort: Int): Boolean = {
    try {
      Upnp.protocols.forall {
        gateway.deletePortMapping(externalPort, _)
      }
    } catch {
      case t: Throwable =>
        logger.error(s"Unable to delete upnp external port $externalPort", t)
        false
    }
  }
}
