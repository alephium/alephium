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

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters._

import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.{ILoggingEvent, LoggingEvent}
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.status.Status

abstract class LoggingConfigSpecBase extends AlephiumSpec {
  "Logging configuration" should "preserve color by default and accept plain output" in {
    Seq(None, Some("color"), Some("plain"), Some("unknown")).foreach { style =>
      val directory = Files.createTempDirectory("logging-config")
      val context   = new LoggerContext()
      context.putProperty("ALEPHIUM_HOME", directory.toString)
      style.foreach(context.putProperty("ALEPHIUM_LOG_STYLE", _))
      val configurator = new JoranConfigurator()
      configurator.setContext(context)
      try {
        configurator.doConfigure(getClass.getResource("/logback.xml"))
        context.getStatusManager.getCopyOfStatusList.asScala
          .exists(_.getLevel == Status.ERROR) is false
        val logger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val event  = new LoggingEvent()
        event.setLevel(Level.WARN)
        event.setLoggerName("logging-test")
        event.setMessage("log-message")
        event.setThreadName("logging-test")
        val appenders = logger.iteratorForAppenders().asScala.toVector
        appenders.length is 3
        appenders.foreach { appender =>
          val encoder = appender
            .asInstanceOf[OutputStreamAppender[ILoggingEvent]]
            .getEncoder
            .asInstanceOf[PatternLayoutEncoder]
          val rendered = new String(encoder.encode(event), StandardCharsets.UTF_8)
          rendered.contains("log-message") is true
          rendered.contains("WARN") is true
          rendered.contains("logging-test") is true
          rendered.contains("\u001b[") is (style != Some("plain"))
        }
      } finally {
        context.stop()
        AlephiumSpec.delete(directory)
      }
    }
  }
}
