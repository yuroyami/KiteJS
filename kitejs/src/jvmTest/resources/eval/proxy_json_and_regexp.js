// A proxy that renames fields on the way out, then feeds the result to JSON and a regexp.
function renaming(target, map) {
  var reverse = {};
  for (var k in map) reverse[map[k]] = k;
  return new Proxy(target, {
    get: function (t, key) {
      var real = Object.prototype.hasOwnProperty.call(reverse, key) ? reverse[key] : key;
      return t[real];
    },
    has: function (t, key) {
      var real = Object.prototype.hasOwnProperty.call(reverse, key) ? reverse[key] : key;
      return real in t;
    },
    ownKeys: function (t) {
      var out = [];
      var keys = Object.keys(t);
      for (var i = 0; i < keys.length; i++) out.push(Object.prototype.hasOwnProperty.call(map, keys[i]) ? map[keys[i]] : keys[i]);
      return out;
    },
    getOwnPropertyDescriptor: function (t, key) {
      var real = Object.prototype.hasOwnProperty.call(reverse, key) ? reverse[key] : key;
      var d = Object.getOwnPropertyDescriptor(t, real);
      if (d === undefined) return undefined;
      d.configurable = true;
      return d;
    }
  });
}

var raw = { first_name: 'ada', last_name: 'lovelace', born_year: 1815 };
var nice = renaming(raw, { first_name: 'firstName', last_name: 'lastName', born_year: 'bornYear' });

var firstName = nice.firstName;
var oldNameGone = String(nice.first_name);
var hasNew = 'lastName' in nice;
var keys = Object.keys(nice).join(',');
var json = JSON.stringify(nice);

var snakeCount = (JSON.stringify(raw).match(/_/g) || []).length;
var camelCount = (json.match(/[a-z][A-Z]/g) || []).length;
var years = json.match(/\d{4}/);
var upperFirst = json.replace(/"([a-z])/g, function (m, c) { return '"' + c.toUpperCase(); });

[firstName, oldNameGone, hasNew, keys, json, snakeCount, camelCount, years[0], upperFirst].join('|');
