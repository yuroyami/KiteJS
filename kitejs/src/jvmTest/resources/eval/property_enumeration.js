var o = { z: 1, 10: 'ten', b: 2, 2: 'two', a: 3, '01': 'zero-one', 1: 'one', '-1': 'neg' };
o.late = 4;
o[5] = 'five';
var proto = { inherited: 'p', 3: 'three' };
var child = Object.create(proto);
child.own = 1;
child[0] = 'zero';
Object.defineProperty(child, 'hidden', { value: 1, enumerable: false });
var forIn = [];
for (var k in child) forIn.push(k);
var arr = [1, 2, 3];
arr.extra = 'x';
var arrKeys = [];
for (var k in arr) arrKeys.push(k);
[
  Object.keys(o).join(), JSON.stringify(o), Object.getOwnPropertyNames(child).join(), Object.keys(child).join(), forIn.join(),
  arrKeys.join(), Object.keys(arr).join(), Object.keys('str').join(), Object.entries({ b: 1, a: 2 }).map(function (e) { return e.join('='); }).join(), Object.values({ x: 1, y: [2] }).length,
  Object.keys(Object.assign({}, { c: 1 }, { a: 2 }, { c: 3 })).join(), JSON.stringify(Object.assign({}, { c: 1 }, { a: 2 }, { c: 3 })),
  Object.keys(new String('ab')).join(), Object.getOwnPropertyNames([1]).join()
].join('|');
