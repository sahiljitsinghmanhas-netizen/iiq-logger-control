# Logger Manager (`TurnOnLoggers`)

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

### Where a logger came from

The page lists **every logger live in each host's JVM**, with its source stated
rather than guessed:

| Source | Meaning | Cleared by the plugin? |
|---|---|---|
| `log4j2.properties` | declared in that host's file | never |
| `this plugin` | an override currently managed here | on removal or expiry |
| `left over` | this plugin created it and lost track of it | yes, by *Clear left over* |
| `set at runtime` | something else set it | **never automatically** |

That last row matters. Anything in the JVM can set a level programmatically -
an IIQ rule doing `Logger.getLogger("Rule.X").setLevel(DEBUG)` creates a
`LoggerConfig` that is not in the file and not the plugin's. Earlier versions
inferred "not in the file and not mine" meant "my litter", which reported such
loggers as stranded and would have deleted logging a rule had configured.

The plugin now keeps a durable per-host record of the loggers it actually
created, so *Clear left over* can only remove its own. A logger set by anything
else is shown, labelled, and left alone - it can only be removed by clicking
**Remove** on that specific row, which spells out what it is doing.

The **File says** column shows the level the file declares. When the live level
differs, the row is flagged `differs from file` - which catches both a hand
edit on one host and a leftover override sitting on a logger the file declares.

### Loggers stranded by a plugin reinstall

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

### Suppress versus Clear

Two different things can be done to a logger this plugin did not set, and the
difference is what happens afterwards:

| | Suppress | Clear |
|---|---|---|
| The logger entry | stays, held at `OFF` | deleted |
| Enforced afterwards? | yes, re-applied every sync | no |
| Survives a rule re-setting it? | **yes** | no - it comes back |
| Reversible? | yes, **Un-suppress** | nothing to reverse |

*Suppress holds it; Clear deletes it and lets go.*

Use **Suppress** when something must stop logging and stay stopped - almost
always the right choice for a logger a rule sets. Use **Clear** to tidy up an
entry that should not exist and that nothing will recreate, such as a
`left over` row from an older plugin install.

**Loggers declared in `log4j2.properties` have no Clear.** Deleting one would
achieve nothing: log4j2 rebuilds its configuration from that file on every
change and restart, so the logger would return - and the plugin would be
quietly fighting the host's declared configuration. Overriding it to `OFF`
is the honest option, and it stays visible and reversible.

### Built-in help

The **?** beside *Sync this host now* opens `ui/help.html` in a new tab - a
plain page, not an overlay - covering everything on the page: what each section is, where a logger came from, what every button does,
Suppress versus Clear, the expiry rules, propagation across hosts, the audit
trail, the plugin settings, what to check when something looks wrong, and the
known limitations. It is the same material as this README, at the point of use.

### Which button appears where

Actions are named after the thing the row represents.

*Loggers live in the JVM* lists **loggers**, so its verbs describe what happens
to the logger. *Overrides in effect* lists **this plugin's overrides**, and the
only thing that can be done to one is delete it - the same operation whatever
its level - so every row there reads **Remove override**.

| Table | Row is | Actions |
|---|---|---|
| Loggers live in the JVM | a logger | **Suppress** (a toggle), **Clear** |
| Overrides in effect | an override | **Remove override** |
| The form at the top | - | **Turn on** |

**Suppress is a toggle, not two buttons.** Green with a filled dot means this
plugin is holding that logger at `OFF` right now; click it again to lift the
hold. The state is visible without reading anything. Where a logger is covered
by more than one override - the same name pinned to different host subsets -
the toggle is disabled and points at *Overrides in effect*, rather than
guessing which override to lift.

What each one does, and where it leaves things:

| | Effect | Appears in Overrides in effect afterwards? |
|---|---|---|
| **Suppress** | plugin holds the logger at `OFF`, re-applied every sync | **yes** - it creates an override |
| **Suppress**, clicked again | deletes that override; the logger goes back to whatever sets it | no - the override is gone |
| **Clear** | deletes the entry from the running configuration, nothing enforced after | no - it never creates an override |

Lifting the suppression on a logger declared in `log4j2.properties` returns it
to its file level immediately. Lifting it on one a rule sets returns it when
that rule next runs - lifting the hold cannot make the rule run.

### Silencing a noisy logger

Turning something **down** is as important as turning it up, and it is the
case a TTL gets wrong. If a logger is noisy because `log4j2.properties` sets it
that way, an override that expires just lets the noise back an hour later.

So the expiry rule depends on direction:

| Level you are setting | Expiry |
|---|---|
| `OFF` - the logger produces nothing | **may be permanent** |
| everything else | must expire, capped by `maxTtlMinutes` |

Logging left on by accident is what fills a disk, and a logger set to `OFF`
cannot. Only `OFF` qualifies: `WARN` or `ERROR` may be a reduction or an
increase depending on what the logger was already at, so "is this definitely
off" is the only question with an unambiguous answer. The *Turn off after* list
offers **never (permanent)**, greyed out until you pick `OFF`.

The quickest route is the **Silence** button on any row of *Already set in
log4j2.properties* - one click adds a permanent `OFF` override for that logger
on every host. The file is not modified, and removing the override hands the
logger straight back to whatever the file says.

Verified end to end: with `org.hibernate.SQL=DEBUG` set in the file, the log
took 31 SQL lines from a single page load; after silencing, 0; after removing
the silence, 33 again.

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

## Two things the buttons do

**Remove all overrides** (header, red) removes every override this plugin
holds, on every host. Each affected logger goes back to whatever its own
`log4j2.properties` gives it. Nothing in the file is changed, loggers set at
runtime by a rule or custom code are not touched, and the plugin stays enabled.
It is an undo of everything this plugin has done, not a kill switch.

**Sync this host now** reconciles the host serving the page immediately.
There is deliberately no "sync all hosts": every host already reconciles itself
on its own timer, so there is nothing to force - a cluster-wide button would
not make a change land any sooner than the tick that is coming anyway. The
**Last sync** column in the Hosts table is how you see each host doing it.

## Audit trail

Every change made through the plugin is recorded as an IIQ audit event, so
"who turned that on, when, and why" has an answer outside the application log.
The note you type when enabling a logger is carried into the event.

| Field | Holds |
|---|---|
| action | `LoggerManagerChange` |
| source | the user who made the change |
| target | the logger |
| string1 | what happened - enabled, silenced, updated, turned off, removed |
| string2 / string3 / string4 | level, target hosts, expiry |
| attribute `note` | the note from the form |

Every button in the plugin writes one, not just the ones that change a level:

| Button | Recorded as |
|---|---|
| Turn on | `enabled` |
| Silence | `silenced` |
| Turn off | `turned off` |
| Remove all overrides | `turned everything off` |
| Clear left over / Remove | `removed from the live configuration` |
| Sync this host now | `synced` |

Verified by pressing all six and counting rows in `spt_audit_event`.

The page's own background refresh is **not** audited. It polls every ten
seconds while open, so recording it would add several hundred rows an hour per
open tab and bury the changes that matter. Reads change nothing; actions are
what the trail is for.

**There is no switch for this.** Changing what a production system logs is a
privileged action, so whether it gets recorded is deliberately not something
the person making the change can turn off. The plugin has no audit on/off
control, and the events are written whatever Audit Configuration says.

IIQ normally only persists an event whose action is enabled under
**gear icon → Global Settings → Audit Configuration**. The plugin registers
`LoggerManagerChange` there - additively, once, leaving every other action
untouched - so its events appear in the Audit Search action list like anything
else. But registration is presentation only: if the action is disabled, or
registration fails, the event is still written. Verified by forcing the action
to `enabled="false"` in the database and confirming the row still landed in
`spt_audit_event`.

Note the plugin does not re-enable an action an administrator has deliberately
switched off - it simply does not depend on it.

Changes are always written to `sailpoint.log` as well, and an audit failure
never blocks the level change itself.

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

Download `TurnOnLoggers-2.15.0.zip` from the
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
**gear icon → Plugins → Logger Manager → Configure**, where the plugin adds an
**Open Logger Manager** button at the top of the settings form.

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

`gear icon → Plugins → Logger Manager → Settings`. Changes take effect on the
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

**Not the normal way to turn a logger on** - use the Logger Manager page for
that. This field exists for the one case the page deliberately does not cover:
a logger with **no expiry**, which stays on until you edit the box. Everything
set on the page expires; anything listed here does not.

Comma-separated `logger=LEVEL`, with an optional `@host` suffix to restrict one
item to a single host:

```
sailpoint.api.Provisioner=DEBUG, sailpoint.connector=TRACE@iiq-app-02
```

They appear in the Logger Manager page's table marked **"from settings"** with
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
