[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/docker/grafana/provisioning/datasources)

The `datasource.yml` file in the `.autodoc/docs/json/docker/grafana/provisioning/datasources` folder is a configuration file for managing datasources in the Alephium project. It specifies the API version and provides a list of datasources to be deleted from the database, as well as a list of datasources to be inserted or updated depending on their availability in the database.

The file is divided into two main sections: `deleteDatasources` and `datasources`. The `deleteDatasources` section lists the datasources that should be removed from the database. In this case, there is only one datasource named "Prometheus" with an orgId of 1.

The `datasources` section lists the datasources to be inserted or updated. Each datasource is defined by a set of key-value pairs, with the `name` and `type` fields being required. The `name` field specifies the datasource's name, while the `type` field specifies its type (e.g., "prometheus"). The `access` field indicates the access mode, which can be either "direct" or "proxy".

Additional optional fields provide further configuration options for the datasource, such as specifying the database URL, enabling basic authentication, and allowing users to edit the datasource from the UI. These fields include `orgId`, `url`, `password`, `user`, `database`, `basicAuth`, `basicAuthUser`, `basicAuthPassword`, `withCredentials`, `isDefault`, `jsonData`, `secureJsonData`, `version`, and `editable`.

This configuration file enables the Alephium project to manage its datasources flexibly and customizably. For instance, if a new datasource is added to the database, it can be automatically inserted into the configuration file and made available to the project without requiring manual configuration.

Here's an example of how this code might be used:

```python
# Load the configuration file
config = load_config_file('alephium.yml')

# Get the list of datasources to delete
delete_list = config['deleteDatasources']

# Get the list of datasources to insert/update
datasources = config['datasources']

# Loop through the datasources and perform the necessary actions
for datasource in datasources:
    if datasource in delete_list:
        delete_datasource(datasource)
    else:
        insert_or_update_datasource(datasource)
```

In this example, the configuration file is loaded, and the lists of datasources to delete and insert/update are retrieved. The code then iterates through the datasources, deleting those in the delete list and inserting or updating the others as needed. This allows for efficient and flexible management of datasources within the Alephium project.
