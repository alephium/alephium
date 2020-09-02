package org.alephium.flow.network.nat

import java.net.InetAddress

import scala.jdk.CollectionConverters.MapHasAsScala

import com.typesafe.scalalogging.StrictLogging
import org.bitlet.weupnp.{GatewayDevice, GatewayDiscover}

import org.alephium.flow.setting.UpnpSettings

object Upnp extends StrictLogging {
  val protocol    = "TCP"
  val description = "Aelphium"

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
      gateway.addPortMapping(externalPort,
                             internalPort,
                             localAddress.getHostAddress,
                             Upnp.protocol,
                             Upnp.description)
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
      gateway.deletePortMapping(externalPort, Upnp.protocol)
    } catch {
      case t: Throwable =>
        logger.error(s"Unable to delete upnp external port $externalPort", t)
        false
    }
  }
}
