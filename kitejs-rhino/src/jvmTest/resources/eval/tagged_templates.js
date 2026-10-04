function html(strings) {
  var values = Array.prototype.slice.call(arguments, 1);
  return strings.reduce(function (out, s, i) { return out + s + (i < values.length ? String(values[i]).split('<').join('&lt;') : ''); }, '');
}
function meta(strings, ...values) {
  return [strings.length, values.length, strings.raw.length, Array.isArray(strings), Object.isFrozen(strings), Object.isFrozen(strings.raw), strings.raw === strings].join(',');
}
function upper(strings, ...values) { return strings.raw.map(function (s, i) { return s + (values[i] === undefined ? '' : String(values[i]).toUpperCase()); }).join(''); }
var user = '<script>', n = 2;
var out = [html`<p>${user} has ${n} items</p>`, meta`a${1}b${2}c`, meta``, meta`${1}`, upper`hello ${'world'} and ${'kite'}!`];
function identity(s) { return s; }
var first = identity`same site`;
var second = identity`same site`;
var loop = [];
for (var i = 0; i < 2; i++) loop.push(identity`in loop`);
out.push(first === second, loop[0] === loop[1], first.raw[0], String.raw`A\n${'x'}`, `A\n${'x'}`.length);
out.push((function () { return identity`inner`; })() === (function () { return identity`inner`; })());
var fn = function () { return identity`fn`; };
out.push(fn() === fn());
out.join('|');
