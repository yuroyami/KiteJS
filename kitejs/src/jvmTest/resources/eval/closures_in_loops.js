var fns = [];
for (var i = 0; i < 3; i++) fns.push(function () { return i; });
var lets = [];
for (let j = 0; j < 3; j++) lets.push(function () { return j; });
var iife = [];
for (var k = 0; k < 3; k++) iife.push((function (n) { return function () { return n; }; })(k));
var blocks = [];
for (var m = 0; m < 3; m++) { let captured = m * 10; blocks.push(function () { return captured; }); }
var accum = [];
[1, 2, 3].forEach(function (v) { accum.push(function () { return v * v; }); });
var results = [fns, lets, iife, blocks, accum].map(function (list) { return list.map(function (f) { return f(); }).join(','); });
var shared = 0;
function makeAdder() { return function () { return ++shared; }; }
var a1 = makeAdder(), a2 = makeAdder();
a1(); a2(); a1();
results.push(shared);
function once(f) { var done = false, r; return function () { if (!done) { done = true; r = f.apply(this, arguments); } return r; }; }
var init = once(function (x) { return 'init:' + x; });
results.push(init(1), init(2));
results.join('|');
