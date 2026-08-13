# Logger Control (`TurnOnLoggers`)

An IdentityIQ plugin that lets a developer turn a log4j2 logger on or off
**across every host in the deployment, from the IIQ UI**, without logging into
a server, editing `log4j2.properties`, or restarting anything.

Tested against IdentityIQ 8.5 on Tomcat 9 / JDK 11.

---

## Why

The normal way to raise a logger in IIQ is to SSH to each app server, edit
`WEB-INF/classes/log4j2.properties`, and wait. In a clustered deployment that
is once per host, and every host is a chance to typo a level, forget one
server, or leave `DEBUG` on afterwards. It also assumes you have shell access
to production, which developers usually should not.

This plugin turns that into: pick a logger, pick a level, pick how long, click.

---

## How it works

Three moving parts, and deliberately no host-to-host communication:

```
   Browser                    Any IIQ host                   Every IIQ host
  ┌────────┐   REST      ┌──────────────────┐            ┌──────────────────┐
  │ Logger │───────────► │ LoggerControl    │            │ TurnOnLoggersSync│
  │Control │             │ Resource         │            │ service (60s)    │
  │  page  │ ◄───────────│                  │            │                  │
  └────────┘   state     └────────┬─────────┘            └────────┬─────────┘
                                  │ write desired state           │ read desired state
                                  ▼                               ▼
                        ┌───────────────────────────────────────────────┐
                        │  IIQ database                                 │
                        │   Custom "TurnOnLoggers Configuration"        │
                        │   Custom "TurnOnLoggers Status <host>"  x N   │
                        └───────────────────────────────────────────────┘
                                  ▲                               │
                                  └── each host reports what it   │
                                      actually applied ───────────┘
                                                                  ▼
                                                    log4j2 LoggerContext
                                                    (in-memory, this JVM)
```

1. **The UI writes desired state to the database.** One `Custom` object holds
   the list of overrides: logger, level, target hosts, expiry, who set it, why.

2. **A service on every host reconciles against it.** The shipped
   `ServiceDefinition` has `hosts="global"`, so IIQ runs one instance of
   `LoggerSyncService` in every JVM. Each tick it reads the desired state,
   works out what *this* host should be running, and mutates that JVM's live
   log4j2 configuration to match.

3. **Each host reports back** into its own `Custom "TurnOnLoggers Status <host>"`
   object - what it applied, plus its OS, JVM, log4j2 config path and log file
   locations. The UI reads those to show you, per host, that the change
   actually landed.

The host that serves the UI click applies the change immediately, so the person
clicking sees it take effect at once. Everyone else converges within one
service interval (60 seconds by default).

### Why it does not write log4j2.properties

Levels are set in each JVM's **live log4j2 runtime**, via the log4j2
`Configuration` API. No file on any host is touched. That is what makes this
work the same way everywhere:

| | file-editing approach | this plugin |
|---|---|---|
| Windows vs Linux vs macOS paths | must be handled per host | never touched |
| read-only webapp dir / container image | breaks | irrelevant |
| file permissions, line endings, encoding | must be handled | irrelevant |
| remote execution (SSH / WinRM / PsExec) | needed | not used |
| restart to take effect | often | never |

Durability across restarts comes from the database instead: a JVM that
restarts re-applies the current desired state on its first service tick.

### Loggers stranded by a plugin reinstall

The plugin's record of which loggers it owns is a static in its own
classloader, but log4j2's configuration belongs to the webapp and outlives it.
Reinstalling or upgrading the plugin replaces that classloader while every
`LoggerConfig` the old copy created stays live. Before 2.2.0 the new copy had
no idea it owned them, so turning one off reverted nothing and left it running
at DEBUG or TRACE while the page showed nothing overridden - and it then
appeared under *Already set in log4j2.properties*, as if an admin had put it
in the file.

In a cluster this showed up unevenly: hosts that still held the in-memory
record cleaned up correctly, hosts whose plugin had been reloaded did not.

Two fixes, both in 2.2.0:

- **Ownership is now persisted per host**, in that host's status object, and
  re-adopted on the first sync after a reinstall. A logger turned on before an
  upgrade is still under management afterwards and turns off normally.
- **The file's loggers are now read from the file.** The plugin parses the
  `log4j2.properties` the JVM actually loaded, so "came from the file" is a
  fact rather than a guess. Anything live that is neither in the file nor
  managed by the plugin is listed separately as **Left over from a previous
  install**, with a *Clear on all hosts* button that switches them off for
  real. Loggers genuinely in the file are never touched by it, and if the file
  cannot be parsed - an XML or YAML configuration, for instance - the plugin
  refuses to guess and clears nothing.

### Turning loggers on is cumulative

Nothing you do here clears anything else. Adding a logger adds one entry;
existing overrides and everything in `log4j2.properties` are left exactly as
they were. The reconciler only ever reverts loggers *this plugin* set, and only
when you remove them or their TTL lapses.

Verified end to end on a live instance:

| Action | Loggers from the file | Plugin-managed |
|---|---|---|
| baseline | 11 (`net.sf.ehcache=ERROR`) | none |
| add `sailpoint.api.Provisioner=DEBUG` | **11, untouched** | 1 |
| override `net.sf.ehcache` to `DEBUG` | 10 - it moved | 2 |
| remove that override | **11, `net.sf.ehcache=ERROR` restored** | 1 |

Overriding a logger the file already sets does not delete anything: ownership
moves to the plugin while the override lives, and the file's own level is put
back exactly when it is removed.

### Reverting is precise

Before overriding a logger, the plugin records whether an exact `LoggerConfig`
already existed for that name and at what level. On revert it either restores
the original level, or deletes the `LoggerConfig` it created so the logger goes
back to inheriting from its ancestor. Turning an override off leaves the host
exactly as `log4j2.properties` says it should be - not quieter, not noisier.

### If someone edits log4j2.properties by hand

They still can, and it still works - but the two do not see each other.

IIQ ships `monitorInterval=20`, and log4j2 really does hot-reload: a logger
added to the file starts producing output about 30 seconds later, with no
restart. That reload rebuilds the entire `Configuration` from the file, which
throws away the plugin's in-memory overrides on that host.

The plugin notices the `Configuration` instance was replaced, discards its now
meaningless snapshots, and re-applies from scratch on its next tick. Measured
on a live instance:

| Moment | a logger the plugin had set to DEBUG |
|---|---|
| plugin sets it | `DEBUG` |
| immediately after a manual file edit | `WARN` - wiped by the reload |
| after one sync tick (≤ 60s) | `DEBUG` - restored automatically |

So a hand edit knocks the plugin's overrides out on that host for **up to one
sync interval**, then they come back on their own. Nobody has to do anything.

Two consequences worth knowing:

- **For a logger both control, the plugin wins**, because it re-asserts every
  tick. And since it re-snapshots against the new configuration, removing the
  override later reverts to whatever the file *now* says, not the stale
  pre-edit value.
- **Loggers set in the file are shown, read-only,** under *Already set in
  log4j2.properties* - including ones added by hand on a single host. A hand
  edit shows up there within about 90 seconds (log4j2's 20s file check, then
  one sync tick). Hosts reporting an identical set are grouped, so the one host
  that differs stands out instead of being buried under ten repeats of the IIQ
  defaults.

---

## Mixed operating systems

A real IIQ cluster is rarely homogeneous - a Windows dev box, RHEL app servers
in production, a macOS laptop, a Linux container. Nothing in the level-setting
path branches on OS; it is the same log4j2 API call everywhere.

What *is* OS-specific is reported rather than acted on. The **Hosts** table
shows, per host:

- OS name, version and architecture, plus a `containerised` marker
- JVM version and vendor
- **which `log4j2.properties` that host actually loaded** (the path differs
  between an exploded webapp, a WAR, a container image, and an external
  `-Dlog4j.configurationFile`)
- **where that host writes its log files**, spelled the way that host's OS
  spells them - which is the practical follow-up to "turn the logger on":
  now where do I read it?

Overrides can target `*` (all hosts) or a named subset, so pinning a logger to
just the one Linux node that is misbehaving is a checkbox.

Host names come from `sailpoint.tools.Util.getHostName()`, the same value IIQ's
own heartbeat uses to name `Server` objects, so it honours `-Diiq.hostname`
where an admin has overridden it (common in containers).

---

## Safety

- **Overrides expire.** New overrides default to 60 minutes and are capped at
  24 hours (both configurable). Debug logging that nobody remembers to switch
  off is the usual way a tool like this causes an incident; here the service
  reverts it unattended when the TTL lapses.
- **The root logger is blocked by default.** Setting root to `DEBUG` turns on
  every logger in the JVM at once. Enable `allowRootLogger` deliberately.
- **Capability gated.** Only holders of the configured capability (directly or
  via a workgroup) can read or change anything. There is no SysAdmin bypass -
  the check is exactly the configured capability name.
- **Attributable.** Every override records who set it, when, and an optional
  note; every change is logged to `sailpoint.log` with the user name.
- **Master switch.** Turning `enabled` off makes every host revert to its own
  `log4j2.properties` on the next tick, without losing the stored config.
- **Panic button.** "Turn everything off" clears every override everywhere.

---

## Install

Download `TurnOnLoggers-2.2.0.zip` from the
[latest release](../../releases/latest), then **gear icon → Plugins → New** and
upload it.

Or build it yourself:

```bash
# Windows
build.bat

# Linux / macOS / Git Bash
IIQ_LIB=/opt/identityiq/WEB-INF/lib ./build.sh
```

Then **gear icon → Plugins → New** and upload `TurnOnLoggers.zip`.

Installing also imports `import/install/ServiceDefinition-TurnOnLoggersSync.xml`
automatically, which is what starts the per-host sync service. **No Tomcat
restart is needed** - each host's `Servicer` picks the new ServiceDefinition up
on its next cycle, typically within a minute of the install.

### Where the page is

```
/identityiq/plugins/pluginPage.jsf?pn=TurnOnLoggers
```

IIQ gives plugin full pages no menu entry of their own - `sailpoint.plugin
.FullPage` carries a `title` and nothing else - so bookmark that URL, or go
**gear icon → Plugins → Logger Control → Configure**, where the plugin adds an
**Open Logger Control** button at the top of the settings form.

The plugin deliberately does not touch IIQ's navigation. Adding a menu entry
would mean either editing the `UIConfig` singleton (which would overwrite
whatever navigation customisation you already have) or injecting into the
navbar DOM on every page, and neither is a reasonable thing for a logging tool
to do to a production IIQ.

> Browsers cache plugin JavaScript aggressively and IIQ's asset URL revision
> does not change between reinstalls, so after upgrading press **Ctrl+F5** once
> or you will still be running the previous version's UI.

**This page is where you turn loggers on.** The top card, *Turn on a logger*,
takes a logger name, a level, how long to keep it on, which hosts, and an
optional note. The `permanentLoggers` setting under **Settings** is not the
normal route - see [Settings](#settings) for when to use it.

### Any logger name works

The Logger field is free text with a dropdown of common IIQ loggers attached.
The dropdown is **suggestions only** - it does not restrict what you can type.
Anything log4j2 recognises is valid, including loggers you declare yourself:

```java
// inside a rule
Logger log = Logger.getLogger("rule.myCustomRule");
```

That name can be typed straight into the Logger field and set to `TRACE` like
any other. Custom loggers from rules and custom Java are a main use case and
will never appear in any catalog.

Enter the **name only** and choose the level from the Level list. The
`logger=LEVEL` form belongs to the `permanentLoggers` setting, not this field.

**This page is where you turn loggers on.** The top card, *Turn on a logger*,
takes a logger name (with a picker of the loggers people usually reach for), a
level, how long to keep it on, which hosts, and an optional note. The
`permanentLoggers` setting under **Settings** is not the normal route - see
[Settings](#settings) for when to use it.

### Upgrading and rolling back

**To upgrade**, just upload the newer zip - **gear icon → Plugins → New**. Do
not uninstall first. IIQ replaces the plugin in place and **your settings are
preserved** (verified upgrading 1.0.0 → 2.0.0 with a non-default
`permanentLoggers` and `defaultTtlMinutes`; both survived).

**Reinstalling the same version also needs an uninstall first** - IIQ rejects
it with the same 400 below, since it is not an upgrade.

**To roll back, you must uninstall first.** IIQ refuses to install an older
version over a newer one:

```
HTTP 400  {"message":"The installed plugin does not meet the
           minimum upgradable version requirements."}
```

So the rollback is: **gear icon → Plugins → uninstall**, then install the older
zip. Two consequences of that extra step:

- **Plugin settings are reset to defaults by the uninstall.** Note down any
  non-blank `permanentLoggers`, and any changed capability or TTL values,
  *before* you roll back.
- **Your logger overrides are not affected.** They live in the
  `Custom "TurnOnLoggers Configuration"` object, which is independent of the
  plugin - verified by creating an override, uninstalling, reinstalling a
  different version, and finding it intact, note and all.

There is no need to delete the ServiceDefinition when moving between versions;
every version registers the same service name and executor class.

### Uninstalling

Remove the plugin the normal way, then **delete the leftover ServiceDefinition**:

```
iiq console
> delete ServiceDefinition TurnOnLoggersSync
```

IIQ has no uninstall-time import hook (only `import/install/` and
`import/upgrade/`), so an object imported at install time outlives the plugin.
If you skip this, every host logs
`Unable to install service TurnOnLoggersSync ... ClassNotFoundException` once
per Servicer cycle, because the executor class left with the plugin jar. This
is true of any IIQ plugin that ships a service, not just this one.

### Granting access to developers

Ships with `requiredCapability = SystemAdministrator` so it works immediately.
For real use, create a narrow capability and point the setting at it:

```xml
<Capability name="LoggerAdmin" displayName="Logger Administrator">
  <RightRefs/>
</Capability>
```

Import it, assign it, and set `requiredCapability=LoggerAdmin`. Developers then
get logging control and nothing else.

---

## Settings

`gear icon → Plugins → Logger Control → Settings`. Changes take effect on the
next read; no restart.

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `true` | Master switch. Off ⇒ every host reverts to its own log4j2.properties. |
| `requiredCapability` | `SystemAdministrator` | Capability needed to view or change loggers. |
| `defaultTtlMinutes` | `60` | Pre-selected lifetime for a new override. |
| `maxTtlMinutes` | `1440` | Cap on override lifetime. `0` allows permanent overrides. |
| `allowRootLogger` | `false` | Whether the root logger may be targeted. |
| `permanentLoggers` | *(blank)* | Loggers enabled from the settings page, with no TTL. |

### `permanentLoggers`

**Not the normal way to turn a logger on** - use the Logger Control page for
that. This field exists for the one case the page deliberately does not cover:
a logger with **no expiry**, which stays on until you edit the box. Everything
set on the page expires; anything listed here does not.

Comma-separated `logger=LEVEL`, with an optional `@host` suffix to restrict one
item to a single host:

```
sailpoint.api.Provisioner=DEBUG, sailpoint.connector=TRACE@iiq-app-02
```

They appear in the Logger Control page's table marked **"from settings"** with
an expiry of *never*, so the page is still a single list of everything that is
on. They cannot be turned off from the page - editing the setting is the way -
and they are the lowest precedence, so anything set in the UI for the same
logger wins.

---

## Changing the propagation delay

Edit the `TurnOnLoggersSync` ServiceDefinition (debug page, or re-import the
XML) and change `interval`. 15-30 seconds makes changes feel instant everywhere
at the cost of one small `SELECT` per host per tick. 300 is fine if a
five-minute cluster-wide delay does not bother you.

---

## REST API

Base: `/identityiq/plugin/rest/TurnOnLoggers`. All calls require the configured
capability; mutating calls require the `X-XSRF-TOKEN` header.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/state` | Everything the UI needs: overrides, hosts, per-host facts, this JVM's live levels, logger catalog |
| `POST` | `/entries` | Add or replace an override. Body: `{logger, level, ttlMinutes, hosts:[...], note}` |
| `PUT` | `/entries/{id}` | Change level, TTL or target hosts of an existing override |
| `DELETE` | `/entries/{id}` | Remove one override |
| `DELETE` | `/entries` | Remove every override |
| `POST` | `/sync` | Force the host serving this request to reconcile now |
| `DELETE` | `/hosts/{host}` | Forget a decommissioned host's status row |

```bash
curl -u spadmin:admin -H 'X-XSRF-TOKEN: t' -H 'Content-Type: application/json' \
  -d '{"logger":"sailpoint.api.Provisioner","level":"DEBUG","ttlMinutes":30,"hosts":["*"]}' \
  http://localhost:8080/identityiq/plugin/rest/TurnOnLoggers/entries
```

`dev-cycle.sh` wraps the common ones:

```bash
./dev-cycle.sh                                  # build + reinstall + verify
./dev-cycle.sh set sailpoint.api.Provisioner DEBUG 30
./dev-cycle.sh state
./dev-cycle.sh clear
./dev-cycle.sh logs 100
```

---

## Where the state lives

| Object | Written by | Purpose |
|---|---|---|
| `Custom "TurnOnLoggers Configuration"` | REST layer only | Desired state + revision counter |
| `Custom "TurnOnLoggers Status <host>"` | that host's service only | What that host applied, plus its OS/JVM/log-file facts |

One writer per object, so no lock contention and no lost updates however many
hosts are in the cluster. Both are plain `Custom` objects - readable and
editable from the debug page if you ever need to inspect or clear them by hand.

---

## Troubleshooting

**"Unable to install service: TurnOnLoggersSync" / `ClassNotFoundException`**
The ServiceDefinition needs `<entry key="pluginName" value="TurnOnLoggers"/>` in
its Attributes map, otherwise IIQ resolves the executor through the webapp
classloader, which cannot see inside a plugin jar. The shipped XML has it; if
you hand-edited the object in the debug page, put it back. The other way to see
this error is a stale ServiceDefinition left behind by a previous uninstall -
see **Uninstalling** above.

**A host shows "not reporting"**
Its sync service has not run. Check that host's `sailpoint.log` for
`Unable to install service`, and confirm the host is not excluded from the
service via the `Server` object's service include/exclude lists.

**A host shows "catching up"**
It is on an older revision than the config. Normal for up to one interval after
a change. If it persists, that host's service is not ticking.

**The level changed but nothing appears in the log**
The logger is set, but the message has to reach an appender. Check the Hosts
table for that host's log file paths - on a stock IIQ install the file appender
in `log4j2.properties` is commented out, and everything goes to stdout
(`catalina.out` / the console window) instead.

**Changes are not reaching other hosts**
Cluster propagation is the service's job. If `POST /sync` works on the host you
are talking to but others never move, the service is not running there - see
the first two entries above.

---

## Limitations

**The ServiceDefinition is not cleaned up on uninstall.** This is the one to
know about. IIQ auto-imports `import/install/*.xml` when a plugin is installed,
but provides no matching uninstall hook - there is `import/install/` and
`import/upgrade/`, and for SQL there is `db/uninstall/`, but no
`import/uninstall/`, and `db/uninstall/` scripts run against the *plugin*
database rather than the IIQ database where `spt_service_definition` lives.

So removing the plugin leaves `ServiceDefinition TurnOnLoggersSync` behind,
pointing at an executor class that departed with the plugin jar. Every host
then logs, once per Servicer cycle:

```
ERROR sailpoint.server.Servicer - Unable to install service TurnOnLoggersSync
Caused by: java.lang.ClassNotFoundException: com.example.turnonloggers.service.LoggerSyncService
```

Nothing breaks, but the log noise is permanent until someone removes it:

```
iiq console
> delete ServiceDefinition TurnOnLoggersSync
```

There is no way for the plugin to do this for itself - by the time the
ServiceDefinition is orphaned, every class that could delete it has already
been unloaded. This applies to any IIQ plugin that ships a service, not just
this one. **Uninstall is a two-step operation; treat the console command as
part of it.**

Related, and the same root cause: a ServiceDefinition whose executor lives in a
plugin jar **must** carry `<entry key="pluginName" value="TurnOnLoggers"/>` in
its Attributes map, or IIQ resolves the class through the webapp classloader
and fails identically. The shipped XML has it. If you ever hand-edit that
object in the debug page, do not drop the attribute.

Other limitations:

- Appenders are not managed - only levels. Where a logger writes is still
  `log4j2.properties`' business.
- A brand-new host that has never run the service does not appear in the Hosts
  table until it either heartbeats a `Server` object or ticks once.
- Overrides live in the IIQ database, so an environment refresh that copies the
  database carries them along. The TTL cap limits how long that can matter.
- Plugin settings survive an in-place upgrade but are reset to defaults by an
  uninstall. See [Upgrading and rolling back](#upgrading-and-rolling-back).

---

## Author

Sahiljit Singh Manhas

Built and tested against IdentityIQ 8.5 on Tomcat 9 / JDK 11 / SQL Server.

## License

[MIT](LICENSE).

SailPoint and IdentityIQ are trademarks of SailPoint Technologies, Inc. This is
an independent plugin, not affiliated with or supported by SailPoint.
