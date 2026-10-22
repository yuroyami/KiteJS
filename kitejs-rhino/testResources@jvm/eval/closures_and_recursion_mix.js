function permutations(arr) {
  if (arr.length <= 1) return [arr.slice()];
  var out = [];
  arr.forEach(function (x, i) {
    var rest = arr.slice(0, i).concat(arr.slice(i + 1));
    permutations(rest).forEach(function (p) { out.push([x].concat(p)); });
  });
  return out;
}
function subsets(arr) { return arr.reduce(function (acc, x) { return acc.concat(acc.map(function (s) { return s.concat(x); })); }, [[]]); }
function flattenDeep(a) { return a.reduce(function (acc, x) { return acc.concat(Array.isArray(x) ? flattenDeep(x) : x); }, []); }
function countChange(amount, coins) { if (amount === 0) return 1; if (amount < 0 || !coins.length) return 0; return countChange(amount - coins[0], coins) + countChange(amount, coins.slice(1)); }
function binarySearch(arr, target) { var lo = 0, hi = arr.length - 1, steps = 0; while (lo <= hi) { steps++; var mid = (lo + hi) >> 1; if (arr[mid] === target) return mid + ':' + steps; if (arr[mid] < target) lo = mid + 1; else hi = mid - 1; } return -1; }
var sorted = [];
for (var i = 0; i < 1000; i += 7) sorted.push(i);
[permutations([1, 2, 3]).map(function (p) { return p.join(''); }).join(), subsets(['a', 'b', 'c']).map(function (s) { return s.join('') || '-'; }).join(), flattenDeep([1, [2, [3, [4, [5]]]], 6]).join(), countChange(100, [50, 25, 10, 5, 1]), binarySearch(sorted, 700), binarySearch(sorted, 701), permutations([]).length].join('|');
