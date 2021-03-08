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

package org.alephium.protocol.config

trait BrokerConfigFixture { self =>
  def groups: Int
  def brokerId: Int
  def brokerNum: Int

  implicit lazy val brokerConfig: BrokerConfig = new BrokerConfig {
    override def brokerId: Int = self.brokerId

    override def brokerNum: Int = self.brokerNum

    def groups: Int = self.groups
  }
}

object BrokerConfigFixture {
  trait Default extends BrokerConfigFixture {
    val brokerId: Int  = 0
    def brokerNum: Int = groups
  }
}
