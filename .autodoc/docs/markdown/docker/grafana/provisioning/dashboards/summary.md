[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/docker/grafana/provisioning/dashboards)

The `dashboard.yml` file in the `.autodoc/docs/json/docker/grafana/provisioning/dashboards` folder is a configuration file that enables the Alephium project to integrate with Prometheus, a popular monitoring and alerting tool. This integration allows Alephium to retrieve monitoring data from Prometheus and display it in a Grafana dashboard.

The configuration file specifies the API version and the provider information for Prometheus. The `providers` section contains a list of providers that Alephium can use to retrieve data. In this case, there is only one provider named "Prometheus". The `name`, `orgId`, `folder`, `type`, `disableDeletion`, and `editable` fields provide essential information about the provider, such as its name, organization ID, associated folder, type, and whether it can be deleted or edited.

The `options` field contains additional configuration options for the provider. Specifically, the `path` field specifies the path to the directory where the Prometheus dashboards are stored. This allows Alephium to retrieve the dashboards and display them in the Grafana dashboard.

Example usage:

```yaml
apiVersion: 1

providers:
- name: 'Prometheus'
  orgId: 1
  folder: ''
  type: file
  disableDeletion: false
  editable: true
  options:
    path: /etc/grafana/provisioning/dashboards
```

To use this configuration file in the Alephium project, save it as `prometheus.yml` and place it in the `/etc/grafana/provisioning/dashboards` directory. Once the file is in place, Alephium can retrieve the Prometheus dashboards and display them in the Grafana dashboard.

In summary, the `dashboard.yml` file is a crucial component for integrating Alephium with Prometheus, enabling the project to retrieve and display monitoring data in a Grafana dashboard. This integration provides valuable insights into the performance and health of the Alephium project, allowing developers to monitor and troubleshoot issues effectively.
