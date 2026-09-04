function argsToArray() { return Array.from(arguments); }
var arrayLike = { length: 3, 0: 'a', 1: 'b', 2: 'c', 5: 'ignored' };
var out = [
  Array.from(arrayLike).join(), Array.from(arrayLike, function (x, i) { return x + i; }).join(), Array.prototype.slice.call(arrayLike).join(),
  Array.prototype.map.call(arrayLike, function (x) { return x.toUpperCase(); }).join(), Array.prototype.join.call(arrayLike, '-'),
  Array.from('hey').join(), Array.from({ length: 3 }).join(), Array.from({ length: 2, 0: 'x' }).join(), Array.from([1, 2, 3].keys()).join(), Array.from([1, 2].entries()).join(';'),
  argsToArray(1, 2, 3).join(), Array.of(7).join(), Array.of(1, 2, 3).length, Array.from({ length: -1 }).length, Array.from({ length: 2.7 }).length,
  Array.prototype.push.call(arrayLike, 'd'), arrayLike.length, Object.keys(arrayLike).join(), Array.prototype.indexOf.call(arrayLike, 'c'), Array.prototype.reverse.call({ length: 2, 0: 'x', 1: 'y' })[0],
  Array.prototype.filter.call('a1b2', function (c) { return isNaN(c); }).join(''), Array.prototype.reduce.call('abc', function (a, c) { return c + a; }, '')
];
out.join('|');
