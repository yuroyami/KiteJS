function pretty(v, indent) {
  indent = indent || '';
  var next = indent + '  ';
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (Array.isArray(v)) {
    if (!v.length) return '[]';
    return '[\n' + v.map(function (x) { return next + pretty(x, next); }).join(',\n') + '\n' + indent + ']';
  }
  var keys = Object.keys(v).filter(function (k) { return v[k] !== undefined && typeof v[k] !== 'function'; });
  if (!keys.length) return '{}';
  return '{\n' + keys.map(function (k) { return next + JSON.stringify(k) + ': ' + pretty(v[k], next); }).join(',\n') + '\n' + indent + '}';
}
var doc = { name: 'doc', items: [1, 'two', { three: 3, four: [4, { five: 5 }] }, []], empty: {}, flag: false, none: null, skip: undefined, f: function () {} };
var mine = pretty(doc);
var theirs = JSON.stringify(doc, null, 2);
[mine === theirs, mine.split('\n').length, mine].join('|');
