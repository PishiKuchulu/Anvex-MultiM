# Glossary

| Term | Meaning |
|---|---|
| Agent | A configured model instance with provider reference, role, grants and limits |
| ChangeSet | The set of file additions, modifications and deletions an agent produced, derived by comparing its fork with the base snapshot |
| CAS | Content-addressed store: blobs keyed by SHA-256 |
| Snapshot | Manifest of paths to blobs, backed by recoverable content |
| Fork | An isolated real-path workspace materialized from a snapshot |
| Integrator | The only component that writes to the integration workspace |
| Gate | A mandatory verification step satisfied only by an independent party |
| Port | An interface through which the pure core reaches the outside world |
| PFP | Project Foundation Package standard (specification plus validators) |
| UNVERIFIED | A claim that was not executed in the current environment |
