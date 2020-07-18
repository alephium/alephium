package org.alephium.util

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

trait Service {
  implicit protected def executionContext: ExecutionContext

  def subServices: ArraySeq[Service] // Note: put high-level services in front

  private val startPromise: Promise[Unit] = Promise()
  private var started: Boolean            = false

  protected def startSelfOnce(): Future[Unit]

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def start(): Future[Unit] = startPromise.synchronized {
    if (!started) {
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

  final def stop(): Future[Unit] = stopPromise.synchronized {
    if (started) stopAfterStarted() else Future.successful(())
  }

  private def stopAfterStarted(): Future[Unit] = {
    if (!stopped) {
      stopped = true
      try {
        stopPromise.completeWith {
          for {
            _ <- stopSelfOnce()
            _ <- Future.sequence(subServices.map(_.stop()))
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
