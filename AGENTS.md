# AGENTS.md

Nuxeo LTS 2025 add-on. Bulk action `expeditedDelete`: permanently deletes documents matching NXQL, as the system
user, on its own stream. Public, client-facing example of how to build and configure a bulk action.

## Layout

| Path | What |
|---|---|
| `nuxeo-expedited-delete-core/src/main/resources/OSGI-INF/expedited-delete-bulk-contrib.xml` | Action, stream processor, scrollers. The teaching file. |
| `.../OSGI-INF/expedited-delete-operation-contrib.xml` | Registers operation `Bulk.ExpeditedDelete`. |
| `.../expediteddelete/ExpeditedDeleteAction.java` | Topology + computation: system login, batch delete, one-by-one retry on refusal. |
| `.../expediteddelete/ExpeditedDeleteValidation.java` | `validationClass`: WHERE required, no params. Runs on every launch route. |
| `.../expediteddelete/ExpeditedDeleteOperation.java` | Admin-only launcher. |
| `nuxeo-expedited-delete-core/src/test/` | `TestExpeditedDelete` (service), `TestExpeditedDeleteRest` (docs/curl.md calls), `TestExpeditedDeleteElasticScroller` (embedded OpenSearch 1). |
| `nuxeo-expedited-delete-package/` | Marketplace zip: the core jar only. |
| `docs/curl.md` | Every bulk REST route and option. |

## Build

- Java 21, Maven, Nuxeo 2025 artifacts from packages.nuxeo.com.
- `mvn clean verify` (H2). Also run `-Dnuxeo.test.core=mem -Dnuxeo.test.directory=sql` (DBS). Both must pass.
- CI (`.github/workflows/build.yml`) is manual only.

## Rules

- Branch `2025` = LTS 2025 only. LTS 2027 gets its own branch.
- Change together: action name in `<action>`, `<streamProcessor>` and `ACTION_NAME`; stream `bulk/expeditedDelete`
  in `inputStream` and `ACTION_FULL_NAME`.
- Comments: one very brief line per setting. Clients read them.
- The XML keeps every LTS 2025 option of `<action>`, `<streamProcessor>`, `<policy>` and `<scroll>`, set or as a
  commented example. Sources (nuxeo-lts, branch 2025): `BulkActionDescriptor`, `StreamProcessorDescriptor`,
  `ScrollDescriptor`.
- Keep the WHERE check in the validation class: REST `/search/bulk/{action}` without `query` means
  `SELECT * FROM Document`.
- Query runs as the launcher; deletion runs as system (`loginSystemOrUser`). Don't change either silently.
- New nuxeo.conf key: add it to the README Configure table.
- Claims in README and docs/curl.md are tested or traced to LTS 2025 source. Keep the "What was checked" section of
  docs/curl.md and README "Validation" current.
