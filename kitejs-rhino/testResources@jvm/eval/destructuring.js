var [a, b, c = 3, ...rest] = [1, 2, undefined, 4, 5];
var { x, y: why, z = 'zed', nested: { deep } } = { x: 10, y: 20, nested: { deep: 'ok' } };
var [p, q] = ['P', 'Q'];
var swap1 = 1, swap2 = 2;
[swap1, swap2] = [swap2, swap1];
function f({ name, age = 0 }, [first, second] = ['none', 'none']) {
  return name + '/' + age + '/' + first + '/' + second;
}
var [[n1, n2], [n3]] = [[1, 2], [3]];
var { length } = 'hello';
var { 0: firstChar, 2: thirdChar } = 'abc';
var out = [a, b, c, rest.join(), x, why, z, deep, p, q, swap1, swap2, f({ name: 'k' }), f({ name: 'j', age: 5 }, ['x', 'y']), n1 + n2 + n3, length, firstChar + thirdChar];
for (var [k, v] of [['a', 1], ['b', 2]]) out.push(k + v);
var obj = { m: 1, n: 2 };
var keys = [];
for (var key in obj) { var { [key]: val } = obj; keys.push(key + '=' + val); }
out.push(keys.join());
out.join('|');
