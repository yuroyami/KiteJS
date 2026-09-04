function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (typeof a === 'number' && isNaN(a) && isNaN(b)) return true;
  if (a === null || b === null || typeof a !== 'object') return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  var ka = Object.keys(a), kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every(function (k) { return kb.indexOf(k) >= 0 && deepEqual(a[k], b[k]); });
}
var cases = [
  [1, 1], [1, '1'], [NaN, NaN], [null, undefined], [null, null], [[1, 2], [1, 2]], [[1, 2], [2, 1]], [{ a: 1, b: [1, { c: 2 }] }, { b: [1, { c: 2 }], a: 1 }],
  [{ a: 1 }, { a: 1, b: undefined }], [[], {}], [{ a: [] }, { a: {} }], ['x', 'x'], [function () {}, function () {}], [{ a: { b: { c: 1 } } }, { a: { b: { c: 2 } } }]
];
cases.map(function (c) { return deepEqual(c[0], c[1]) ? 1 : 0; }).join('');
