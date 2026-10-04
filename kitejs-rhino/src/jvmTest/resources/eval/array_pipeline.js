var people = [
  { name: 'Ann', age: 31, city: 'Oslo', tags: ['a', 'b'] },
  { name: 'Bob', age: 25, city: 'Rome', tags: ['b'] },
  { name: 'Cid', age: 40, city: 'Oslo', tags: [] },
  { name: 'Dee', age: 25, city: 'Lima', tags: ['c', 'a'] },
  { name: 'Eve', age: 35, city: 'Rome', tags: ['d'] }
];
var adults = people.filter(function (p) { return p.age >= 30; }).map(function (p) { return p.name; });
var totalAge = people.reduce(function (s, p) { return s + p.age; }, 0);
var byCity = people.reduce(function (acc, p) { (acc[p.city] = acc[p.city] || []).push(p.name); return acc; }, {});
var tags = people.flatMap(function (p) { return p.tags; }).filter(function (t, i, a) { return a.indexOf(t) === i; }).sort();
var sorted = people.slice().sort(function (a, b) { return a.age - b.age || a.name.localeCompare(b.name); }).map(function (p) { return p.name + p.age; });
var oldest = people.reduce(function (m, p) { return p.age > m.age ? p : m; });
var idx = people.findIndex(function (p) { return p.city === 'Lima'; });
var found = people.find(function (p) { return p.age === 25; }).name;
var lastYoung = people.findLast(function (p) { return p.age === 25; }).name;
var every = people.every(function (p) { return p.age > 20; });
var some = people.some(function (p) { return p.city === 'Paris'; });
var chunks = [];
for (var i = 0; i < people.length; i += 2) chunks.push(people.slice(i, i + 2).length);
var out = [adults.join(), totalAge, JSON.stringify(byCity), tags.join(), sorted.join(), oldest.name, idx, found, lastYoung, every, some, chunks.join()];
out.push(people.map(function (p) { return p.age; }).includes(40), [1, 2, 3].at(-1), [[1, 2], [3]].flat().join(), Array.from({ length: 4 }, function (_, i) { return i * i; }).join());
out.join('|');
