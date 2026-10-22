function sloppy(a, b) { arguments[0] = 'changed'; b = 'set'; return [a, arguments[1], arguments.length, typeof arguments, Object.prototype.toString.call(arguments)].join(); }
function strict(a, b) { 'use strict'; arguments[0] = 'changed'; b = 'set'; return [a, arguments[1], arguments.length].join(); }
function toArray() { return Array.prototype.slice.call(arguments).join('+'); }
function fromArguments() { return Array.from(arguments).map(function (x) { return x * 2; }).join(); }
function outer() { var inner = () => arguments.length; return inner(1, 2, 3, 4, 5); }
function lengths(a, b, c) { return [arguments.length, lengths.length].join('/'); }
function spread() { return [].concat.apply([], arguments).length; }
function callee() { return arguments.length ? callee() + 1 : 0; }
[sloppy('orig', 'b'), strict('orig', 'b'), toArray(1, 2, 3), fromArguments(1, 2), outer(9), lengths(1), lengths(1, 2, 3, 4), spread([1, 2], [3]), callee(1), (function () { return arguments[0] === undefined; })(), (function (x) { return arguments.length; })(undefined)].join('|');
