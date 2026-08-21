/*
 * Pulls isUntouchable/escapeRe out of the shipped page script and exercises
 * them against the same cases as GlobTest.java, so the greying the page does
 * and the refusal the API does cannot drift apart unnoticed.
 */
var path = arguments[0];
var src = new java.lang.String(
    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
    java.nio.charset.StandardCharsets.UTF_8) + '';

function extract(name) {
    var start = src.indexOf('function ' + name + '(');
    if (start < 0) throw 'could not find function ' + name;
    var depth = 0, i = src.indexOf('{', start);
    var open = i;
    for (; i < src.length; i++) {
        if (src.charAt(i) === '{') depth++;
        else if (src.charAt(i) === '}') { depth--; if (depth === 0) break; }
    }
    return src.substring(start, i + 1);
}

var state = { untouchableLoggers: [] };
eval(extract('escapeRe'));
eval(extract('isUntouchable'));

var failed = 0;
function check(patterns, name, want) {
    state.untouchableLoggers = patterns;
    var got = isUntouchable(name);
    if (got !== want) {
        failed++;
        print('  FAIL  [' + patterns + '] vs "' + name + '" = ' + got + ', wanted ' + want);
    } else {
        print('  ok    [' + patterns + '] vs "' + name + '" = ' + got);
    }
}

check(['sailpoint'], 'sailpoint', true);
check(['sailpoint'], 'sailpoint.api.Provisioner', false);
check(['root'], 'root', true);
check(['root'], 'rooted', false);
check(['sailpoint.*'], 'sailpoint.api.Provisioner', true);
check(['sailpoint.*'], 'sailpoint.connector.LDAPConnector', true);
check(['sailpoint.*'], 'sailpoint', false);
check(['sailpoint.*'], 'org.hibernate.SQL', false);
check(['*.Provisioner'], 'sailpoint.api.Provisioner', true);
check(['*.Provisioner'], 'sailpoint.api.Provisioners', false);
check(['sailpoint.*.Provisioner'], 'sailpoint.api.Provisioner', true);
check(['*'], 'anything.at.all', true);
check(['sailpoint.api'], 'sailpointXapi', false);
check(['rule.my(rule)'], 'rule.my(rule)', true);
check(['rule.my(rule)'], 'rule.myrule', false);
check(['root', 'sailpoint.*'], 'sailpoint.api.Provisioner', true);
check(['root', 'sailpoint.*'], 'org.hibernate.SQL', false);
check([], 'sailpoint', false);
check(['sailpoint'], null, false);

print(failed === 0 ? 'ALL PASS' : failed + ' FAILURES');
if (failed) { java.lang.System.exit(1); }
