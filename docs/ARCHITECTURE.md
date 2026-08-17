# Logger Manager - how it works

Written as a handoff. If you are picking this up cold, read this before
changing anything: several parts look redundant and are not, and the reasons
are the expensive part.

Plugin name `TurnOnLoggers`, display name **Logger Manager**. IdentityIQ 8.3+,
JDK 11, log4j2.

---

## 1. The one-paragraph version

Developers need `sailpoint.api.Provisioner` at DEBUG on every host for twenty
minutes. Without this plugin that means shell access to each host, an edit to
`log4j2.properties`, and remembering to undo it. This plugin turns the level on
across the cluster from the IIQ UI, applies it to each JVM's **live log4j2
runtime**, and takes it away again when it expires. No file is ever written.

That last point is the load-bearing one. Everything this plugin does lives in
memory in each JVM, so **a restart always returns a host to its
`log4j2.properties`**. There is no state on disk to get out of sync.

## 2. Why it is shaped like this

There is no cross-host messaging in IIQ that a plugin can use. So the design is
a **desired-state loop**, not a broadcast:

```
  browser ──REST──► one host ──writes──► Custom "TurnOnLoggers Configuration"
                                                    │  (desired state + revision)
                                                    ▼
  every host, on its own timer:  read desired ──► reconcile its own JVM
                                                └─► write Custom "TurnOnLoggers Status <host>"
```

- **One writer per object.** The configuration object is only written by the
  REST layer. Each status object is only written by its own host. No locking,
  no lost updates, however large the cluster.
- **The host serving the page reconciles immediately** so the UI feels
  instant; every other host follows on its next tick (default 60s). The page is
  explicit about this - it shows which hosts have confirmed and which have not.
- **`revision` is a sync watermark, not a version history.** Hosts compare
  their applied revision against it to know they are behind. Do not overload
  it. "What changed at revision 131" is answered by the audit trail (§7).

## 3. Source layout

| File | Lines | Responsibility |
|---|---:|---|
| `core/Log4jAgent.java` | 755 | All log4j2 interaction. The only class that touches the runtime. |
| `core/LoggerConfigStore.java` | 514 | Reads/writes both `Custom` objects. All persistence. |
| `core/LoggerSync.java` | 253 | One reconcile tick: adopt → clear → apply → answer log request → publish status. |
| `core/LogTail.java` | 217 | Bounded reads of this host's own log files. |
| `core/HostFacts.java` | 151 | OS, JVM, config path, log file paths for this host. |
| `core/AuditWriter.java` | ~150 | One audit action, written unconditionally. |
| `core/CollectionStore.java` | ~120 | Saved logger collections. |
| `core/PluginSettings.java` | 93 | Reads settings off the `Plugin` object. |
| `rest/LoggerControlResource.java` | 1181 | Every endpoint. Validation lives here. |
| `service/LoggerSyncService.java` | 55 | The `ServiceDefinition` executor that calls `LoggerSync` on a timer. |
| `ui/js/turnOnLoggers.js` | 2070 | The entire page. Vanilla JS - see §8. |

## 4. Log4jAgent - the part to be careful with

Three pieces of state, all `static`, all per JVM:

```java
OWNED    // logger -> Snapshot of what it was before we overrode it
CREATED  // loggers this plugin actually created (vs. ones it re-levelled)
ownedAgainst // the Configuration object OWNED was captured against
```

**`OWNED` is how revert works.** Overriding a logger snapshots its previous
state first. Releasing it restores that snapshot - either setting the level
back, or removing the `LoggerConfig` entirely if we were the one who added it.

**`CREATED` is how "left over" is decided**, and it has bitten this project
twice:

- It exists because after a plugin reinstall the in-memory maps are empty, and
  a logger the previous instance added is still live with nothing managing it.
  `CREATED` is persisted per host so the new instance can recognise its own
  litter and offer to clear it.
- The trap: **a claim must be released when the logger is released.** For a
  long time `restore()` deleted the `LoggerConfig` but left the name in
  `CREATED`, so if a *rule* later set the same logger it was reported as this
  plugin's leftover - and "Clear all left over" only removes names in `CREATED`,
  so that button would have offered to delete a logger a rule was using.
  Fixed, plus `pruneCreated()` at the end of `apply()` drops any claim on a
  logger that is not currently live, so hosts with stale ledgers self-heal.

**Source classification** (`configuredLoggers()`), in order:

| Test | Source | Clearable |
|---|---|---|
| in `OWNED` | `plugin` | remove the override instead |
| config file could not be parsed | `unknown` | no - we cannot tell what removing it would do |
| declared in `log4j2.properties` | `file` | yes (one-shot; the file wins on next reload/restart) |
| in `CREATED` | `left over` | yes |
| otherwise | `set at runtime` | yes |

`set at runtime` means a rule or custom code did it. The **automatic** sweep
(`clearRuntimeLeftovers`) never touches those - only an explicit Clear on a
named row does, because that is a person looking at that row and asking.

### The guarantee worth preserving

*All Logger Status* is built by asking log4j2 what it currently has configured -
**not** from the plugin's own records. That is why a logger cannot get lost:
destroy every record the plugin has (uninstall, delete both `Custom` objects,
reinstall) and the logger is still listed, still suppressible, still clearable.
Losing the bookkeeping costs you the label, not the control. If you refactor
this, keep that property.

## 5. Persistence

Two `Custom` objects, both plain and readable from the debug page.

**`TurnOnLoggers Configuration`** - desired state. Entries, `revision`, plus
transient request fields (`clearRuntimeAt`, `logQuery`, `logQueryAt`, …). A
request is a timestamp the hosts compare against, not a queue.

**`TurnOnLoggers Status <host>`** - what one host published: applied levels,
errors, facts, its live logger list, `owned`, `created`, and any log-search
answer. This is the only thing the UI knows about other hosts.

Entries carry `logger, level, hosts, expires, created, createdBy, note, id`.
`expires = 0` means never, and is only permitted for `OFF` - a level that
produces output must expire, which is the guard rail against leaving DEBUG on.

## 6. Precedence

1. `permanentLoggers` plugin setting (lowest)
2. `*` host entries
3. host-specific entries (highest)

Later wins on the same logger. Expired entries and non-matching hosts are
filtered out in `desiredFor()` before anything is applied.

## 7. Audit

One action, `LoggerManagerChange`, written **unconditionally** - not gated on
being enabled in `AuditConfig`, because whether privileged changes are recorded
must not be switchable off by the person making them. `Auditor.log()` is tried
first, then a direct `saveObject`, then **an explicit commit** - without that
the next `decache()` silently discarded the event.

Each event carries the revision it produced and a `kind` of `change` or `read`.
The History panel reads these back rather than keeping a second copy: the trail
is already the record, it outlives the plugin, and a duplicate would be another
thing to keep correct.

Its one blind spot, stated in the UI: a rule calling `Logger.getLogger(…)
.setLevel(…)` never came through the plugin, so it is not in the trail.
*All Logger Status* is where those show up.

## 8. The page

`ui/js/turnOnLoggers.js`, injected on every page by a `Snippet`, and no-ops
immediately unless it finds `#turn-on-loggers-root`.

- **Vanilla JS only.** AngularJS is *not* loaded on plugin full pages; `ng-*`
  attributes render as literal text.
- `render()` rebuilds the whole tree, so it **saves and restores scroll
  position** - without that, any action that shortens the page throws the
  reader to the top.
- Host chips are one component used in three sections. **Colour is status,
  strike-through is selection**, and they are independent axes.
- Filter counts are **distinct logger names**, not rendered rows. A logger on
  nine hosts is one logger. The row count is in the tooltip.

## 9. Build

`build.sh` / `build.bat`. Set `IIQ_LIB` to your IIQ `WEB-INF/lib`.

Before packaging, the build **executes** `ui/js/turnOnLoggers.js` against a stub
DOM under `jjs`, twice (plugin page, configure page), against two state
fixtures, and aborts if the page would not render. This exists because a single
unescaped apostrophe once shipped in three consecutive releases - the old check
grepped stderr for "SyntaxError", which `jjs` never prints for a file. If the
gate fails it prints what the page said, which is usually the whole diagnosis.

The build also mirrors `docs/screenshots/` into `ui/img/`, so the help page
shipped inside the plugin matches the README. **Re-run the build after
regenerating screenshots** or the two drift.

Package with `jar cfM`, never PowerShell `Compress-Archive` - that writes
backslash entry paths and IIQ's `PluginsCache` looks them up with forward
slashes, surfacing as "Premature end of file".

## 10. Things that look wrong and are not

- **Two sections both listing loggers.** *Plugin Logger Status* is what this
  plugin is holding; *All Logger Status* is what log4j2 actually has, whoever
  set it. They disagree when a rule is involved, and that disagreement is the
  useful part.
- **Counts that do not add up across filters.** Grouping is recomputed per
  filter, so a narrower filter merges hosts that `All` keeps separate.
- **`Clear` on a file-declared logger is allowed.** `monitorInterval` only
  rebuilds when the file's timestamp changes, so it stays cleared until someone
  edits the file or the host restarts - the same one-shot shape as any other
  Clear.
- **Not-reporting hosts are grey, not red.** IIQ lists every `Server` it knows,
  including ones retired years ago. Red is reserved for a host that reported an
  error.

## 11. Testing

Three audit scripts exist outside the repo (they need admin credentials):

- **REST audit** - ~66 checks over the endpoint surface, validation rules,
  expiry, collections, log viewer, history.
- **UI audit** - 23 checks in headless Chrome for things REST cannot reach:
  chip selection, filter counts vs. rendered rows, scroll retention, disabled
  controls, collapse/expand.
- **Source/ownership audit** - 21 checks on classification across realistic
  sequences, which is where the real bugs were.

If you change `Log4jAgent`, the third one is the one that matters. Endpoint
tests will not catch a mislabelled source or a leaked claim.

## 12. Known limitations

- Levels only; appenders are not managed.
- Only the **properties** format of log4j2 config is parsed. XML or YAML means
  source cannot be determined, and the page says so rather than guessing.
- Uninstalling leaves the `ServiceDefinition` behind (IIQ has no uninstall
  hook). Remove it with `iiq console` → `delete ServiceDefinition
  TurnOnLoggersSync`.
- Log search reads the **last 256 KB** of each file and keeps the newest 40
  matches. There is no timestamp filtering, so on a quiet log, entries from days
  ago can still appear.
- Overrides live in the IIQ database, so a database refresh carries them between
  environments.
