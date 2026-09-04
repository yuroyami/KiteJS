var inputs = ['', ' ', '0', '-0', '1', '-1.5', '1e3', '1E-3', '.5', '5.', '+5', '-', '0x1f', '0X1F', '-0x1f', '0b101', '0o17', '0b', '1_0', 'Infinity', '-Infinity', 'infinity', 'NaN', '12px', '  7  ', '\n8\t', '1e1000', '-1e1000', '9007199254740993', '0.1', '.', 'e5', '1e', '1.2.3', 'true', null, undefined, true, false, [], [5], ['6'], [1, 2], {}, { valueOf: function () { return 9; } }, { toString: function () { return '10'; } }];
var out = inputs.map(function (v) {
  var n = Number(v), p = parseFloat(v), i = parseInt(v), u = +v;
  function s(x) { return Object.is(x, -0) ? '-0' : String(x); }
  return JSON.stringify(v === undefined ? 'undefined' : typeof v === 'object' && v !== null && !Array.isArray(v) ? 'obj' : v) + '=>' + [s(n), s(p), s(i), s(u)].join(',');
});
out.push([Number.MAX_SAFE_INTEGER, Number.MIN_SAFE_INTEGER, Number.EPSILON, Number.MAX_VALUE, Number.MIN_VALUE].join(' '));
out.push([1e21, 1e20, 123456789012345680000, 0.000001, 0.0000001, 1.5e-7, 100, 1e2, 12e1, 1.0, 1.50, -0, 0.1 + 0.7, 4.35 * 100, 1 / 3].map(String).join(' '));
out.push([(0.5).toString(), (1e21).toFixed(), (1234.5678).toFixed(2), (0.000001).toFixed(3), (1.45).toFixed(1), (1.55).toFixed(1), (8.345).toFixed(2), (1e-10).toPrecision(2), (123456).toPrecision(2), (0).toPrecision(5), (12345.6789).toExponential(), (12345.6789).toExponential(3)].join(' '));
out.join('\n');
