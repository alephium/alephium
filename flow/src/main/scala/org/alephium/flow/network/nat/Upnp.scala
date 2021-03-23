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
        GatewayDevice.setHttpReadTimeout(duration.millis.toInt)
      )
      val discovery = new GatewayDiscover()
      setting.discoveryTimeout.foreach(duration => discovery.setTimeout(duration.millis.toInt))

      val gatewayMapOpt = Option(discovery.discover()).map(_.asScala).map(_.toMap)
      gatewayMapOpt.flatMap { gatewayMap =>
        gatewayMap.foreach { case (address, _) =>
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
        gateway.addPortMapping(
          externalPort,
          internalPort,
          localAddress.getHostAddress,
          _,
          Upnp.description
        )
      }
    } catch {
      case t: Throwable =>
        logger.error(
          s"Unable to map external [${externalAddress.getHostAddress}]:$externalPort to" +
            s" internal [${localAddress.getHostAddress}]:$internalPort",
          t
        )
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
