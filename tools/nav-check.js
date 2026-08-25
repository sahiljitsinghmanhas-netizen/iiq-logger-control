/*
 * Build gate: execute the header-icon snippet against a stub DOM.
 *
 * This script is unusual for this plugin in that it runs on every page of the
 * product, for users who are not looking at Logger Manager at all. A throw
 * here lands in somebody's console while they are doing something else, and
 * the existing render check cannot see it - that one drives
 * ui/js/turnOnLoggers.js and knows nothing about this file.
 *
 * The four things worth failing a build over:
 *
 *   1. it inserts exactly one item when there is a navbar
 *   2. it inserts nothing when there is no navbar (login, hideMenu pages)
 *   3. running it twice leaves one item, not two - IdentityIQ's Ext pages
 *      re-render, and a duplicated icon is the obvious way this breaks
 *   4. it draws nothing when the server says show:false, which is the whole
 *      point of the second gate
 *
 * Run: jjs tools/nav-check.js -- ui/js/snippets/header.js
 */

var scriptPath = arguments[0];

var Files = Java.type('java.nio.file.Files');
var Paths = Java.type('java.nio.file.Paths');
var source = new java.lang.String(Files.readAllBytes(Paths.get(scriptPath)), 'UTF-8') + '';

var failures = [];

// ---- stub DOM -------------------------------------------------------------
function Elem(tag) {
    this.tagName = String(tag).toUpperCase();
    this.children = [];
    this.attrs = {};
    this.id = '';
    this.className = '';
    this.title = '';
    this.href = '';
    this.firstChild = null;
}
Elem.prototype.appendChild = function (c) {
    if (c === undefined || c === null) {
        failures.push('appendChild(null) on <' + this.tagName + '>');
        return c;
    }
    this.children.push(c);
    if (!this.firstChild) this.firstChild = c;
    return c;
};
Elem.prototype.insertBefore = function (c, ref) {
    if (c === undefined || c === null) {
        failures.push('insertBefore(null) on <' + this.tagName + '>');
        return c;
    }
    var i = this.children.indexOf(ref);
    if (i < 0) i = 0;
    this.children.splice(i, 0, c);
    this.firstChild = this.children[0];
    return c;
};
Elem.prototype.setAttribute = function (k, v) { this.attrs[k] = v; };
Elem.prototype.getAttribute = function (k) { return this.attrs[k]; };

function collect(node, out) {
    out = out || [];
    // Text nodes are plain objects with no children of their own.
    if (!node || !node.children) return out;
    for (var i = 0; i < node.children.length; i++) {
        out.push(node.children[i]);
        collect(node.children[i], out);
    }
    return out;
}

// ---- one run --------------------------------------------------------------
// withBar   - whether the page has a right-hand navbar at all
// answer    - what GET /nav replies, or null to make the request fail
// runs      - how many times to execute the script against the same document
var lastHeaders = null;

// token: what window.SailPoint should carry, or null to make the script fall
// back to the cookie. cookie: what document.cookie should say.
function run(label, withBar, answer, runs, token, cookie) {
    lastHeaders = null;
    var bar = withBar ? new Elem('ul') : null;
    var listeners = [];
    var timeouts = [];

    document = {
        readyState: 'loading',
        createElement: function (t) { return new Elem(t); },
        createTextNode: function (t) { return { text: String(t) }; },
        addEventListener: function (ev, fn) { listeners.push({ ev: ev, fn: fn }); },
        querySelector: function (s) {
            return (bar && String(s).indexOf('navbar-right') > -1) ? bar : null;
        },
        getElementById: function (id) {
            if (!bar) return null;
            var all = collect(bar);
            for (var i = 0; i < all.length; i++) if (all[i].id === id) return all[i];
            return null;
        }
    };

    document.cookie = (cookie === undefined) ? '' : cookie;

    var store = {};
    // window.SailPoint is a FUNCTION in IIQ 8.5 carrying its properties on the
    // function object, not a plain object. The stub is a function for the same
    // reason: a typeof === 'object' guard has to fail here the way it fails in
    // the product.
    var sp = function () {};
    sp.CONTEXT_PATH = '/identityiq';
    if (token !== null && token !== undefined) sp.XSRF_TOKEN = token;
    window = {
        SailPoint: sp,
        sessionStorage: {
            getItem: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
            setItem: function (k, v) { store[k] = String(v); }
        }
    };

    setTimeout = function (fn) { timeouts.push(fn); return 1; };

    // Synchronous stand-in for fetch. Resolves immediately so the whole run
    // completes before this function returns.
    fetch = function (url, o) {
        lastHeaders = (o && o.headers) || {};
        return {
            then: function (ok) {
                var res = {
                    ok: answer !== null,
                    headers: { get: function () { return answer === null ? 'text/html' : 'application/json'; } },
                    json: function () { return answer; }
                };
                var v;
                try { v = ok(res); } catch (e) { failures.push(label + ': threw in fetch handler - ' + e); }
                return {
                    then: function (ok2) {
                        try { ok2(v); } catch (e) { failures.push(label + ': threw handling the answer - ' + e); }
                        return { 'catch': function () {} };
                    },
                    'catch': function () { return this; }
                };
            },
            'catch': function () { return this; }
        };
    };

    for (var r = 0; r < (runs || 1); r++) {
        try {
            load({ script: source, name: scriptPath });
        } catch (e) {
            failures.push(label + ': script threw on load - ' + e);
            return null;
        }
        // fire DOMContentLoaded, then drain the setTimeout(fn, 0) it schedules
        var pending = listeners.splice(0, listeners.length);
        for (var i = 0; i < pending.length; i++) {
            if (pending[i].ev === 'DOMContentLoaded') {
                try { pending[i].fn(); } catch (e) { failures.push(label + ': DOMContentLoaded threw - ' + e); }
            }
        }
        var due = timeouts.splice(0, timeouts.length);
        for (var j = 0; j < due.length; j++) {
            try { due[j](); } catch (e) { failures.push(label + ': deferred start threw - ' + e); }
        }
    }

    if (!bar) return 0;
    var items = 0;
    var all = collect(bar);
    for (var k = 0; k < all.length; k++) if (all[k].id === 'tol-nav-item') items++;
    return items;
}

// ---- the cases ------------------------------------------------------------
var n;

n = run('allowed', true, { show: true }, 1);
if (n !== 1) failures.push('show:true should insert exactly one item, inserted ' + n);

n = run('denied', true, { show: false }, 1);
if (n !== 0) failures.push('show:false must insert nothing, inserted ' + n);

n = run('no navbar', false, { show: true }, 1);
if (n !== 0) failures.push('a page with no navbar must be left alone');

n = run('session gone', true, null, 1);
if (n !== 0) failures.push('an HTML answer means the session expired - draw nothing, inserted ' + n);

n = run('re-render', true, { show: true }, 2);
if (n !== 1) failures.push('running twice must leave one item, left ' + n);

// ---- the CSRF token ------------------------------------------------------
// Sending no token gets GET /nav rejected with "CSRF validation failed" on any
// install that enforces it, and the icon then never appears. That shipped once:
// the token was read only off window.SailPoint, which does not carry it on
// ordinary product pages.
run('token from SailPoint', true, { show: true }, 1, 'from-sailpoint', '');
if (!lastHeaders || lastHeaders['X-XSRF-TOKEN'] !== 'from-sailpoint') {
    failures.push('should send the token window.SailPoint carries, sent ' +
                  (lastHeaders && lastHeaders['X-XSRF-TOKEN']));
}

run('token from CSRF-TOKEN cookie', true, { show: true }, 1, null, 'CSRF-TOKEN=from-cookie');
if (!lastHeaders || lastHeaders['X-XSRF-TOKEN'] !== 'from-cookie') {
    failures.push('with no token on window.SailPoint it must fall back to the ' +
                  'CSRF-TOKEN cookie, sent ' + (lastHeaders && lastHeaders['X-XSRF-TOKEN']));
}

run('token from XSRF-TOKEN cookie', true, { show: true }, 1, null, 'XSRF-TOKEN=other-name');
if (!lastHeaders || lastHeaders['X-XSRF-TOKEN'] !== 'other-name') {
    failures.push('the cookie is XSRF-TOKEN on some builds and must also be ' +
                  'accepted, sent ' + (lastHeaders && lastHeaders['X-XSRF-TOKEN']));
}

// ---- verdict --------------------------------------------------------------
if (failures.length) {
    print('nav-check FAILED:');
    for (var f = 0; f < failures.length; f++) print('  - ' + failures[f]);
    exit(1);
}
print('nav-check ok: icon appears when allowed, never otherwise, never twice, and always carries a CSRF token');
