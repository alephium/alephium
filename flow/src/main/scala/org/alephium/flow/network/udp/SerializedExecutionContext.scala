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

package org.alephium.flow.network.udp

import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import akka.dispatch.AbstractNodeQueue

object SerializedExecutionContext {
  def apply(context: ExecutionContext): SerializedExecutionContext = {
    new SerializedExecutionContext(context)
  }
}

final class SerializedExecutionContext(context: ExecutionContext)
    extends AbstractNodeQueue[Runnable]
    with Runnable
    with ExecutionContext {
  private val isOn = new AtomicBoolean(false)

  // scalastyle:off null
  @tailrec
  final def run(): Unit = {
    poll() match {
      case null => turnOff()
      case some =>
        try some.run()
        catch {
          case NonFatal(t) => context.reportFailure(t)
        }
        run()
    }
  }
  // scalastyle:on null

  def turnOff(): Unit = if (isOn.compareAndSet(true, false)) attach() else run()

  final def attach(): Unit =
    if (!isEmpty && isOn.compareAndSet(false, true)) context.execute(this)

  final override def execute(task: Runnable): Unit =
    try add(task)
    finally attach()

  final override def reportFailure(t: Throwable): Unit = context.reportFailure(t)
}
