# Building IdentityIQ plugins - what this project cost to learn

Notes from building Logger Manager. Most of this is absent from SailPoint's
documentation, and nearly all of it was learned by something failing silently.

Bias throughout: **IIQ's plugin framework fails quietly.** A wrong manifest key
is ignored rather than rejected, a missing entry produces a blank page, and a
broken script leaves the previous page working. Assume nothing worked until you
have seen it work.

---

## 1. The manifest

- **`<FullPage>` only accepts `title`.** `pluginFile`, `titleKey`,
  `allowedRights` look plausible and fail DTD validation on install.
- **The page file must be at `ui/page.xhtml`.** Hardcoded in
  `PluginBean.getPageIncludeUrl()`. It must be a JSF facelet
  (`<ui:composition xmlns:ui="http://java.sun.com/jsf/facelets">`), not plain
  HTML.
- **`scripts` and `styleSheets` must be inside a `<Snippet>`** within
  `<entry key="snippets">`. As top-level entries they are silently ignored - no
  `<script>` tag is injected and you get a blank page with no error anywhere.
- **The DTD is generated at runtime** from `@XMLClass` annotations, not a static
  file. When validation fails the error names the bad attribute; trust it. To
  find valid fields: `javap -p sailpoint/plugin/<Class>.class` inside
  `identityiq.jar`.
- **`ServiceDefinition` needs `<entry key="pluginName" value="..."/>`** or the
  executor is never found and you get a `ClassNotFoundException` once per
  Servicer cycle, forever, with the plugin otherwise looking installed.
- **`dataType="secret"` is rejected** without a custom Forms settings page. Use
  `string` until you ship one.
- **Settings changes take effect immediately** - `PluginsCache` reads the
  current value on every `getSettingString/Int/Bool`. No restart needed.
- **`minSystemVersion` is worth setting honestly.** Ours is 8.3 because that is
  where IIQ moved to log4j2. Setting it to whatever you developed on excludes
  environments that would work fine.

## 2. Packaging

- **Use `jar cfM`, never PowerShell `Compress-Archive`.** Compress-Archive
  writes entry paths with backslashes; `PluginsCache` looks them up with forward
  slashes, gets empty bytes, and the facelet parser reports "Premature end of
  file at line 1". Nothing points at the zip.
- **IIQ rejects reinstalling the same version**, and rejects downgrades. Bump
  the version for every test install or automate uninstall-then-install.
- **`install/` XML is not auto-imported.** There is no manifest hook. Users run
  `iiq console` → `import <path>` by hand. Document it.

## 3. The page

- **AngularJS is not loaded on plugin full pages.** `ng-*` attributes render as
  literal text. Vanilla JS, `fetch`, DOM manipulation.
- **Snippet scripts parse during `<head>`, before `<body>` exists.** Anything
  touching `document.body` synchronously silently does nothing. Use
  `setTimeout(fn, 0)` plus a `DOMContentLoaded` listener.
- **CSRF:** mutating calls need `X-XSRF-TOKEN`. The value is on
  `window.SailPoint.XSRF_TOKEN` - note `window.SailPoint` is a **function**, so
  a `typeof === 'object'` guard silently skips it - or the `CSRF-TOKEN` cookie.
  It is `CSRF-TOKEN`, not `XSRF-TOKEN`.
- **Never send `Accept: application/json`.** With it, an expired session sends
  IIQ into a redirect loop to `exception.jsf` and `fetch` dies with
  `ERR_TOO_MANY_REDIRECTS` instead of something diagnosable.
- **Browsers cache plugin JS hard.** The asset URL carries a static revision
  query that does not change between reinstalls, so Chrome serves stale JS from
  disk cache after a reinstall. Ctrl+F5, or keep DevTools open with "Disable
  cache" during development.
- **A `<button>` with no `type` is a submit button.** Harmless until something
  wraps your markup in a form, then every button posts the page.
- **Rebuilding the whole DOM loses scroll position.** If a section shrinks, the
  browser clamps the scroll and the reader is thrown to the top. Save and
  restore it around the rebuild.
- **URL shapes:** REST is `/plugin/rest/<@Path>/<method>`, assets are
  `/plugin/<plugin-name>/ui/<file>`. Two different roots.
- **`@Path` is case-sensitive and must match the plugin name.**

## 4. Java side

- **`sailpoint.tools.Message`, not `sailpoint.object.Message`.** Most IIQ types
  live in `sailpoint.object`, this one does not.
- **`sailpoint.object.Bundle` is what the UI calls a Role.** There is no
  `sailpoint.object.Role`.
- **`BasePluginResource.getConnection()`** returns the *plugin* database, not
  the main one.
- **`AuditConfig` is a singleton - never ship one in `install/`.** Importing it
  replaces the whole configuration and silently disables every other audit
  action in the environment. Register actions additively at runtime instead.
- **`Auditor.log()` does not commit.** It puts the event in the Hibernate
  session; the next `decache()` throws it away. Events were being written and
  silently lost. Commit explicitly.
- **Batch `javac` does not recurse.** Build a file list first.
- **Deleting a plugin's `Custom` objects while it is running** leaves a stale
  Hibernate reference and the REST layer starts returning
  "No row with the given identifier exists". Reinstall clears it.

## 5. Testing - where the real lessons were

**Test by executing, not by grepping.** The build gate originally ran the page
script through `jjs` and grepped stderr for "SyntaxError". `jjs` never prints
that for a file, so a single unescaped apostrophe shipped in three consecutive
releases. The replacement executes the script against a stub DOM and fails the
build if the page does not render. It has caught three real breakages since.

**Make the gate say why.** For a while it could report "the page did not render"
but not the cause, because the stub promise caught the exception into the page's
own error banner. Printing what the page said turned a guessing game into a
one-line diagnosis.

**Endpoint tests are not behaviour tests.** ~66 REST checks passed while the
source-classification logic had a hole that would have let "Clear all left over"
delete a logger a rule was actively using. What found it was testing *sequences*
- turn a logger on, let it expire, see what the source says afterwards - not
testing that endpoints return 200.

**Test the disaster you are afraid of, properly.** The first attempt at
"what if the plugin forgets a logger it set" edited the database, but ownership
lived in memory, so nothing was actually stranded and the test passed
meaninglessly. The faithful version needed uninstall → delete both `Custom`
objects → reinstall. Only then was the failure real, and only then was the
recovery path (still visible, still suppressible, still clearable) actually
proven.

**Watch what your tests consume.** The REST audit cleared a file-declared logger
on every run to prove that path worked. That is one-shot until a restart, so it
quietly ate the host's `log4j2.properties` loggers one per run. Destructive
checks should be opt-in.

**Screenshots need looking at, not grepping.** Names rendered into pixels are
invisible to every text search. A screenshot of live data can publish something
you did not intend - cap what it captures and read it back before committing.

## 6. Documentation drift

- **Renamed things hide across line breaks.** Three stale section names survived
  a rename because they wrapped mid-phrase in the source. Checking the new name
  appears is not the same as checking the old one is gone - collapse whitespace
  and search for the old ones explicitly.
- **Images go stale silently.** If a build step copies screenshots into the
  shipped plugin, regenerating screenshots *after* the build means the shipped
  help page is a release behind. Automate the copy and verify the two
  directories hash-match.
- **Release titles drift.** Ours went from a stable name to a per-release
  description for ten releases before anyone noticed. Write the convention down;
  intent is not a control.

## 7. Design calls that held up

- **One writer per object.** Desired state written only by the REST layer, each
  host's status written only by that host. No locking, scales to any cluster.
- **Read the runtime, not your own records.** The live-logger view is built by
  asking log4j2 what it has configured, so a logger the plugin has forgotten is
  still visible and controllable. This is the single property that makes the
  plugin safe to use.
- **Never write files.** Everything is applied to the live log4j2 runtime, so a
  restart always returns a host to its `log4j2.properties`. There is no
  half-written config to recover from, and that is the strongest reassurance you
  can give someone nervous about turning DEBUG on in production.
- **Make refusals visible.** Protected loggers show their controls *disabled*
  rather than hidden, so it is clear the action exists and is being refused
  rather than missing.
- **Colour means one thing.** Red for "this host reported a problem" only.
  Anything else - not yet reported, nothing found - is grey, or a cluster
  mid-rollout looks like a cluster on fire.
