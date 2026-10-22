// Independent counters, each with its own captured state.
function makeCounter(start) {
  var count = start;
  return {
    inc: function () { return ++count; },
    dec: function () { return --count; },
    get: function () { return count; }
  };
}
var a = makeCounter(10), b = makeCounter(100);
a.inc(); a.inc(); b.dec();
var log = [];
log.push(a.get(), b.get(), a.inc() + b.inc());
var adders = [];
for (var i = 0; i < 3; i++) {
  adders.push((function (n) { return function (x) { return x + n; }; })(i));
}
log.push(adders[0](10), adders[1](10), adders[2](10));
log.join(',');
