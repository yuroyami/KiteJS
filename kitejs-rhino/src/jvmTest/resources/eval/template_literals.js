var name = 'Kite', n = 3, obj = { a: 1, b: [1, 2] };
var plain = `Hello, ${name}!`;
var expr = `${n} * ${n} = ${n * n}`;
var nested = `outer ${`inner ${name.toLowerCase()}`} end`;
var multi = `line1
line2`;
var calls = `${obj.b.map(function (x) { return x * 2; }).join('+')} and ${JSON.stringify(obj)}`;
var cond = `${n > 2 ? 'big' : 'small'}`;
var escaped = `a\`b\${c}d`;
var empty = ``;
function tag(strings, ...values) {
  return strings.raw.join('|') + '#' + values.join(',') + '#' + strings.length;
}
var tagged = tag`x${1}y${2}z`;
var tagged2 = tag`\n${'v'}`;
var raw = String.raw`a\nb${1}`;
var sites = [];
function site(s) { return s; }
for (var i = 0; i < 2; i++) sites.push(site`same`);
[plain, expr, nested, multi.split('\n').length, calls, cond, escaped, empty.length, tagged, tagged2, raw, sites[0] === sites[1], site`x` === site`x`, Object.isFrozen(sites[0])].join('|');
