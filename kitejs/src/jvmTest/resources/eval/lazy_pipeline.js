// A pull-based pipeline: nothing runs until the consumer asks for the next value.
function* range(start, end, step) {
  for (var i = start; i < end; i += (step || 1)) yield i;
}
function* map(it, f) {
  for (var v of it) yield f(v);
}
function* filter(it, pred) {
  for (var v of it) if (pred(v)) yield v;
}
function* take(it, n) {
  var i = 0;
  for (var v of it) {
    if (i++ >= n) return;
    yield v;
  }
}
function* zip(a, b) {
  var ia = a[Symbol.iterator]();
  var ib = b[Symbol.iterator]();
  while (true) {
    var ra = ia.next();
    var rb = ib.next();
    if (ra.done || rb.done) return;
    yield [ra.value, rb.value];
  }
}
function drain(it) {
  var out = [];
  for (var v of it) out.push(v);
  return out;
}

var calls = 0;
function counted(x) { calls++; return x * x; }

var squares = take(map(range(1, 1000), counted), 5);
var first = drain(squares).join();
var lazily = calls;

var evens = drain(take(filter(range(0, 100), function (n) { return n % 2 === 0; }), 6)).join();
var pairs = drain(zip(range(0, 4), 'abcd')).map(function (p) { return p.join(':'); }).join();
var spread = [...take(range(10, 100, 10), 4)].join();
var summed = drain(map(range(1, 6), function (n) { return n; })).reduce(function (a, b) { return a + b; }, 0);

// Rhino does not close a generator when a for-of loop breaks, so this finally never runs.
// The value stays at its starting point, which is what both engines have to agree on.
var closedAt = -1;
function* watched() {
  try {
    var i = 0;
    while (true) yield i++;
  } finally {
    closedAt = i;
  }
}
for (var v of watched()) if (v === 3) break;

// Delegation keeps the same laziness.
function* concat(a, b) { yield* a; yield* b; }
var joined = drain(concat(range(0, 3), range(10, 13))).join();

[first, lazily, evens, pairs, spread, summed, closedAt, joined].join('|');
