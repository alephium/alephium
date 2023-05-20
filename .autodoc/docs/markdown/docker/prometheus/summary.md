[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/docker/prometheus)

The `prometheus.yml` file in the `.autodoc/docs/json/docker/prometheus` folder is a configuration file for the Prometheus monitoring and alerting system. It is specifically tailored for the Alephium project, allowing developers to monitor the performance and health of the Alephium application.

The configuration file consists of two main sections: `global` and `scrape_configs`. The `global` section sets the default scrape interval and evaluation interval to 15 seconds, which means that Prometheus will collect data from the target every 15 seconds. It also sets an external label named 'alephium' for all time series and alerts, making it easier to identify the source of the data.

The `scrape_configs` section defines the job name as 'alephium' and sets the scrape interval to 15 seconds, which is the same as the global default. The `static_configs` section within `scrape_configs` specifies the target to be scraped, which is the Alephium application running on port 12973. Additionally, it adds a label 'app' with the value 'alephium' to all scraped time series and alerts, further categorizing the collected data.

This configuration file is essential for integrating Prometheus with the Alephium project. By using this file, developers can set up Prometheus to scrape metrics from the Alephium application at the defined interval and store them in a time series database. These metrics can then be visualized and analyzed using Prometheus' built-in query language, or used to trigger alerts based on predefined rules.

To use this configuration file in the Alephium project, follow the example below:

```bash
# Start Prometheus with the alephium.yml configuration file
prometheus --config.file=alephium.yml

# Access the Prometheus web UI to view metrics and alerts
http://localhost:9090/
```

By following these steps, developers can monitor the Alephium application's performance and health, allowing them to identify potential issues and optimize the application accordingly.
