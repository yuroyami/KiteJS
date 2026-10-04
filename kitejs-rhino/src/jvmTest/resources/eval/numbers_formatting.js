var vals = [0, 1, -1, 0.5, 1.005, 2.5, -2.5, 123.456, 1e21, 1e-7, 123456789, 0.000001234, 1 / 3, NaN, Infinity];
function fmt(v) { return [v.toFixed(2), v.toPrecision(3), v.toExponential(1)].join(' '); }
var formatted = vals.map(fmt).join('|');
var parsed = ['42', '  42  ', '42abc', '0x1F', '1e3', '.5', '5.', '-0', '', ' ', 'abc', '1,000', 'Infinity', '0b11', '0o17', '08'].map(function (s) {
  return [Number(s), parseInt(s), parseFloat(s), +s].map(function (n) { return Object.is(n, -0) ? '-0' : String(n); }).join('/');
}).join('|');
var rounding = [2.5, 3.5, -2.5, 0.49999999999999994, 1.4999999999999998, -0.5, 1e16 + 1].map(function (x) { return [Math.round(x), Math.floor(x), Math.ceil(x), Math.trunc(x)].join(' '); }).join('|');
var checks = [Number.isInteger(5.0), Number.isInteger(5.1), Number.isSafeInteger(2 ** 53), Number.isSafeInteger(2 ** 53 - 1), Number.isNaN('x'), isNaN('x'), Number.isFinite('1'), isFinite('1'), 0.1 + 0.2 === 0.3, Math.abs(0.1 + 0.2 - 0.3) < Number.EPSILON, 9007199254740993 === 9007199254740992, 2 ** 53 + 1, -(2 ** 31) | 0, 2 ** 32 >>> 0, 1 / -0 < 0, Object.is(0, -0), Math.max(), Math.min(), Math.hypot(3, 4), Math.sign(-3), Math.cbrt(27), Math.log2(1024), Math.clz32(1), Math.fround(0.1) === 0.1, Math.imul(0xffffffff, 5), (255).toString(), (0.1).toFixed(20), (1e21).toLocaleString()].join('|');
[formatted, parsed, rounding, checks].join('\n');
