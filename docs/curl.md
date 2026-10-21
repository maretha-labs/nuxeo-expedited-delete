# Calling a bulk action with curl

How to launch, follow and stop any Nuxeo LTS 2025 bulk action over HTTP, with every option the REST API accepts.
The examples use `expeditedDelete`; put any other action name in its place.

## Before you start

- Base URL: `https://nuxeo.example.com/nuxeo/api/v1`. To work on a repository other than the default one, insert
  `/repo/<repositoryName>`: `https://nuxeo.example.com/nuxeo/api/v1/repo/<repositoryName>/search/bulk/...`
- `-u Administrator` makes curl ask for the password. Any Nuxeo authentication works.
- Always send `-H 'Content-Type: application/json'`, even with no body.
- `--url-query` (curl 7.87 or later) URL-encodes each parameter for you. With an older curl, encode the values
  yourself and add them to the URL.
- Who may launch: any logged-in user, for an action declared with `httpEnabled="true"`; administrators only
  otherwise.
  `expeditedDelete` is `httpEnabled="false"`.

## 1. Launch a run

Three routes. Each is a `POST` that answers `202 Accepted` with the run's status (see [Status](#status)). Keep its
`commandId`.

The body is optional: a JSON object of action parameters, for example `{"dc:description": "archived"}` for Nuxeo's
`setProperties` action. `expeditedDelete` takes no parameters and refuses any.

### a. From an NXQL query

`POST /api/v1/search/bulk/{action}`

```bash
curl -u Administrator -X POST -H 'Content-Type: application/json' \
  --url-query "query=SELECT * FROM File WHERE dc:created < DATE '2015-01-01'" \
  https://nuxeo.example.com/nuxeo/api/v1/search/bulk/expeditedDelete
```

Every parameter, with all of them in one call:

```bash
curl -u Administrator -X POST -H 'Content-Type: application/json' \
  --url-query "query=SELECT * FROM File WHERE ecm:parentId = ? AND dc:creator = :creator" \
  --url-query "queryParams=5f2b0a4c-0000-0000-0000-000000000000" \
  --url-query "creator=jdoe" \
  --url-query "scroll=search" \
  --url-query "queryLimit=10000" \
  https://nuxeo.example.com/nuxeo/api/v1/search/bulk/expeditedDelete
```

| Parameter | What it does |
|---|---|
| `query` | NXQL selecting the documents. **If missing, Nuxeo uses `SELECT * FROM Document`: every document.** `expeditedDelete` refuses it (no `WHERE`); other actions may not. |
| `queryParams` | Fills the `?` marks of the query, in order. Repeat it for several. Values are quoted for you. |
| any other name, such as `creator` | Fills `:creator` in the query. Values are quoted for you. `$currentUser` and `$currentRepository` are replaced by the caller's name and the repository. |
| `scroll` | The scroller for this run (list in [`expedited-delete-bulk-contrib.xml`](../nuxeo-expedited-delete-core/src/main/resources/OSGI-INF/expedited-delete-bulk-contrib.xml)). Default: the action's `defaultScroller`. |
| `queryLimit` | The most documents this run may touch. Default: the action's `defaultQueryLimit`. |

The search endpoint's paging and sorting parameters (`pageSize`, `currentPageIndex`, `offset`, `maxResults`,
`sortBy`, `sortOrder`, `highlight`) do not change which documents a bulk run touches.

#### Scrollers

```bash
# OpenSearch 1 "enhanced" index
curl -u Administrator -X POST -H 'Content-Type: application/json' \
  --url-query "query=SELECT * FROM File WHERE dc:created < DATE '2015-01-01'" \
  --url-query "scroll=elastic" \
  https://nuxeo.example.com/nuxeo/api/v1/search/bulk/expeditedDelete
```

| `scroll` | Reads from | Needs |
|---|---|---|
| `repository` | the database | nothing (this action's default) |
| `search` | the Search Service, index `nuxeo.search.client.default.scroller.index.name` (`repository` unless set) | that index; the server template declares `repository` |
| `repository_search` | the Search Service, `repository` index | that index |
| `elastic` | the OpenSearch 1 `enhanced` index | the OpenSearch 1 search client package; deprecated since 2025.0. The OpenSearch 2 client package (2025.4.9 checked) adds no scroller. |

Index-based scrollers only find documents already indexed. If the named search index does not exist, the error
stops Nuxeo's shared bulk scroller on that node until restart, so every later bulk run waits (seen in tests;
`nuxeo.core.bulk.scroller.continueOnFailure=true` is the setting meant to skip such a failure, not tested).

### b. From a page provider

`POST /api/v1/search/pp/{pageProviderName}/bulk/{action}`

```bash
curl -u Administrator -X POST -H 'Content-Type: application/json' \
  --url-query "queryParams=5f2b0a4c-0000-0000-0000-000000000000" \
  --url-query "quickFilters=noFolders,onlyMine" \
  --url-query "scroll=repository" \
  --url-query "queryLimit=10000" \
  https://nuxeo.example.com/nuxeo/api/v1/search/pp/my_page_provider/bulk/expeditedDelete
```

| Parameter | What it does |
|---|---|
| `queryParams` | Fills the `?` marks of the page provider's pattern, in order. Repeat it for several. |
| any other name | A named parameter of the page provider: fills `:name` in its pattern or the field of its where clause. `$currentUser` and `$currentRepository` are replaced as above. |
| `quickFilters` | Names of the page provider's quick filters to add, comma-separated. |
| `scroll` | The scroller for this run. **Default: the page provider's own scroller, which wins over the action's `defaultScroller`**: `repository` for NXQL page providers, `search` (or the one it names) for Search Service page providers. |
| `queryLimit` | The most documents this run may touch. Default: the action's `defaultQueryLimit`. |

`query` is ignored on this route.

### c. From a saved search

`POST /api/v1/search/saved/{savedSearchId}/bulk/{action}`

```bash
curl -u Administrator -X POST -H 'Content-Type: application/json' \
  https://nuxeo.example.com/nuxeo/api/v1/search/saved/0a1b2c3d-0000-0000-0000-000000000000/bulk/expeditedDelete
```

The query comes from the saved search's page provider, filled with this request's parameters (the same as route b).
`scroll` and `queryLimit` are not read: the action's defaults apply. A saved search with no page provider takes
route a's `query` parameter instead, and so falls back to `SELECT * FROM Document` without it.

### Launch answers

| HTTP status | When |
|---|---|
| `202` | Accepted. The body is the run's status. |
| `400` | Unknown action (empty body), or the command was refused: for `expeditedDelete`, no `WHERE` clause, a body parameter, or an unknown `scroll` name. |
| `403` | The action is `httpEnabled="false"` and the caller is not an administrator (empty body). |
| `404` | Route c: no saved search with that id. |

## 2. Follow and stop a run

```bash
# progress
curl -u Administrator https://nuxeo.example.com/nuxeo/api/v1/bulk/<commandId>
# stop
curl -u Administrator -X PUT https://nuxeo.example.com/nuxeo/api/v1/bulk/<commandId>/abort
```

| Call | Who may call it | What it does |
|---|---|---|
| `GET /api/v1/bulk/{commandId}` | The user who launched the run, or an administrator. Others get `404`. | Returns the status. |
| `PUT /api/v1/bulk/{commandId}/abort` | Same | Stops the run and returns its status. A finished run is returned unchanged. |
| `GET /api/v1/management/bulk/{commandId}` | Management API users (below) | Returns the status. `404` if unknown. |
| `DELETE /api/v1/management/bulk/{commandId}` | Management API users | Stops the run and returns its status. `400` if it already completed. |
| `PUT /api/v1/management/bulk/idle?totalDocs=<n>&millisDurationPerDoc=<ms>` | Management API users | Launches Nuxeo's test action `idle` to size a cluster. Needs `nuxeo.bulk.action.idle.enabled=true`. Not related to this add-on. |

Stopping a run: the documents already processed stay processed (for `expeditedDelete`, deleted); the buckets still
waiting in the stream are skipped.

Management API users: administrators, the user named by `nuxeo.management.api.user`, and members of the groups listed
in `nuxeo.management.api.groups`. The management API answers only on the port `nuxeo.management.api.http.port`
(default: Nuxeo's HTTP port), and `404` on any other.

### Status

| Field | Meaning |
|---|---|
| `commandId` | The run's id. |
| `state` | `SCHEDULED`, `SCROLLING_RUNNING` (finding the documents), `RUNNING`, `COMPLETED`, `ABORTED`, or `UNKNOWN`. |
| `total` | Documents the query found (known once scrolling ends). |
| `processed` | Documents handled so far. |
| `errorCount`, `error`, `errorMessage` | Documents in error, whether there is any, and the first error's message. |
| `skipCount` | Documents skipped. |
| `action`, `username` | The action and the user who launched it. |
| `submitted`, `scrollStart`, `scrollEnd`, `processingStart`, `processingEnd`, `completed` | Timestamps. |
| `processingMillis` | Processing time. |
| `result` | Action-specific output; absent when empty. |

## 3. Other ways to launch

| Call | Notes |
|---|---|
| `POST /api/v1/automation/Bulk.ExpeditedDelete`, body `{"params": {"query": "..."}}` | This add-on's launcher, administrators only. Answers `200` with `{"entity-type": "bulkStatus", "value": {...status...}}`: the `commandId` is in `value`. |
| `POST /api/v1/automation/Bulk.RunAction` | Nuxeo's generic launcher. Params: `action` (required), `query` or `providerName`, `queryParams`, `namedParameters`, `quickFilters`, `repositoryName`, `bucketSize`, `batchSize`, `parameters` (action parameters as a JSON string), `excludeDocs` (ids to leave out), `queryLimit`. The only HTTP route that sets `bucketSize` and `batchSize` for one run. It passes the page provider's scroller, which wins over the action's `defaultScroller` when the provider has one. |
| `POST /api/v1/automation/Bulk.WaitForAction`, params `commandId`, `timeoutSecond` | Waits for the run to end. |
| `/api/v1/user/search/bulk/{action}`, `/api/v1/group/search/bulk/{action}`, and the `bulk` route of the group adapters `@users` and `@groups` | These send user or group ids to the action, not document ids. Not for `expeditedDelete`. |

## What was checked

`TestExpeditedDeleteRest` runs these calls against an embedded Nuxeo 2025.26.16 REST API: route a with `query`,
`queryParams`, a named parameter, `scroll` (`repository`, `search`, and the custom scroller example of
`expedited-delete-bulk-contrib.xml`) and `queryLimit`; a request encoded exactly as `curl --url-query` sends it; the
`202`, `400` and `403` answers listed above; `GET /bulk`, `PUT /bulk/.../abort`, `GET` and `DELETE /management/bulk`; and
`Bulk.ExpeditedDelete`. `TestExpeditedDeleteElasticScroller` runs `scroll=elastic` against an embedded OpenSearch 1
node.

Described from the LTS 2025 source, not run: `scroll=repository_search`, routes b and c, the `idle` call,
`Bulk.RunAction`, `Bulk.WaitForAction` over HTTP, the user and group routes and the `/repo/` prefix.
