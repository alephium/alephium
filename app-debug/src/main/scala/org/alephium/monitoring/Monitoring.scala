package org.alephium.monitoring

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.jmx.JmxReporter

object Monitoring {

  val metrics: MetricRegistry = new MetricRegistry()
  val reporter: JmxReporter   = JmxReporter.forRegistry(metrics).build()

  reporter.start()
}
