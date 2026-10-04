// A dictionary that answers a default instead of undefined, and hides its bookkeeping keys.
function defaultDict(defaultValue) {
  var store = {};
  return new Proxy(store, {
    get: function (t, key) {
      if (key in t) return t[key];
      if (typeof key === 'string' && key.charAt(0) === '_') return undefined;
      return defaultValue;
    },
    has: function (t, key) {
      return typeof key === 'string' && key.charAt(0) === '_' ? false : true;
    },
    ownKeys: function (t) {
      var out = [];
      var all = Object.keys(t);
      for (var i = 0; i < all.length; i++) if (all[i].charAt(0) !== '_') out.push(all[i]);
      return out;
    }
  });
}

var counts = defaultDict(0);
var words = 'the cat sat on the mat the end'.split(' ');
for (var i = 0; i < words.length; i++) counts[words[i]] = counts[words[i]] + 1;
counts._internal = 'hidden';

var the = counts['the'];
var missing = counts['zebra'];
var hiddenRead = String(counts._internal);
var hiddenHas = '_internal' in counts;
var normalHas = 'cat' in counts;
var keys = Object.keys(counts).sort().join(',');

var totals = [];
var sorted = Object.keys(counts).sort();
for (var j = 0; j < sorted.length; j++) totals.push(sorted[j] + ':' + counts[sorted[j]]);

[the, missing, hiddenRead, hiddenHas, normalHas, keys, totals.join(' ')].join('|');
