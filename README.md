# Nuxeo Expedited Delete

A Nuxeo LTS 2023 add-on that permanently deletes every document matching an NXQL query, as fast as the platform
allows, and a worked example of **how to create and configure a Nuxeo bulk action**.

- Same result as `Document.Delete` (`CoreSession.removeDocument`): permanent, no trash.
- Runs as the **system user**: no permission is evaluated on the documents.
- One call and one commit per batch of documents, instead of one per document.
- Its own stream, `bulk/expeditedDelete` (a Kafka topic of its own when Kafka is enabled), so work never waits
  behind other bulk jobs and its progress and lag can be tracked on their own.
- Launched by administrators with the `Bulk.ExpeditedDelete` Automation operation.

Branch `2023` (this one) targets LTS 2023. Branch `2025` targets LTS 2025. LTS 2027 TBD & will live on its own branch.

## Why it is faster than `Bulk.RunAction` + `Document.Delete`

| | `automation` bulk action running `Document.Delete` | `expeditedDelete` |
|---|---|---|
| Runs as | the user who launched it: permissions are checked on every document | the system user: no permission checks |
| Documents per transaction | 5 (`batchSize`) | 50, configurable |
| Stream | `bulk/automation`, shared with every other Automation bulk job | `bulk/expeditedDelete`, its own |
| Threads per node / partitions | 2 / 4 | 4 / 8, configurable |

The values for the `automation` action are Nuxeo's LTS 2023 defaults (`bulk-contrib.xml` in
`nuxeo-automation-features`). No benchmark ships with this repository: measure with your own data and tune the
settings below.

## Install

Build with Java 17 and Maven, with access to the Nuxeo LTS 2023 Maven artifacts:

```bash
mvn clean verify
```

Install the package on each Nuxeo node, then restart:

```bash
nuxeoctl mp-install nuxeo-expedited-delete-package/target/nuxeo-expedited-delete-package-1.0.0-SNAPSHOT.zip
```

## Use

Launch it as an administrator. The query must have a `WHERE` clause.

```bash
curl -u Administrator -H 'Content-Type: application/json' \
  -X POST https://nuxeo.example.com/nuxeo/api/v1/automation/Bulk.ExpeditedDelete \
  -d '{"params": {"query": "SELECT * FROM File WHERE dc:created < DATE '\''2015-01-01'\''"}}'
```

The response wraps the bulk command status: `{"entity-type": "bulkStatus", "value": {...}}`. Keep `value.commandId`.

Or launch it straight through Nuxeo's bulk REST API (administrators only, since the action is `httpEnabled="false"`):

```bash
curl -u Administrator -X POST -H 'Content-Type: application/json' \
  --url-query "query=SELECT * FROM File WHERE dc:created < DATE '2015-01-01'" \
  https://nuxeo.example.com/nuxeo/api/v1/search/bulk/expeditedDelete
```

This answers `202` with the status itself; keep its `commandId`. Every route and option of that API (page providers,
saved searches, query parameters, scroller, query limit, status and abort calls) is in
[docs/curl.md](docs/curl.md).

| To | Call |
|---|---|
| Wait for the end | operation `Bulk.WaitForAction`, params `commandId`, `timeoutSecond` |
| Read progress | `GET /nuxeo/api/v1/bulk/<commandId>` |
| Abort | `PUT /nuxeo/api/v1/bulk/<commandId>/abort` |

In the status, `processed` counts the documents handled, `errorCount` the documents Nuxeo refused to delete and
`errorMessage` the first refusal. Every refusal is also logged at `WARN` with the document id.

## Configure

Every setting has a default. Override it in `nuxeo.conf`, then restart.

| nuxeo.conf key | Default | What it does |
|---|---|---|
| `nuxeo.bulk.action.expeditedDelete.enabled` | `true` | Turns the action and its processor on or off. |
| `nuxeo.bulk.action.expeditedDelete.defaultScroller` | `repository` | What runs the query: `repository` asks the database. Other choices are listed in the XML. |
| `nuxeo.bulk.action.expeditedDelete.defaultQueryLimit` | `0` | Most documents one run may delete; `0` = no limit. |
| `nuxeo.bulk.action.expeditedDelete.bucketSize` | `100` | Document ids per stream message. |
| `nuxeo.bulk.action.expeditedDelete.batchSize` | `50` | Documents deleted per transaction. Larger is faster; a refused document makes its batch run again one by one. |
| `nuxeo.bulk.action.expeditedDelete.batchTransactionTimeout` | `0s` | Time allowed for one batch, e.g. `5m`; `0s` = `nuxeo.db.transactiontimeout`. |
| `nuxeo.bulk.action.expeditedDelete.defaultConcurrency` | `4` | Delete threads per Nuxeo node. |
| `nuxeo.bulk.action.expeditedDelete.defaultPartitions` | `8` | Stream partitions: the most threads that can work at once across the cluster. Read only when the stream is first created. |
| `nuxeo.bulk.action.expeditedDelete.continueOnFailure` | `true` | After 3 failed tries of a message: `true` counts it as an error and moves on, `false` stops the processor until Nuxeo restarts. |

With Kafka enabled, the action's topic is `<kafka.topicPrefix>bulk-expeditedDelete` (`nuxeo-bulk-expeditedDelete` with the default prefix) and its consumer group has the same name.

## How a bulk action is built

Five files make the add-on. Each setting in them carries a short comment.

| File | Role |
|---|---|
| [`OSGI-INF/expedited-delete-bulk-contrib.xml`](nuxeo-expedited-delete-core/src/main/resources/OSGI-INF/expedited-delete-bulk-contrib.xml) | Registers the **action** with the Bulk Service and the **stream processor** that does the work. Lists every option Nuxeo LTS 2023 accepts for both, the scrollers, and how to register your own scroller. |
| [`ExpeditedDeleteAction.java`](nuxeo-expedited-delete-core/src/main/java/io/maretha/expediteddelete/ExpeditedDeleteAction.java) | The **topology** (which stream it reads and writes) and the **computation** (what happens to each batch of ids). |
| [`ExpeditedDeleteValidation.java`](nuxeo-expedited-delete-core/src/main/java/io/maretha/expediteddelete/ExpeditedDeleteValidation.java) | The **validation class**: Nuxeo runs it on every launch, from any route, before accepting the command. |
| [`ExpeditedDeleteOperation.java`](nuxeo-expedited-delete-core/src/main/java/io/maretha/expediteddelete/ExpeditedDeleteOperation.java) | The **launcher**: checks the caller, builds a `BulkCommand` and submits it. |
| [`META-INF/MANIFEST.MF`](nuxeo-expedited-delete-core/src/main/resources/META-INF/MANIFEST.MF) | Lists the XML components so Nuxeo loads them. |

What happens on a run:

1. The operation submits a `BulkCommand`: action name, NXQL query, launching user, repository. The validation class
   checks it.
2. Nuxeo's scroller runs the query (as the launching user, against the database) and writes the matching ids, in
   buckets of `bucketSize`, to `bulk/expeditedDelete`.
3. Each of the processor's threads reads a bucket, splits it into batches of `batchSize` and calls `compute()` once
   per batch, in its own transaction, logged in as the system user.
4. `compute()` deletes the batch with `CoreSession.removeDocuments` and saves. Progress goes to the shared
   `bulk/status` stream, which the status calls read.

To write your own action, copy the pattern: change the action name and stream in the XML and the constants in the
action class, put your logic in `compute()`, and keep `name` identical in `<action>` and `<streamProcessor>`. The
options come from `BulkActionDescriptor` (`nuxeo-core-bulk`), `StreamProcessorDescriptor` (`nuxeo-runtime-stream`)
and `ScrollDescriptor` (`nuxeo-core-bulk`) in the LTS 2023 source.

## Behaviour and security notes

- **Whoever can launch it can delete anything their query can see.** The operation refuses non-administrators.
  `httpEnabled="false"` keeps non-administrators away from the REST bulk endpoint and `Bulk.RunAction`;
  administrators can still launch the action through both.
- **The query must have a `WHERE` clause, on every route.** The validation class runs inside the Bulk Service, so
  the REST API and `Bulk.RunAction` get the same check as the operation. This matters: the REST endpoint
  `/search/bulk/{action}` uses `SELECT * FROM Document` when no query is sent. A `WHERE` clause that matches
  everything (`WHERE 1 = 1`) is still accepted.
- **The query runs with the launcher's read permissions; the deletion ignores permissions.** Nuxeo's scroller logs in
  as the launching user, the computation as the system user (with the launcher kept as originating user).
- **Deletion is permanent.** Nothing goes to the trash.
- **Some documents are refused even for the system user**: under retention or legal hold, the target of a proxy, or
  the version a checked-in document is based on. They are skipped, logged and counted in `errorCount`; the rest of
  the batch is deleted. Run the same query again to retry what is left.
- **Folders:** on a MongoDB (DBS) repository, deleting a folder removes the folder at once and hands its descendants
  to Nuxeo's own `deletion` bulk action, on the shared `bulk/deletion` stream, not on this one.
- **Listeners and audit still run.** `removeDocument` fires `aboutToRemove` and `documentRemoved` for each document;
  there is no per-call switch to turn them off. Their cost is part of the deletion time.

## Validation

- 20 tests pass on Nuxeo 2023.54.20 artifacts, run on Java 17, with an H2 (VCS) repository and with an in-memory DBS repository
  (`-Dnuxeo.test.core=mem -Dnuxeo.test.directory=sql`), both with in-memory streams.
- `TestExpeditedDelete` (8) covers: every setting registered, on its own stream; a 230-document delete across
  several buckets and batches; a folder and its children in one query; a document under legal hold skipped while
  the rest are deleted; deletion as system for a launcher who can only read; the validation class on a direct
  submit; the operation's administrator and `WHERE` checks.
- `TestExpeditedDeleteRest` (11) runs the HTTP calls of [docs/curl.md](docs/curl.md) against an embedded REST API,
  with the `repository` and custom scrollers; `TestExpeditedDeleteElasticScroller` (1) runs `scroll=elastic`
  against Nuxeo's embedded Elasticsearch-compatible test server. docs/curl.md lists what is covered and what is not.
- Not yet run against MongoDB, Kafka, a running Nuxeo server or a cluster. No performance benchmark.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Report security issues as described in
[SECURITY.md](SECURITY.md).
