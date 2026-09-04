/*
 * Build gate: actually EXECUTE the plugin page script against a stub DOM.
 *
 * Why this exists: a parse-only check is not enough, and worse, it is easy to
 * get wrong. Releases 2.2.0 through 2.4.0 shipped a script with an unescaped
 * apostrophe in a string literal - the whole file failed to parse, so the page
 * never rendered and the Configure-page banner never appeared. It got through
 * because the check grepped stderr for the text "SyntaxError", which jjs does
 * not print when it runs a file directly. A silent false pass, three releases
 * in a row.
 *
 * So this harness runs the real script, in both the situations it has to work
 * in, and fails the build on any error:
 *
 *   1. the plugin full page  - the root div exists, the page must render
 *   2. the Configure page    - no root div, the banner must be inserted
 *
 * The plugin page is rendered once per state fixture, because the interesting
 * regressions are state-dependent: a log panel that renders fine with nothing
 * loaded can still throw the moment a host reports an error.
 *
 * Run: jjs tools/render-check.js -- ui/js/turnOnLoggers.js state1.json [state2.json ...]
 */

var scriptPath = arguments[0];
var statePaths = Array.prototype.slice.call(arguments, 1);

var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
function readFile(p) {
    return new java.lang.String(Files.readAllBytes(Paths.get(p)), 'UTF-8') + '';
}

var failures = [];

// ---- minimal Promise (synchronous; enough for a smoke test) ---------------
function P(fn) {
    var self = this;
    this._s = 'pending'; this._v = undefined; this._cbs = [];
    function res(v) {
        if (self._s !== 'pending') return;
        if (v && typeof v.then === 'function') { v.then(res, rej); return; }
        self._s = 'ok'; self._v = v;
        self._cbs.forEach(function (c) { if (c.ok) c.ok(v); });
    }
    function rej(e) {
        if (self._s !== 'pending') return;
        self._s = 'err'; self._v = e;
        self._cbs.forEach(function (c) { if (c.err) c.err(e); });
    }
    try { fn(res, rej); } catch (e) { rej(e); }
}
P.prototype.then = function (ok, err) {
    var self = this;
    return new P(function (res, rej) {
        function hOk(v) { try { res(ok ? ok(v) : v); } catch (e) { rej(e); } }
        function hErr(e) { if (err) { try { res(err(e)); } catch (e2) { rej(e2); } } else rej(e); }
        if (self._s === 'ok') hOk(self._v);
        else if (self._s === 'err') hErr(self._v);
        else self._cbs.push({ ok: hOk, err: hErr });
    });
};
P.prototype['catch'] = function (err) { return this.then(null, err); };
P.resolve = function (v) { return new P(function (r) { r(v); }); };

// ---- stub DOM -------------------------------------------------------------
function Elem(tag) {
    this.tagName = tag ? String(tag).toUpperCase() : undefined;
    this.children = []; this.style = {}; this.attrs = {};
    this.className = ''; this.id = ''; this.value = '';
    this.disabled = false; this.checked = false; this.firstChild = null;
    this.nodeValue = '';
}
Elem.prototype.appendChild = function (c) {
    if (c === undefined || c === null) { failures.push('appendChild(null) on <' + this.tagName + '>'); return c; }
    this.children.push(c); this.firstChild = this.children[0]; return c;
};
Elem.prototype.insertBefore = function (n) { this.children.unshift(n); this.firstChild = this.children[0]; return n; };
Elem.prototype.removeChild = function (c) {
    var i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1);
    this.firstChild = this.children.length ? this.children[0] : null; return c;
};
Elem.prototype.setAttribute = function (k, v) { this.attrs[k] = v; };
Elem.prototype.getAttribute = function (k) { return this.attrs[k]; };
Elem.prototype.addEventListener = function () {};
Elem.prototype.querySelector = function (s) { return String(s).indexOf('header') > -1 ? new Elem('header') : null; };
Elem.prototype.querySelectorAll = function () { return []; };
Elem.prototype.text = function () {
    var out = '';
    for (var i = 0; i < this.children.length; i++) {
        var c = this.children[i];
        if (c && c.tagName === undefined) out += String(c.nodeValue);
        else if (c && c.text) out += c.text() + ' ';
    }
    return out;
};
Elem.prototype.textLen = function () {
    var n = 0;
    for (var i = 0; i < this.children.length; i++) {
        var c = this.children[i];
        if (c && c.tagName === undefined) n += String(c.nodeValue).length;
        else if (c && c.textLen) n += c.textLen();
    }
    return n;
};

var src = readFile(scriptPath);
var stateJson = null;   // set per run

function run(scenario) {
    var rootEl = new Elem('div'); rootEl.id = 'turn-on-loggers-root';
    var mainEl = new Elem('div');
    var hasRoot = scenario.hasRoot;

    document = {
        readyState: 'complete', hidden: false, cookie: 'XSRF-TOKEN=t', title: 't',
        createElement: function (t) { return new Elem(t); },
        createTextNode: function (t) { var n = new Elem(); n.nodeValue = String(t); return n; },
        createComment: function () { return new Elem(); },
        getElementById: function (id) { return (hasRoot && id === 'turn-on-loggers-root') ? rootEl : null; },
        querySelector: function (s) { return String(s).indexOf('role="main"') > -1 ? mainEl : null; },
        querySelectorAll: function () { return []; },
        addEventListener: function () {},
        location: scenario.location
    };
    window = {
        SailPoint: { CONTEXT_PATH: '/identityiq', XSRF_TOKEN: 't' },
        setTimeout: function (fn) { fn(); return 1; },
        setInterval: function () { return 2; },
        clearInterval: function () {},
        confirm: function () { return true; },
        addEventListener: function () {},
        sessionStorage: { getItem: function () { return null; }, setItem: function () {} },
        location: scenario.location
    };
    fetch = function () {
        return new P(function (res) {
            res({
                type: 'basic', status: 200, ok: true,
                headers: { get: function () { return 'application/json'; } },
                text: function () { return P.resolve(stateJson); }
            });
        });
    };
    Promise = P;
    console = { log: function () {}, warn: function () {}, error: function () {} };

    try {
        load({ script: src, name: 'turnOnLoggers.js' });
    } catch (e) {
        failures.push(scenario.name + ': ' + e);
        return;
    }
    if (hasRoot) {
        if (scenario.logOnly) {
            // A popped-out window must show the log viewer and nothing else.
            // Anything more means a second set of controls that change shared
            // state, in a window the user opened to read output side by side.
            var ids = sectionIds(rootEl);
            if (ids.length !== 1 || ids[0] !== 'tol-sec-logs') {
                failures.push(scenario.name + ': expected only the log viewer, got ['
                    + ids.join(', ') + ']');
            } else {
                print('  ' + scenario.name + ': log viewer only, '
                    + rootEl.textLen() + ' chars');
            }
        } else if (rootEl.children.length < 2 || rootEl.textLen() < 500) {
            var shown = rootEl.text().replace(/\s+/g, ' ').trim();
            failures.push(scenario.name + ': page did not render (children='
                + rootEl.children.length + ', text=' + rootEl.textLen() + ')'
                + (shown ? '  |  page said: ' + shown.slice(0, 300) : ''));
        } else {
            print('  ' + scenario.name + ': rendered ' + rootEl.children.length
                + ' sections, ' + rootEl.textLen() + ' chars');
            checkPlacement(scenario.name, rootEl, stateJson);
        }
    } else {
        if (mainEl.children.length < 1) {
            failures.push(scenario.name + ': Configure-page banner was not inserted');
        } else {
            print('  ' + scenario.name + ': banner inserted');
        }
    }
}

/** The ids of every <section> the page put on the root, in order. */
function sectionIds(node, out) {
    out = out || [];
    if (!node || !node.children) return out;
    for (var i = 0; i < node.children.length; i++) {
        var c = node.children[i];
        if (c && c.tagName === 'SECTION' && c.id) out.push(c.id);
        sectionIds(c, out);
    }
    return out;
}

/**
 * Assert that things render in the section they belong to.
 *
 * Counting sections is not enough. The orphaned-host notice was added by
 * anchoring on "var all = sortedHosts()", which appears in three functions, so
 * it landed in All Logger Status instead of Host Status - and every count-based
 * check still passed, because it did render, just in the wrong card.
 */
function sectionText(rootEl, id) {
    for (var i = 0; i < rootEl.children.length; i++) {
        if (rootEl.children[i] && rootEl.children[i].id === id) {
            return rootEl.children[i].text();
        }
    }
    return null;
}

function checkPlacement(name, rootEl, json) {
    var state;
    try { state = JSON.parse(json); } catch (e) { return; }

    var orphans = state.orphanHosts || [];
    if (!orphans.length) return;

    var needle = orphans[0];

    // Host Status names it either way - as a row when they are shown, and in
    // the cleanup notice when they are not.
    var hostsText = sectionText(rootEl, 'tol-sec-hosts');
    if (hostsText !== null && hostsText.indexOf(needle) < 0) {
        failures.push(name + ': orphaned host "' + needle + '" is not named in Host Status');
    }

    if (state.showOrphans) {
        // Shown: the point is that it is reachable everywhere a host is picked,
        // so it must carry the label into those sections rather than appear
        // there as an ordinary host.
        ['tol-sec-live', 'tol-sec-logs'].forEach(function (id) {
            var t = sectionText(rootEl, id);
            if (t === null) return;
            if (t.indexOf(needle) < 0) {
                failures.push(name + ': orphaned host "' + needle + '" is missing from ' + id);
            }
        });
        if (hostsText !== null && hostsText.indexOf('orphaned') < 0) {
            failures.push(name + ': orphaned host "' + needle + '" is not labelled orphaned');
        }
    } else {
        // Not shown: it is a record, not a host, so nothing but the notice in
        // Host Status may mention it.
        ['tol-sec-form', 'tol-sec-collections', 'tol-sec-overrides',
         'tol-sec-live', 'tol-sec-logs', 'tol-sec-history'].forEach(function (id) {
            var t = sectionText(rootEl, id);
            if (t !== null && t.indexOf(needle) > -1) {
                failures.push(name + ': orphaned host "' + needle + '" leaked into ' + id);
            }
        });
    }
}

print('render-check:');
statePaths.forEach(function (sp) {
    stateJson = readFile(sp);
    run({
        name: 'plugin page [' + sp.replace(/^.*[\\/]/, '') + ']',
        hasRoot: true,
        location: { pathname: '/identityiq/plugins/pluginPage.jsf', search: '?pn=TurnOnLoggers', hash: '' }
    });
});
// The pop-out: same page, same script, ?tolview=logs in the URL.
stateJson = readFile(statePaths[statePaths.length - 1]);
run({
    name: 'popped-out log viewer',
    hasRoot: true,
    logOnly: true,
    location: {
        pathname: '/identityiq/plugins/pluginPage.jsf',
        search: '?pn=TurnOnLoggers&tolview=logs',
        hash: ''
    }
});

stateJson = readFile(statePaths[0]);
run({
    name: 'configure page',
    hasRoot: false,
    location: {
        pathname: '/identityiq/plugins/pluginConfig.jsf',
        search: '',
        hash: '#/configuration?pn=TurnOnLoggers&pid=0a3267509ffb186f819ffbafe1651cce'
    }
});

if (failures.length) {
    print('render-check FAILED:');
    for (var i = 0; i < failures.length; i++) print('  - ' + failures[i]);
    exit(1);
}
print('render-check passed');
