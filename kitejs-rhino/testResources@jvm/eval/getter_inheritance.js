function Base() { this._v = 1; }
Object.defineProperty(Base.prototype, 'value', {
  get: function () { return this._v; },
  set: function (v) { this._v = v; },
  configurable: true
});
Object.defineProperty(Base.prototype, 'doubled', { get: function () { return this.value * 2; } });
Object.defineProperty(Base, 'kind', { get: function () { return 'base'; } });
Base.prototype.describe = function () { return this.constructor.name + ':' + this.value; };
function Derived() { Base.call(this); }
Derived.prototype = Object.create(Base.prototype);
Derived.prototype.constructor = Derived;
var baseValue = Object.getOwnPropertyDescriptor(Base.prototype, 'value');
Object.defineProperty(Derived.prototype, 'value', {
  get: function () { return baseValue.get.call(this) + 100; },
  set: function (v) { baseValue.set.call(this, v * 10); },
  configurable: true
});
Derived.prototype.describe = function () { return 'D(' + Base.prototype.describe.call(this) + ')'; };
var b = new Base(), d = new Derived();
d.value = 2;
var out = [b.value, b.doubled, d.value, d.doubled, d._v, Base.kind, b.describe(), d.describe(), Object.getOwnPropertyNames(Derived.prototype).sort().join(), 'value' in d, d.hasOwnProperty('value')];
out.push(typeof baseValue.get, typeof baseValue.set, baseValue.enumerable, baseValue.configurable);
var lit = { get late() { return this.v; }, v: 'lit' };
var sub = Object.create(lit);
sub.v = 'sub';
out.push(lit.late, sub.late, Object.keys(lit).join());
var counter = { n: 0, get next() { return ++this.n; } };
var copy = Object.assign({}, counter);
out.push(counter.next, counter.next, copy.next, copy.next, JSON.stringify(Object.getOwnPropertyDescriptor(copy, 'next')));
out.join('|');
