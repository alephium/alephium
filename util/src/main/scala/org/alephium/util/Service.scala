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
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

import com.typesafe.scalalogging.StrictLogging

trait Service extends StrictLogging {
  def serviceName: String = this.getClass.getSimpleName

  implicit protected def executionContext: ExecutionContext

  def subServices: ArraySeq[Service] // Note: put high-level services in front

  private val startPromise: Promise[Unit] = Promise()
  private var started: Boolean            = false

  protected def startSelfOnce(): Future[Unit]

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def start(): Future[Unit] =
    startPromise.synchronized {
      if (!started) {
        logger.info(s"Starting service: ${serviceName}\n")
        started = true
        try {
          startPromise.completeWith {
            for {
              _ <- Future.sequence(subServices.view.reverse.map(_.start()))
              _ <- startSelfOnce()
            } yield ()
          }
        } catch {
          case NonFatal(e) =>
            startPromise.failure(e)
          case t: Throwable =>
            startPromise.failure(t)
            throw t
        }
      }
      startPromise.future
    }

  private val stopPromise: Promise[Unit] = Promise()
  private var stopped: Boolean           = false

  protected def stopSelfOnce(): Future[Unit]

  def stop(): Future[Unit] =
    stopPromise.synchronized {
      if (started) stopAfterStarted() else Future.successful(())
    }

  def stopSubServices(): Future[Unit] = Future.sequence(subServices.map(_.stop())).map(_ => ())

  private def stopAfterStarted(): Future[Unit] = {
    if (!stopped) {
      logger.info(s"Stopping service: ${serviceName}\n")
      stopped = true
      try {
        stopPromise.completeWith {
          for {
            _ <- stopSelfOnce()
            _ <- stopSubServices()
          } yield ()
        }
      } catch {
        case NonFatal(e) =>
          stopPromise.failure(e)
        case t: Throwable =>
          stopPromise.failure(t)
          throw t
      }
    }
    stopPromise.future
  }
}
