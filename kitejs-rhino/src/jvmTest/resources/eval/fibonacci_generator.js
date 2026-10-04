// Several ways to produce the same sequence, so the generator can be checked against them.
function* fib() {
  var a = 0, b = 1;
  while (true) {
    yield a;
    var next = a + b;
    a = b;
    b = next;
  }
}
function fibArray(n) {
  var out = [0, 1];
  while (out.length < n) out.push(out[out.length - 1] + out[out.length - 2]);
  return out.slice(0, n);
}
function fibRecursive(n) {
  return n < 2 ? n : fibRecursive(n - 1) + fibRecursive(n - 2);
}

function takeN(it, n) {
  var out = [];
  for (var v of it) {
    if (out.length >= n) break;
    out.push(v);
  }
  return out;
}

var fromGenerator = takeN(fib(), 15);
var fromArray = fibArray(15);
var same = fromGenerator.join() === fromArray.join();
var recursiveMatch = fromGenerator.slice(0, 12).every(function (v, i) { return v === fibRecursive(i); });

// Two iterators over the same generator function are independent.
var a = fib();
var b = fib();
a.next(); a.next(); a.next();
var independent = a.next().value + ':' + b.next().value;

// Sending a value in restarts the sequence.
function* resettable() {
  var a = 0, b = 1;
  while (true) {
    var reset = yield a;
    if (reset !== undefined) { a = reset; b = 1; continue; }
    var next = a + b;
    a = b;
    b = next;
  }
}
var r = resettable();
var before = [r.next().value, r.next().value, r.next().value, r.next().value].join();
var afterReset = r.next(100).value;
var thenOn = [r.next().value, r.next().value].join();

// Big values stay exact until doubles run out of integer precision.
var big = takeN(fib(), 79);
var last = big[big.length - 1];
var exact = last === 14472334024676221;

[fromGenerator.join(), same, recursiveMatch, independent, before, afterReset, thenOn, big.length, last, exact].join('|');
