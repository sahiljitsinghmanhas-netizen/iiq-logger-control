/*
 * Logger Manager - header icon.
 *
 * Puts one icon in IdentityIQ's top-right navigation that opens the Logger
 * Manager page, so reaching it does not mean gear -> Plugins -> click through.
 *
 * There is no supported hook for this. sailpoint.object.Plugin has no menu
 * field and FullPage carries nothing but a title, so a plugin that wants a nav
 * item has to insert one itself. SailPoint's own Todo sample plugin does the
 * same thing the same way, which is the closest thing to a blessing available.
 *
 * WHO SEES IT - two independent gates, because an icon everybody can see and
 * nobody can use is worse than no icon:
 *
 *   1. The manifest declares this file inside a Snippet with
 *      rightRequired="ViewLoggerManagerIcon". IdentityIQ decides server-side
 *      whether to emit the <script> tag at all, so for everyone else this file
 *      is never sent, never parsed, and costs nothing. SystemAdministrator
 *      passes this without the right being granted to anything; everyone else
 *      needs it granted to a capability they hold.
 *   2. GET /nav confirms the same user also passes the plugin's own capability
 *      check and that showNavIcon is on. The two gates are different axes - a
 *      right is not a capability - and this one closes the gap where somebody
 *      holds the right but not the capability and would otherwise be handed a
 *      link to a 403.
 *
 * The answer is cached in sessionStorage, so this costs one small request per
 * browser session rather than one per page load.
 *
 * This file is deliberately standalone: no jQuery, nothing shared with
 * turnOnLoggers.js. It loads on every page in the product for the people who
 * can see it, so it stays small and it never throws.
 */
(function () {
    'use strict';

    var ITEM_ID = 'tol-nav-item';
    var CACHE_KEY = 'tol-nav-show';
    var LABEL = 'Logger Manager';
    var CTX = (window.SailPoint && typeof window.SailPoint === 'object'
               && window.SailPoint.CONTEXT_PATH) || '/identityiq';

    // The right-hand menubar in menu.xhtml. Shops re-skin IdentityIQ - colours,
    // logos, fonts - but they do not restructure this bootstrap navbar, so the
    // class is a safer anchor than any position or index would be. The second
    // selector is the looser form SailPoint's own sample uses.
    function navBar() {
        return document.querySelector('ul.nav.navbar-nav.navbar-right')
            || document.querySelector('ul.navbar-right');
    }

    function cacheGet() {
        try { return window.sessionStorage.getItem(CACHE_KEY); } catch (e) { return null; }
    }

    function cacheSet(v) {
        // Private browsing and some hardened configurations throw on write.
        // Losing the cache costs a request per page, not the feature.
        try { window.sessionStorage.setItem(CACHE_KEY, v); } catch (e) { /* ignore */ }
    }

    function insert(bar) {
        // The snippet runs on every page, and IdentityIQ's Ext pages re-render
        // parts of themselves. Without this guard a long-lived page could
        // collect a row of identical icons.
        if (document.getElementById(ITEM_ID)) return;

        var li = document.createElement('li');
        li.id = ITEM_ID;
        li.setAttribute('role', 'presentation');

        var a = document.createElement('a');
        a.className = 'menuitem';
        a.setAttribute('role', 'menuitem');
        a.href = CTX + '/plugins/pluginPage.jsf?pn=TurnOnLoggers';
        a.title = LABEL;

        // The mark is the word, not a picture: this plugin drives log4j2, and
        // no Font Awesome glyph says "log levels" without the reader guessing.
        // Styling lives in ui/css/snippets/header.css, which inherits
        // currentColor so it follows whatever the shop has skinned the navbar.
        var mark = document.createElement('span');
        mark.className = 'tol-nav-word';
        mark.setAttribute('aria-hidden', 'true');
        mark.appendChild(document.createTextNode('log4j'));
        a.appendChild(mark);

        var sr = document.createElement('span');
        sr.className = 'sr-only';
        sr.appendChild(document.createTextNode(LABEL));
        a.appendChild(sr);

        li.appendChild(a);

        // First position in the right-hand group, which is where SailPoint's
        // sample puts its own. Anything else that inserts there lands beside
        // this rather than being displaced by it.
        if (bar.firstChild) bar.insertBefore(li, bar.firstChild);
        else bar.appendChild(li);
    }

    function ask(bar) {
        var url = CTX + '/plugin/rest/TurnOnLoggers/nav';
        var opts = { credentials: 'same-origin', headers: {} };

        // Deliberately no "Accept: application/json". IdentityIQ answers an
        // expired session by redirecting to exception.jsf, and that header
        // turns the redirect into a loop rather than a page.
        var token = (window.SailPoint && window.SailPoint.XSRF_TOKEN) || null;
        if (token) opts.headers['X-XSRF-TOKEN'] = token;

        fetch(url, opts).then(function (r) {
            var ct = r.headers && r.headers.get ? (r.headers.get('content-type') || '') : '';
            // HTML back means the session went away and this is the login page.
            // Say nothing and draw nothing.
            if (!r.ok || ct.indexOf('json') === -1) return null;
            return r.json();
        }).then(function (d) {
            if (!d) return;
            if (d.show) { cacheSet('y'); insert(bar); }
            else cacheSet('n');
        })['catch'](function () { /* never break the page we are a guest on */ });
    }

    function start() {
        // No navbar on the login page, on pages rendered with hideMenu, or
        // inside the plugin page's own iframe-less include. Nothing to do -
        // and never build a bar that IdentityIQ chose not to draw.
        var bar = navBar();
        if (!bar) return;
        if (document.getElementById(ITEM_ID)) return;

        var known = cacheGet();
        if (known === 'n') return;
        if (known === 'y') { insert(bar); return; }
        ask(bar);
    }

    // Snippet scripts are injected during <head> parsing, so <body> does not
    // exist yet and any synchronous DOM work here silently does nothing.
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { setTimeout(start, 0); });
    } else {
        setTimeout(start, 0);
    }
}());
