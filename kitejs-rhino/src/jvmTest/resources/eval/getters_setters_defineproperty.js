var temp = { _c: 25, get f() { return this._c * 9 / 5 + 32; }, set f(v) { this._c = (v - 32) * 5 / 9; } };
temp.f = 212;
var o = {};
Object.defineProperty(o, 'ro', { value: 1, writable: false, enumerable: true, configurable: false });
Object.defineProperty(o, 'hidden', { value: 2, enumerable: false });
Object.defineProperty(o, 'acc', { get: function () { return 'got'; }, enumerable: true, configurable: true });
Object.defineProperties(o, { p: { value: 'p', enumerable: true }, q: { value: 'q' } });
o.ro = 99;
delete o.ro;
var d = Object.getOwnPropertyDescriptor(o, 'ro');
var out = [temp._c, temp.f, Object.keys(temp).join(), o.ro, o.hidden, o.acc, Object.keys(o).join(), Object.getOwnPropertyNames(o).join(), JSON.stringify(d), JSON.stringify(Object.getOwnPropertyDescriptor(o, 'acc').get === undefined)];
var errors = [];
(function () {
  'use strict';
  try { o.ro = 5; } catch (e) { errors.push(e.name); }
  try { delete o.ro; } catch (e) { errors.push(e.name); }
  try { o.acc = 1; } catch (e) { errors.push(e.name); }
  try { Object.defineProperty(o, 'ro', { value: 3 }); } catch (e) { errors.push(e.name); }
  var frozen = Object.freeze({ a: 1 });
  try { frozen.a = 2; } catch (e) { errors.push(e.name); }
  try { frozen.b = 2; } catch (e) { errors.push(e.name); }
  var sealed = Object.seal({ a: 1 });
  sealed.a = 2;
  try { sealed.b = 1; } catch (e) { errors.push(e.name); }
  errors.push(sealed.a);
})();
out.push(errors.join());
var counter = { count: 0 };
Object.defineProperty(counter, 'next', { get: function () { return ++this.count; } });
out.push(counter.next, counter.next, counter.count, JSON.stringify(counter));
out.join('|');
