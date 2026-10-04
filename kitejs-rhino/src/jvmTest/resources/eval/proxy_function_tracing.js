// Counting calls without touching the functions themselves.
function traced(fn, log) {
  return new Proxy(fn, {
    apply: function (target, thisArg, args) {
      log.push('call ' + fn.name + '(' + args.join(',') + ')');
      var out = target.apply(thisArg, args);
      log.push('  -> ' + out);
      return out;
    }
  });
}

function add(a, b) { return a + b; }
function Point(x, y) { this.x = x; this.y = y; }
Point.prototype.norm = function () { return Math.sqrt(this.x * this.x + this.y * this.y); };

var traceLog = [];
var tracedAdd = traced(add, traceLog);

var sum = tracedAdd(2, 3);
var again = tracedAdd(sum, 5);

// A proxy with no construct trap still works with new: the target does the building.
var PlainPoint = new Proxy(Point, {});
var p = new PlainPoint(3, 4);
var norm = p.norm();
var isPoint = p instanceof Point;

// The proxy still looks like a function from the outside.
var kind = typeof tracedAdd;
var arity = tracedAdd.length;
var tracedName = tracedAdd.name;

// Reduce over a traced function, so it is called many times.
var total = [1, 2, 3, 4].reduce(function (acc, v) { return tracedAdd(acc, v); }, 0);

// The trap sees the receiver too.
var bag = { base: 100, plus: traced(function (n) { return this.base + n; }, traceLog) };
var withReceiver = bag.plus(5);

[sum, again, p.x, p.y, norm, isPoint, kind, arity, tracedName, total, withReceiver, traceLog.length].join('|');
