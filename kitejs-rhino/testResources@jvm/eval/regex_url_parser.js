// Splits a URL into its parts, then puts a few back together.
var URL_RE = /^(?<scheme>[a-z][a-z0-9+.-]*):\/\/(?:(?<user>[^:@\/]+)(?::(?<pass>[^@\/]*))?@)?(?<host>[^:\/?#]+)(?::(?<port>\d+))?(?<path>\/[^?#]*)?(?:\?(?<query>[^#]*))?(?:#(?<hash>.*))?$/;
function parse(url) {
  var m = URL_RE.exec(url);
  if (m === null) return null;
  var g = m.groups;
  var out = [];
  var keys = ['scheme', 'user', 'pass', 'host', 'port', 'path', 'query', 'hash'];
  for (var i = 0; i < keys.length; i++) out.push(keys[i] + '=' + (g[keys[i]] === undefined ? '' : g[keys[i]]));
  return out.join(' ');
}
var a = parse('https://ann:secret@example.com:8443/a/b?x=1&y=2#frag');
var b = parse('http://example.com');
var c = parse('not a url');

function parseQuery(q) {
  var out = {}, re = /([^&=]+)=([^&]*)/g, m;
  while ((m = re.exec(q)) !== null) out[decodeURIComponent(m[1])] = decodeURIComponent(m[2]);
  return out;
}
var q = parseQuery('a=1&b=two&c=%20sp%20&a=again');

// Normalising a path by folding away . and .. segments.
function normalize(path) {
  var parts = path.split('/'), out = [];
  for (var i = 0; i < parts.length; i++) {
    if (parts[i] === '' || parts[i] === '.') continue;
    if (parts[i] === '..') { out.pop(); continue; }
    out.push(parts[i]);
  }
  return '/' + out.join('/');
}

[a, b, c, JSON.stringify(q), normalize('/a/b/../c/./d//e'), 'a/b'.replace(/\//g, '::')].join('|');
