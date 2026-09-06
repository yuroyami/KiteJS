// Python style negative indexing on top of a normal array.
function withNegativeIndex(arr) {
  return new Proxy(arr, {
    get: function (t, key) {
      if (typeof key === 'string' && /^-\d+$/.test(key)) {
        var i = t.length + parseInt(key, 10);
        return i >= 0 ? t[i] : undefined;
      }
      return t[key];
    },
    set: function (t, key, value) {
      if (typeof key === 'string' && /^-\d+$/.test(key)) {
        t[t.length + parseInt(key, 10)] = value;
        return true;
      }
      t[key] = value;
      return true;
    }
  });
}

var list = withNegativeIndex([10, 20, 30, 40, 50]);
var last = list[-1];
var secondLast = list[-2];
var tooFar = String(list[-99]);
var first = list[0];
var len = list.length;

list[-1] = 500;
list[1] = 200;
list.push(60);

var isArray = Array.isArray(list);
var joined = list.join(',');
var mapped = list.map(function (v) { return v / 10; }).join(',');
var sliced = list.slice(1, 3).join(',');
var found = list.indexOf(200);

[last, secondLast, tooFar, first, len, isArray, joined, mapped, sliced, found].join('|');
