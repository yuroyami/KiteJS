var out = [];
outer: for (var i = 0; i < 4; i++) {
  for (var j = 0; j < 4; j++) {
    if (j === 2) continue outer;
    if (i === 3) break outer;
    out.push(i + '' + j);
  }
}
function sw(x) {
  var r = '';
  switch (x) {
    case 1: r += 'one';
    case 2: r += 'two'; break;
    case 'a': case 'b': r += 'ab'; break;
    default: r += 'def';
    case 3: r += 'three';
  }
  return r;
}
out.push(sw(1), sw(2), sw('a'), sw(9), sw(3));
var k = 0;
do { k += 3; } while (k < 10);
out.push(k);
var w = 5, steps = '';
while (w--) steps += w;
out.push(steps);
var obj = { b: 1, a: 2, 10: 'x', 2: 'y' }, keys = '';
for (var key in obj) keys += key + ';';
out.push(keys);
var s = '';
for (var ch of 'hey') s += ch.toUpperCase();
out.push(s);
var x = (1, 2, 3);
out.push(x, 5 > 3 ? 'yes' : 'no', 0 || 'or', 1 && 'and', null ?? 'nullish', 0 ?? 'zero');
var counter = 0;
function tick() { return ++counter; }
if (false && tick()) {}
if (true || tick()) {}
out.push(counter);
block: { out.push('in'); break block; out.push('never'); }
out.join('|');
