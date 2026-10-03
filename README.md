<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 3 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 3 minutes.</i></p>

# Kestra Quickwit Plugin

## Why

- **What user problem does this solve?** Teams that run [Quickwit](https://quickwit.io) today drive it from Kestra with `io.kestra.plugin.core.http.Request` and hand-built JSON: every flow repeats the base URL, the `api/v1` prefix, the query-string encoding, and the error handling. This plugin wraps the Quickwit REST API in typed tasks, so a flow only declares *what* it wants to search, ingest or manage.
- **Why would a team adopt this plugin in a workflow?** Search, ingest, index, source and delete-task operations become first-class tasks with validated properties and outputs that downstream tasks consume directly (`outputs.search.rows`, `outputs.ingest.numRejectedDocs`), instead of nested Pebble expressions over raw HTTP responses.
- **What operational/business outcome does it enable?** Log and trace pipelines that load data into Quickwit and alert on it become readable and reviewable: a doc-mapping mismatch surfaces as Quickwit's own error message, and `search.Trigger` reacts to new documents without polling from scratch on every run.

## What

Tasks live under `io.kestra.plugin.quickwit`, grouped by subpackage:

| Subpackage | Components |
|---|---|
| `search` | `Search`, `Trigger` |
| `ingest` | `Ingest` |
| `index` | `Create`, `Get`, `List`, `Delete`, `Clear` |
| `source` | `Create`, `Toggle`, `Delete`, `ResetCheckpoint` |
| `deletetask` | `Create`, `List` |

Every task and the trigger share the same connection properties, declared flat on the component: `url` (required), plus optional `basicAuth`, `headers`, `connectTimeout` and `readTimeout`. Quickwit itself has no authentication layer, so credentials only matter when the cluster sits behind a reverse proxy or an API gateway.

```yaml
tasks:
  - id: search
    type: io.kestra.plugin.quickwit.search.Search
    url: "http://localhost:7280"
    index: app-logs
    query: "severity:ERROR"
    maxHits: 100
```

See [src/main/resources/doc/io.kestra.plugin.quickwit.md](src/main/resources/doc/io.kestra.plugin.quickwit.md) for the full how-to, and the [Quickwit REST API reference](https://quickwit.io/docs/reference/rest-api) for the underlying endpoints.

## Running Kestra locally with this plugin

1. Build the shadow JAR: `./gradlew shadowJar`. The output lands in `build/libs/`.
2. Run `docker compose up`. `docker-compose.yml` builds `kestra/kestra:latest`, mounts `build/libs/` to `/app/plugins/`, and starts a Quickwit node on port 7280, so Kestra picks up the jar and the flows can be exercised against a real cluster.
3. Kestra UI is available at [localhost:8080](http://localhost:8080); Quickwit's REST API at [localhost:7280](http://localhost:7280).

### Plugins folder gotcha

Mounting a host folder onto `/app/plugins/` replaces the container's plugins directory rather than adding to it. Core plugins (the ones logged as `Registered N core plugins`) are compiled into Kestra itself and aren't affected, but any additional plugin normally bundled in the base image under `/app/plugins/` (e.g. the Python script plugin) gets hidden once the mount is in place. If a flow you're testing depends on another plugin, copy its jar into `build/libs/` too before starting the container.

### JFR startup error

On some hosts, `command: server local` fails with:
```
Unable to create JFR repository directory using base location (/tmp)
```
`docker-compose.yml` works around this by mounting `/tmp` as `tmpfs`. If you build your own compose file or run Kestra via `docker run`, add the same workaround, e.g. `-v /tmp:/tmp` or `--tmpfs /tmp`. Tracked upstream in [kestra-io/kestra#17405](https://github.com/kestra-io/kestra/issues/17405).

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)
