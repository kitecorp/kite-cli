# Components

Portable, cloud-agnostic components go here.

Components should NOT import from `cloud/` directories to maintain portability.

Example component:
```kite
component WebServer server {
    input number port = 8080
    input string size = "small"

    // Use generic resource types that get mapped by mixins
    resource Compute instance {
        size = size
    }

    output string endpoint = instance.publicIp + ":" + port
}
```
