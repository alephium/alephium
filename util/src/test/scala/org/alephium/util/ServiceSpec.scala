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

package org.alephium.util

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits

class ServiceSpec extends AlephiumFutureSpec {
  trait Test extends Service {
    implicit override protected def executionContext: ExecutionContext = Implicits.global

    var startNum: Int = 0
    var stopNum: Int  = 0

    override protected def startSelfOnce(): Future[Unit] =
      Future {
        startNum += 1
      }

    override protected def stopSelfOnce(): Future[Unit] =
      Future {
        stopNum += 1
      }
  }

  it should "start&stop only once" in {
    val foo0 = new Test { override def subServices: ArraySeq[Service] = ArraySeq.empty }
    val foo1 = new Test { override def subServices: ArraySeq[Service] = ArraySeq.empty }
    val foo2 = new Test { override def subServices: ArraySeq[Service] = ArraySeq(foo1, foo0) }
    val foo3 = new Test { override def subServices: ArraySeq[Service] = ArraySeq(foo2, foo1) }
    val foo4 = new Test { override def subServices: ArraySeq[Service] = ArraySeq(foo3, foo2) }
    foo4.start().futureValue is ()
    foo3.start().futureValue is ()
    Seq(foo0, foo1, foo2, foo3, foo4).foreach(_.startNum is 1)
    foo4.stop().futureValue is ()
    foo3.stop().futureValue is ()
    Seq(foo0, foo1, foo2, foo3, foo4).foreach(_.stopNum is 1)
  }
}
