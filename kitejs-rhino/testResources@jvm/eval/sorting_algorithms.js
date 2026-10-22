var input = [33, 7, -2, 19, 7, 0, 100, -50, 42, 3, 8, 8, 1];
function bubble(a) {
  a = a.slice();
  for (var i = 0; i < a.length; i++)
    for (var j = 0; j < a.length - i - 1; j++)
      if (a[j] > a[j + 1]) { var t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
  return a;
}
function insertion(a) {
  a = a.slice();
  for (var i = 1; i < a.length; i++) {
    var v = a[i], j = i - 1;
    while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j--; }
    a[j + 1] = v;
  }
  return a;
}
function quick(a) {
  if (a.length <= 1) return a;
  var pivot = a[0], left = [], right = [];
  for (var i = 1; i < a.length; i++) (a[i] < pivot ? left : right).push(a[i]);
  return quick(left).concat([pivot], quick(right));
}
function merge(a) {
  if (a.length <= 1) return a;
  var mid = a.length >> 1;
  var l = merge(a.slice(0, mid)), r = merge(a.slice(mid)), out = [];
  while (l.length && r.length) out.push(l[0] <= r[0] ? l.shift() : r.shift());
  return out.concat(l, r);
}
var builtin = input.slice().sort(function (x, y) { return x - y; });
var lexical = input.slice().sort();
var all = [bubble(input), insertion(input), quick(input), merge(input)];
var agree = all.every(function (a) { return a.join() === builtin.join(); });
[builtin.join(' '), lexical.join(' '), agree, input.join(' ')].join('|');
