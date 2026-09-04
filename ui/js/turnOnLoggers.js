/*
 * Logger Manager - plugin page UI.
 *
 * Vanilla JS on purpose: AngularJS is not loaded on IIQ plugin fullPages, so
 * ng-* attributes render as literal text. Everything here is fetch + DOM.
 *
 * The snippet that loads this file is injected during <head> parsing, before
 * <body> exists, so all DOM work is deferred to DOMContentLoaded (or a
 * setTimeout when the document is already parsed).
 */
(function () {
    'use strict';

    var ROOT_ID = 'turn-on-loggers-root';
    var CTX = (window.SailPoint && typeof window.SailPoint === 'object' && window.SailPoint.CONTEXT_PATH) || '/identityiq';
    var REST = CTX + '/plugin/rest/TurnOnLoggers';
    var POLL_MS = 10000;
    /**
     * How often to look while a download is outstanding.
     *
     * A host other than the one serving this page answers on its own sync,
     * once a minute, and nothing can make that happen sooner - no host can
     * reach into another to ask. But the answer then sat in the database until
     * the next ordinary poll, adding up to ten more seconds of nothing
     * happening on top of a wait that already felt long. This is only ever
     * running while somebody is actually waiting for a file.
     */
    var POLL_FAST_MS = 1500;
    /**
     * How long to wait for a host before saying something is wrong.
     *
     * A host answers on its own sync, once a minute by default, and a slow one
     * can take longer. Three minutes is well past any healthy interval, so past
     * it the honest thing is to stop counting and name the likely reason rather
     * than let a number climb until somebody gives up on the tool.
     */
    var GIVE_UP_MS = 180000;

    /**
     * Which window this is.
     *
     * Fresh per page load, deliberately not stored: a popped-out window opened
     * from this one would inherit sessionStorage in some browsers, which is
     * precisely the confusion being removed. A download belongs to the window
     * that asked for it, so opening a second window - or having three open on
     * three hosts, which this plugin encourages - cannot make any of them save
     * a file nobody just asked for.
     */
    var CLIENT = 'w' + Math.random().toString(36).slice(2) + Date.now().toString(36);

    var state = null;
    var banner = null;
    var pollTimer = null;
    var formTouched = false;
    var root;

    // ------------------------------------------------------------------
    // tiny DOM helpers
    // ------------------------------------------------------------------

    function el(tag, cls, text) {
        var n = document.createElement(tag);
        // A <button> with no type is a submit button. Nothing on this page sits
        // inside a form today, so it is harmless today - but if IIQ ever wraps
        // the plugin include in one, every button here would post the page.
        if (tag === 'button') n.type = 'button';
        if (cls) n.className = cls;
        if (text !== undefined && text !== null) n.appendChild(document.createTextNode(String(text)));
        return n;
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
    }

    function opt(value, label, selected) {
        var o = document.createElement('option');
        o.value = value;
        o.appendChild(document.createTextNode(label));
        if (selected) o.selected = true;
        return o;
    }

    function fmtTime(ms) {
        var n = parseInt(ms, 10);
        if (!n) return 'never';
        return new Date(n).toLocaleString();
    }

    function fmtAgo(ms) {
        var n = parseInt(ms, 10);
        if (!n) return 'never';
        var d = Date.now() - n;
        if (d < 0) d = 0;
        return fmtDuration(d) + ' ago';
    }

    function fmtDuration(ms) {
        var s = Math.floor(ms / 1000);
        if (s < 60) return s + 's';
        var m = Math.floor(s / 60);
        if (m < 60) return m + 'm ' + (s % 60) + 's';
        var h = Math.floor(m / 60);
        if (h < 24) return h + 'h ' + (m % 60) + 'm';
        return Math.floor(h / 24) + 'd ' + (h % 24) + 'h';
    }

    // Named rather than "this plugin". Every other badge here says where a
    // level came from in terms that survive being looked at somewhere else -
    // log4j2.properties names a file - and this one used to point at context
    // the reader had to already be standing in. These badges end up in
    // screenshots, in the help page and in the demo, where "this plugin"
    // answers nothing. "Logger Manager" rather than "Logger Manager Plugin":
    // the display name is enough, and at 14 characters it fits the column
    // better than the widest badge already in it.
    var SRC = {
        file: 'log4j2.properties',
        plugin: 'Logger Manager',
        leftover: 'left over',
        runtime: 'set at runtime',
        unknown: 'source unknown'
    };

    var OS_ICON = { windows: 'Win', linux: 'Linux', macos: 'macOS', aix: 'AIX', solaris: 'Solaris', other: 'OS' };

    // ------------------------------------------------------------------
    // REST
    // ------------------------------------------------------------------

    function xsrf() {
        // window.SailPoint is a FUNCTION in IIQ 8.5, not a plain object, and it
        // carries the token as a property. Guarding on typeof === 'object'
        // meant the token was never found, so no header was sent and every REST
        // call came back as the login page - which this code then reported as
        // "session expired" on a perfectly good session.
        try {
            if (window.SailPoint && window.SailPoint.XSRF_TOKEN) {
                return window.SailPoint.XSRF_TOKEN;
            }
        } catch (e) { /* fall through to the cookie */ }

        // And the cookie is CSRF-TOKEN, not XSRF-TOKEN. Both are accepted here
        // so this keeps working whichever name a given IIQ build uses.
        var m = document.cookie.match(/(?:^|;\s*)[XC]SRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : null;
    }

    function api(method, path, body) {
        // No Accept: application/json header on purpose - with one, an expired
        // session sends IIQ into a redirect loop to exception.jsf and fetch
        // dies with ERR_TOO_MANY_REDIRECTS instead of something diagnosable.
        var opts = { method: method, headers: {}, credentials: 'same-origin', redirect: 'manual' };
        // IIQ accepts either of these to treat the call as an API request. With
        // neither it hands back the login page as HTML, which this code then
        // reports as "session expired" - a confusing lie when the session is
        // perfectly good and only the token lookup came up empty. Sending both
        // means the API works even if window.SailPoint.XSRF_TOKEN is missing.
        opts.headers['X-Requested-With'] = 'XMLHttpRequest';
        var t = xsrf();
        if (t) opts.headers['X-XSRF-TOKEN'] = t;
        if (body !== undefined) {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(body);
        }
        return fetch(REST + path, opts).then(function (res) {
            if (res.type === 'opaqueredirect' || res.status === 0) {
                throw new Error('Session expired - reload the page and log in again.');
            }
            var ct = res.headers.get('Content-Type') || '';
            if (ct.indexOf('text/html') > -1) {
                throw new Error('Session expired - reload the page and log in again.');
            }
            return res.text().then(function (txt) {
                var data = null;
                try { data = txt ? JSON.parse(txt) : null; } catch (e) { data = null; }
                if (!res.ok) {
                    throw new Error((data && data.error) || ('HTTP ' + res.status));
                }
                return data;
            });
        });
    }

    function say(msg, kind) {
        if (!banner) return;
        clear(banner);
        if (!msg) { banner.className = 'tol-banner tol-hidden'; return; }
        banner.className = 'tol-banner tol-' + (kind || 'info');
        banner.appendChild(document.createTextNode(msg));
    }

    /**
     * Fetch state, act on it, and usually repaint.
     *
     * noPaint exists because those are three different things and only the
     * last one is unsafe while somebody is typing. Conflating them cost a
     * fortnight of "the download takes forever, but if I refresh the page it
     * arrives instantly": typing anywhere - the turn-on form, the collection
     * editor, the highlight box in the log viewer - set a flag that made the
     * poll return before doing anything at all, so nothing was fetched and the
     * download that had been sitting there ready was never noticed. Reloading
     * cleared the flag, which is what made a refresh look like a fix.
     */
    /**
     * Where each log block was scrolled to, so a repaint can put it back.
     *
     * The page rebuilds itself on a timer, and rebuilding a log block resets it
     * to the top. On a two thousand line log that means being thrown back to
     * the beginning every few seconds while trying to read the middle, which
     * is the single most irritating thing a page like this can do.
     */
    function logScrollPositions() {
        var out = {};
        var pres = document.querySelectorAll('#tol-sec-logs pre.tol-log');
        for (var i = 0; i < pres.length; i++) {
            if (pres[i].scrollTop > 0) out[pres[i].getAttribute('data-host') || i] = pres[i].scrollTop;
        }
        return out;
    }

    function restoreLogScroll(saved) {
        if (!saved) return;
        var pres = document.querySelectorAll('#tol-sec-logs pre.tol-log');
        for (var i = 0; i < pres.length; i++) {
            var key = pres[i].getAttribute('data-host') || i;
            if (saved[key]) pres[i].scrollTop = saved[key];
        }
    }

    function load(quiet, noPaint) {
        return api('GET', '/state').then(function (data) {
            state = data;
            checkPendingDownload();
            if (!noPaint) {
                var where = logScrollPositions();
                render();
                restoreLogScroll(where);
            }
            if (!quiet) say(null);
        }).catch(function (e) {
            say(e.message, 'error');
            // Before the first successful render there is no banner to write
            // into, so without this the page sits on "Loading logger control..."
            // forever and the real error is never seen.
            if (!state && root) {
                clear(root);
                var b = el('div', 'tol-banner tol-error');
                b.appendChild(document.createTextNode('Could not load Logger Manager: ' + e.message));
                root.appendChild(b);
            }
        });
    }

    function mutate(promise, okMsg) {
        say('Working...', 'info');
        return promise.then(function (data) {
            state = data;
            render();
            say(okMsg, 'ok');
        }).catch(function (e) {
            say(e.message, 'error');
        });
    }

    // ------------------------------------------------------------------
    // render
    // ------------------------------------------------------------------

    function render() {
        if (!root || !state) return;

        // Rebuilding the whole tree empties the page for an instant. If the
        // reader was scrolled further down than the momentarily-shorter
        // document allows, the browser clamps the scroll position - and pressing
        // a button that changes one row throws you back to the top. Note the
        // position before tearing it down and put it back afterwards.
        var scroller = document.scrollingElement || document.documentElement;
        var wasAt = (scroller && scroller.scrollTop) || window.scrollY || 0;

        clear(root);

        root.appendChild(header());
        banner = el('div', 'tol-banner tol-hidden');
        root.appendChild(banner);

        if (!state.enabled) {
            var off = el('div', 'tol-banner tol-warn');
            off.appendChild(document.createTextNode(
                'The plugin is disabled in its settings. Overrides below are stored but not applied - ' +
                'every host is running the levels from its own log4j2.properties.'));
            root.appendChild(off);
        }
        if (state.log4jAvailable === false) {
            var nolog = el('div', 'tol-banner tol-error');
            nolog.appendChild(document.createTextNode(
                'log4j2-core is not reachable from this host, so levels cannot be changed here.'));
            root.appendChild(nolog);
        }

        // Stable ids rather than positional selectors: the sections have been
        // reordered twice, and anything targeting them by position - the
        // screenshot tool, a stylesheet, a bookmarklet - silently pointed at
        // the wrong one each time.
        // A popped-out window is only there to read logs. Everything else would
        // be a second copy of controls that change shared state, which is a
        // good way for two windows to fight over the same override.
        var sections = LOG_ONLY
            ? [[logsSection(), 'tol-sec-logs']]
            : [[addForm(), 'tol-sec-form'],
               [collectionsSection(), 'tol-sec-collections'],
               [overridesSection(), 'tol-sec-overrides'],
               [liveLoggersSection(), 'tol-sec-live'],
               [hostsSection(), 'tol-sec-hosts'],
               [logsSection(), 'tol-sec-logs'],
               [historySection(), 'tol-sec-history']];
        sections.forEach(function (pair) {
            if (!pair[0]) return;
            pair[0].id = pair[1];
            root.appendChild(pair[0]);
        });
        root.appendChild(footer());

        if (wasAt > 0) {
            // Clamped by the browser if the new page really is shorter, which is
            // the right answer in that case.
            if (scroller) scroller.scrollTop = wasAt;
            if (window.scrollY !== wasAt && window.scrollTo) window.scrollTo(0, wasAt);
        }

        // The tree was just rebuilt, so any marks are gone and the double-click
        // handler is on a node that no longer exists. Both are cheap to redo and
        // losing your highlight every ten seconds would be maddening.
        if (LOG_ONLY) {
            stripToReader();
            wireDoubleClickHighlight();
            if (logState.hilite) paintHighlights();
        }
    }

    function header() {
        var h = el('div', 'tol-header');
        var left = el('div', 'tol-header-left');
        left.appendChild(el('h1', 'tol-title', 'Logger Manager'));
        var facts = state.thisHostFacts || {};
        var sub = el('div', 'tol-subtitle');
        sub.appendChild(document.createTextNode(
            'Turn loggers on and off across every host. No shell, no file edit, no restart.'));
        left.appendChild(sub);
        var chips = el('div', 'tol-chips');
        chips.appendChild(chip('Serving host', state.thisHost));
        chips.appendChild(chip(OS_ICON[facts.osFamily] || 'OS', facts.os || '?'));
        chips.appendChild(chip('Config revision', String(state.revision)));
        left.appendChild(chips);
        h.appendChild(left);

        var right = el('div', 'tol-header-right');
        var sync = el('button', 'tol-btn', 'Sync this host now');
        sync.title = 'Reconcile the host serving this page immediately. Every other host '
            + 'reconciles itself on its own timer, so there is nothing to force there - '
            + 'watch Last sync in the Host Status table.';
        sync.onclick = function () {
            mutate(api('POST', '/sync'), 'This host reconciled against the stored configuration.');
        };
        // A link to a separate page, not an overlay. The overlay version could
        // not be hidden reliably - .tol-hidden was declared before .tol-help in
        // the stylesheet, so display:flex won and Close did nothing.
        var helpBtn = document.createElement('a');
        helpBtn.className = 'tol-help-btn';
        helpBtn.href = CTX + '/plugin/TurnOnLoggers/ui/help.html';
        helpBtn.target = '_blank';
        helpBtn.rel = 'noopener noreferrer';
        helpBtn.title = 'Help - what everything on this page means (opens in a new tab)';
        helpBtn.setAttribute('aria-label', 'Help');
        helpBtn.appendChild(document.createTextNode('?'));
        right.appendChild(helpBtn);
        right.appendChild(sync);

        var panic = el('button', 'tol-btn tol-btn-danger', 'Remove all overrides');
        panic.title = 'Remove every override this plugin holds, on every host';
        panic.onclick = function () {
            if (!window.confirm('Remove every override this plugin holds, on every host?'
                + ' Each affected logger goes back to the level its own log4j2.properties gives it.'
                + ' Nothing in log4j2.properties is changed, and loggers set at runtime by a rule'
                + ' or custom code are not touched. The plugin stays enabled.')) return;
            mutate(api('DELETE', '/entries'),
                'All overrides removed. This host is back to its file levels; others follow within a minute.');
        };
        right.appendChild(panic);

        h.appendChild(right);
        return h;
    }

    function chip(label, value) {
        var c = el('span', 'tol-chip');
        c.appendChild(el('span', 'tol-chip-label', label));
        c.appendChild(el('span', 'tol-chip-value', value));
        return c;
    }

    function addForm() {
        var box = el('section', 'tol-card');
        box.appendChild(el('h2', 'tol-card-title', 'Add a Logger'));

        var form = el('form', 'tol-form');
        form.onsubmit = function (ev) { ev.preventDefault(); submit(); };
        form.oninput = function () { formTouched = true; };

        // Free-text field. The catalog is offered through a datalist purely as
        // suggestions - a datalist never restricts what can be typed - but the
        // browser renders the dropdown arrow exactly like a <select>, so the
        // hint has to say out loud that anything is accepted. Custom loggers
        // declared inside a rule (Logger.getLogger("rule.myRule")) are a main
        // use case and will never appear in any catalog.
        var f1 = field('Logger',
            'Type any logger name - anything log4j2 knows about, including your own, '
            + 'e.g. rule.myCustomRule. The dropdown only suggests common IIQ ones.');
        var input = document.createElement('input');
        input.type = 'text';
        input.id = 'tol-logger';
        input.className = 'tol-input';
        input.setAttribute('list', 'tol-catalog');
        input.setAttribute('placeholder', 'type or pick a logger name');
        input.setAttribute('autocomplete', 'off');
        f1.appendChild(input);

        var dl = document.createElement('datalist');
        dl.id = 'tol-catalog';
        (state.catalog || []).forEach(function (c) {
            var o = document.createElement('option');
            o.value = c.logger;
            o.label = c.label;
            dl.appendChild(o);
        });
        f1.appendChild(dl);
        form.appendChild(f1);

        // level
        var f2 = field('Level', 'DEBUG is what you want most of the time. TRACE includes method entry/exit.');
        var lvl = document.createElement('select');
        lvl.id = 'tol-level';
        lvl.className = 'tol-input';
        (state.levels || []).forEach(function (l) { lvl.appendChild(opt(l, l, l === 'DEBUG')); });
        f2.appendChild(lvl);
        form.appendChild(f2);

        // ttl
        var f3 = field('Turn off after',
            'Overrides expire so logging cannot be left on by accident. Only OFF may be '
            + 'permanent, so a logger you switch off stays off.');
        var ttl = document.createElement('select');
        ttl.id = 'tol-ttl';
        ttl.className = 'tol-input';
        var choices = [15, 30, 60, 120, 240, 480, 1440];
        var max = parseInt(state.maxTtlMinutes, 10);
        var def = parseInt(state.defaultTtlMinutes, 10);
        if (choices.indexOf(def) < 0) choices.push(def);
        choices.sort(function (a, b) { return a - b; });
        choices.forEach(function (m) {
            if (max > 0 && m > max) return;
            ttl.appendChild(opt(String(m), fmtDuration(m * 60000), m === def));
        });
        // Always present, enabled only when the chosen level cannot increase
        // output. Greying it out rather than hiding it makes the rule
        // discoverable instead of leaving people wondering where it went.
        var never = opt('0', 'never (permanent)', false);
        ttl.appendChild(never);
        f3.appendChild(ttl);
        var ttlNote = el('div', 'tol-hint', '');
        f3.appendChild(ttlNote);
        form.appendChild(f3);

        function syncNeverOption() {
            var quieting = (state.quietingLevels || []).indexOf(lvl.value) > -1;
            var permitted = quieting || !(max > 0);
            never.disabled = !permitted;
            if (!permitted && ttl.value === '0') ttl.value = String(def);
            clear(ttlNote);
            ttlNote.appendChild(document.createTextNode(permitted
                ? 'OFF can be permanent - switching a logger off cannot flood a disk.'
                : lvl.value + ' produces output, so it has to expire. Only OFF may be permanent.'));
        }
        lvl.onchange = syncNeverOption;
        syncNeverOption();

        // hosts
        var f4 = field('Hosts', 'Leave on "All hosts" unless you are chasing something host-specific.');
        var hostBox = el('div', 'tol-hostpicker');
        var all = checkbox('tol-host-all', 'All hosts', true);
        hostBox.appendChild(all.wrap);
        var perHost = [];
        (state.hosts || []).forEach(function (h) {
            var c = checkbox('tol-host-' + h.name, h.name, false);
            c.input.setAttribute('data-host', h.name);
            c.input.disabled = true;
            perHost.push(c);
            hostBox.appendChild(c.wrap);
        });
        all.input.onchange = function () {
            perHost.forEach(function (c) {
                c.input.disabled = all.input.checked;
                if (all.input.checked) c.input.checked = false;
            });
        };
        f4.appendChild(hostBox);
        form.appendChild(f4);

        // note
        var f5 = field('Note (required)',
            'Why this is on. Recorded in the audit trail and shown to whoever finds the logger '
            + 'later - a ticket number or a sentence.');
        var note = document.createElement('input');
        note.type = 'text';
        note.id = 'tol-note';
        note.className = 'tol-input';
        note.setAttribute('placeholder', 'INC0012345 - provisioning failures to AD');
        note.setAttribute('required', 'required');
        f5.appendChild(note);
        form.appendChild(f5);

        var actions = el('div', 'tol-form-actions');
        var btn = el('button', 'tol-btn tol-btn-primary', 'Turn on');
        btn.type = 'submit';
        actions.appendChild(btn);
        form.appendChild(actions);

        box.appendChild(form);
        return box;
    }

    function field(label, hint) {
        var f = el('div', 'tol-field');
        f.appendChild(el('label', 'tol-label', label));
        if (hint) f.appendChild(el('div', 'tol-hint', hint));
        return f;
    }

    function checkbox(id, label, checked) {
        var wrap = el('label', 'tol-check');
        var input = document.createElement('input');
        input.type = 'checkbox';
        input.id = id;
        input.checked = !!checked;
        wrap.appendChild(input);
        wrap.appendChild(document.createTextNode(' ' + label));
        return { wrap: wrap, input: input };
    }

    function submit() {
        var logger = document.getElementById('tol-logger').value.trim();
        var level = document.getElementById('tol-level').value;
        var ttl = document.getElementById('tol-ttl').value;
        var note = document.getElementById('tol-note').value.trim();

        if (!logger) { say('Enter a logger name.', 'error'); return; }
        if (!note || note.length < 3) {
            say('A note is required - say why this logger is being turned on.', 'error');
            var n = document.getElementById('tol-note');
            if (n) n.focus();
            return;
        }

        var hosts = ['*'];
        var all = document.getElementById('tol-host-all');
        if (all && !all.checked) {
            hosts = [];
            var boxes = root.querySelectorAll('input[data-host]');
            for (var i = 0; i < boxes.length; i++) {
                if (boxes[i].checked) hosts.push(boxes[i].getAttribute('data-host'));
            }
            if (!hosts.length) { say('Pick at least one host, or tick "All hosts".', 'error'); return; }
        }

        formTouched = false;
        mutate(api('POST', '/entries', {
            logger: logger, level: level, ttlMinutes: parseInt(ttl, 10), hosts: hosts, note: note
        }), logger + ' set to ' + level + '. This host is live now; other hosts follow within a minute.');
    }

    // ------------------------------------------------------------------

    function overridesSection() {
        var box = el('section', 'tol-card');
        var head = el('div', 'tol-card-head');
        head.appendChild(el('h2', 'tol-card-title', 'Plugin Logger Status'));
        // The header is a space-between flex row. With a title and two buttons
        // as three children it pushed one button into the middle of the card;
        // grouping them makes it title-on-the-left, actions-on-the-right.
        var actions = el('div', 'tol-card-actions');
        if ((state.entries || []).length) {
            var picked = (state.entries || []).filter(collectChecked);
            var save = el('button', 'tol-btn tol-btn-small',
                'Save as collection (' + picked.length + ')');
            save.disabled = picked.length === 0;
            save.title = picked.length
                ? 'Save the ' + picked.length + ' ticked logger'
                  + (picked.length === 1 ? '' : 's') + ' under a name, so anyone can turn the same '
                  + 'set on again. Untick a row to leave it out.'
                : 'Tick at least one logger to save. Expired rows start unticked.';
            save.onclick = function () {
                var chosen = (state.entries || []).filter(collectChecked)
                    .map(function (e) { return { logger: e.logger, level: e.level }; });
                if (!chosen.length) return;
                var name = window.prompt('Name this collection - everyone using the plugin will see it.'
                    + '\n\nSaving ' + chosen.length + ' logger'
                    + (chosen.length === 1 ? '' : 's') + ':\n'
                    + chosen.map(function (c) { return '  ' + c.logger + ' = ' + c.level; }).join('\n')
                    + '\n\nFor example: LDAP connector debugging');
                if (!name) return;
                var desc = window.prompt('A one-line description (optional) - what is it for?') || '';
                mutate(api('POST', '/collections',
                    { name: name, description: desc, loggers: chosen }),
                    'Saved ' + chosen.length + ' logger'
                    + (chosen.length === 1 ? '' : 's') + ' as "' + name + '".');
            };
            actions.appendChild(save);
        }

        // Only offered when there is something to remove. An expired override
        // is harmless - nothing is applied from it - but it stays in the table
        // until someone clears it, and on a busy system they pile up.
        var expiredCount = (state.entries || []).filter(function (e) {
            return e.expired && !e.permanent && e.id;
        }).length;
        if (expiredCount) {
            var rmExp = el('button', 'tol-btn tol-btn-small',
                'Remove expired (' + expiredCount + ')');
            rmExp.title = 'Delete the ' + expiredCount + ' override'
                + (expiredCount === 1 ? '' : 's') + ' that have already expired. '
                + 'They are not applied to anything - this only tidies the table. '
                + 'Live overrides are left alone.';
            rmExp.onclick = function () {
                if (!window.confirm('Remove ' + expiredCount + ' expired override'
                    + (expiredCount === 1 ? '' : 's') + '?\n\nThey have already stopped applying, '
                    + 'so nothing on any host changes. Live overrides are untouched.')) return;
                mutate(api('DELETE', '/entries?expiredOnly=true'),
                    expiredCount + ' expired override'
                    + (expiredCount === 1 ? '' : 's') + ' removed.');
            };
            actions.appendChild(rmExp);
        }

        head.appendChild(actions);
        box.appendChild(head);
        box.appendChild(el('div', 'tol-hint',
            'Everything this plugin has turned on, from both this page and the plugin settings. ' +
            'Adding a logger adds to this list; it does not affect the others.'));

        (state.permanentErrors || []).forEach(function (m) {
            box.appendChild(el('div', 'tol-banner tol-warn', m));
        });

        var entries = state.entries || [];
        if (!entries.length) {
            box.appendChild(el('div', 'tol-empty',
                'Nothing overridden. Every host is running the levels from its own log4j2.properties.'));
            return box;
        }

        var t = el('table', 'tol-table');
        t.appendChild(headRow(['', 'Logger', 'Level', 'Hosts', 'Expires', 'Live on', 'Set by', '']));
        var tb = el('tbody');

        entries.forEach(function (e) {
            var tr = el('tr', e.expired ? 'tol-expired' : '');

            // Which rows a saved collection takes. Without this the only option
            // was "save everything currently listed", which on a real system
            // means sweeping up whatever else happened to be on - and expired
            // rows with it.
            var c0 = el('td', 'tol-pickcell');
            var box = document.createElement('input');
            box.type = 'checkbox';
            box.className = 'tol-pick';
            box.checked = collectChecked(e);
            box.title = 'Include ' + e.logger + ' when saving a collection';
            box.setAttribute('aria-label', 'Include ' + e.logger + ' in a saved collection');
            box.onchange = (function (key) {
                return function (ev) { collectPick[key] = ev.target.checked; render(); };
            })(collectKey(e));
            c0.appendChild(box);
            tr.appendChild(c0);

            var c1 = el('td');
            c1.appendChild(el('code', 'tol-logger-name', e.logger));
            if (e.permanent) c1.appendChild(el('span', 'tol-badge', 'from settings'));
            if (e.note) c1.appendChild(el('div', 'tol-note-text', e.note));
            tr.appendChild(c1);

            var c2 = el('td');
            c2.appendChild(el('span', 'tol-level tol-level-' + String(e.level).toLowerCase(), e.level));
            tr.appendChild(c2);

            tr.appendChild(el('td', 'tol-mono', e.hosts === '*' ? 'all hosts' : e.hosts));

            var c4 = el('td');
            var rem = parseInt(e.remainingMs, 10);
            if (e.expired) {
                c4.appendChild(el('span', 'tol-badge tol-badge-off', 'expired'));
            } else if (e.permanent) {
                c4.appendChild(el('span', 'tol-badge tol-badge-warn', 'never'));
            } else if (rem < 0) {
                c4.appendChild(el('span', 'tol-badge tol-badge-warn', 'never'));
            } else {
                c4.appendChild(el('span', 'tol-badge', 'in ' + fmtDuration(rem)));
            }
            tr.appendChild(c4);

            var c5 = el('td');
            var conf = e.confirmedOn || [];
            var pend = e.pendingOn || [];
            if (conf.length) {
                c5.appendChild(el('span', 'tol-badge tol-badge-ok', conf.length + ' host' + (conf.length === 1 ? '' : 's')));
                c5.appendChild(el('div', 'tol-mono tol-small', conf.join(', ')));
            }
            if (pend.length) {
                c5.appendChild(el('span', 'tol-badge tol-badge-warn', 'pending: ' + pend.join(', ')));
            }
            if (!conf.length && !pend.length) {
                // An expired row is not waiting on anybody, so saying "no host
                // has reported yet" would be the same lie the pending list used
                // to tell - it ran everywhere and has since been withdrawn.
                c5.appendChild(el('span', 'tol-small', e.expired
                    ? 'no longer applied'
                    : 'no host has reported yet'));
            }
            tr.appendChild(c5);

            var c6 = el('td', 'tol-small');
            c6.appendChild(document.createTextNode(e.createdBy || '?'));
            if (!e.permanent) c6.appendChild(el('div', 'tol-small', fmtAgo(e.created)));
            tr.appendChild(c6);

            var c7 = el('td');
            if (state.logTailEnabled !== false) {
                // Straight from "this logger is on" to "show me what it said",
                // which is the next question every single time.
                var find = el('button', 'tol-btn tol-btn-small', 'Find in logs');
                find.title = 'Search every host for lines mentioning this logger';
                find.onclick = (function (name) {
                    return function () {
                        mutate(api('POST', '/logquery', { text: name }),
                            'Searching every host for ' + name + '.');
                    };
                })(e.logger);
                c7.appendChild(find);
            }
            if (e.permanent) {
                // Owned by the settings page, so there is nothing safe to
                // delete here - editing the setting is the way to turn it off.
                c7.appendChild(el('span', 'tol-small', 'edit in plugin settings'));
            } else {
                // Every row in this table is an override, and the only thing
                // that can be done to one is delete it - the same operation
                // whatever its level. Naming the OFF case differently made one
                // uniform action look like two. The verb here describes the
                // row; the live-logger table names the effect on the logger.
                var del = el('button', 'tol-btn tol-btn-small tol-btn-danger', 'Remove override');
                del.title = String(e.level).toUpperCase() === 'OFF'
                    ? 'Stop holding this logger at OFF. It goes back to whatever sets it.'
                    : 'Remove this override; the logger goes back to its log4j2.properties level.';
                del.onclick = function () {
                    mutate(api('DELETE', '/entries/' + encodeURIComponent(e.id)),
                        e.logger + ' override removed here; other hosts follow within a minute.');
                };
                c7.appendChild(del);
            }
            tr.appendChild(c7);

            tb.appendChild(tr);
        });
        t.appendChild(tb);
        box.appendChild(t);
        return box;
    }

    /** Shared host-label rendering, so a host looks the same in every table. */
    /**
     * Whether a logger is protected by the untouchableLoggers setting.
     *
     * Greying the buttons out is a courtesy - the API refuses the call either
     * way - but the two have to agree or the page looks broken. Same rules as
     * PluginSettings.matches on the server: case-insensitive, '*' matches any
     * run of characters including dots, and a bare name means that name alone.
     */
    function isUntouchable(logger) {
        if (!logger) return false;
        var name = String(logger).trim().toLowerCase();
        var list = state.untouchableLoggers || [];
        for (var i = 0; i < list.length; i++) {
            var p = String(list[i]).toLowerCase();
            if (p.indexOf('*') < 0) {
                if (p === name) return true;
                continue;
            }
            var re = '';
            for (var j = 0; j < p.length; j++) {
                var c = p.charAt(j);
                re += (c === '*') ? '.*' : escapeRe(c);
            }
            try {
                if (new RegExp('^' + re + '$').test(name)) return true;
            } catch (e) { /* a pattern that will not compile protects nothing */ }
        }
        return false;
    }

    function escapeRe(c) {
        return '.*+?^${}()|[]\\'.indexOf(c) > -1 ? '\\' + c : c;
    }

    /**
     * Hosts in a stable order, the one serving this page first.
     *
     * Used everywhere chips are drawn, so "the first chip" means the same
     * thing in every section - and it is the host you are actually sitting on,
     * which is the one you usually want.
     */
    function sortedHosts() {
        return (state.hosts || []).slice().sort(function (a, b) {
            if (a.name === state.thisHost) return -1;
            if (b.name === state.thisHost) return 1;
            return a.name < b.name ? -1 : 1;
        });
    }

    /**
     * How a host is doing, independent of any log request.
     *
     * The Logs panel colours its chips by what the host found; everywhere else
     * there is no query to answer, so the colour means the other thing a host
     * can be doing badly - not reporting, lagging, or erroring. Same palette,
     * same rule underneath: colour is this host's state with respect to
     * whatever the section is about.
     */
    function hostHealth(h) {
        if (!h.reporting) {
            // Grey, not red. This is an absence of information, not a fault:
            // IIQ lists every Server it knows about, including ones the sync
            // service has not reached yet and ones that were decommissioned
            // years ago, and none of that is something going wrong. Red is
            // reserved for a host that has actually reported a problem -
            // otherwise a cluster where the plugin is still rolling out looks
            // like a cluster on fire.
            return {
                key: 'down',
                why: h.inactive
                    ? h.name + ' is marked inactive in IIQ and is not reporting.'
                    : 'Nothing heard from this host. The plugin’s sync service has not run '
                      + 'here - it may still be starting, or the plugin may not be active on this '
                      + 'host. That is not the same as something being wrong.'
            };
        }
        var errs = h.errors || [];
        if (errs.length) {
            return { key: 'error', why: errs.join(' ') };
        }
        // Never say this about a host that is plainly working. Configuration is
        // read second-hand and the observed behaviour is first-hand: if it is
        // reporting at the current revision, it is running the service,
        // whatever any list says.
        if (h.serviceOff && !h.inSync) {
            // Not a fault, and not stale in any useful sense: IdentityIQ has
            // been told not to run this service here, so this host will never
            // tick no matter how long you wait. Saying "stale" sends people
            // hunting a failure that does not exist.
            return {
                key: 'disabled',
                why: 'IdentityIQ is configured not to run the sync service on ' + h.name
                     + ' - see gear icon, Global Settings, Host Configuration. Overrides will '
                     + 'not reach this host on their own. Anything done from this page still '
                     + 'applies here, which is why it briefly reads as in sync afterwards.'
            };
        }
        if (h.stale) {
            // A host whose ticks are dying and a host that is merely slow both
            // just stop updating. If the failing host managed to say why, say
            // it here rather than leaving the reader to guess.
            if (h.tickError) {
                return { key: 'error', why: 'Its last sync was ' + fmtAgo(h.lastSync)
                                            + ', and its sync service is failing: ' + h.tickError };
            }
            return { key: 'stale', why: 'Reporting, but its last sync was ' + fmtAgo(h.lastSync) + '.' };
        }
        if (!h.inSync) {
            return { key: 'wait', why: 'Catching up - host revision ' + h.revision
                                       + ' against ' + state.revision + '.' };
        }
        return { key: 'ok', why: 'In sync, last synced ' + fmtAgo(h.lastSync) + '.' };
    }

    /**
     * One host chip.
     *
     * Colour is status. Whether it is picked is a separate axis:
     *
     *   strike  a control - struck through and faded when it is not picked
     *   static  not a control at all, a label above some output
     *
     * Both pickable sections use strike, including the one that starts with a
     * single host picked. An earlier version faded those without the line,
     * on the theory that they had never been removed so should not look
     * removed - but two ways of drawing the same idea is worse than one that
     * is slightly over-stated.
     */
    function hostChip(h, opts) {
        opts = opts || {};
        var st = opts.status || hostHealth(h);
        var mode = opts.mode || 'static';
        var picked = opts.picked !== false;

        var cls = 'tol-lhost tol-lhost-' + st.key;
        if (h.name === state.thisHost) cls += ' tol-lhost-self';
        if (h.orphaned) cls += ' tol-lhost-orphaned';
        if (mode === 'static') cls += ' tol-lhost-static';
        else if (!picked) cls += ' tol-lhost-off';

        var why = st.why;
        if (h.orphaned) {
            why = 'Orphaned: IdentityIQ no longer lists ' + h.name + ' as a Server, so it is '
                + 'retired as far as IIQ is concerned. Its status record is still here, so you '
                + 'can read what it last reported and still aim an override at it - but if '
                + 'nothing is running there, nothing will ever confirm.\n\n' + why;
        }

        var chip = el(mode === 'static' ? 'span' : 'button', cls);
        if (mode !== 'static') {
            chip.setAttribute('aria-pressed', picked ? 'true' : 'false');
            chip.title = why + '\n\n' + (opts.hint || (picked
                ? 'Click to drop ' + h.name + '.'
                : 'Click to include ' + h.name + '.'));
            chip.onclick = opts.onclick;
        } else {
            chip.title = why;
        }

        if (st.key === 'wait' && opts.spin !== false) chip.appendChild(el('span', 'tol-lhost-spin'));
        chip.appendChild(el('span', 'tol-lhost-name', h.name));
        // The badge rides on the chip rather than living in one banner, because
        // the chip is the one thing every section draws. Labelling it here
        // labels it in Host Status, All Logger Status and the Log Viewer at
        // once, and there is nowhere a retired host can appear unmarked.
        if (h.orphaned) chip.appendChild(el('span', 'tol-lhost-orphan', 'orphaned'));
        if (opts.count !== undefined && opts.count !== null && opts.count !== '') {
            chip.appendChild(el('span', 'tol-lhost-count', String(opts.count)));
        }
        return chip;
    }

    /**
     * All / None, for when clicking twelve chips individually is the wrong
     * way to say "just this one" or "put them all back".
     *
     * Only drawn when there is more than one host - on a single-host install
     * they would be two buttons that cannot change anything.
     */
    function bulkPick(hosts, setAll, isPicked) {
        var wrap = el('span', 'tol-hostbulk');
        var allOn = hosts.every(isPicked);
        var allOff = !hosts.some(isPicked);

        var all = el('button', 'tol-bulk', 'All');
        all.title = 'Pick every host';
        all.disabled = allOn;
        all.onclick = function () { setAll(true); render(); };
        wrap.appendChild(all);

        var none = el('button', 'tol-bulk', 'None');
        none.title = 'Pick no host';
        none.disabled = allOff;
        none.onclick = function () { setAll(false); render(); };
        wrap.appendChild(none);
        return wrap;
    }

    /** A row of chips, for the sections that pick hosts rather than answer queries. */
    function hostPicker(hosts, isPicked, toggle, mode, setAll) {
        var strip = el('div', 'tol-hoststrip');
        hosts.forEach(function (h) {
            strip.appendChild(hostChip(h, {
                mode: mode,
                picked: isPicked(h),
                onclick: (function (name) {
                    return function () { toggle(name); render(); };
                })(h.name)
            }));
        });
        if (setAll && hosts.length > 1) strip.appendChild(bulkPick(hosts, setAll, isPicked));
        return strip;
    }

    /** Static label chips, for a table that belongs to one or more hosts. */
    function hostChips(names) {
        var wrap = el('span', 'tol-hostchips');
        var byName = {};
        (state.hosts || []).forEach(function (h) { byName[h.name] = h; });
        names.forEach(function (n) {
            wrap.appendChild(hostChip(byName[n] || { name: n, reporting: false }, { mode: 'static' }));
        });
        return wrap;
    }

    /**
     * How current the rows under a group banner are.
     *
     * A group can cover several hosts, which is the whole reason this was on
     * the banner chip before rather than in a column. It reports the oldest of
     * them: a shared table is only as current as its least recently synced
     * host, and claiming the freshest would overstate it. The tooltip breaks it
     * down per host so the single number is never the only thing on offer.
     */
    function groupSync(names) {
        var byName = {};
        (state.hosts || []).forEach(function (h) { byName[h.name] = h; });
        var seen = [];
        names.forEach(function (n) {
            var h = byName[n];
            if (h && h.reporting) seen.push({ name: n, at: parseInt(h.lastSync, 10) || 0 });
        });
        if (!seen.length) return { text: '-', title: 'Not reporting, so nothing to be current as of.' };
        seen.sort(function (a, b) { return a.at - b.at; });
        if (seen.length === 1) {
            return { text: fmtAgo(seen[0].at), title: seen[0].name + ' last synced ' + fmtAgo(seen[0].at) + '.' };
        }
        return {
            text: fmtAgo(seen[0].at),
            title: 'Oldest of the ' + seen.length + ' hosts sharing this table:\n'
                 + seen.map(function (t) { return '  ' + t.name + ' - ' + fmtAgo(t.at); }).join('\n')
        };
    }
    // Which overrides go into the next saved collection. Only deviations from
    // the default are stored, so a row added after you have started choosing
    // still behaves sensibly instead of being silently left out.
    var collectPick = {};

    function collectKey(e) { return e.id || ('settings:' + e.logger); }

    function collectChecked(e) {
        var k = collectKey(e);
        // Expired rows start unticked: a collection is a set of loggers you
        // want to be able to turn on again, and an expired row is not on.
        return collectPick[k] === undefined ? !e.expired : !!collectPick[k];
    }

    var liveFilter = 'all';
    // null until the first render, which seeds it with the host serving the page.
    var livePick = null;   // reassigned wholesale by All/None
    // Hosts clicked out of Host Status. Everything starts in it.
    var hostHide = {};

    /**
     * The rows this section would draw for one source filter, grouped exactly
     * the way it draws them - hosts reporting an identical picture share a
     * table. Used for the filter counts as well as the rendering, so the two
     * cannot drift apart.
     */
    function liveGroups(hosts, filterKey) {
        var groups = [];
        hosts.forEach(function (h) {
            var rows = (h.liveLoggers || []).filter(function (r) {
                return filterKey === 'all' || r.source === filterKey;
            });
            if (!rows.length) return;
            rows.sort(function (a, b) { return a.logger < b.logger ? -1 : 1; });
            var sig = rows.map(function (r) {
                return r.logger + '=' + r.level + '/' + r.source;
            }).join('|');
            var found = null;
            groups.forEach(function (g) { if (g.sig === sig) found = g; });
            if (found) found.hosts.push(h.name);
            else groups.push({ sig: sig, hosts: [h.name], rows: rows });
        });
        return groups;
    }

    /**
     * The UI-managed override behind a live logger row, if there is exactly
     * one. Ambiguous cases (the same logger pinned to several host subsets)
     * are left to the Overrides table rather than guessed at here.
     */
    function overrideFor(loggerName) {
        var m = (state.entries || []).filter(function (e) {
            return e.logger === loggerName && !e.permanent && e.id;
        });
        return m.length === 1 ? m[0] : null;
    }


    /**
     * Everything log4j2 currently has configured in each host's JVM, whatever
     * put it there.
     *
     * This replaced two separate curated lists, because deciding which list a
     * logger belonged in meant inferring its origin, and the inference was
     * wrong often enough to matter - a rule doing
     * Logger.getLogger("Rule.X").setLevel(DEBUG) creates a LoggerConfig that
     * is not in the file and not ours, and it was being reported as our litter.
     *
     * Source is a fact per row now:
     *   log4j2.properties - declared in that host's file
     *   Logger Manager    - an override currently managed here
     *   left over         - this plugin created it and lost track of it
     *   set at runtime    - something else set it: a rule, custom Java, a
     *                       connector. Never touched, never cleared.
     */
    function liveLoggersSection() {
        var box = el('section', 'tol-card');
        var head = el('div', 'tol-card-head');
        head.appendChild(el('h2', 'tol-card-title', 'All Logger Status'));

        var anyLeftover = false;
        var ambiguousLeft = {};
        (state.hosts || []).forEach(function (h) {
            (h.liveLoggers || []).forEach(function (r) {
                if (r.source !== 'leftover') return;
                anyLeftover = true;
                if (String(r.ambiguous) === 'true') ambiguousLeft[r.logger] = 1;
            });
        });
        if (anyLeftover) {
            var ambNames = Object.keys(ambiguousLeft);
            var clr = el('button', 'tol-btn tol-btn-danger', 'Clear all left over');
            clr.onclick = function () {
                // This sweep removes what each host recorded as its own. Where
                // that record cannot be told apart from a rule's doing, say so
                // before acting rather than after: it is the one action on this
                // page that could take away logging somebody else is relying on.
                var warn = ambNames.length
                    ? '\n\nWARNING: ' + ambNames.length + ' of these could equally have been set '
                      + 'by a rule. They are marked "left over / set at runtime":\n'
                      + ambNames.map(function (n) { return '  ' + n; }).join('\n')
                      + '\n\nClearing is one-shot, so if a rule is setting one it will come back the '
                      + 'next time that rule runs. Suppress holds a logger off instead.'
                    : '';
                if (!window.confirm('Remove the loggers this plugin left behind, on every host?'
                    + '\n\nOnly loggers this plugin created are removed. Anything in '
                    + 'log4j2.properties, and anything a rule or custom code set, is left alone.'
                    + warn)) return;
                mutate(api('POST', '/cleanup'),
                    'Cleanup requested. This host is done; others follow on their next sync.');
            };
            if (ambNames.length) {
                clr.title = ambNames.length + ' of the loggers this would remove might have been set '
                    + 'by a rule rather than left behind by this plugin. They are listed before you '
                    + 'confirm.';
            }
            head.appendChild(clr);
        }
        box.appendChild(head);
        box.appendChild(el('div', 'tol-hint',
            'Every logger log4j2 has configured on each host, and where it came from. '
            + '"set at runtime" means something outside Logger Manager set it, typically a rule '
            + 'calling Logger.getLogger(...).setLevel(...). Those are never touched or cleared. '
            + 'Pick the hosts you want below - this starts on the host serving the page, because '
            + 'a cluster mostly reports the same picture everywhere and reading it starts with '
            + 'one host. The counts are distinct logger names, so a logger set on nine hosts '
            + 'counts once even though each host lists it separately. Last sync says how '
            + 'long ago the host reported each row - watch it change to see that this page is '
            + 'polling, not a snapshot.'));
        var legend = el('div', 'tol-hint');
        legend.appendChild(el('strong', '', 'Suppress'));
        legend.appendChild(document.createTextNode(
            ' holds a logger at OFF and keeps it there - the plugin re-applies it every sync, and '
            + 'Un-suppress lifts it. '));
        legend.appendChild(el('strong', '', 'Clear'));
        legend.appendChild(document.createTextNode(
            ' deletes it from the running configuration once and then lets go. For a logger a rule '
            + 'keeps setting, that rule can set it again. For one declared in log4j2.properties, it '
            + 'stays cleared until someone edits and saves that file or the host restarts - either of '
            + 'which rebuilds the configuration from the file regardless. Suppress holds either kind '
            + 'off permanently instead.'));
        box.appendChild(legend);

        // A Clear is a cluster-wide request, not a local delete. Show it in
        // flight, the way an override shows its confirmed and pending hosts -
        // otherwise the logger vanishes here and nothing says the other hosts
        // are still to do it.
        var reqAt = parseInt(state.clearRequestedAt, 10) || 0;
        if (reqAt > 0) {
            var done = [], waiting = [];
            (state.hosts || []).forEach(function (h) {
                if (!h.reporting) return;
                if ((parseInt(h.lastClear, 10) || 0) >= reqAt) done.push(h.name);
                else waiting.push(h.name);
            });
            if (waiting.length) {
                var prog = el('div', 'tol-banner tol-info');
                prog.appendChild(document.createTextNode(
                    'Clearing ' + (state.clearRequestedLogger || 'loggers left over')
                    + ' across the cluster: done on ' + done.length + ' of '
                    + (done.length + waiting.length) + ' hosts. Still to do: '
                    + waiting.join(', ') + '.'));
                box.appendChild(prog);
            }
        }

        var unparsed = [];
        (state.hosts || []).forEach(function (h) {
            if (h.reporting && h.fileParsed === false) unparsed.push(h.name);
        });
        if (unparsed.length) {
            var warn = el('div', 'tol-banner tol-warn');
            warn.appendChild(document.createTextNode(
                'Could not read the log4j2 configuration file on ' + unparsed.join(', ')
                + ', so the source of those loggers cannot be determined. Only the properties '
                + 'format is parsed.'));
            box.appendChild(warn);
        }

        // Which hosts to show. This section starts with one host rather than
        // all of them: a thirteen-host cluster mostly reports the same picture
        // thirteen times, and reading it starts with one host and widens only
        // when you are comparing.
        var all = sortedHosts();
        if (!all.length) {
            box.appendChild(el('div', 'tol-empty', 'No hosts reported yet.'));
            return box;
        }
        // Seeded once, on the first render, and never again. Inferring "not
        // seeded yet" from "no hosts picked" meant None could not work: it
        // emptied the set, the next render saw an empty set, and helpfully put
        // the first host back. Picking nothing is a thing a reader is allowed
        // to have chosen, and is different from never having chosen.
        if (livePick === null) {
            livePick = {};
            livePick[all[0].name] = true;
        }

        box.appendChild(hostPicker(all,
            function (h) { return !!livePick[h.name]; },
            function (n) {
                if (livePick[n]) delete livePick[n]; else livePick[n] = true;
            },
            'strike',
            function (on) {
                livePick = {};
                if (on) all.forEach(function (h) { livePick[h.name] = true; });
            }));

        var picked = all.filter(function (h) { return livePick[h.name]; });
        if (!picked.length) {
            box.appendChild(el('div', 'tol-empty',
                'No host picked. Click a host above to see the loggers running on it.'));
            return box;
        }

        // Distinct logger names across the picked hosts. One logger set on nine
        // hosts is one logger.
        //
        // This counted rendered rows for a while, so the number on the button
        // always matched the rows underneath it. That property is nice, but it
        // made the button answer the wrong question: these are category
        // filters, and "3 set at runtime" when two of the three are the same
        // logger on different hosts is not true. It also looked arbitrary,
        // because a cluster with identical log4j2.properties collapses into a
        // single group where rows and distinct names are the same number - so
        // only the categories that genuinely vary per host appeared wrong.
        //
        // The row count is kept rather than discarded: where the two differ,
        // that difference is itself the finding - this logger is on more than
        // one host - so the tooltip says so instead of hiding it.
        var counts = {};
        var hostCounts = {};
        var rowCounts = {};
        ['all', 'file', 'plugin', 'leftover', 'runtime', 'unknown'].forEach(function (k) {
            var gs = liveGroups(picked, k);
            var rows = 0, hs = {}, names = {};
            gs.forEach(function (g) {
                rows += g.rows.length;
                g.hosts.forEach(function (n) { hs[n] = 1; });
                g.rows.forEach(function (r) { names[r.logger] = 1; });
            });
            counts[k] = Object.keys(names).length;
            rowCounts[k] = rows;
            hostCounts[k] = Object.keys(hs).length;
        });
        var bar = el('div', 'tol-filters');
        [['all', 'All'], ['file', 'From the file (log4j2.properties)'], ['plugin', 'Logger Manager'],
            ['leftover', 'Left over'], ['runtime', 'Set at runtime']].forEach(function (f) {
            if (f[0] !== 'all' && !counts[f[0]]) return;
            var b = el('button', 'tol-filter' + (liveFilter === f[0] ? ' tol-filter-on' : ''),
                f[1] + ' (' + counts[f[0]] + ')');
            var dn = counts[f[0]], rc = rowCounts[f[0]], hc = hostCounts[f[0]];
            b.title = dn + ' distinct logger' + (dn === 1 ? '' : 's')
                + ' across ' + hc + ' host' + (hc === 1 ? '' : 's')
                + (rc === dn ? '.'
                    : '. ' + rc + ' rows below - some of these are set on more than one host, '
                      + 'and each host is listed separately.');
            b.onclick = (function (key) {
                return function () { liveFilter = key; render(); };
            })(f[0]);
            bar.appendChild(b);
        });
        box.appendChild(bar);

        // Among the picked hosts, ones reporting an identical picture share a
        // table, so the one host that differs stands out instead of being
        // buried under repeats.
        var groups = liveGroups(picked, liveFilter);

        if (!groups.length) {
            box.appendChild(el('div', 'tol-empty',
                'Nothing to show for this filter on the hosts you picked. Hosts report on their '
                + 'next sync tick.'));
            return box;
        }

        // A host you picked that contributes no rows used to just disappear,
        // which reads as the click not working. Say why instead.
        var covered = {};
        groups.forEach(function (g) { g.hosts.forEach(function (n) { covered[n] = true; }); });
        picked.forEach(function (h) {
            if (covered[h.name]) return;
            var note = el('div', 'tol-logmeta');
            note.appendChild(hostChip(h, { mode: 'static' }));
            note.appendChild(el('span', 'tol-small', h.reporting
                ? (liveFilter === 'all'
                    ? 'has not reported its loggers yet - it will on its next sync'
                    : 'has no loggers from this source')
                : 'is not reporting, so it has nothing to show'));
            box.appendChild(note);
        });

        // One table for every picked host, with a banner row between them,
        // rather than a table each. Separate tables each sized their columns
        // to their own content, so Level sat somewhere different on every host
        // - and comparing hosts means reading down a column. Pinning the widths
        // fixed that but broke narrow screens, which is the wrong trade for a
        // page people open on a phone during an incident. One table computes
        // one geometry from all the rows and stays fluid.
        var wrap = el('div', 'tol-tablewrap');
        var t = el('table', 'tol-table');
        t.appendChild(headRow(['Logger', 'Level', 'Source', 'File says', 'Last sync', '']));

        groups.forEach(function (g) {
            var tb = el('tbody');
            var banner = el('tr', 'tol-grouprow');
            var bcell = el('td');
            bcell.setAttribute('colspan', '6');
            bcell.appendChild(hostChips(g.hosts));
            banner.appendChild(bcell);
            tb.appendChild(banner);
            var sync = groupSync(g.hosts);
            g.rows.forEach(function (r) {
                var tr = el('tr');

                var c1 = el('td');
                c1.appendChild(el('code', 'tol-logger-name', r.logger));
                tr.appendChild(c1);

                var c2 = el('td');
                c2.appendChild(el('span', 'tol-level tol-level-' + String(r.level).toLowerCase(), r.level));
                tr.appendChild(c2);

                var c3 = el('td', 'tol-small');
                // Where the plugin cannot honestly pick between its own litter
                // and a rule's doing, it shows both tags rather than inventing a
                // third one. Two familiar chips say "it is one of these" without
                // the reader having to learn a new label.
                var amb = String(r.ambiguous) === 'true';
                if (amb) {
                    c3.appendChild(el('span', 'tol-src tol-src-leftover', SRC.leftover));
                    c3.appendChild(el('span', 'tol-src-or', '/'));
                    c3.appendChild(el('span', 'tol-src tol-src-runtime', SRC.runtime));
                    c3.title = 'Either one. This plugin has a record of creating ' + r.logger
                        + ' on this host, which normally means left over - but the same logger is '
                        + 'being set at runtime elsewhere in the cluster, and a rule can set it on '
                        + 'any host at any time. Nothing in the running configuration tells the two '
                        + 'apart, so this does not pretend to.\n\n'
                        + 'Clear removes it either way. If a rule is setting it, the rule will set '
                        + 'it again next time it runs - use Suppress to hold it off.';
                } else {
                    c3.appendChild(el('span', 'tol-src tol-src-' + r.source,
                        SRC[r.source] || r.source));
                }
                tr.appendChild(c3);

                // What the file declares, and whether the JVM disagrees.
                var c4 = el('td', 'tol-small');
                if (r.fileLevel) {
                    c4.appendChild(document.createTextNode(r.fileLevel));
                    if (String(r.fileLevel).toUpperCase() !== String(r.level).toUpperCase()
                        && r.source !== 'plugin') {
                        c4.appendChild(el('div', 'tol-badge tol-badge-warn', 'differs from file'));
                    }
                } else {
                    c4.appendChild(document.createTextNode('-'));
                }
                tr.appendChild(c4);

                // Repeated on every row rather than shown once per group: this
                // is the column Host Status has, and a value that appears on
                // one row in twelve is not a column, it is a footnote.
                var cSync = el('td', 'tol-small');
                cSync.appendChild(document.createTextNode(sync.text));
                cSync.title = sync.title;
                tr.appendChild(cSync);

                var c5 = el('td', 'tol-actions');

                // One toggle, not two buttons. Its colour is the state: green
                // means this plugin is holding the logger at OFF right now.
                // Clicking on applies that hold, clicking off lifts it.
                var ours = r.source === 'plugin' ? overrideFor(r.logger) : null;
                var suppressed = r.source === 'plugin'
                    && String(r.level).toUpperCase() === 'OFF' && ours;

                // Protected loggers keep their controls visible but disabled,
                // so it is clear the action exists and is being refused rather
                // than missing. The list comes from the untouchableLoggers
                // plugin setting and is enforced by the API as well.
                var protectedLogger = isUntouchable(r.logger);
                if (protectedLogger) {
                    var lock = el('button', 'tol-toggle', 'Suppress');
                    lock.disabled = true;
                    lock.title = r.logger + ' is in the untouchable loggers list, so this plugin '
                        + 'will not change it. Edit "Untouchable loggers" in the plugin settings to '
                        + 'allow it.';
                    c5.appendChild(lock);
                    var lockClear = el('button', 'tol-btn tol-btn-small', 'Clear');
                    lockClear.disabled = true;
                    lockClear.title = lock.title;
                    c5.appendChild(lockClear);
                }
                if (!protectedLogger) {
                    var tgl = el('button', 'tol-toggle' + (suppressed ? ' tol-toggle-on' : ''), 'Suppress');
                    tgl.setAttribute('aria-pressed', suppressed ? 'true' : 'false');

                    if (r.source === 'plugin' && !ours) {
                        // Several overrides target this logger on different host
                        // subsets; which one to lift is not ours to guess.
                        tgl.disabled = true;
                        tgl.title = 'This logger is covered by more than one override - '
                            + 'manage them in Plugin Logger Status.';
                    } else if (suppressed) {
                        tgl.title = 'Held at OFF by this plugin. Click to lift it - the logger goes '
                            + 'back to whatever sets it.';
                        tgl.onclick = (function (entry, name) {
                            return function () {
                                if (!window.confirm('Stop suppressing ' + name + ' on every host?'
                                    + ' It goes back to its log4j2.properties level straight away, or,'
                                    + ' if a rule sets it, the next time that rule runs.')) return;
                                mutate(api('DELETE', '/entries/' + encodeURIComponent(entry.id)),
                                    name + ' is no longer suppressed.');
                            };
                        })(ours, r.logger);
                    } else {
                        tgl.title = 'Hold ' + r.logger + ' at OFF on every host. The plugin re-applies '
                            + 'it every sync, so it stays off even if a rule keeps switching it back on.';
                        tgl.onclick = (function (name, was, isOurs) {
                            return function () {
                                var extra = isOurs
                                    ? ' This replaces the existing override on it.'
                                    : '';
                                if (!window.confirm('Suppress ' + name + ' on every host?'
                                    + ' It is running at ' + was + '. This holds it at OFF and keeps it'
                                    + ' there until you lift it. Nothing on disk is changed.'
                                    + extra)) return;
                                mutate(api('POST', '/entries', {
                                    logger: name, level: 'OFF', ttlMinutes: 0, hosts: ['*'],
                                    note: 'suppressed - was ' + was
                                }), name + ' suppressed on every host.');
                            };
                        })(r.logger, r.level, r.source === 'plugin');
                    }
                    c5.appendChild(tgl);
                }

                // Shown disabled rather than omitted for file-declared loggers:
                // an absent button leaves people wondering whether it is missing
                // or forbidden, and there is a real reason worth stating.
                // Shown disabled rather than omitted, for the same reason as
                // above: an absent button leaves people wondering whether it is
                // missing or forbidden.
                if (!protectedLogger && r.source === 'unknown') {
                    var noClear = el('button', 'tol-btn tol-btn-small', 'Clear');
                    noClear.disabled = true;
                    noClear.title = 'Cannot be cleared: the log4j2 configuration on this host could '
                        + 'not be read, so it is not known what would happen if this were removed. '
                        + 'Use Suppress instead.';
                    c5.appendChild(noClear);
                }
                if (!protectedLogger
                        && (r.source === 'runtime' || r.source === 'leftover' || r.source === 'file')) {
                    var rm = el('button', 'tol-btn tol-btn-small tol-btn-danger', 'Clear');
                    rm.title = 'Delete ' + r.logger + ' from the running configuration on every host. '
                        + 'One-shot: nothing is enforced afterwards, so whatever created it can create '
                        + 'it again. Use Suppress if you need it to stay off.';
                    rm.onclick = (function (name, src) {
                        return function () {
                            var extra = src === 'runtime'
                                ? ' It was set by something other than this plugin, most likely a'
                                  + ' rule calling Logger.getLogger(...).setLevel(...). Clear is'
                                  + ' one-shot: it stops the logging now, but whatever set it will'
                                  + ' set it again next time it runs. Use Suppress to keep it off.'
                                : src === 'file'
                                ? ' It is declared in this host’s log4j2.properties. Clear is'
                                  + ' one-shot: it stops the logging now and stays off unless someone'
                                  + ' edits and saves that file, or the host restarts - either of which'
                                  + ' rebuilds the running configuration from the file and brings it back.'
                                  + ' Use Suppress if you need it to stay off across a restart.'
                                : ' This plugin created it and lost track of it, so clearing it'
                                  + ' switches it off for good.';
                            if (!window.confirm('Clear ' + name + ' on every host?' + extra)) return;
                            mutate(api('POST', '/cleanup', { logger: name }),
                                name + ' cleared. This host is done; others follow on their next sync.');
                        };
                    })(r.logger, r.source);
                    c5.appendChild(rm);
                }
                // Turning a logger on and then reading what it wrote are the
                // same errand, so the row that shows it running is the right
                // place to start the search.
                if (state.logTailEnabled !== false) {
                    var find = el('button', 'tol-btn tol-btn-small', 'Find in logs');
                    var needle = logNeedle(r.logger);
                    find.title = 'Search every host\u2019s log for "' + needle + '"'
                        + (needle !== r.logger
                            ? ' - the last four components of the name, because the stock IIQ '
                              + 'pattern prints loggers as %c{4} and the full name would match '
                              + 'nothing.'
                            : '.');
                    find.onclick = (function (text) {
                        return function () {
                            runLogSearch(text).then(function () {
                                var sec = document.getElementById('tol-sec-logs');
                                if (sec && sec.scrollIntoView) sec.scrollIntoView({ block: 'start' });
                            });
                        };
                    })(needle);
                    c5.appendChild(find);
                }

                tr.appendChild(c5);
                tb.appendChild(tr);
            });
            t.appendChild(tb);
        });
        wrap.appendChild(t);
        box.appendChild(wrap);
        return box;
    }


    function hostsSection() {
        var box = el('section', 'tol-card');
        box.appendChild(el('h2', 'tol-card-title', 'Host Status'));
        box.appendChild(el('div', 'tol-hint',
            'The hosts are IdentityIQ’s own Server list, so retiring a host in IIQ retires it ' +
            'here too. Each one reports its own OS, its log4j2 config file and where it writes ' +
            'logs - that is the host-specific part; the level change itself works the same ' +
            'everywhere. Every host is in the table to begin with; click one to drop it, click it ' +
            'again to bring it back.'));

        // Hosts running an older build of the plugin than the one installed.
        //
        // Worth a banner rather than a column, because it is invisible from
        // every other angle: IdentityIQ builds a service executor once and
        // holds it, so upgrading the plugin does not reach a host that has not
        // restarted since. That host keeps answering everything the older
        // version knew how to answer and silently ignores anything the release
        // added, which looks like an intermittent fault rather than an old
        // build. It has to be named or nobody will ever guess.
        var behind = [];
        (state.hosts || []).forEach(function (h) {
            if (h.buildStale) behind.push(h.name + ' ('
                + (h.build || 'too old to say which') + ')');
        });
        if (behind.length) {
            var one2 = behind.length === 1;
            var vb = el('div', 'tol-banner tol-warn');
            vb.appendChild(document.createTextNode(
                (one2 ? 'One host is' : behind.length + ' hosts are')
                + ' running an older build of this plugin than the '
                + (state.build || 'installed') + ' that is installed: ' + behind.join(', ')
                + '. IdentityIQ builds a service once and keeps the instance, so a plugin '
                + 'upgrade does not reach a host until its JVM restarts. '
                + (one2 ? 'That host' : 'Those hosts')
                + ' will go on doing everything the older version could and quietly ignoring '
                + 'anything newer - which is why nothing looks wrong. Restart IdentityIQ on '
                + (one2 ? 'it' : 'them') + ' when convenient.'));
            box.appendChild(vb);
        }

        // Status records belonging to hosts IIQ no longer lists. They are not
        // hosts any more, so they are deliberately not rows in the table - but
        // saying nothing would strand plugin data in a database no screen can
        // reach. One line and a button is the whole feature.
        var orphans = state.orphanHosts || [];
        if (orphans.length) {
            var one = orphans.length === 1;
            var ob = el('div', 'tol-banner tol-warn tol-banner-split');
            ob.appendChild(el('span', 'tol-banner-text', state.showOrphans
                ? (one ? 'One host below is' : orphans.length + ' hosts below are')
                  + ' orphaned - IdentityIQ no longer lists ' + (one ? 'it' : 'them')
                  + ' as a Server: ' + orphans.join(', ')
                  + '. ' + (one ? 'It is' : 'They are') + ' still shown, and still work the same '
                  + 'way, so you can read what ' + (one ? 'it' : 'they') + ' last reported and '
                  + 'aim an override there - but with nothing running to answer, an override '
                  + 'will sit on pending.'
                : orphans.length + ' status record' + (one ? '' : 's')
                  + ' left behind by ' + (one ? 'a host' : 'hosts')
                  + ' IdentityIQ no longer lists: ' + orphans.join(', ')
                  + '. Nothing is running there; only this plugin’s own record remains.'));
            var ob2 = el('button', 'tol-btn tol-btn-danger',
                'Delete orphaned host' + (one ? '' : 's'));
            ob2.onclick = function () {
                if (!window.confirm('Delete orphaned host'
                    + (one ? ' ' : 's ') + orphans.join(', ') + '?'
                    + '\n\nThis removes only this plugin’s own record of what '
                    + (one ? 'that host' : 'those hosts') + ' last reported. It does not touch '
                    + 'IdentityIQ, and it cannot affect a running host - none of them are in '
                    + 'IIQ’s Server list.')) return;
                mutate(api('DELETE', '/hosts?orphans=true'),
                    'Deleted ' + orphans.length + ' orphaned host'
                    + (one ? '' : 's') + '.');
            };
            ob.appendChild(ob2);
            box.appendChild(ob);
        }

        var all = sortedHosts();
        if (!all.length) {
            box.appendChild(el('div', 'tol-empty', 'No hosts reported yet.'));
            return box;
        }

        // Everything starts in the table here, so unpicking a host is a
        // removal and is drawn as one.
        box.appendChild(hostPicker(all,
            function (h) { return !hostHide[h.name]; },
            function (n) {
                if (hostHide[n]) delete hostHide[n]; else hostHide[n] = true;
            },
            'strike',
            function (on) {
                hostHide = {};
                if (!on) all.forEach(function (h) { hostHide[h.name] = true; });
            }));

        var hosts = all.filter(function (h) { return !hostHide[h.name]; });
        if (!hosts.length) {
            box.appendChild(el('div', 'tol-empty',
                'Every host has been clicked out of the table. Click one above to bring it back.'));
            return box;
        }

        var t = el('table', 'tol-table');
        t.appendChild(headRow(['Host', 'OS / JVM', 'State', 'Last sync', 'Applied here', 'log4j2 config / log files']));
        var tb = el('tbody');

        hosts.forEach(function (h) {
            var facts = h.facts || {};
            var tr = el('tr');

            var c1 = el('td');
            c1.appendChild(hostChips([h.name]));
            if (h.isThisHost) c1.appendChild(el('div', 'tol-badge tol-badge-ok', 'serving this page'));
            if (h.orphaned) {
                // The chip in this cell already carries an ORPHANED badge, so
                // this line says what that means rather than repeating it.
                c1.appendChild(el('div', 'tol-small',
                    'retired in IIQ - no Server record'));
            } else if (!h.knownToIIQ) {
                c1.appendChild(el('div', 'tol-small', 'no Server record'));
            }
            tr.appendChild(c1);

            var c2 = el('td', 'tol-small');
            var fam = facts.osFamily || 'other';
            c2.appendChild(el('span', 'tol-os tol-os-' + fam, OS_ICON[fam] || 'OS'));
            c2.appendChild(document.createTextNode(' ' + (facts.os || 'unknown')));
            if (facts.arch) c2.appendChild(el('div', 'tol-small', facts.arch + ' - Java ' + (facts.java || '?')));
            if (facts.containerHint === 'true') c2.appendChild(el('div', 'tol-badge', 'containerised'));
            tr.appendChild(c2);

            var c3 = el('td');
            if (!h.reporting) {
                c3.appendChild(el('span', 'tol-badge tol-badge-warn', 'not reporting'));
                c3.appendChild(el('div', 'tol-small', 'sync service has not run here yet'));
            } else if (h.serviceOff && !h.inSync) {
                c3.appendChild(el('span', 'tol-badge tol-badge-warn', 'service not enabled here'));
                c3.appendChild(el('div', 'tol-small',
                    'Host Configuration excludes ' + 'TurnOnLoggersSync' + ' on this host'));
            } else if (h.stale) {
                // Amber, matching this host's chip. It was pink, which read as
                // an error next to an amber chip saying the opposite.
                c3.appendChild(el('span', 'tol-badge tol-badge-warn', 'stale'));
            } else if (h.inSync) {
                c3.appendChild(el('span', 'tol-badge tol-badge-ok', 'in sync'));
            } else {
                c3.appendChild(el('span', 'tol-badge tol-badge-warn', 'catching up'));
                c3.appendChild(el('div', 'tol-small', 'host rev ' + h.revision + ' vs ' + state.revision));
            }
            var errs = h.errors || [];
            errs.forEach(function (m) { c3.appendChild(el('div', 'tol-err-text', m)); });
            // Distinct from errs: those are per-logger problems from a tick
            // that finished. This one means the tick itself died, so nothing
            // else on this row was refreshed.
            if (h.tickError) {
                c3.appendChild(el('div', 'tol-err-text', 'sync service failing: ' + h.tickError));
            }
            tr.appendChild(c3);

            tr.appendChild(el('td', 'tol-small', h.reporting ? fmtAgo(h.lastSync) : '-'));

            var c5 = el('td', 'tol-small');
            var applied = h.applied || {};
            var keys = Object.keys(applied);
            if (!keys.length) {
                c5.appendChild(el('span', 'tol-small', 'nothing overridden'));
            } else {
                keys.forEach(function (k) {
                    var line = el('div', 'tol-mono tol-small');
                    line.appendChild(document.createTextNode(k + ' = '));
                    line.appendChild(el('span', 'tol-level tol-level-' + String(applied[k]).toLowerCase(), applied[k]));
                    c5.appendChild(line);
                });
            }
            tr.appendChild(c5);

            var c6 = el('td', 'tol-small tol-mono tol-wrap');
            c6.appendChild(document.createTextNode(facts.log4jConfig || 'unknown'));
            if (facts.logFiles) {
                c6.appendChild(el('div', 'tol-small tol-mono tol-wrap', facts.logFiles));
            }
            tr.appendChild(c6);

            tb.appendChild(tr);
        });
        t.appendChild(tb);
        box.appendChild(t);
        return box;
    }


    /**
     * Saved sets of loggers, shared by everyone.
     *
     * The point is that whoever worked out which five loggers matter for an
     * LDAP bind failure can leave that where the next person finds it, so these
     * are deliberately global rather than per-user favourites.
     */
    /**
     * The editing form for one collection, as a row that replaces its own.
     *
     * Everything is held in collEdit until Save, so removing four loggers and
     * changing your mind costs nothing. Typing sets formTouched, which stops
     * the poll re-rendering the page underneath a half-filled form.
     */
    function collectionEditor(c) {
        var tr = el('tr');
        var td = el('td');
        td.setAttribute('colspan', '4');

        var f1 = field('Name', 'Shared with everyone using the plugin.');
        var name = el('input', 'tol-input');
        name.type = 'text';
        name.value = collEdit.name;
        name.maxLength = 80;
        name.oninput = function () { formTouched = true; collEdit.name = name.value; };
        f1.appendChild(name);
        td.appendChild(f1);

        var f2 = field('Description', 'What this set is for. Optional.');
        var desc = el('input', 'tol-input');
        desc.type = 'text';
        desc.value = collEdit.description;
        desc.maxLength = 200;
        desc.oninput = function () { formTouched = true; collEdit.description = desc.value; };
        f2.appendChild(desc);
        td.appendChild(f2);

        if (!collEdit.rows.length) {
            td.appendChild(el('div', 'tol-empty',
                'No loggers left. Add one, or cancel - a collection cannot be saved empty.'));
        }

        collEdit.rows.forEach(function (r, i) {
            var line = el('div', 'tol-edit-row');
            line.appendChild(el('span', 'tol-mono tol-small', r.logger));

            var lvl = el('select', 'tol-input tol-input-inline');
            (state.levels || []).forEach(function (L) {
                lvl.appendChild(opt(L, L, L === r.level));
            });
            lvl.onchange = function () { formTouched = true; r.level = lvl.value; };
            line.appendChild(lvl);

            var rm = el('button', 'tol-btn tol-btn-small tol-btn-danger', 'Remove');
            rm.title = 'Take ' + r.logger + ' out of this collection';
            rm.onclick = function () {
                collEdit.rows.splice(i, 1);
                render();
            };
            line.appendChild(rm);
            td.appendChild(line);
        });

        // add a logger
        var add = el('div', 'tol-edit-row');
        var box = el('input', 'tol-input');
        box.type = 'text';
        box.placeholder = 'add a logger, e.g. sailpoint.api.Provisioner';
        box.setAttribute('list', 'tol-catalog');
        box.setAttribute('autocomplete', 'off');
        box.oninput = function () { formTouched = true; };
        add.appendChild(box);

        var addLvl = el('select', 'tol-input tol-input-inline');
        (state.levels || []).forEach(function (L) { addLvl.appendChild(opt(L, L, L === 'DEBUG')); });
        add.appendChild(addLvl);

        var addBtn = el('button', 'tol-btn tol-btn-small', 'Add');
        addBtn.onclick = function () {
            var v = String(box.value || '').trim();
            if (!v) return;
            for (var i = 0; i < collEdit.rows.length; i++) {
                if (collEdit.rows[i].logger === v) {
                    // Already here: treat it as setting the level rather than
                    // adding a duplicate the store would silently collapse.
                    collEdit.rows[i].level = addLvl.value;
                    box.value = '';
                    render();
                    return;
                }
            }
            collEdit.rows.push({ logger: v, level: addLvl.value });
            box.value = '';
            render();
        };
        add.appendChild(addBtn);
        td.appendChild(add);

        var actions = el('div', 'tol-edit-actions');
        var save = el('button', 'tol-btn tol-btn-small tol-btn-primary', 'Save changes');
        save.disabled = !collEdit.rows.length;
        save.onclick = function () {
            var body = {
                name: collEdit.name,
                description: collEdit.description,
                loggers: collEdit.rows.map(function (r) {
                    return { logger: r.logger, level: r.level };
                })
            };
            var id = collEdit.id;
            var label = collEdit.name;
            collEdit = null;
            formTouched = false;
            mutate(api('PUT', '/collections/' + encodeURIComponent(id), body),
                   label + ' updated.');
        };
        actions.appendChild(save);

        var cancel = el('button', 'tol-btn tol-btn-small', 'Cancel');
        cancel.onclick = function () { collEdit = null; formTouched = false; render(); };
        actions.appendChild(cancel);
        td.appendChild(actions);

        tr.appendChild(td);
        return tr;
    }

    function collectionsSection() {
        var colls = state.collections || [];
        var box = el('section', 'tol-card');
        box.appendChild(el('h2', 'tol-card-title', 'Saved Logger Collections'));
        box.appendChild(el('div', 'tol-hint',
            'Named sets of loggers, shared with everyone who uses this plugin. Applying one turns '
            + 'the whole set on with the expiry you choose. Save the current overrides as a '
            + 'collection from the section below, and use Edit to add or remove loggers '
            + 'from one afterwards.'));

        if (!colls.length) {
            box.appendChild(el('div', 'tol-empty',
                'None saved yet. Turn some loggers on, then use "Save as collection".'));
            return box;
        }

        var t = el('table', 'tol-table');
        t.appendChild(headRow(['Collection', 'Loggers', 'Saved by', '']));
        var tb = el('tbody');
        colls.forEach(function (c) {
            if (collEdit && collEdit.id === c.id) {
                tb.appendChild(collectionEditor(c));
                return;
            }
            var tr = el('tr');

            var c1 = el('td');
            c1.appendChild(el('strong', '', c.name));
            if (c.description) c1.appendChild(el('div', 'tol-note-text', c.description));
            tr.appendChild(c1);

            var c2 = el('td', 'tol-small');
            String(c.loggers || '').split(',').forEach(function (pair) {
                if (!pair.trim()) return;
                var bits = pair.split('=');
                var line = el('div', 'tol-mono tol-small');
                line.appendChild(document.createTextNode(bits[0] + ' '));
                line.appendChild(el('span', 'tol-level tol-level-' + String(bits[1]).toLowerCase(), bits[1]));
                c2.appendChild(line);
            });
            tr.appendChild(c2);

            var c3 = el('td', 'tol-small');
            c3.appendChild(document.createTextNode(c.createdBy || '?'));
            c3.appendChild(el('div', 'tol-small', fmtAgo(c.created)));
            tr.appendChild(c3);

            var c4 = el('td');
            var apply = el('button', 'tol-btn tol-btn-small tol-btn-primary', 'Apply');
            apply.title = 'Turn this whole set on, on every host';
            apply.onclick = (function (coll) {
                return function () {
                    var mins = window.prompt('Turn on ' + coll.name + ' for how many minutes?\n\n'
                        + 'Leave the default unless you need longer.', String(state.defaultTtlMinutes));
                    if (mins === null) return;
                    mutate(api('POST', '/collections/' + encodeURIComponent(coll.id) + '/apply',
                        { ttlMinutes: parseInt(mins, 10), hosts: ['*'] }),
                        coll.name + ' applied on every host.');
                };
            })(c);
            c4.appendChild(apply);

            var edit = el('button', 'tol-btn tol-btn-small', 'Edit');
            edit.title = 'Add or remove loggers, or change the level of one';
            edit.onclick = (function (coll) {
                return function () {
                    collEdit = {
                        id: coll.id,
                        name: coll.name || '',
                        description: coll.description || '',
                        rows: String(coll.loggers || '').split(',')
                            .filter(function (x) { return x.trim(); })
                            .map(function (pair) {
                                var bits = pair.split('=');
                                return { logger: bits[0], level: (bits[1] || 'DEBUG') };
                            })
                    };
                    render();
                };
            })(c);
            c4.appendChild(edit);

            var del = el('button', 'tol-btn tol-btn-small tol-btn-danger', 'Delete');
            del.onclick = (function (coll) {
                return function () {
                    if (!window.confirm('Delete the collection "' + coll.name + '"?'
                        + ' This removes it for everyone. Loggers currently on are not affected.')) return;
                    mutate(api('DELETE', '/collections/' + encodeURIComponent(coll.id)),
                        coll.name + ' deleted.');
                };
            })(c);
            c4.appendChild(del);
            tr.appendChild(c4);

            tb.appendChild(tr);
        });
        t.appendChild(tb);
        box.appendChild(t);
        return box;
    }

    // "off" is a set of hosts the reader has clicked out of the results. It is
    // deliberately never reset by a query: you narrow the cluster down to the
    // three hosts you care about once, then run tail, then a search, then
    // another search, and the narrowing holds across all of them. Rebuilding it
    // per query would make the chips useless for the thing they are for.
    // The collection being edited, if any: { id, name, description, rows }.
    // Module-level so it survives the page's own ten-second re-render, the same
    // way the log and history panels keep their state.
    var collEdit = null;

    // Reading state for the log viewer. The answers themselves belong to
    // whoever ran the query, so what arrives here is only ever this person's -
    // there is nothing to opt into and nothing of anyone else's to hide.
    var logState = { lines: 40, text: '', off: {}, wrap: true, hilite: '' };
    try {
        if (window.sessionStorage.getItem('tol-log-wrap') === 'n') logState.wrap = false;
    } catch (e) { /* ignore */ }

    // A popped-out window shows the log viewer on its own, so several can be
    // opened side by side. Same page, same script, one section.
    var LOG_ONLY = (function () {
        try {
            return /[?&]tolview=logs(&|$)/.test(window.location.search);
        } catch (e) { return false; }
    }());

    // A popped-out window can be pinned to one host, which is what makes two of
    // them side by side worth having.
    /**
     * A popped-out window is a reader, not a copy of the page.
     *
     * What was asked for is one host's output, as large as the window will
     * allow, resizable and scrollable - the complaint being that the log block
     * on the page is a small box that is hard to move around in. So everything
     * else goes: IdentityIQ's own banner and navigation, the plugin's title and
     * footer, the host chips, the search form, the other hosts. What is left is
     * the log, a wrap toggle and a highlight box.
     *
     * IdentityIQ's chrome is hidden rather than avoided, because the plugin
     * page is served inside it and there is no other page to be served from.
     * Every selector here is one that simply matches nothing if a future
     * release renames it, which is the failure mode to want.
     */
    function stripToReader() {
        if (!LOG_ONLY) return;
        if (document.getElementById('tol-reader-css')) return;
        var css = document.createElement('style');
        css.id = 'tol-reader-css';
        css.appendChild(document.createTextNode([
            '#menuMainDiv, .navbar, header.topbar, .tol-header, .tol-footer,',
            '  #footer, .footer, .copyright, .tol-card-title, .tol-hint { display: none !important; }',
            'body, html { margin: 0 !important; padding: 0 !important; height: 100%;',
            '  overflow: hidden !important; background: #0f172a; }',
            '#turn-on-loggers-root { margin: 0 !important; padding: 0 !important; height: 100vh;',
            '  display: flex; flex-direction: column; }',
            '#tol-sec-logs { margin: 0 !important; border: 0 !important; border-radius: 0 !important;',
            '  box-shadow: none !important; padding: 0 !important; background: #0f172a;',
            '  display: flex; flex-direction: column; flex: 1 1 auto; min-height: 0; }',
            '.tol-readbar { margin: 0 !important; border-radius: 0 !important;',
            '  border-left: 0; border-right: 0; border-top: 0; flex: 0 0 auto; }',
            '.tol-logmeta { flex: 0 0 auto; padding: 6px 10px; background: #f5f7fa;',
            '  border-bottom: 1px solid #e4e7eb; }',
            // The point of the whole exercise: the log fills what is left of the
            // window and scrolls inside itself.
            'pre.tol-log { flex: 1 1 auto; min-height: 0; height: auto !important;',
            '  max-height: none !important; overflow: auto !important; margin: 0 !important;',
            '  border-radius: 0 !important; resize: none; }'
        ].join('\n')));
        document.getElementsByTagName('head')[0].appendChild(css);
    }

    var LOG_HOST = (function () {
        try {
            var m = /[?&]tolhost=([^&]+)/.exec(window.location.search);
            return m ? decodeURIComponent(m[1]) : '';
        } catch (e) { return ''; }
    }());

    /**
     * What one host has to say about the request that is currently out.
     *
     * Status and selection are separate things. This is status only - whether
     * the host has answered and what came back - and it is computed against
     * the timestamp of the current request, not "has this host ever answered",
     * so a second search sends every chip back to waiting instead of leaving
     * the previous answer sitting there looking current.
     */
    /**
     * Write lines already on the page to a file the browser saves.
     *
     * No round trip: whatever this host sent is already in the state we are
     * rendering from, so asking the server for it again would be fetching the
     * same bytes twice.
     */
    function saveLines(host, lines) {
        try {
            var blob = new Blob([lines.join('\n') + '\n'], { type: 'text/plain' });
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = host + '-sailpoint.log';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            // Revoked on a timer rather than immediately: some browsers have not
            // finished reading the blob by the time click() returns.
            window.setTimeout(function () { URL.revokeObjectURL(url); }, 30000);
        } catch (e) {
            say('Could not save the file: ' + e.message, 'error');
        }
    }

    /**
     * Fetch this host's whole log and hand it to the browser to save.
     *
     * It used to be a plain navigation - set window.location to the endpoint
     * and let the browser download it. That can never work here. IdentityIQ
     * puts /plugin/rest/* behind its CSRF filter, and that filter validates
     * GET requests too, taking the token from the X-XSRF-TOKEN header and
     * nowhere else. A navigation cannot carry a header, so the request arrived
     * without a token and IdentityIQ answered with its error page - HTTP 200,
     * content-type text/html, a stack trace where the log should have been.
     *
     * Only IdentityIQ's own report, image and export paths are exempt from
     * that filter, and the exemption list lives in web.xml, which is not
     * something a plugin can add itself to and not something worth asking
     * anyone to edit. So the file is fetched with the header like every other
     * call this page makes, and saved from memory the same way a truncated
     * download already is.
     *
     * The cost is that the file passes through the browser rather than going
     * straight to disk. That is what the Full download limit setting is for on
     * a deployment whose logs run to gigabytes.
     */
    /**
     * How much of a file this page is willing to hold in memory at once.
     *
     * The server streams from disk and never holds the file, so its size costs
     * IdentityIQ nothing. The browser is the constraint: a fetch that ends in
     * blob() keeps the whole response, and a tab asked to keep several
     * gigabytes does not fail politely, it dies. Half a gigabyte is generous
     * for a log and well inside what a tab survives.
     *
     * This only applies when the file cannot be streamed straight to disk.
     */
    var MEM_LIMIT_BYTES = 512 * 1024 * 1024;

    /** Whether this browser can write a response to disk as it arrives. */
    function canStreamToDisk() {
        return typeof window.showSaveFilePicker === 'function'
            && window.isSecureContext === true;
    }

    function downloadFullLog(host, button) {
        var label = button ? button.textContent : '';
        var known = Number(state.thisHostLogBytes || 0);

        // The file picker has to be opened from the click itself. Asking for it
        // after the fetch resolves loses the user gesture the browser requires
        // and it throws instead of opening, so the choice of where to save is
        // made first and the request follows.
        var handle = null;
        var picking = Promise.resolve(null);
        if (canStreamToDisk() && known > MEM_LIMIT_BYTES) {
            try {
                picking = window.showSaveFilePicker({
                    suggestedName: host + '-sailpoint.log',
                    types: [{ description: 'Log file', accept: { 'text/plain': ['.log'] } }]
                }).then(function (h) { handle = h; return h; });
            } catch (e) {
                picking = Promise.resolve(null);
            }
        }

        return picking.then(function () {
            // Ask for less than the whole file only when it would otherwise have
            // to be held in one piece. A file being written straight to disk has
            // no such limit, and neither does one that already fits.
            var askMb = 0;
            if (!handle && known > MEM_LIMIT_BYTES) {
                askMb = Math.floor(MEM_LIMIT_BYTES / (1024 * 1024));
            }
            return fetchLog(host, button, label, known, askMb, handle);
        })['catch'](function (e) {
            // Cancelling the save dialog is a decision, not a failure.
            if (e && (e.name === 'AbortError' || e.name === 'NotAllowedError')) return;
            if (button) { button.disabled = false; button.textContent = label; }
            say('Could not download ' + host + '\u2019s log: ' + e.message, 'error');
        });
    }

    function fetchLog(host, button, label, known, askMb, handle) {
        var url = CTX + '/plugin/rest/TurnOnLoggers/logfile?host=' + encodeURIComponent(host)
                + (askMb > 0 ? '&mb=' + askMb : '');
        if (button) { button.disabled = true; button.textContent = 'Fetching...'; }

        function done(msg, kind) {
            if (button) { button.disabled = false; button.textContent = label; }
            if (msg) say(msg, kind || 'info');
        }

        var headers = {};
        var t = xsrf();
        if (t) headers['X-XSRF-TOKEN'] = t;

        if (askMb > 0) {
            say('This log is ' + fmtBytes(known) + '. It cannot be written straight to disk in '
                + 'this browser, so the whole file would have to be held in the tab - and a tab '
                + 'asked to hold that much does not survive it. Sending the last ' + askMb
                + 'MB instead, which is the part you want when you are looking at what just '
                + 'happened. IdentityIQ is unaffected either way: it streams the file in pieces '
                + 'and never holds it. Set Full download limit in the plugin settings to choose '
                + 'the size yourself.', 'info');
        }

        return fetch(url, { method: 'GET', credentials: 'same-origin', headers: headers })
            .then(function (r) {
                var type = r.headers.get('content-type') || '';
                // The failure mode worth naming, because it looks like success:
                // a rejected request still answers 200, just with HTML. Saving
                // that would put an error page on disk named sailpoint.log.
                if (!r.ok || type.indexOf('text/html') === 0) {
                    throw new Error(type.indexOf('text/html') === 0
                        ? 'IdentityIQ answered with a page rather than the file, which usually '
                          + 'means the session has expired. Reload and try again.'
                        : 'the host answered ' + r.status);
                }
                var total = Number(r.headers.get('content-length') || 0);
                if (total && button) {
                    button.textContent = 'Fetching ' + fmtBytes(total) + '...';
                }
                if (handle && r.body) return streamToDisk(r, handle, total, button);
                return r.blob().then(function (blob) { return saveBlob(blob, host); });
            })
            .then(function (n) {
                done(host + ' sent ' + fmtBytes(n) + '. Saved.', 'info');
            })
            ['catch'](function (e) {
                if (e && e.name === 'AbortError') { done(null); return; }
                done('Could not download ' + host + '\u2019s log: ' + e.message, 'error');
            });
    }

    /**
     * Write a response to disk as it arrives, holding one chunk at a time.
     *
     * This is what makes an unbounded Full download limit safe on a browser
     * that supports it: a five gigabyte log costs the tab a 64 KB chunk rather
     * than five gigabytes. Where it is not supported the caller asks the server
     * for less instead.
     */
    function streamToDisk(response, handle, total, button) {
        return handle.createWritable().then(function (writable) {
            var reader = response.body.getReader();
            var written = 0;
            function pump() {
                return reader.read().then(function (chunk) {
                    if (chunk.done) return writable.close().then(function () { return written; });
                    written += chunk.value.length;
                    if (button && total) {
                        button.textContent = Math.floor(written / total * 100) + '%';
                    }
                    return writable.write(chunk.value).then(pump);
                });
            }
            return pump();
        });
    }

    /** Hand a blob to the browser to save. */
    function saveBlob(blob, host) {
        var link = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = link;
        a.download = host + '-sailpoint.log';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        // On a timer, not immediately: some browsers have not finished reading
        // the blob by the time click() returns.
        window.setTimeout(function () { URL.revokeObjectURL(link); }, 30000);
        return blob.size;
    }

    /**
     * Which host we asked for a file, so the save fires once and only once.
     *
     * There used to be a second variable here holding the search a download had
     * interrupted, so it could be put back afterwards. That is gone, because
     * nothing is interrupted any more: a download is its own request on the
     * server now, and pressing the button leaves the log viewer exactly as it
     * was. Restoring state you should not have disturbed is a worse fix than
     * not disturbing it.
     */
    var pendingDownload = null;

    /**
     * Save a requested file the moment its host answers, then stop the request.
     *
     * Stopping matters as much as saving: the answer is a couple of megabytes
     * sitting in that host's status record, and until the request ends it would
     * be sent again on every ten-second poll.
     */
    /**
     * Run the poll fast while a download is outstanding, and slowly otherwise.
     *
     * Restarting the interval rather than polling fast all the time: on a large
     * cluster every poll is a state read, and doing that every second and a
     * half all day for the sake of a download nobody has asked for would be a
     * poor trade.
     */
    var pollingFast = false;

    /**
     * The fast tick while a download is outstanding.
     *
     * Deliberately does not repaint. Repainting rebuilds the log block, which
     * throws away where you had scrolled to and interrupts whatever you were
     * doing - and at one and a half seconds it does that constantly: clearing
     * the search box became a race, and scrolling through a log jumped you
     * somewhere else before you could read it. Nothing on screen needs
     * rebuilding to notice that a host has answered; only the waiting counter
     * changes, and that is one string, so it is written in place.
     */
    function fastTick() {
        retimePoll();
        if (!(state && state.downloads && state.downloads.length)) return;
        load(true, true).then(tickWaitingLabels);
    }

    /**
     * Update just the "Waiting for ... 24s" text, without touching anything
     * else. A whole-page render to move one number is what made the page feel
     * like it was fighting the person using it.
     */
    function tickWaitingLabels() {
        var list = (state && state.downloads) || [];
        if (!list.length) return;
        var byHost = {};
        for (var i = 0; i < list.length; i++) byHost[list[i].host] = list[i];
        var btns = document.querySelectorAll('#tol-sec-logs button');
        for (var k = 0; k < btns.length; k++) {
            var m = /^Waiting for (.+?)\.\.\./.exec(btns[k].textContent);
            if (!m) continue;
            var d = byHost[m[1]];
            if (!d) continue;
            var secs = Math.max(0, Math.round((Date.now() - Number(d.askedAt || 0)) / 1000));
            btns[k].textContent = 'Waiting for ' + m[1] + '... ' + secs + 's';
        }
    }

    function pollTick() {
        retimePoll();

        // A hidden tab is not worth polling - except while a file is on its
        // way, because coming back to a tab that quietly gave up on the
        // download you asked for is the same complaint in a different shape.
        var awaited = !!(state && state.downloads && state.downloads.length);
        if (document.hidden && !awaited) return;

        // Typing means do not repaint - it does NOT mean do not look. The
        // repaint is what would yank the form out from under someone; fetching
        // state and noticing that a host has answered disturbs nothing.
        load(true, formTouched);
    }
    function retimePoll() {
        var waiting = !!(state && state.downloads && state.downloads.length);
        if (waiting === pollingFast) return;
        pollingFast = waiting;
        if (!pollTimer) return;
        window.clearInterval(pollTimer);
        pollTimer = window.setInterval(waiting ? fastTick : pollTick,
                                       waiting ? POLL_FAST_MS : POLL_MS);
    }

    /** One host's row out of the current state, or null. */
    function hostByName(name) {
        var hosts = (state && state.hosts) || [];
        for (var i = 0; i < hosts.length; i++) {
            if (hosts[i].name === name) return hosts[i];
        }
        return null;
    }

    /** Bytes a set of lines will occupy once written out, newlines included. */
    function byteLength(lines) {
        var n = 0;
        for (var i = 0; i < lines.length; i++) n += lines[i].length + 1;
        return n;
    }

    /**
     * Save a download once its host has answered.
     *
     * Driven by the request on the server rather than by a variable set when
     * the button was pressed, so a file that lands after the page was reloaded
     * still saves itself. `saving` guards the gap between saving and the
     * server confirming the request is cleared, which is a poll or two wide
     * and would otherwise save the same file twice.
     */
    /**
     * How long to leave an answered download for the window that asked.
     *
     * A download is saved by the window that asked for it, so that opening a
     * second window does not download a log nobody just asked for. But a window
     * can go away - most often by being reloaded, which gives the page a new
     * identity - and the file it asked for would then sit there answered and
     * unclaimed for ever. Nobody watching a browser knows any of this; they
     * pressed a button and no file arrived, and pressing refresh made it worse
     * rather than better.
     *
     * So ownership lapses. If the window that asked has not taken its answer
     * within this long, any window may. Long enough that a live window always
     * wins - it polls every 1.5s while waiting - and short enough that a
     * reload feels like it fixed things.
     */
    var ADOPT_AFTER_MS = 12000;

    /** Downloads this window is part way through saving, by host. */
    var saving = {};

    /**
     * Files waiting to be handed to the browser, one at a time.
     *
     * Browsers throttle a page that starts several downloads at once - Chrome
     * quietly drops all but the first unless the person allows it. Asking three
     * hosts for their logs and getting one file is not something anybody would
     * connect to that, so the saves are spaced instead: the answers still
     * arrive together, they are just handed over one after another.
     */
    var saveQueue = [];
    var draining = false;

    function queueSave(host, lines) {
        saveQueue.push({ host: host, lines: lines });
        if (draining) return;
        draining = true;
        (function next() {
            var item = saveQueue.shift();
            if (!item) { draining = false; return; }
            saveLines(item.host, item.lines);
            say(item.host + ' sent ' + fmtBytes(byteLength(item.lines))
                + (saveQueue.length ? '. Saving, ' + saveQueue.length + ' to go.' : '. Saving.'),
                'info');
            window.setTimeout(next, 1200);
        })();
    }

    function checkPendingDownload() {
        var list = (state && state.downloads) || [];
        for (var i = 0; i < list.length; i++) checkOneDownload(list[i]);
    }

    function finishDownload(host) {
        api('POST', '/logdownload', { host: '', only: host, client: CLIENT })
            .then(function () { delete saving[host]; })
            ['catch'](function () { delete saving[host]; });
    }

    function checkOneDownload(dl) {
        if (!dl || !dl.host || saving[dl.host]) return;

        var asked = Number(dl.askedAt || 0);
        var answered = Number(dl.answeredAt || 0);
        var hasAnswered = answered > 0 && answered >= asked;

        // Somebody else's window asked for this one. Leave it alone while they
        // are still there to take it - acting on it is how one click became a
        // file in every open window. Once their claim has lapsed it is fair
        // game, which is what makes a reload work again.
        var mine = !dl.client || dl.client === CLIENT;
        if (!mine) {
            if (!hasAnswered) return;
            if (Date.now() - answered < ADOPT_AFTER_MS) return;
        }

        if (dl.error) {
            saving[dl.host] = true;
            say(dl.host + ' could not read its log: ' + dl.error, 'error');
            finishDownload(dl.host);
            return;
        }

        if (!hasAnswered) {
            if (!mine) return;
            var waited = Date.now() - asked;

            // Worth catching at once rather than after three minutes, because
            // it will never resolve on its own.
            var target = hostByName(dl.host);
            if (target && target.buildStale) {
                saving[dl.host] = true;
                say(dl.host + ' is running plugin ' + (target.build || 'a build too old to say which')
                    + ' in its sync service, but ' + (state.build || 'a newer version')
                    + ' is installed. IdentityIQ builds a service once and keeps it, so that host '
                    + 'will go on running the older code - and ignoring downloads, which it does '
                    + 'not know about - until its JVM is restarted. Searches still work there, '
                    + 'which is why it looks fine. Restart IdentityIQ on ' + dl.host + '.', 'error');
                finishDownload(dl.host);
                return;
            }
            if (waited > GIVE_UP_MS) {
                saving[dl.host] = true;
                say(dl.hostReporting === false
                    ? dl.host + ' is not reporting to this page at all, so it cannot answer - it '
                      + 'is either down or not running the Logger Manager sync service.'
                    : dl.host + ' has not answered in ' + Math.round(waited / 1000) + ' seconds. '
                      + 'Its last sync was ' + fmtAgo(dl.hostLastSync) + '. Giving up on this one '
                      + '- nothing else was affected.', 'error');
                finishDownload(dl.host);
            }
            return;
        }

        var lines = dl.lines || [];
        saving[dl.host] = true;

        if (!lines.length) {
            say(dl.host + ' read its log and there was nothing in it to send - most often a log '
                + 'that has just been rotated. Nothing was downloaded.', 'info');
        } else {
            queueSave(dl.host, lines);
        }
        finishDownload(dl.host);
    }

    /** One host's row out of the current state, or null. */
    function hostByName(name) {
        var hosts = (state && state.hosts) || [];
        for (var i = 0; i < hosts.length; i++) {
            if (hosts[i].name === name) return hosts[i];
        }
        return null;
    }

    /** Bytes a set of lines will occupy once written out, newlines included. */
    function byteLength(lines) {
        var n = 0;
        for (var i = 0; i < lines.length; i++) n += lines[i].length + 1;
        return n;
    }


    /**
     * Mark every occurrence of the highlight text in the log blocks.
     *
     * Plain text nodes rebuilt with <mark> around the matches, rather than a
     * regex over innerHTML - log lines are full of angle brackets and quotes
     * and rewriting them as HTML would both corrupt the output and hand anyone
     * who can write to a log a way to inject markup into this page.
     *
     * Capped, because a wide-open highlight over a two-megabyte tail would
     * build hundreds of thousands of nodes and lock the window up.
     */
    var HILITE_MAX = 2000;

    function paintHighlights() {
        var want = (logState.hilite || '').trim();
        var pres = document.querySelectorAll('#tol-sec-logs pre.tol-log');
        for (var i = 0; i < pres.length; i++) {
            var pre = pres[i];
            if (pre.getAttribute('data-raw') === null) {
                pre.setAttribute('data-raw', pre.textContent);
            }
            var raw = pre.getAttribute('data-raw');
            clear(pre);
            if (!want) {
                pre.appendChild(document.createTextNode(raw));
                continue;
            }
            var hay = raw.toLowerCase();
            var needle = want.toLowerCase();
            var at = 0, found = 0, idx;
            while ((idx = hay.indexOf(needle, at)) !== -1 && found < HILITE_MAX) {
                if (idx > at) pre.appendChild(document.createTextNode(raw.slice(at, idx)));
                var m = el('mark', 'tol-hi');
                m.appendChild(document.createTextNode(raw.slice(idx, idx + want.length)));
                pre.appendChild(m);
                at = idx + want.length;
                found++;
            }
            pre.appendChild(document.createTextNode(raw.slice(at)));
        }
    }

    /** Double-clicking a word in the log fills the highlight box with it. */
    function wireDoubleClickHighlight() {
        var sec = document.getElementById('tol-sec-logs');
        if (!sec || sec.getAttribute('data-dbl') === 'y') return;
        sec.setAttribute('data-dbl', 'y');
        sec.addEventListener('dblclick', function () {
            var sel = '';
            try { sel = String(window.getSelection()); } catch (e) { return; }
            sel = sel.trim();
            if (!sel || sel.length > 200) return;
            logState.hilite = sel;
            var f = document.getElementById('tol-hilite');
            if (f) f.value = sel;
            paintHighlights();
        });
    }

    function fmtBytes(n) {
        n = Number(n) || 0;
        if (n < 1024) return n + ' B';
        if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
        if (n < 1024 * 1024 * 1024) return (n / 1048576).toFixed(1) + ' MB';
        return (n / 1073741824).toFixed(2) + ' GB';
    }

    function logStatus(h) {
        if (!h.reporting) {
            // Grey for the same reason as hostHealth: a host the plugin has
            // never heard from cannot answer, but that is missing information
            // rather than a failure. Red here stays for a host that tried to
            // read its log and could not.
            return { key: 'down', count: '', why: h.name + ' has never reported, so it cannot '
                     + 'answer this. The plugin’s sync service has not run there.' };
        }
        if (!state.logActive) {
            return { key: 'idle', count: '', why: 'Nothing asked yet.' };
        }
        var askedAt = parseInt(state.logQueryAt, 10) || 0;
        var answeredAt = parseInt(h.logAnsweredAt, 10) || 0;
        if (answeredAt < askedAt) {
            return { key: 'wait', count: '', why: 'Reading its log - answers on its next sync.' };
        }
        if (h.logError) {
            return { key: 'error', count: '!', why: h.logError };
        }
        // Not logMatches.length: the lines are not on the page unless somebody
        // asked for them, but the count always is.
        var n = (typeof h.logMatchCount === 'number')
            ? h.logMatchCount : (h.logMatches || []).length;
        if (!n) {
            return {
                key: 'none', count: '0',
                why: state.logMode === 'tail'
                    ? 'Its log is empty.'
                    : 'Read its log fine, nothing matched.'
            };
        }
        return {
            key: 'ok', count: String(n),
            why: n + ' line' + (n === 1 ? '' : 's') + ', answered ' + fmtAgo(h.logAnsweredAt)
        };
    }

    /**
     * Ask every host to look for some text. Shared with the Find in logs
     * buttons in the live logger table, which is the same request arrived at
     * from a different direction.
     */
    function runLogSearch(text) {
        logState.text = text;
        return mutate(api('POST', '/logquery', { mode: 'search', text: text }),
            'Searching every host for "' + text + '".');
    }

    /**
     * The logger name as it is likely to appear in a log line.
     *
     * IIQ's stock pattern uses %c{4}, which prints only the last four
     * components, so searching for the full sailpoint.a.b.c.d.Whatever would
     * match nothing. The last four components are a substring of the full name
     * as well, so this matches whichever the host's pattern happens to print.
     */
    function logNeedle(logger) {
        var parts = String(logger).split('.');
        return parts.length > 4 ? parts.slice(parts.length - 4).join('.') : String(logger);
    }

    /**
     * Log lines from every host.
     *
     * Two things you can ask for, both cluster-wide:
     *   Output last N lines - the raw tail, for when a search finds nothing and
     *                         you just want to see what the host is writing
     *   Search all hosts    - lines matching some text
     *
     * Either way every host answers about its own file, because no host can
     * read another one's disk. The host serving the page answers immediately;
     * the rest follow on their next sync.
     *
     * Every host gets a chip, from the moment the panel is open, whether or not
     * anything has been asked. Colour is what the host did; struck through is
     * whether you are looking at it. The two are independent, so a host that
     * found forty lines and has been clicked out of the results is green and
     * struck through, which is exactly what it is.
     */
    function logsSection() {
        var box = el('section', 'tol-card');
        box.appendChild(el('h2', 'tol-card-title', 'Log Viewer'));

        if (state.logTailEnabled === false) {
            box.appendChild(el('div', 'tol-empty', 'Switched off in the plugin settings.'));
            return box;
        }

        box.appendChild(el('div', 'tol-hint',
            'Every host reads its own log file and reports back. The host serving this page '
            + 'answers immediately; the others answer on their next sync, so give them a minute. '
            + 'The chips below show what each host found - click one to drop it from the output, '
            + 'click it again to bring it back.'));



        // Reading controls, in the window somebody opened to read in. The
        // complaint these answer is real: a fixed-height box you cannot search
        // or resize is worse than a text editor, and if the answer is "go to
        // Splunk" then people go to Splunk and stay there.
        if (LOG_ONLY) {
            var rd = el('div', 'tol-readbar');

            var wrapLbl = el('label', 'tol-check');
            var wrapBox = document.createElement('input');
            wrapBox.type = 'checkbox';
            wrapBox.checked = !!logState.wrap;
            wrapBox.onchange = function () {
                logState.wrap = wrapBox.checked;
                try { window.sessionStorage.setItem('tol-log-wrap', logState.wrap ? 'y' : 'n'); }
                catch (e) { /* ignore */ }
                render();
            };
            wrapLbl.appendChild(wrapBox);
            wrapLbl.appendChild(el('span', '', ' Wrap long lines'));
            rd.appendChild(wrapLbl);

            var hi = document.createElement('input');
            hi.type = 'text';
            hi.className = 'tol-input';
            hi.id = 'tol-hilite';
            hi.placeholder = 'highlight text - or double-click a word in the log';
            hi.value = logState.hilite || '';
            hi.oninput = function () {
                logState.hilite = hi.value;
                formTouched = true;
                paintHighlights();
            };
            rd.appendChild(hi);

            var clear = el('button', 'tol-btn tol-btn-small', 'Clear');
            clear.onclick = function () {
                logState.hilite = '';
                var f = document.getElementById('tol-hilite');
                if (f) f.value = '';
                paintHighlights();
            };
            rd.appendChild(clear);

            rd.appendChild(el('span', 'tol-small',
                'Ctrl+F searches this window like any page. Drag the window edge to resize.'));
            box.appendChild(rd);
        }

        // --- raw tail -------------------------------------------------------
        var bar1 = el('div', 'tol-logbar');
        var lineSel = document.createElement('select');
        lineSel.className = 'tol-input tol-log-lines';
        [20, 40, 100, 300].forEach(function (n) {
            lineSel.appendChild(opt(String(n), n + ' lines', n === logState.lines));
        });
        lineSel.onchange = function () { logState.lines = parseInt(lineSel.value, 10); };

        var tailBtn = el('button', 'tol-btn tol-btn-small', 'Output last');
        tailBtn.title = 'The end of the log, with no filter - no search term needed';
        tailBtn.onclick = function () {
            mutate(api('POST', '/logquery', { mode: 'tail', lines: logState.lines, text: '' }),
                'Reading the last ' + logState.lines + ' lines on every host.');
        };
        bar1.appendChild(tailBtn);
        bar1.appendChild(lineSel);
        bar1.appendChild(el('span', 'tol-small', 'on every host - no filter, just the raw log'));
        // A reader window is for reading one host's output, not for choosing
        // what to fetch - that choice was made by the button that opened it.
        if (!LOG_ONLY) box.appendChild(bar1);

        // --- search ---------------------------------------------------------
        var bar2 = el('div', 'tol-logbar');
        var q = document.createElement('input');
        q.type = 'text';
        q.id = 'tol-log-q';
        q.className = 'tol-input tol-log-select';
        q.setAttribute('placeholder',
            'text to look for - a logger name, an identity, an error. Leave blank for the recent log.');
        q.title = 'Text to look for in every host\u2019s log - a logger name, an identity, an '
            + 'error message. Leave it blank and this shows the most recent lines instead, the '
            + 'same as "Output last".';
        q.value = (state.logMode === 'search' ? state.logQuery : logState.text) || '';
        bar2.appendChild(q);

        var searchBtn = el('button', 'tol-btn tol-btn-primary tol-btn-small', 'Search all hosts');
        searchBtn.onclick = function () {
            var text = document.getElementById('tol-log-q').value || '';
            logState.text = text;
            if (!text.trim()) {
                // Blank is not an error, it is a reasonable thing to mean: show
                // me the log. Refusing it would just be a lecture.
                mutate(api('POST', '/logquery', { mode: 'tail', lines: logState.lines, text: '' }),
                    'No search term, so showing the last ' + logState.lines
                        + ' lines on every host.');
                return;
            }
            runLogSearch(text.trim());
        };
        bar2.appendChild(searchBtn);

        if (state.logActive) {
            var stop = el('button', 'tol-btn tol-btn-small', 'Stop');
            stop.title = 'Stop every host reading its log';
            stop.onclick = function () {
                mutate(api('POST', '/logquery', { mode: 'search', text: '' }), 'Stopped.');
            };
            bar2.appendChild(stop);
        }
        if (!LOG_ONLY) box.appendChild(bar2);

        // --- one chip per host, always ---------------------------------------
        var hosts = sortedHosts();
        if (!hosts.length) {
            box.appendChild(el('div', 'tol-empty', 'No host has reported in yet.'));
            return box;
        }

        var strip = el('div', 'tol-hoststrip');
        hosts.forEach(function (h) {
            var st = logStatus(h);
            var off = !!logState.off[h.name];
            // The same chip the other sections draw. Only the colour means
            // something different here - what this host found, rather than how
            // the host itself is doing.
            strip.appendChild(hostChip(h, {
                status: st, mode: 'strike', picked: !off, count: st.count,
                hint: off ? 'Click to put ' + h.name + ' back in the output.'
                          : 'Click to drop ' + h.name + ' from the output.',
                onclick: (function (name) {
                    return function () {
                        if (logState.off[name]) delete logState.off[name];
                        else logState.off[name] = true;
                        render();
                    };
                })(h.name)
            }));
        });
        if (hosts.length > 1) {
            strip.appendChild(bulkPick(hosts,
                function (on) {
                    logState.off = {};
                    if (!on) hosts.forEach(function (h) { logState.off[h.name] = true; });
                },
                function (h) { return !logState.off[h.name]; }));
        }
        if (!LOG_ONLY) box.appendChild(strip);

        if (!state.logActive) {
            box.appendChild(el('div', 'tol-empty',
                'Nothing loaded. Press "Output last" for the raw end of every host\u2019s log, or '
                + 'search for something.'));
            return box;
        }

        var tailing = state.logMode === 'tail';
        var shown = hosts.filter(function (h) { return !logState.off[h.name]; });
        if (!shown.length) {
            box.appendChild(el('div', 'tol-empty',
                'Every host has been clicked out of the output. Click a chip to bring one back.'));
            return box;
        }

        // --- one block per host still in the output --------------------------
        var any = false;
        shown.forEach(function (h) {
            if (LOG_HOST && h.name !== LOG_HOST) return;
            var st = logStatus(h);
            if (st.key === 'idle' || st.key === 'down') return;
            any = true;

            var head = el('div', 'tol-logmeta');
            head.appendChild(hostChip(h, { status: st, mode: 'static' }));
            // Deliberately not st.why for the failure cases. That text is the
            // chip's tooltip and belongs in one place: printing it here as well
            // put the same sentence twice on screen, once in grey and once in
            // red, which reads as two different problems.
            var line = st.key === 'wait' ? 'reading its log...'
                : st.key === 'error' ? 'could not read its log'
                : st.key === 'none' ? (tailing
                    ? 'its log is empty'
                    : 'read its log fine, nothing matched "' + state.logQuery + '"')
                : st.why;
            head.appendChild(el('span', 'tol-small',
                line + (h.logPath ? ' - ' + h.logPath : '')));

            // Per host, because comparing two hosts side by side is the whole
            // reason to want a window of your own, and downloading everything
            // when you wanted one host's log is not a kindness.
            var acts = el('span', 'tol-logacts');

            // Offering this inside a reader window would be offering to do
            // what has already been done.
            if (!LOG_ONLY) {
                var win = el('button', 'tol-btn tol-btn-small', 'Open in window');
                win.title = 'Open ' + h.name + ' on its own, resizable. Open as many as you like.';
                win.onclick = (function (name) {
                    return function () {
                        window.open(CTX + '/plugins/pluginPage.jsf?pn=TurnOnLoggers&tolview=logs'
                            + '&tolhost=' + encodeURIComponent(name),
                            'tolLogs' + Date.now(),
                            'width=1280,height=860,resizable=yes,scrollbars=yes');
                    };
                })(h.name);
                acts.appendChild(win);
            }

            var truncMb = state.truncatedDownloadMb || 2;
            var fullMax = state.fullDownloadMaxMb || 0;

            if (h.name === state.thisHost) {
                var bytes = Number(state.thisHostLogBytes || 0);
                var capped = fullMax > 0 && bytes > fullMax * 1024 * 1024;
                var dl = el('button', 'tol-btn tol-btn-small',
                    capped ? 'Download last ' + fullMax + 'MB' : 'Download full log');
                dl.title = capped
                    ? 'This log is ' + fmtBytes(bytes) + ', past the ' + fullMax + 'MB limit in the '
                      + 'plugin settings, so the END of the file is sent - the part you want when '
                      + 'you are looking at what just happened. Raise Full download limit to send '
                      + 'more, or 0 to always send all of it. It streams from disk in pieces, so '
                      + 'the size costs you a slow download rather than IdentityIQ any memory.'
                    : 'The whole file, ' + fmtBytes(bytes) + ', streamed straight off this host\u2019s '
                      + 'disk. This is the host serving your page, so there is no size limit beyond '
                      + 'the Full download limit setting and nothing crosses the database.';
                dl.onclick = (function (name) {
                    return function () { downloadFullLog(name, this); };
                })(h.name);
                acts.appendChild(dl);
            } else {
                // While this host owes us a file, the button stops offering to
                // ask again and starts reporting. Silence for up to a minute
                // reads as a broken button; a count that moves reads as a wait.
                // Shown for any of this person's outstanding downloads, not
                // only this window's: they asked for it, so they should see it
                // is still coming wherever they happen to be looking from.
                var owed = null;
                ((state.downloads) || []).forEach(function (d) {
                    if (d.host === h.name) owed = d;
                });
                var waited = owed ? Math.max(0, Math.round(
                        (Date.now() - Number(owed.askedAt || 0)) / 1000)) : 0;
                var trunc = el('button', 'tol-btn tol-btn-small',
                    owed ? 'Waiting for ' + h.name + '... ' + waited + 's'
                         : 'Download truncated log (last ' + truncMb + 'MB)');
                if (owed) trunc.disabled = true;
                trunc.title = owed
                    ? h.name + ' is reading its log and will publish the result on its next '
                      + 'sync, which is once a minute by default. Nothing can make that happen '
                      + 'sooner - no host can reach into another to ask - but this page is now '
                      + 'checking every second and a half, so the download starts the moment it '
                      + 'lands. Shorten the interval on the TurnOnLoggersSync service definition '
                      + 'if a minute is too long for your deployment.'
                    : 'The last ' + truncMb + 'MB of ' + h.name + '\u2019s log, not the whole '
                    + 'file. No host can read another host\u2019s disk - that is what lets this work '
                    + 'across Windows, Linux and containers with nothing shared - so ' + h.name
                    + ' has to read its own file and publish the result through the database. '
                    + 'It answers on its next sync, within a minute, and the download starts by '
                    + 'itself when it arrives. Change the size with Truncated download size in the '
                    + 'plugin settings.';
                trunc.onclick = (function (name) {
                    return function () {
                        pendingDownload = name;
                        // Not mutate(): that repaints the page, and there is
                        // nothing here to repaint. Asking for a file changes
                        // nothing about what is on screen.
                        api('POST', '/logdownload', { host: name, client: CLIENT })
                            .then(function () {
                                say('Asking ' + name + ' for the last ' + truncMb + 'MB of its '
                                    + 'log. It answers on its next sync, so this can take up to '
                                    + 'a minute - the button shows how long it has been, and the '
                                    + 'download starts on its own the moment the file lands. '
                                    + 'Nothing here changes meanwhile, so carry on.', 'info');
                                load(true);
                            })['catch'](function (e) {
                                pendingDownload = null;
                                say('Could not ask ' + name + ' for its log: ' + e.message, 'error');
                            });
                    };
                })(h.name);
                acts.appendChild(trunc);
            }

            head.appendChild(acts);
            box.appendChild(head);

            // No black panel for a host with nothing to show. On a thirteen-host
            // search most hosts match nothing, and thirteen empty terminals to
            // scroll past would bury the two hosts that actually answered. The
            // line above already says what happened.
            if (st.key === 'wait' || st.key === 'none') return;

            var pre = el('pre', 'tol-log' + (logState.wrap ? ' tol-log-wrap' : ''));
            if (st.key === 'error') {
                pre.appendChild(el('div', 'tol-err-text', h.logError));
            } else {
                (h.logMatches || []).forEach(function (l) {
                    pre.appendChild(document.createTextNode(l + '\n'));
                });
            }
            // Tagged so a repaint can put the scroll position back on the
            // right block - hosts come and go between renders as they answer,
            // so an index would restore the wrong one.
            pre.setAttribute('data-host', h.name);
            box.appendChild(pre);
        });

        if (!any) {
            box.appendChild(el('div', 'tol-empty', 'Waiting for hosts to answer...'));
        }
        return box;
    }

    // Closed until asked for. It is a look-back, not something you need in
    // front of you while working, and fetching it on every ten-second refresh
    // would be a query nobody asked for. Rows are kept once loaded so the
    // refresh timer does not re-fetch them either.
    var historyState = { open: false, rows: null, loading: false, error: null, kind: 'change' };

    function loadHistory() {
        // Deliberately does not clear the rows it already has. Blanking them
        // shrinks this panel from fifty rows to one line, the document loses
        // several thousand pixels of height, and the browser clamps the scroll
        // position - which is what "pressing Refresh jumps me to the top" was.
        // The old rows stay on screen, greyed, until the new ones arrive.
        historyState.loading = true;
        historyState.error = null;
        render();
        api('GET', '/history?limit=50&kind=' + historyState.kind).then(function (d) {
            historyState.rows = d.rows || [];
            historyState.truncated = !!d.truncated;
            historyState.loading = false;
            render();
        }).catch(function (e) {
            historyState.loading = false;
            historyState.error = e.message;
            render();
        });
    }

    /**
     * Changes, or changes and reads.
     *
     * Reading production logs is audited too, and should be - but a handful of
     * searches will bury the one override anyone is looking for, so the default
     * is changes only and the reads are a click away.
     */
    function historyBar() {
        var bar = el('div', 'tol-filters');
        [['change', 'Changes'], ['all', 'Everything']].forEach(function (o) {
            var b = el('button', 'tol-filter' + (historyState.kind === o[0] ? ' tol-filter-on' : ''),
                o[1]);
            b.title = o[0] === 'change'
                ? 'Overrides added, removed and expired; collections; hosts forgotten'
                : 'Changes plus log reads, searches and forced syncs';
            b.disabled = !!historyState.loading;
            b.onclick = (function (k) {
                return function () {
                    if (historyState.kind === k) return;
                    historyState.kind = k;
                    loadHistory();
                };
            })(o[0]);
            bar.appendChild(b);
        });
        var refresh = el('button', 'tol-btn tol-btn-small');
        if (historyState.loading) refresh.appendChild(el('span', 'tol-spin'));
        refresh.appendChild(document.createTextNode(
            historyState.loading ? 'Refreshing' : 'Refresh history'));
        refresh.disabled = !!historyState.loading;
        refresh.title = 'Re-read the audit trail. This does not reload the page.';
        refresh.onclick = function () { loadHistory(); };
        bar.appendChild(refresh);
        return bar;
    }

    /**
     * What has been changed through this plugin, newest first.
     *
     * Read back out of the audit trail, which is the record - not a second
     * copy kept for the page's convenience. So this shows exactly what an
     * auditor would see in Audit Search, and it is honest about its one blind
     * spot: a rule calling Logger.getLogger(...).setLevel(...) never came
     * through here and so is not in it.
     */
    function historySection() {
        var box = el('section', 'tol-card');
        var head = el('div', 'tol-card-head');
        head.appendChild(el('h2', 'tol-card-title', 'History'));

        var toggle = el('button', 'tol-btn tol-btn-small',
            historyState.open ? 'Hide' : 'Show');
        toggle.setAttribute('aria-expanded', historyState.open ? 'true' : 'false');
        toggle.title = historyState.open
            ? 'Collapse the history'
            : 'Every change made through this plugin, newest first';
        toggle.onclick = function () {
            historyState.open = !historyState.open;
            if (historyState.open && historyState.rows === null) loadHistory();
            else render();
        };
        head.appendChild(toggle);
        box.appendChild(head);

        if (!historyState.open) return box;

        box.appendChild(el('div', 'tol-hint',
            'Read back out of the IIQ audit trail, newest first. The revision column is the '
            + 'configuration revision that change produced, so the number in the page header ties '
            + 'back to a row here. Reading and searching logs is audited too but is not a change, '
            + 'so it sits behind Everything. Changes made by a rule calling '
            + 'Logger.getLogger(...).setLevel(...) never came through this plugin and are in '
            + 'neither.'));

        if (historyState.error) {
            box.appendChild(el('div', 'tol-banner tol-error', historyState.error));
            return box;
        }
        if (historyState.rows === null) {
            box.appendChild(el('div', 'tol-empty', 'Reading the audit trail...'));
            return box;
        }
        box.appendChild(historyBar());
        if (!historyState.rows.length) {
            box.appendChild(el('div', 'tol-empty', historyState.kind === 'change'
                ? 'No changes recorded yet. Switch to Everything to include log reads and searches.'
                : 'Nothing recorded yet. Every action from this page is written here as it happens.'));
            return box;
        }

        var wrap = el('div', 'tol-tablewrap' + (historyState.loading ? ' tol-stale' : ''));
        var t = el('table', 'tol-table');
        t.appendChild(headRow(['Rev', 'When', 'Who', 'What', 'Logger', 'Level', 'Hosts',
            'Expires', 'Note']));
        var tb = el('tbody');
        historyState.rows.forEach(function (r) {
            var tr = el('tr');
            tr.appendChild(el('td', 'tol-small tol-mono', r.revision || '-'));
            var when = el('td', 'tol-small');
            when.appendChild(document.createTextNode(fmtTime(r.when)));
            when.appendChild(el('div', 'tol-small', fmtAgo(r.when)));
            tr.appendChild(when);
            tr.appendChild(el('td', 'tol-small', r.who || '-'));
            tr.appendChild(el('td', 'tol-small', r.what || '-'));
            var lg = el('td');
            lg.appendChild(el('code', 'tol-logger-name', r.logger || '-'));
            tr.appendChild(lg);
            var lv = el('td');
            if (r.level) {
                lv.appendChild(el('span', 'tol-level tol-level-' + String(r.level).toLowerCase(),
                    r.level));
            } else {
                lv.appendChild(document.createTextNode('-'));
            }
            tr.appendChild(lv);
            tr.appendChild(el('td', 'tol-small tol-mono', r.hosts || '-'));
            tr.appendChild(el('td', 'tol-small', r.expires || '-'));
            tr.appendChild(el('td', 'tol-small', r.note || ''));
            tb.appendChild(tr);
        });
        t.appendChild(tb);
        wrap.appendChild(t);
        box.appendChild(wrap);

        if (historyState.truncated) {
            box.appendChild(el('div', 'tol-small',
                'Showing the most recent ' + historyState.rows.length
                + '. Older changes are still in the audit trail - search Audit Search for the '
                + '"Logger Manager change" action.'));
        }
        return box;
    }

    function footer() {
        var f = el('div', 'tol-footer');

        // One line each. This was three lines of small print and read as clutter.
        f.appendChild(el('div', 'tol-footer-line',
            'Levels are set in each JVM’s live log4j2 runtime — no file is ever modified, '
            + 'turning a logger on only adds to the list, and the list survives restarts.'));

        var credit = el('div', 'tol-credit');
        var name = 'Logger Manager';
        if (state.pluginVersion) name += ' ' + state.pluginVersion;
        credit.appendChild(el('span', 'tol-credit-name', name));
        credit.appendChild(el('span', 'tol-credit-sep', '•'));
        if (state.author) {
            credit.appendChild(el('span', 'tol-credit-author', state.author));
        }
        if (state.projectUrl) {
            credit.appendChild(el('span', 'tol-credit-sep', '•'));
            var a = document.createElement('a');
            a.href = state.projectUrl;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.appendChild(document.createTextNode('Documentation and issues'));
            credit.appendChild(a);
        }
        f.appendChild(credit);
        return f;
    }

    function headRow(labels) {
        var thead = el('thead');
        var tr = el('tr');
        labels.forEach(function (l) { tr.appendChild(el('th', '', l)); });
        thead.appendChild(tr);
        return thead;
    }

    // ------------------------------------------------------------------
    // boot
    // ------------------------------------------------------------------

    var PAGE_URL = CTX + '/plugins/pluginPage.jsf?pn=TurnOnLoggers';

    // ------------------------------------------------------------------
    // signpost on this plugin's own Configure page
    //
    // "Configure" on the Plugins screen opens pluginConfig.jsf, which is where
    // people reasonably go looking for somewhere to add a logger - and it is
    // the wrong screen. The settings there are policy (who may use it, how long
    // overrides live); loggers get turned on from the Logger Manager page. This
    // puts a link to it right at the top of that form.
    // ------------------------------------------------------------------

    function isOurConfigPage() {
        if (document.location.pathname.indexOf('/plugins/pluginConfig.jsf') < 0) return false;
        // IIQ routes this page client-side, so pn= can be in the query string
        // or in the hash (#/configuration?pn=TurnOnLoggers&...).
        var where = (document.location.search || '') + ' ' + (document.location.hash || '');
        return /[?&]pn=TurnOnLoggers(?:[&\s]|$)/.test(where);
    }

    function addConfigBanner() {
        if (!isOurConfigPage()) return;
        if (document.getElementById('tol-config-banner')) return;

        var main = document.querySelector('div[role="main"].sp-body')
                || document.getElementById('mainContent');
        if (!main) return;

        var box = el('div', 'tol-config-banner');
        box.id = 'tol-config-banner';

        var text = el('div', 'tol-config-banner-text');
        text.appendChild(el('strong', '', 'Looking for where to turn a logger on? '));
        text.appendChild(document.createTextNode(
            'Not here. These settings control who may use the plugin and how long '
            + 'overrides live. Loggers are turned on from the Logger Manager page, '
            + 'which has a logger picker, per-host targeting and an expiry.'));
        box.appendChild(text);

        var link = document.createElement('a');
        link.href = PAGE_URL;
        link.className = 'tol-btn tol-btn-primary tol-config-banner-link';
        link.appendChild(document.createTextNode('Open Logger Manager'));
        box.appendChild(link);

        // Slot in under the "Back" header bar rather than above it, so it does
        // not displace IIQ's own chrome.
        var header = main.querySelector('header');
        if (header && header.nextSibling) {
            main.insertBefore(box, header.nextSibling);
        } else {
            main.insertBefore(box, main.firstChild);
        }
    }

    function start() {
        addConfigBanner();
        // The config screen is a hash-routed SPA; re-check when the route moves.
        window.addEventListener('hashchange', addConfigBanner);

        root = document.getElementById(ROOT_ID);
        if (!root) return; // not our page - every other IIQ page lands here

        clear(root);
        root.appendChild(el('div', 'tol-boot', 'Loading logger control...'));

        load().then(function () {
            if (!state) return;
            if (pollTimer) window.clearInterval(pollTimer);
            pollTimer = window.setInterval(pollTick, POLL_MS);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { window.setTimeout(start, 0); });
    } else {
        window.setTimeout(start, 0);
    }
})();
