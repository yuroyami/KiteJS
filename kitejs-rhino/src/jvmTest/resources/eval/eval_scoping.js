var out = [];
var g = 'global';
function direct() { var local = 'local'; return eval('local + g'); }
function indirect() { var local = 'local'; var e = eval; try { return e('local'); } catch (err) { return err.name; } }
function declares() { eval('var created = 1'); return typeof created; }
function strictDeclares() { 'use strict'; eval('var isolated = 1'); return typeof isolated; }
function evalThis() { return eval('this') === this; }
function returnsLast() { return eval('1; 2; var z = 3;'); }
function evalFn() { return eval('(function (a) { return a * 2 })')(21); }
function nestedEval() { return eval('eval("1 + 1")'); }
out.push(direct(), indirect(), declares(), strictDeclares(), evalThis.call({ a: 1 }), returnsLast(), evalFn(), nestedEval(), eval('g'), eval(), eval(5), eval('({a: 1})').a, eval('[1,2,3]').length, typeof eval('function inner() {}'));
try { eval('var = 1'); } catch (e) { out.push(e.name); }
try { eval('throw new RangeError("from eval")'); } catch (e) { out.push(e.name + ':' + e.message); }
var counter = 0;
eval('counter++; counter += 2');
out.push(counter, (0, eval)('typeof g'), new Function('a', 'b', 'return a + b')(1, 2), Function('return this')() === globalThis);
out.join('|');
