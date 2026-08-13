/*
 * Logger Control - plugin page UI.
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
        root.appendChild(hostsSection());
        root.appendChild(footer());
    }

    function header() {
        var h = el('div', 'tol-header');
        var left = el('div', 'tol-header-left');
        left.appendChild(el('h1', 'tol-title', 'Logger Control'));
        var facts = state.thisHostFacts || {};
        var sub = el('div', 'tol-subtitle');
        sub.appendChild(document.createTextNode(
            'Turn IIQ loggers on and off across every host from here. No shell, no log4j2.properties edit, no restart.'));
        left.appendChild(sub);
        var chips = el('div', 'tol-chips');
        chips.appendChild(chip('Serving host', state.thisHost));
        chips.appendChild(chip(OS_ICON[facts.osFamily] || 'OS', facts.os || '?'));
        chips.appendChild(chip('Config revision', String(state.revision)));
        left.appendChild(chips);
        h.appendChild(left);

        var right = el('div', 'tol-header-right');
        var sync = el('button', 'tol-btn', 'Sync this host now');
        sync.onclick = function () {
            mutate(api('POST', '/sync'), 'This host reconciled against the stored configuration.');
        };
        right.appendChild(sync);

        var panic = el('button', 'tol-btn tol-btn-danger', 'Turn everything off');
        panic.onclick = function () {
            if (!window.confirm('Remove every logger override on every host?')) return;
            mutate(api('DELETE', '/entries'), 'All overrides removed. Other hosts revert on their next sync.');
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

        // logger name, with the catalog as suggestions
        var f1 = field('Logger', 'The Java class or package to trace, e.g. sailpoint.api.Provisioner');
        var input = document.createElement('input');
        input.type = 'text';
        input.id = 'tol-logger';
        input.className = 'tol-input';
        input.setAttribute('list', 'tol-catalog');
        input.setAttribute('placeholder', 'sailpoint.api.Provisioner');
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
        var f3 = field('Turn off after', 'Overrides expire on their own so nobody has to remember.');
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
        if (!(max > 0)) ttl.appendChild(opt('0', 'never (permanent)', false));
        f3.appendChild(ttl);
        form.appendChild(f3);

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
        head.appendChild(el('h2', 'tol-card-title', 'Loggers currently on'));
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
            c1.appendChild(el('strong', '', h.name));
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
        var bits = [];
        bits.push('Turning a logger on adds to the list - it never disturbs the other overrides, ' +
            'and never touches loggers configured in log4j2.properties.');
        bits.push('Levels are set in each JVM\'s live log4j2 runtime, so no file on any host is modified; ' +
            'the list is stored in the Custom object "TurnOnLoggers Configuration" and re-applied after a restart.');
        f.appendChild(document.createTextNode(bits.join(' ')));
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

    // ------------------------------------------------------------------
    // navigation link
    //
    // IIQ gives plugin full pages no menu entry of their own - sailpoint.plugin
    // .FullPage carries a title and nothing else - so without this the page is
    // reachable only by URL or via gear -> Plugins. This snippet already loads
    // on every IIQ page, so it adds the link itself.
    //
    // Done in the DOM rather than by editing the UIConfig object on purpose:
    // UIConfig is a singleton, so a plugin that shipped one would overwrite
    // whatever navigation customisation the customer already has.
    // ------------------------------------------------------------------

    var PAGE_URL = CTX + '/plugins/pluginPage.jsf?pn=TurnOnLoggers';
    var ACCESS_KEY = 'tol.access';

    function addNavLink() {
        if (document.getElementById('tol-nav-item')) return;
        // Matches IIQ's own top-level items (see the "Home" entry). If a future
        // IIQ release restyles the navbar this simply finds nothing and the
        // link is skipped - it must never break the page it is injected into.
        var bar = document.querySelector('ul.nav.navbar-nav.navbar-left');
        if (!bar) return;

        var li = document.createElement('li');
        li.id = 'tol-nav-item';
        li.setAttribute('role', 'presentation');

        var a = document.createElement('a');
        a.href = PAGE_URL;
        a.className = 'menuitem';
        a.setAttribute('role', 'menuitem');
        a.appendChild(document.createTextNode('Logger Control'));

        li.appendChild(a);
        bar.appendChild(li);
    }

    function ensureNavLink() {
        // Cached per session so this costs one small request per user login,
        // not one per page view.
        var cached = null;
        try {
            cached = window.sessionStorage && window.sessionStorage.getItem(ACCESS_KEY);
        } catch (e) {
            cached = null; // private mode / storage disabled
        }
        if (cached === '1') { addNavLink(); return; }
        if (cached === '0') { return; }

        api('GET', '/access').then(function (d) {
            var ok = !!(d && d.allowed);
            try {
                if (window.sessionStorage) window.sessionStorage.setItem(ACCESS_KEY, ok ? '1' : '0');
            } catch (e) { /* not cacheable, will re-probe next page */ }
            if (ok) addNavLink();
        }).catch(function () {
            // Not logged in, plugin disabled, session expired - no link, no noise.
        });
    }

    function start() {
        ensureNavLink();

        root = document.getElementById(ROOT_ID);
        if (!root) return; // not our page - every other IIQ page lands here

        clear(root);
        root.appendChild(el('div', 'tol-boot', 'Loading logger control...'));

        load().then(function () {
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
