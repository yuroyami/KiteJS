// A deep copy that keeps property descriptors, built out of Reflect calls only.
function cloneDeep(source, seen) {
  if (source === null || typeof source !== 'object') return source;
  seen = seen || [];
  for (var s = 0; s < seen.length; s += 2) if (seen[s] === source) return seen[s + 1];

  var copy = Array.isArray(source) ? [] : Object.create(Reflect.getPrototypeOf(source));
  seen.push(source, copy);

  var keys = Reflect.ownKeys(source);
  for (var i = 0; i < keys.length; i++) {
    var desc = Reflect.getOwnPropertyDescriptor(source, keys[i]);
    if (desc === undefined) continue;
    if (Reflect.has(desc, 'value')) desc.value = cloneDeep(desc.value, seen);
    Reflect.defineProperty(copy, keys[i], desc);
  }
  return copy;
}

var original = {
  name: 'root',
  nested: { list: [1, 2, { deep: true }], flag: false },
  count: 3
};
Object.defineProperty(original, 'hidden', { value: 'x', enumerable: false, configurable: true, writable: true });
original.self = original;

var copy = cloneDeep(original);

var sameShape = copy.name + ',' + copy.count + ',' + copy.nested.flag;
var deepValue = copy.nested.list[2].deep;
var independent = (function () {
  copy.nested.list[0] = 99;
  return original.nested.list[0] + '/' + copy.nested.list[0];
})();
var cycleHeld = copy.self === copy;
var notShared = copy.nested !== original.nested;
var hiddenKept = copy.hidden;
var hiddenStillHidden = Object.keys(copy).indexOf('hidden');
var keyOrder = Reflect.ownKeys(copy).join(',');

[sameShape, deepValue, independent, cycleHeld, notShared, hiddenKept, hiddenStillHidden, keyOrder].join('|');
