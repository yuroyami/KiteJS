var attempts = [
  function () { return null.prop; },
  function () { return undefined.prop; },
  function () { var o = {}; return o.missing.deeper; },
  function () { var o = {}; return o.notAFunction(); },
  function () { return notDefinedAnywhere; },
  function () { notDefinedAnywhere = 1; },
  function () { 'use strict'; notDefinedStrict = 1; },
  function () { return new Array(-1); },
  function () { return [].length = -1; },
  function () { return (1).toFixed(200); },
  function () { return 'abc'.repeat(-1); },
  function () { return new (function () {})().x.y; },
  function () { return JSON.parse('{bad'); },
  function () { const c = 1; c = 2; },
  function () { return Object.defineProperty(1, 'x', {}); },
  function () { var o = Object.freeze({}); 'use strict'; o.x = 1; return o.x; },
  function () { 'use strict'; var o = Object.freeze({}); o.x = 1; },
  function () { return decodeURIComponent('%'); },
  function () { throw new TypeError('custom type'); },
  function () { return [1, 2].reduce(function () {}, undefined) + [].reduce(function () {}); },
  function () { return new 5.5; },
  function () { return 1 in 1; },
  function () { return {} instanceof 1; },
  function () { return Object.create(5); },
  function () { return 'x'.charAt.call(null); },
  function () { return Array.prototype.join.call(undefined); }
];
attempts.map(function (f, i) {
  try { var r = f(); return i + ':ok:' + r; }
  catch (e) { return i + ':' + (e && e.name) + ':' + (e && e.message); }
}).join('\n');
