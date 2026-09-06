// Describing objects using Reflect, then rendering the result as text.
function describe(obj) {
  var rows = [];
  var keys = Reflect.ownKeys(obj);
  for (var i = 0; i < keys.length; i++) {
    var key = keys[i];
    var d = Reflect.getOwnPropertyDescriptor(obj, key);
    var kind = Reflect.has(d, 'get') || Reflect.has(d, 'set') ? 'accessor' : 'data';
    var flags = [d.enumerable ? 'e' : '-', d.configurable ? 'c' : '-', d.writable ? 'w' : '-'].join('');
    rows.push(String(key) + ':' + kind + ':' + flags);
  }
  return rows.join(' ');
}

var target = {};
Reflect.defineProperty(target, 'plain', { value: 1, enumerable: true, configurable: true, writable: true });
Reflect.defineProperty(target, 'locked', { value: 2, enumerable: true, configurable: false, writable: false });
Reflect.defineProperty(target, 'quiet', { value: 3, enumerable: false, configurable: true, writable: true });
Reflect.defineProperty(target, 'computed', {
  get: function () { return this.plain * 10; },
  enumerable: true,
  configurable: true
});

var table = describe(target);
var computed = Reflect.get(target, 'computed');
var lockedWrite = Reflect.set(target, 'locked', 99);
var lockedValue = Reflect.get(target, 'locked');
var lockedDelete = Reflect.deleteProperty(target, 'locked');

var proto = { inherited: 'yes' };
Reflect.setPrototypeOf(target, proto);
var fromProto = Reflect.get(target, 'inherited');
var ownOnly = Reflect.ownKeys(target).indexOf('inherited');
var hasThroughChain = Reflect.has(target, 'inherited');

var frozen = Object.freeze({ a: 1 });
var frozenExtensible = Reflect.isExtensible(frozen);
var frozenDefine = Reflect.defineProperty(frozen, 'b', { value: 2 });

[table, computed, lockedWrite, lockedValue, lockedDelete, fromProto, ownOnly, hasThroughChain, frozenExtensible, frozenDefine].join('|');
