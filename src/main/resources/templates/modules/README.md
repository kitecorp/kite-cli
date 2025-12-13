# Modules

Application-level compositions that combine components.

Example module:
```kite
import { WebServer } from "../components/webserver.kite"
import { Database } from "../components/database.kite"

component Backend api {
    input string env = "dev"

    WebServer server {
        port = 8080
    }

    Database db {
        engine = "postgres"
    }

    output string apiEndpoint = server.endpoint
}
```
