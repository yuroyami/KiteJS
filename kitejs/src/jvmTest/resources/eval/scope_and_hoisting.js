var out = [];
out.push(typeof hoistedFn, typeof hoistedVar, hoistedVar);
var hoistedVar = 'set';
function hoistedFn() { return 'fn'; }
out.push(hoistedVar, hoistedFn());
try { tdz; } catch (e) { out.push(e.name); }
let tdz = 1;
try { eval('const c = 1; c = 2;'); } catch (e) { out.push(e.name); }
var shadow = 'outer';
function f() { var shadow = 'inner'; return shadow; }
out.push(f(), shadow);
{ let shadow = 'block'; out.push(shadow); }
out.push(shadow);
if (true) { var leaks = 'var leaks'; let stays = 'let stays'; }
out.push(leaks, typeof stays);
function g() { return typeof inner; function inner() {} }
out.push(g(), typeof inner);
var x = 1;
function h() { out.push(typeof x); var x = 2; return x; }
out.push(h(), x);
out.push(typeof undeclaredThing, (function () { try { return undeclaredThing; } catch (e) { return e.name; } })());
function params(a, a2) { var a = a || 'default'; return a + a2; }
out.push(params(undefined, '!'), params('given', '?'));
var fnExpr = function named() { return typeof named; };
out.push(fnExpr(), typeof named);
out.join('|');
