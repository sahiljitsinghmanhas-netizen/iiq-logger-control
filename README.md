# Logger Manager

An IdentityIQ plugin for turning log4j2 loggers on and off across every host in
a deployment, from the IIQ UI. No shell access, no editing `log4j2.properties`,
no restart.

Built for IdentityIQ 8.5 (JDK 11, log4j2). Tested on Tomcat 9 with SQL Server.

![The Logger Manager page](docs/screenshots/01-logger-manager.png)

---

## Why

Raising a logger in IIQ normally means SSH to each app server, edit
`WEB-INF/classes/log4j2.properties`, and wait. In a cluster that is once per
host, with a chance to typo a level or miss a server each time, and it assumes
developers have shell access to production.

This makes it: pick a logger, pick a level, pick how long, click.

## How it works

Levels are set in each JVM's **live log4j2 runtime** - no file on any host is
modified. The desired state lives in the IIQ database, and a service on every
host reconciles against it on a timer.

```
 Browser ──REST──► the host serving it ──writes──► IIQ database
                                                        │
                        ┌───────────────────────────────┼───────────────┐
                        ▼                               ▼               ▼
                     host A                          host B          host C
              log4j2 runtime                  log4j2 runtime   log4j2 runtime
```

No host talks to another. Three things follow from that:

- **Propagation** - a change reaches every host within one interval (60s by
  default), with no host-to-host networking and no shared filesystem.
- **Durability** - a JVM that restarts re-applies the current state on its
  first tick, without anything having been written to disk.
- **Portability** - a mixed Windows, Linux, macOS and container cluster needs
  no special handling, because nothing depends on paths, permissions or a shell.

The `ServiceDefinition` ships with `hosts="global"`, which is what starts the
reconciler in every JVM.

## Install

Download the zip from the [latest release](../../releases/latest), then
**gear icon → Plugins → New** and upload it. No restart needed.

To build instead:

```bash
build.bat                                        # Windows
IIQ_LIB=/opt/identityiq/WEB-INF/lib ./build.sh   # Linux / macOS
```

Open the page at `/identityiq/plugins/pluginPage.jsf?pn=TurnOnLoggers`, or
**gear icon → Plugins → Logger Manager → Configure**, which carries a link to
it. Plugin full pages get no menu entry of their own in IIQ, so bookmark it.

Grant access by pointing `requiredCapability` at a capability of your own. The
default is `SystemAdministrator`, which works out of the box but is broader
than most people need.

## Using it

### The page

| Section | What it shows |
|---|---|
| Turn on a logger | The form. The logger box is free text; the dropdown only suggests common IIQ loggers. |
| Overrides in effect | What this plugin is holding, and which hosts have confirmed each one. |
| Loggers live in the JVM | Every logger log4j2 has configured on each host, with its source. |
| Hosts | Each JVM's OS, log4j2 config path, log files and last sync. |

![Loggers live in the JVM](docs/screenshots/04-live-loggers.png)

### Where a logger came from

| Source | Meaning | Cleared automatically? |
|---|---|---|
| `log4j2.properties` | Declared in that host's file | never |
| `this plugin` | An override managed here | on removal or expiry |
| `left over` | Created by this plugin, then orphaned by a reinstall | yes |
| `set at runtime` | A rule or custom code set it | **never** |

That last row matters. A rule doing
`Logger.getLogger("Rule.X").setLevel(DEBUG)` creates a `LoggerConfig` that is
neither in the file nor this plugin's, and it is never removed automatically.

### Suppress and Clear

| | Suppress | Clear |
|---|---|---|
| The logger entry | stays, held at `OFF` | deleted |
| Enforced afterwards | yes, re-applied every sync | no |
| Survives a rule re-setting it | **yes** | no |
| Reversible | yes, click the toggle again | nothing to reverse |

**Suppress holds it; Clear deletes it and lets go.** Suppress is the right
choice for a logger a rule keeps switching back on. Clear is for tidying an
entry that should not exist and that nothing will recreate.

Loggers declared in `log4j2.properties` have no Clear: log4j2 rebuilds its
configuration from that file on every change and restart, so the logger would
come straight back. Suppress is the only thing that holds for them.

### Saved collections

A collection is a named set of loggers and levels, **shared with everyone** who
uses the plugin. Turn the loggers on, press **Save as collection**, and the next
person chasing the same problem can turn the whole set on in one click with an
expiry of their choosing.

Each override a collection creates is noted `from collection: <name>`, so the
table and the audit trail both say where it came from.

![Saved collections](docs/screenshots/10-collections.png)

### Reading the log

The page can show the end of a log file on the host serving it, with a
plain-text filter.

![Log on this host](docs/screenshots/12-logs.png)

Only files that host's own log4j2 configuration writes to are offered, and the
request picks one **by index into that list, never by path** - so it cannot be
pointed anywhere else, and there are no OS-specific paths in it at all. Reads
are taken from the end with a seek, capped by `logTailKb` (512KB hard ceiling),
and audited. Set `showLogFiles` to `false` to remove the panel.

### Searching every host

A single search, answered by every host about its own file. Nothing is fetched
remotely - no host can read another's disk. The text is recorded once, each
host looks in its own log on its next sync and publishes what it found, and the
page merges the results with how long ago each host answered.

Bounded deliberately: up to 40 matching lines per host from the last 256KB,
long lines truncated, and the search stops being answered after fifteen
minutes. **Find in logs** on any override searches for that logger by name.

### Expiry

Overrides expire so logging cannot be left on by accident.

| Level | Expiry |
|---|---|
| `OFF` | may be permanent |
| everything else | must expire, capped by `maxTtlMinutes` |

## Settings

**gear icon → Plugins → Logger Manager → Configure.** Changes take effect on
the next read; no restart.

| Setting | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch. Off reverts every host to its own file. |
| `requiredCapability` | `SystemAdministrator` | Capability needed to use the page. No SysAdmin bypass. |
| `defaultTtlMinutes` | `60` | Preselected lifetime for a new override. |
| `maxTtlMinutes` | `1440` | Cap on lifetime. `0` allows permanent overrides at any level. |
| `allowRootLogger` | `false` | Whether the root logger may be targeted. |
| `untouchableLoggers` | `root,sailpoint` | Loggers the plugin refuses to change. |
| `permanentLoggers` | *(blank)* | Loggers enabled from the settings page, with no expiry. |
| `showLogFiles` | `true` | Whether the page can show the end of this host's log files. |
| `logTailKb` | `64` | How much of the end to read. Capped at 512 regardless. |

`untouchableLoggers` greys out Suppress and Clear for those loggers and makes
the API reject them. Matched **exactly, not by prefix** - protecting
`sailpoint` does not protect `sailpoint.api.Provisioner`.

`permanentLoggers` takes comma-separated `logger=LEVEL`, with an optional
`@host` suffix to restrict one item to a single host:

```
sailpoint.api.Provisioner=DEBUG, sailpoint.connector=TRACE@iiq-app-02
```

## Audit

Every action writes an IIQ audit event under the action `LoggerManagerChange`:
who, what happened, the logger, level, target hosts, expiry, and the note from
the form.

There is no switch for this, and events are written whatever Audit
Configuration says. Changing what a production system logs is privileged, so
whether it gets recorded is not left to the person making the change.

To find them: **Advanced Analytics → Search Type: Audit → Refine Search →
Action = `Logger Manager change`**. Filtering by Source needs your IIQ *login*
name, not the display name shown in the header.

The page's own ten-second background refresh is not audited.

## Security

- Capability gated, with no SysAdmin bypass.
- Overrides expire by default.
- The root logger is blocked unless deliberately enabled.
- Protected loggers are refused by the API, not merely greyed out in the UI.
- Every change is attributable and audited.

## REST API

Base `/identityiq/plugin/rest/TurnOnLoggers`. All calls need the configured
capability; mutating calls need an `X-XSRF-TOKEN` header.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/state` | Everything the page renders |
| `POST` | `/entries` | Add or replace an override |
| `PUT` | `/entries/{id}` | Change level, TTL or hosts |
| `DELETE` | `/entries/{id}` | Remove one override |
| `DELETE` | `/entries` | Remove all overrides |
| `POST` | `/sync` | Reconcile the host serving the request |
| `POST` | `/collections` | Save the current overrides, or a given list, under a name |
| `POST` | `/collections/{id}/apply` | Turn a whole collection on |
| `DELETE` | `/collections/{id}` | Delete a collection |
| `GET` | `/logtail?index=N&kb=K` | The end of one of this host's log files |
| `POST` | `/logquery` | Start or stop a cluster-wide log search |
| `POST` | `/cleanup` | Clear left-over loggers, or one named logger |
| `DELETE` | `/hosts/{host}` | Forget a decommissioned host |

```bash
curl -u user:pass -H 'X-XSRF-TOKEN: t' -H 'Content-Type: application/json' \
  -d '{"logger":"sailpoint.api.Provisioner","level":"DEBUG","ttlMinutes":30}' \
  https://iiq.example.com/identityiq/plugin/rest/TurnOnLoggers/entries
```

`dev-cycle.sh` wraps the common operations for development.

## Where state lives

| Object | Written by | Contents |
|---|---|---|
| `Custom "TurnOnLoggers Configuration"` | the REST layer | Desired state and a revision counter |
| `Custom "TurnOnLoggers Status <host>"` | that host's service | What the host applied, plus its OS and log4j2 facts |

One writer per object, so there is no lock contention and no lost updates
however large the cluster. Both are plain `Custom` objects, readable from the
debug page.

## Upgrading and rolling back

Upload the newer zip - no uninstall, and settings are preserved. Press
**Ctrl+F5** afterwards: IIQ's plugin asset URLs do not change between installs,
so browsers serve the old JavaScript.

IIQ refuses to install an older version over a newer one, or to reinstall the
same version:

```
HTTP 400  The installed plugin does not meet the minimum upgradable version requirements.
```

Rolling back therefore means uninstalling first, which resets plugin settings
to defaults. Logger overrides live in the `Custom` object and survive.

## Troubleshooting

| Symptom | Cause |
|---|---|
| A host says *not reporting* | Its sync service has not run. Check that host's `sailpoint.log` for `Unable to install service`. |
| A host says *catching up* | It is behind the current revision. Normal for up to one interval. |
| Level changed, nothing in the log | The record still has to reach an appender. On a stock IIQ the file appender is commented out and everything goes to stdout. |
| A logger is noisy and nobody set it | Read the Source column. *differs from file* means something changed it at runtime. |
| The page does not load after an upgrade | Cached JavaScript. Ctrl+F5. |

## Limitations

- **Appenders are not managed**, only levels.
- **Uninstalling leaves the ServiceDefinition behind**, because IIQ has no
  uninstall-time import hook. Remove it with `iiq console` →
  `delete ServiceDefinition TurnOnLoggersSync`, or every host logs a
  `ClassNotFoundException` once per Servicer cycle.
- **Only the properties format** of log4j2 configuration is parsed. For XML or
  YAML the source of each logger cannot be determined, and the page says so.
- **Overrides live in the IIQ database**, so a database refresh carries them
  between environments.

## Screenshots

`tools/screenshot.ps1` drives headless Chrome over the DevTools protocol to
regenerate `docs/screenshots/`. It needs Chrome and nothing else - no Selenium,
Playwright or Node.

```powershell
powershell -ExecutionPolicy Bypass -File tools\screenshot.ps1 `
    -BaseUrl http://localhost:8080/identityiq -User spadmin -Password admin
```

The plugin settings form and IIQ's audit search are client-side apps that do
not populate from a directly-navigated URL, so those two are not captured.

## Author

Sahiljit Singh Manhas

## License

[MIT](LICENSE).

SailPoint and IdentityIQ are trademarks of SailPoint Technologies, Inc. This is
an independent plugin, not affiliated with or supported by SailPoint.
