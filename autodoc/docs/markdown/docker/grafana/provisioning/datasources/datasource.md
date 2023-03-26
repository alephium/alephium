[View code on GitHub](https://github.com/alephium/alephium/blob/master/docker/grafana/provisioning/datasources/datasource.yml)

This code is a configuration file for the Alephium project. It specifies the version of the API and contains a list of datasources that should be deleted from the database, as well as a list of datasources to insert or update depending on what is available in the database.

The `deleteDatasources` section contains a list of datasources that should be deleted from the database. In this case, there is only one datasource named "Prometheus" with an orgId of 1.

The `datasources` section contains a list of datasources to insert or update. Each datasource has a name, type, access mode, and orgId. The `name` and `type` fields are required, while the `access` field can be either "direct" or "proxy". The `orgId` field defaults to 1 if not specified.

Other optional fields include `url`, `password`, `user`, `database`, `basicAuth`, `basicAuthUser`, `basicAuthPassword`, `withCredentials`, `isDefault`, `jsonData`, `secureJsonData`, `version`, and `editable`. These fields allow for additional configuration options such as specifying a URL for the datasource, enabling basic authentication, and marking a datasource as the default for the organization.

Overall, this configuration file allows for easy management of datasources in the Alephium project. By specifying which datasources should be deleted and which should be inserted or updated, developers can ensure that the database is always up-to-date with the latest information. Additionally, the various configuration options allow for customization of each datasource to fit specific needs. 

Example usage:

```
apiVersion: 1

deleteDatasources:
  - name: Prometheus
    orgId: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    orgId: 1
    url: http://prometheus:9090
    isDefault: true
    editable: true
```
## Questions: 
 1. What is the purpose of the `deleteDatasources` section in the config file?
   - The `deleteDatasources` section lists the datasources that should be deleted from the database.
2. What is the significance of the `isDefault` field in the `datasources` section?
   - The `isDefault` field marks the datasource as the default datasource for the organization, and there can only be one default datasource per organization.
3. What is the purpose of the `secureJsonData` field in the `datasources` section?
   - The `secureJsonData` field contains a JSON object of data that will be encrypted, and can be used to store sensitive information like passwords and keys.