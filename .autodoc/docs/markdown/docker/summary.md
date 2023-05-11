[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/docker)

The `.autodoc/docs/json/docker` folder contains essential files for running and monitoring the Alephium blockchain node using Docker. It also includes configurations for the Alephium blockchain explorer and a GPU miner service. The folder is organized into several subfolders, each containing specific configurations and settings for different aspects of the Alephium project.

The `docker-compose.yml` file defines the services and configurations for running the Alephium blockchain node, along with Prometheus and Grafana for monitoring. By using this file, developers can easily deploy and manage the node and monitoring services. For example, running `docker-compose up -d` will start the services in detached mode.

The `docker-compose.explorer.yml` file defines the services and configurations needed to run the Alephium blockchain explorer, which allows users to view and analyze data on the Alephium blockchain network. To start the explorer, navigate to the directory containing the docker-compose file and run `docker-compose up`.

The `docker-compose.gpu-miner.yml` file defines a service called `alephium_gpu_miner` for running a GPU miner for the Alephium cryptocurrency. To start the GPU miner service, run `docker-compose up -d`.

The `grafana` subfolder contains configuration files and provisioning settings for integrating the Alephium project with Grafana, a popular open-source platform for monitoring and observability. This integration allows Alephium to display monitoring data from Prometheus in a Grafana dashboard, providing valuable insights into the performance and health of the project.

The `prometheus` subfolder contains a `prometheus.yml` configuration file for the Prometheus monitoring and alerting system, tailored for the Alephium project. By using this file, developers can set up Prometheus to scrape metrics from the Alephium application and store them in a time series database. These metrics can then be visualized and analyzed using Prometheus' built-in query language or used to trigger alerts based on predefined rules.

The `release` subfolder provides essential files for building and running the Alephium node software using Docker. The Dockerfiles create a Docker image that includes the Alephium binary, sets up the necessary directories and configuration files, and exposes the necessary ports for the node to communicate with other nodes on the network.

In summary, the `.autodoc/docs/json/docker` folder provides a comprehensive set of files and configurations for running, monitoring, and managing the Alephium blockchain node using Docker. It also includes configurations for the Alephium blockchain explorer and a GPU miner service. By using the files in this folder, developers can easily deploy and manage the Alephium project and its related services.
