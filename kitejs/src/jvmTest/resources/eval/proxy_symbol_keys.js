// Symbol keyed data behind a proxy, mixed with Map and Set.
var ID = Symbol('id');
var TAGS = Symbol('tags');

function entity(name) {
  var store = {};
  store.name = name;
  store[ID] = name.length * 7;
  store[TAGS] = new Set(['base']);
  return new Proxy(store, {
    get: function (t, key) {
      if (key === 'describe') {
        return function () { return t.name + '#' + t[ID]; };
      }
      return t[key];
    },
    has: function (t, key) {
      return key === 'describe' || key in t;
    },
    ownKeys: function (t) {
      return Reflect.ownKeys(t);
    }
  });
}

var a = entity('alpha');
var b = entity('beta');

var idA = a[ID];
var idB = b[ID];
var describeA = a.describe();
var hasSymbol = ID in a;
var hasVirtual = 'describe' in a;
var hasMissing = 'nope' in a;

a[TAGS].add('extra');
var tags = Array.from(a[TAGS]).join(',');

var index = new Map();
index.set(a, 'first');
index.set(b, 'second');
var lookup = index.get(a) + '/' + index.get(b) + '/' + index.size;

var stringKeys = Object.keys(a).join(',');
var allKeys = Reflect.ownKeys(a).length;
var symbolCount = Object.getOwnPropertySymbols(a).length;

[idA, idB, describeA, hasSymbol, hasVirtual, hasMissing, tags, lookup, stringKeys, allKeys, symbolCount].join('|');
