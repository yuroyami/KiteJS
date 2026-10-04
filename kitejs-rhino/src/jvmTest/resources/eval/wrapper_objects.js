var n = new Number(5), s = new String('str'), b = new Boolean(false);
var prim = 5;
prim.prop = 'lost';
var out = [typeof n, typeof s, typeof b, n + 1, s + '!', b ? 'truthy' : 'falsy', n == 5, n === 5, s == 'str', s === 'str', b == false, b === false, !b, !!b,
  n.valueOf() === 5, s.valueOf() === 'str', b.valueOf() === false, n instanceof Number, (5) instanceof Number, Object(5) instanceof Number, typeof Object('x'), Object(true).valueOf(),
  prim.prop, s.length, s[0], s.charAt(1), Object.keys(s).join(), JSON.stringify([n, s, b]), n.toFixed(1), new Number(1) + new Number(2), new String('a') + new String('b'), new Boolean(false) + 1,
  Number(new Number(3)) === 3, String(new String('x')) === 'x', new Number(NaN) == NaN, [new Number(1)].indexOf(1), [1].indexOf(new Number(1)), new String('ab') == new String('ab'), Object.prototype.toString.call(n), Object.prototype.toString.call(s), Object.prototype.toString.call(b),
  (function () { 'use strict'; return typeof this; }).call(5), (function () { return typeof this; }).call(5), (function () { return this instanceof Number; }).call(5)];
out.join('|');
