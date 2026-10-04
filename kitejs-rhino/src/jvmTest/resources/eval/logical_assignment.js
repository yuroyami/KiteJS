var o = { a: 0, b: null, c: 'set', d: undefined };
o.a ||= 'or';
o.b ??= 'nullish';
o.c &&= 'and';
o.d ??= 'undef';
o.e ??= 'new';
var count = 0;
function tick() { count++; return 'ticked'; }
o.c ||= tick();
o.a &&= tick();
o.b ??= tick();
var x = 5;
x ||= tick();
x &&= x * 2;
x ??= tick();
[JSON.stringify(o), count, x].join('|');
