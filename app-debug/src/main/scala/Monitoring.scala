package org.alephium

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter

object Monitoring {
  val metrics = new MetricRegistry()
  val reporter = JmxReporter.forRegistry(metrics).build()

  reporter.start()
}
