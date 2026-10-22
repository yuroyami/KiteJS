var data = {
  name: 'Kite', version: 1.5, tags: ['js', 'kmp'], nested: { deep: { deeper: [1, { x: null }] } },
  empty: {}, emptyArr: [], flag: true, nothing: null, skipped: undefined, fn: function () {},
  unicode: 'café 😀', ctrl: 'a\tb\nc"d\\e'
};
var text = JSON.stringify(data);
var back = JSON.parse(text);
var same = JSON.stringify(back) === text;
var pretty = JSON.stringify({ a: [1, { b: 2 }], c: 'x' }, null, 2);
var tabbed = JSON.stringify({ a: [1] }, null, '\t');
var filtered = JSON.stringify(data, ['name', 'tags', 'version']);
var replaced = JSON.stringify(data, function (k, v) { return typeof v === 'number' ? v * 2 : v; });
var revived = JSON.parse('{"n":1,"o":{"n":2},"a":[{"n":3}]}', function (k, v) { return k === 'n' ? v * 10 : v; });
var dropped = JSON.parse('{"keep":1,"drop":2}', function (k, v) { return k === 'drop' ? undefined : v; });
var custom = JSON.stringify({ when: { toJSON: function (key) { return 'json:' + key; } }, list: [{ toJSON: function () { return 1; } }] });
var edge = JSON.stringify([NaN, Infinity, -0, 1e21, new Number(2), new String('s'), new Boolean(false), undefined, function () {}]);
var order = JSON.stringify({ b: 1, a: 2, 2: 'two', 1: 'one' });
var errors = [];
['{', '[1,]', "{'a':1}", '01', 'undefined', '{"a":1,}', '"\\x41"', '', ' '].forEach(function (s) { try { JSON.parse(s); errors.push('ok'); } catch (e) { errors.push(e.name); } });
var cyc = {}; cyc.self = cyc;
try { JSON.stringify(cyc); } catch (e) { errors.push(e.name); }
[text, same, pretty, tabbed, filtered, replaced, JSON.stringify(revived), JSON.stringify(dropped), custom, edge, order, errors.join(), typeof back.nested.deep.deeper[1].x, back.unicode.length, Object.keys(back).join()].join('\n');
