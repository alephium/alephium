[View code on GitHub](https://github.com/alephium/alephium/.autodoc/docs/json/http/src)

The `.autodoc/docs/json/http/src` folder contains essential components for handling HTTP requests and responses in the Alephium project. It includes classes for sending requests to endpoints, configuring server options, and serving a Swagger UI for API documentation.

`EndpointSender.scala` provides a convenient way to send HTTP requests to endpoints defined in the Alephium project. It handles decoding failures and provides a simple interface for sending requests and receiving responses. For example, you can create an `EndpointSender` instance and use it to send a request to an endpoint:

```scala
import org.alephium.http.EndpointSender

val sender = new EndpointSender(apiKey = None)
val response = sender.send(endpoint, params, uri)
```

`ServerOptions.scala` configures the server options for the Alephium project's HTTP server. By customizing the interceptors, the project can handle decoding failures in a specific way. The `ServerOptions` object can be imported and used in other parts of the project to access the `VertxFutureServerOptions` instance and its configured options. For example, it could be used to start the HTTP server with the custom interceptors:

```scala
import org.alephium.http.ServerOptions

val server = VertxServerBuilder
  .newBuilder[Future]
  .withOptions(ServerOptions.serverOptions)
  .build(new MyApiEndpoints)
```

`SwaggerUI.scala` provides a set of server endpoints for serving a Swagger UI, which allows users to interact with a RESTful API by providing a web-based interface for exploring the API's endpoints and parameters. You can use the `SwaggerUI` object to serve a Swagger UI for your API:

```scala
import org.alephium.http.SwaggerUI
import org.alephium.util.AVector

val openapiContent: String = ???
val contextPath: String = "docs"
val openapiFileName: String = "openapi.json"

val endpoints: AVector[ServerEndpoint[Any, Future]] = SwaggerUI(openapiContent, contextPath, openapiFileName)
```

In summary, this folder contains essential components for handling HTTP requests and responses in the Alephium project. The `EndpointSender` class simplifies sending requests to endpoints, while the `ServerOptions` object configures the server options for handling decoding failures. The `SwaggerUI` object provides a set of server endpoints for serving a Swagger UI, allowing users to interact with the API through a web-based interface. These components work together to provide a robust and user-friendly HTTP layer for the Alephium project.
