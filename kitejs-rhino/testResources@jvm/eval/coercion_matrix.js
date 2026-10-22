var values = [0, 1, -1, '', '0', '1', 'a', ' ', true, false, null, undefined, NaN, [], [0], [1], [1, 2], {}, Infinity];
function show(v) {
  if (v === undefined) return 'undefined';
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v);
  if (Array.isArray(v)) return '[' + v.join(',') + ']';
  if (typeof v === 'object') return '{}';
  return String(v);
}
var rows = [];
for (var i = 0; i < values.length; i++) {
  var row = show(values[i]) + ':';
  for (var j = 0; j < values.length; j++) row += (values[i] == values[j] ? '1' : '0');
  rows.push(row);
}
var plus = values.map(function (v) { return show(v + 1); }).join(',');
var minus = values.map(function (v) { return show(v - 1); }).join(',');
var bool = values.map(function (v) { return !!v ? 't' : 'f'; }).join('');
var types = values.map(function (v) { return typeof v; }).join(',');
var nums = values.map(function (v) { return show(Number(v)); }).join(',');
var strs = values.map(function (v) { return String(v); }).join('|');
[rows.join('\n'), plus, minus, bool, types, nums, strs, null < 1, undefined < 1, NaN == NaN, [1] == 1, [1, 2] == '1,2', {} + '', '' + {}, [] + [], [] + {}, 1 + '2' - 1, '3' * '4', true + true, [] == ![], null == false, undefined == null].join('\n');
