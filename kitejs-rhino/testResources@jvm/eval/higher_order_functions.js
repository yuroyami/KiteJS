function compose() { var fns = Array.prototype.slice.call(arguments); return function (x) { return fns.reduceRight(function (acc, f) { return f(acc); }, x); }; }
function pipe() { var fns = Array.prototype.slice.call(arguments); return function (x) { return fns.reduce(function (acc, f) { return f(acc); }, x); }; }
function curry(fn) {
  return function curried() {
    var args = Array.prototype.slice.call(arguments);
    if (args.length >= fn.length) return fn.apply(this, args);
    return function () { return curried.apply(this, args.concat(Array.prototype.slice.call(arguments))); };
  };
}
function memoize(fn) { var cache = {}, calls = 0; var m = function (x) { if (!(x in cache)) { calls++; cache[x] = fn(x); } return cache[x]; }; m.calls = function () { return calls; }; return m; }
function partial(fn) { var preset = Array.prototype.slice.call(arguments, 1); return function () { return fn.apply(this, preset.concat(Array.prototype.slice.call(arguments))); }; }
function debounceLike(fn) { var last; return function (x) { if (x === last) return 'skip'; last = x; return fn(x); }; }
var inc = function (x) { return x + 1; }, dbl = function (x) { return x * 2; }, sq = function (x) { return x * x; };
var add3 = curry(function (a, b, c) { return a + b + c; });
var slowSquare = memoize(function (x) { return x * x; });
slowSquare(4); slowSquare(4); slowSquare(5);
var greet = partial(function (greeting, name, punct) { return greeting + ', ' + name + punct; }, 'Hello');
var d = debounceLike(function (x) { return 'ran:' + x; });
[compose(inc, dbl)(5), pipe(inc, dbl)(5), compose(sq, inc, dbl)(2), add3(1)(2)(3), add3(1, 2)(3), add3(1)(2, 3), add3(1, 2, 3), slowSquare(4), slowSquare.calls(), greet('Kite', '!'), d(1), d(1), d(2),
  [1, 2, 3, 4].map(sq).filter(function (x) { return x % 2; }).reduce(function (a, b) { return a + b; }), ['a', 'b'].map(function (c, i) { return c + i; }).join(), typeof compose()].join('|');
