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

    var SRC = {
        file: 'log4j2.properties',
        plugin: 'this plugin',
        leftover: 'left over',
        runtime: 'set at runtime',
        unknown: 'source unknown'
    };

    var OS_ICON = { windows: 'Win', linux: 'Linux', macos: 'macOS', aix: 'AIX', solaris: 'Solaris', other: 'OS' };

    // ------------------------------------------------------------------
    // REST
    // ------------------------------------------------------------------

    function xsrf() {
        if (window.SailPoint && typeof window.SailPoint === 'object' && window.SailPoint.XSRF_TOKEN) {
            return window.SailPoint.XSRF_TOKEN;
        }
        var m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : null;
    }

    function api(method, path, body) {
        // No Accept: application/json header on purpose - with one, an expired
        // session sends IIQ into a redirect loop to exception.jsf and fetch
        // dies with ERR_TOO_MANY_REDIRECTS instead of something diagnosable.
        var opts = { method: method, headers: {}, credentials: 'same-origin', redirect: 'manual' };
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

    function load(quiet) {
        return api('GET', '/state').then(function (data) {
            state = data;
            render();
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

        root.appendChild(addForm());
        root.appendChild(overridesSection());
        root.appendChild(liveLoggersSection());
        root.appendChild(hostsSection());
        root.appendChild(footer());
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
            + 'watch Last sync in the Hosts table.';
        sync.onclick = function () {
            mutate(api('POST', '/sync'), 'This host reconciled against the stored configuration.');
        };
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
        box.appendChild(el('h2', 'tol-card-title', 'Turn on a logger'));

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
        var f5 = field('Note (optional)', 'Why this is on. Shows up in the table for whoever finds it later.');
        var note = document.createElement('input');
        note.type = 'text';
        note.id = 'tol-note';
        note.className = 'tol-input';
        note.setAttribute('placeholder', 'INC0012345 - provisioning failures to AD');
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
        head.appendChild(el('h2', 'tol-card-title', 'Overrides in effect'));
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
        t.appendChild(headRow(['Logger', 'Level', 'Hosts', 'Expires', 'Live on', 'Set by', '']));
        var tb = el('tbody');

        entries.forEach(function (e) {
            var tr = el('tr', e.expired ? 'tol-expired' : '');

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
                c5.appendChild(el('span', 'tol-small', 'no host has reported yet'));
            }
            tr.appendChild(c5);

            var c6 = el('td', 'tol-small');
            c6.appendChild(document.createTextNode(e.createdBy || '?'));
            if (!e.permanent) c6.appendChild(el('div', 'tol-small', fmtAgo(e.created)));
            tr.appendChild(c6);

            var c7 = el('td');
            if (e.permanent) {
                // Owned by the settings page, so there is nothing safe to
                // delete here - editing the setting is the way to turn it off.
                c7.appendChild(el('span', 'tol-small', 'edit in plugin settings'));
            } else {
                var del = el('button', 'tol-btn tol-btn-small tol-btn-danger', 'Turn off');
                del.onclick = function () {
                    mutate(api('DELETE', '/entries/' + encodeURIComponent(e.id)),
                        e.logger + ' reverted on this host; other hosts follow within a minute.');
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
    function hostChips(names) {
        var wrap = el('span', 'tol-hostchips');
        names.forEach(function (n) {
            var c = el('span', 'tol-hostchip', n);
            if (n === state.thisHost) c.className = 'tol-hostchip tol-hostchip-self';
            wrap.appendChild(c);
        });
        return wrap;
    }
    var liveFilter = 'all';

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
     *   this plugin       - an override currently managed here
     *   left over         - this plugin created it and lost track of it
     *   set at runtime    - something else set it: a rule, custom Java, a
     *                       connector. Never touched, never cleared.
     */
    function liveLoggersSection() {
        var box = el('section', 'tol-card');
        var head = el('div', 'tol-card-head');
        head.appendChild(el('h2', 'tol-card-title', 'Loggers live in the JVM'));

        var anyLeftover = false;
        (state.hosts || []).forEach(function (h) {
            (h.liveLoggers || []).forEach(function (r) {
                if (r.source === 'leftover') anyLeftover = true;
            });
        });
        if (anyLeftover) {
            var clr = el('button', 'tol-btn tol-btn-danger', 'Clear left over');
            clr.onclick = function () {
                if (!window.confirm('Remove the loggers this plugin left behind, on every host?'
                    + '\n\nOnly loggers this plugin created are removed. Anything in '
                    + 'log4j2.properties, and anything a rule or custom code set, is left alone.')) return;
                mutate(api('POST', '/cleanup'),
                    'Cleanup requested. This host is done; others follow on their next sync.');
            };
            head.appendChild(clr);
        }
        box.appendChild(head);
        box.appendChild(el('div', 'tol-hint',
            'Every logger log4j2 has configured on each host, and where it came from. '
            + '"set at runtime" means something outside this plugin set it, typically a rule '
            + 'calling Logger.getLogger(...).setLevel(...). Those are never touched or cleared. '
            + 'Counts are distinct logger names; the tables list them per host, so the same '
            + 'logger on several hosts appears once per host.'));

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

        // Distinct logger names, not one per host. The same logger on ten hosts
        // is one logger; counting rows made "Set at runtime (3)" mean two
        // loggers, which reads as a discrepancy against the tables below.
        var seen = { all: {}, file: {}, plugin: {}, leftover: {}, runtime: {}, unknown: {} };
        var hostsWith = { all: {}, file: {}, plugin: {}, leftover: {}, runtime: {}, unknown: {} };
        (state.hosts || []).forEach(function (h) {
            (h.liveLoggers || []).forEach(function (r) {
                seen.all[r.logger] = 1;
                hostsWith.all[h.name] = 1;
                if (seen[r.source]) {
                    seen[r.source][r.logger] = 1;
                    hostsWith[r.source][h.name] = 1;
                }
            });
        });
        var counts = {};
        var hostCounts = {};
        for (var k in seen) {
            if (seen.hasOwnProperty(k)) {
                counts[k] = Object.keys(seen[k]).length;
                hostCounts[k] = Object.keys(hostsWith[k]).length;
            }
        }
        var bar = el('div', 'tol-filters');
        [['all', 'All'], ['file', 'From the file (log4j2.properties)'], ['plugin', 'This plugin'],
            ['leftover', 'Left over'], ['runtime', 'Set at runtime']].forEach(function (f) {
            if (f[0] !== 'all' && !counts[f[0]]) return;
            var b = el('button', 'tol-filter' + (liveFilter === f[0] ? ' tol-filter-on' : ''),
                f[1] + ' (' + counts[f[0]] + ')');
            b.title = counts[f[0]] + ' distinct logger' + (counts[f[0]] === 1 ? '' : 's')
                + ' across ' + hostCounts[f[0]] + ' host' + (hostCounts[f[0]] === 1 ? '' : 's');
            b.onclick = (function (key) {
                return function () { liveFilter = key; render(); };
            })(f[0]);
            bar.appendChild(b);
        });
        box.appendChild(bar);

        // Hosts reporting an identical picture are grouped, so the one host
        // that differs stands out instead of being buried under repeats.
        var groups = [];
        (state.hosts || []).forEach(function (h) {
            var rows = (h.liveLoggers || []).filter(function (r) {
                return liveFilter === 'all' || r.source === liveFilter;
            });
            if (!rows.length) return;
            rows.sort(function (a, b) { return a.logger < b.logger ? -1 : 1; });
            var sig = rows.map(function (r) { return r.logger + '=' + r.level + '/' + r.source; }).join('|');
            var found = null;
            groups.forEach(function (g) { if (g.sig === sig) found = g; });
            if (found) { found.hosts.push(h.name); } else { groups.push({ sig: sig, hosts: [h.name], rows: rows }); }
        });

        if (!groups.length) {
            box.appendChild(el('div', 'tol-empty',
                'Nothing to show for this filter. Hosts report on their next sync tick.'));
            return box;
        }

        groups.forEach(function (g) {
            box.appendChild(hostChips(g.hosts));
            var t = el('table', 'tol-table');
            t.appendChild(headRow(['Logger', 'Level', 'Source', 'File says', '']));
            var tb = el('tbody');
            g.rows.forEach(function (r) {
                var tr = el('tr');

                var c1 = el('td');
                c1.appendChild(el('code', 'tol-logger-name', r.logger));
                tr.appendChild(c1);

                var c2 = el('td');
                c2.appendChild(el('span', 'tol-level tol-level-' + String(r.level).toLowerCase(), r.level));
                tr.appendChild(c2);

                var c3 = el('td', 'tol-small');
                c3.appendChild(el('span', 'tol-src tol-src-' + r.source, SRC[r.source] || r.source));
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

                var c5 = el('td');
                if (r.source === 'file' && r.logger !== 'root'
                        && String(r.level).toUpperCase() !== 'OFF') {
                    var silence = el('button', 'tol-btn tol-btn-small', 'Silence');
                    silence.title = 'Set ' + r.logger + ' to OFF on every host, permanently';
                    silence.onclick = (function (name, was) {
                        return function () {
                            if (!window.confirm('Silence ' + name + ' on all hosts?'
                                + '\n\nIt is set to ' + was + ' in log4j2.properties. This adds a '
                                + 'permanent OFF override that does not expire. The file itself is '
                                + 'not changed, and removing the override here puts it back.')) return;
                            mutate(api('POST', '/entries', {
                                logger: name, level: 'OFF', ttlMinutes: 0, hosts: ['*'],
                                note: 'silenced - was ' + was + ' in log4j2.properties'
                            }), name + ' silenced on every host.');
                        };
                    })(r.logger, r.level);
                    c5.appendChild(silence);
                }
                if (r.source === 'runtime' || r.source === 'leftover') {
                    // Deliberate, named removal. Bulk cleanup deliberately will
                    // not touch 'set at runtime' rows, because something else
                    // configured them - so removing one has to be an explicit
                    // choice about that specific logger.
                    var rm = el('button', 'tol-btn tol-btn-small tol-btn-danger', 'Remove');
                    rm.title = 'Remove ' + r.logger + ' from the live configuration on every host';
                    rm.onclick = (function (name, src) {
                        return function () {
                            var extra = src === 'runtime'
                                ? ' It was set by something other than this plugin, most likely a'
                                  + ' rule calling Logger.getLogger(...).setLevel(...). Removing it'
                                  + ' stops that logging until whatever set it runs again, or the'
                                  + ' host restarts.'
                                : ' This plugin created it and lost track of it, so removing it'
                                  + ' switches it off for good.';
                            if (!window.confirm('Remove ' + name + ' on every host?' + extra)) return;
                            mutate(api('POST', '/cleanup', { logger: name }),
                                name + ' removal requested. This host is done; others follow on their next sync.');
                        };
                    })(r.logger, r.source);
                    c5.appendChild(rm);
                }
                tr.appendChild(c5);
                tb.appendChild(tr);
            });
            t.appendChild(tb);
            box.appendChild(t);
        });
        return box;
    }


    function hostsSection() {
        var box = el('section', 'tol-card');
        box.appendChild(el('h2', 'tol-card-title', 'Hosts'));
        box.appendChild(el('div', 'tol-hint',
            'Every IIQ JVM reports its own OS, its log4j2 config file and where it writes logs. ' +
            'That is the host-specific part - the level change itself works the same everywhere.'));

        var hosts = state.hosts || [];
        if (!hosts.length) {
            box.appendChild(el('div', 'tol-empty', 'No hosts reported yet.'));
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
            if (!h.knownToIIQ) c1.appendChild(el('div', 'tol-small', 'no Server record'));
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
            } else if (h.stale) {
                c3.appendChild(el('span', 'tol-badge tol-badge-off', 'stale'));
            } else if (h.inSync) {
                c3.appendChild(el('span', 'tol-badge tol-badge-ok', 'in sync'));
            } else {
                c3.appendChild(el('span', 'tol-badge tol-badge-warn', 'catching up'));
                c3.appendChild(el('div', 'tol-small', 'host rev ' + h.revision + ' vs ' + state.revision));
            }
            var errs = h.errors || [];
            errs.forEach(function (m) { c3.appendChild(el('div', 'tol-err-text', m)); });
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
            pollTimer = window.setInterval(function () {
                // Do not yank the form out from under someone mid-type.
                if (document.hidden || formTouched) return;
                load(true);
            }, POLL_MS);
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { window.setTimeout(start, 0); });
    } else {
        window.setTimeout(start, 0);
    }
})();
