// Records that build their fields the first time each one is read, using a generator as the source.
function* sourceRows() {
  yield { id: 1, kind: 'a', weight: 5 };
  yield { id: 2, kind: 'b', weight: 3 };
  yield { id: 3, kind: 'a', weight: 9 };
  yield { id: 4, kind: 'c', weight: 1 };
}

function lazyRecord(row, work) {
  var cache = {};
  return new Proxy(row, {
    get: function (t, key) {
      if (key in t) return t[key];
      if (Object.prototype.hasOwnProperty.call(cache, key)) return cache[key];
      if (Object.prototype.hasOwnProperty.call(work, key)) {
        work.calls.push(String(key) + '@' + t.id);
        cache[key] = work[key](t);
        return cache[key];
      }
      return undefined;
    },
    has: function (t, key) {
      return key in t || Object.prototype.hasOwnProperty.call(work, key);
    }
  });
}

var derived = {
  calls: [],
  label: function (r) { return r.kind.toUpperCase() + '-' + r.id; },
  heavy: function (r) { return r.weight * r.weight * 10; }
};

var records = [];
for (var row of sourceRows()) records.push(lazyRecord(row, derived));

var labels = records.map(function (r) { return r.label; }).join(',');
// Reading the same field twice does the work only once.
var labelsAgain = records.map(function (r) { return r.label; }).join(',');
var heavyForA = records.filter(function (r) { return r.kind === 'a'; }).map(function (r) { return r.heavy; }).join(',');
var totalWeight = records.reduce(function (acc, r) { return acc + r.weight; }, 0);
var hasDerived = 'heavy' in records[0];
var hasNothing = 'nope' in records[0];
var missing = String(records[0].nope);

[labels, labelsAgain, heavyForA, totalWeight, hasDerived, hasNothing, missing, derived.calls.length, derived.calls.join(' ')].join('|');
