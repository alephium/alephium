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

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.Duration
import scala.language.implicitConversions
import scala.reflect.ClassTag

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{TestActorRef, TestKit}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach

trait AlephiumActorSpec extends AlephiumSpec with BeforeAndAfterEach with ActorKit {
  implicit def actorSpec: AlephiumActorSpec = this

  def actorSystemConfig: Config = AlephiumActorSpec.warningConfig

  val systems: ArrayBuffer[ActorSystem] = ArrayBuffer.empty[ActorSystem]
  implicit def system: ActorSystem      = systems.synchronized(systems.head)
  var testKit: TestKit                  = _

  def createSystem(configOpt: Option[Config] = None): ActorSystem = {
    val name   = s"test-${systems.length}-${UnsecureRandom.source.nextLong()}"
    val system = ActorSystem(name, configOpt.getOrElse(actorSystemConfig))
    systems.addOne(system)
    system
  }

  override def beforeEach(): Unit = systems.synchronized {
    super.beforeEach()
    createSystem()
    testKit = new TestKit(system)
    ()
  }

  override def afterEach(): Unit = systems.synchronized {
    super.afterEach()
    systems.foreach { system =>
      TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
    }
    systems.clear()
  }

  // TestActorRef can't be used together with Stash sometimes, ref: internet
  def newTestActorRef[T <: Actor](props: Props): TestActorRef[T] = {
    newTestActorRef(props, SecureAndSlowRandom.nextU256().toString)
  }

  def newTestActorRef[T <: Actor](props: Props, name: String): TestActorRef[T] = {
    akka.testkit.TestActorRef[T](props.withDispatcher("akka.actor.default-dispatcher"), name)
  }
}

object AlephiumActorSpec {
  lazy val warningConfig: Config = config("WARNING")
  lazy val infoConfig: Config    = config("INFO")
  lazy val debugConfig: Config   = config("DEBUG")

  def config(logLevel: String): Config =
    ConfigFactory.parseString(
      s"""
         |akka {
         |  loglevel = "$logLevel"
         |  loggers = ["akka.testkit.TestEventListener"]
         |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
         |
         |  io.tcp.trace-logging = off
         |
         |  actor {
         |    debug {
         |      unhandled = on
         |    }
         |
         |    guardian-supervisor-strategy = "org.alephium.util.DefaultStrategy"
         |
         |    default-dispatcher {
         |      executor = "fork-join-executor"
         |      throughput = 1
         |    }
         |
         |    mining-dispatcher {
         |      parallelism-min = 1
         |      parallelism-max = 4
         |      parallelism-factor = 0.5
         |    }
         |  }
         |}
    """.stripMargin
    )
}

trait ActorKit {
  def testKit: TestKit

  implicit def safeActor[T](ref: ActorRef): ActorRefT[T] = ActorRefT(ref)

  def testActor: ActorRef              = testKit.testActor
  implicit def self: ActorRef          = testKit.testActor
  def watch(ref: ActorRef): ActorRef   = testKit.watch(ref)
  def unwatch(ref: ActorRef): ActorRef = testKit.unwatch(ref)
  def expectMsg[T](obj: T): T          = testKit.expectMsg(obj)
  def expectNoMsg(): Unit              = testKit.expectNoMessage()
  def expectNoMessage(): Unit          = testKit.expectNoMessage()

  def expectMsgType[T](implicit t: ClassTag[T]): T = testKit.expectMsgType[T]
  def expectMsgPF[T](max: Duration = Duration.Undefined, hint: String = "")(
      f: PartialFunction[Any, T]
  ): T = testKit.expectMsgPF(max, hint)(f)
  def expectTerminated(target: ActorRef, max: Duration = Duration.Undefined): Terminated =
    testKit.expectTerminated(target, max)
}
