function f(a, b = a * 2, c = a + b) { return [a, b, c].join(); }
function g(x = 'default') { return x; }
function h(...items) { return items.length + ':' + items.join('-'); }
function k(first, ...others) { return first + '/' + others.length; }
function count() { return arguments.length; }
var sideEffects = [];
function s(a = sideEffects.push('a'), b = sideEffects.push('b')) { return a + b; }
var out = [
  f(1), f(1, 5), f(1, undefined, 9), g(), g(undefined), g(null), g(0),
  h(), h(1), h(1, 2, 3), k('x'), k('x', 1, 2), count(1, 2, 3), count(),
  s(), s(10), sideEffects.join(), f.length, g.length, h.length, k.length,
  (function (a, b = 1, c) {}).length,
  [1, 2, 3].map(function (x, i = 100) { return x + i; }).join()
];
out.join('|');
