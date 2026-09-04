/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.yuroyami.kitejs

/**
 * Whole programs from `jvmTest/resources/eval`, each with the answer upstream Rhino gives.
 * Generated from the corpus files; `EvalCorpusSliceOracleTest` fails when either side drifts.
 */
object EvalCorpusSlice {

    class Program(val name: String, val source: String, val expected: String)

    val programs: List<Program> = listOf(
        Program(
            "closures_counter",
            """// Independent counters, each with its own captured state.
function makeCounter(start) {
  var count = start;
  return {
    inc: function () { return ++count; },
    dec: function () { return --count; },
    get: function () { return count; }
  };
}
var a = makeCounter(10), b = makeCounter(100);
a.inc(); a.inc(); b.dec();
var log = [];
log.push(a.get(), b.get(), a.inc() + b.inc());
var adders = [];
for (var i = 0; i < 3; i++) {
  adders.push((function (n) { return function (x) { return x + n; }; })(i));
}
log.push(adders[0](10), adders[1](10), adders[2](10));
log.join(',');
""",
            """12,99,113,10,11,12""",
        ),
        Program(
            "recursion",
            """function fact(n) { return n <= 1 ? 1 : n * fact(n - 1); }
var memo = {};
function fib(n) {
  if (n < 2) return n;
  if (memo[n] !== undefined) return memo[n];
  return memo[n] = fib(n - 1) + fib(n - 2);
}
function ack(m, n) {
  if (m === 0) return n + 1;
  if (n === 0) return ack(m - 1, 1);
  return ack(m - 1, ack(m, n - 1));
}
function gcd(a, b) { return b === 0 ? a : gcd(b, a % b); }
function hanoi(n, from, to, via, moves) {
  if (n === 0) return moves;
  hanoi(n - 1, from, via, to, moves);
  moves.push(from + '>' + to);
  return hanoi(n - 1, via, to, from, moves);
}
function sumDigits(n) { return n < 10 ? n : (n % 10) + sumDigits(Math.floor(n / 10)); }
[fact(10), fib(40), ack(2, 3), gcd(1071, 462), hanoi(3, 'A', 'C', 'B', []).join(' '), sumDigits(987654321)].join('|');
""",
            """3628800|102334155|9|21|A>C A>B C>B A>C B>A B>C A>C|45""",
        ),
        Program(
            "sorting_algorithms",
            """var input = [33, 7, -2, 19, 7, 0, 100, -50, 42, 3, 8, 8, 1];
function bubble(a) {
  a = a.slice();
  for (var i = 0; i < a.length; i++)
    for (var j = 0; j < a.length - i - 1; j++)
      if (a[j] > a[j + 1]) { var t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
  return a;
}
function insertion(a) {
  a = a.slice();
  for (var i = 1; i < a.length; i++) {
    var v = a[i], j = i - 1;
    while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j--; }
    a[j + 1] = v;
  }
  return a;
}
function quick(a) {
  if (a.length <= 1) return a;
  var pivot = a[0], left = [], right = [];
  for (var i = 1; i < a.length; i++) (a[i] < pivot ? left : right).push(a[i]);
  return quick(left).concat([pivot], quick(right));
}
function merge(a) {
  if (a.length <= 1) return a;
  var mid = a.length >> 1;
  var l = merge(a.slice(0, mid)), r = merge(a.slice(mid)), out = [];
  while (l.length && r.length) out.push(l[0] <= r[0] ? l.shift() : r.shift());
  return out.concat(l, r);
}
var builtin = input.slice().sort(function (x, y) { return x - y; });
var lexical = input.slice().sort();
var all = [bubble(input), insertion(input), quick(input), merge(input)];
var agree = all.every(function (a) { return a.join() === builtin.join(); });
[builtin.join(' '), lexical.join(' '), agree, input.join(' ')].join('|');
""",
            """-50 -2 0 1 3 7 7 8 8 19 33 42 100|-2 -50 0 1 100 19 3 33 42 7 7 8 8|true|33 7 -2 19 7 0 100 -50 42 3 8 8 1""",
        ),
        Program(
            "string_processing",
            """function reverseWords(s) { return s.split(' ').reverse().join(' '); }
function isPalindrome(s) {
  var t = s.toLowerCase().split('').filter(function (c) { return c >= 'a' && c <= 'z'; }).join('');
  return t === t.split('').reverse().join('');
}
function caesar(s, k) {
  var out = '';
  for (var i = 0; i < s.length; i++) {
    var c = s.charCodeAt(i);
    if (c >= 65 && c <= 90) out += String.fromCharCode((c - 65 + k) % 26 + 65);
    else if (c >= 97 && c <= 122) out += String.fromCharCode((c - 97 + k) % 26 + 97);
    else out += s[i];
  }
  return out;
}
function capitalize(s) {
  return s.split(' ').map(function (w) { return w.charAt(0).toUpperCase() + w.slice(1).toLowerCase(); }).join(' ');
}
function frequency(s) {
  var f = {};
  for (var i = 0; i < s.length; i++) f[s[i]] = (f[s[i]] || 0) + 1;
  return Object.keys(f).sort().map(function (k) { return k + '=' + f[k]; }).join(',');
}
function vowels(s) { return s.split('').filter(function (c) { return 'aeiou'.indexOf(c.toLowerCase()) >= 0; }).length; }
[
  reverseWords('the quick brown fox'),
  isPalindrome('A man, a plan, a canal: Panama'),
  isPalindrome('not one'),
  caesar('Hello, World!', 3),
  caesar(caesar('Hello, World!', 3), 23),
  capitalize('hELLO wORLD from kiteJS'),
  frequency('mississippi'),
  vowels('Programming Languages'),
  'abc'.padStart(6, '-') + 'abc'.padEnd(6, '+'),
  '  trim me  '.trim().length
].join('|');
""",
            """fox brown quick the|true|false|Khoor, Zruog!|Hello, World!|Hello World From Kitejs|i=4,m=1,p=2,s=4|7|---abcabc+++|7""",
        ),
        Program(
            "objects_and_prototypes",
            """function Animal(name) { this.name = name; }
Animal.prototype.speak = function () { return this.name + ' makes a sound'; };
Animal.prototype.kind = 'animal';
function Dog(name) { Animal.call(this, name); }
Dog.prototype = Object.create(Animal.prototype);
Dog.prototype.constructor = Dog;
Dog.prototype.speak = function () { return Animal.prototype.speak.call(this) + ': woof'; };
var d = new Dog('Rex');
var base = { greet: function () { return 'hi ' + this.who; } };
var derived = Object.create(base, { who: { value: 'there', enumerable: true } });
var chain = [];
for (var o = d; o; o = Object.getPrototypeOf(o)) chain.push(o.constructor.name || '?');
var out = [
  d.speak(),
  d instanceof Dog, d instanceof Animal, d instanceof Object,
  d.hasOwnProperty('name'), d.hasOwnProperty('speak'), 'speak' in d, 'kind' in d,
  d.kind, Object.keys(d).join(), chain.join('>'),
  derived.greet(), Object.getPrototypeOf(derived) === base,
  Dog.prototype.isPrototypeOf(d), Animal.prototype.isPrototypeOf(d),
  Object.getOwnPropertyNames(Dog.prototype).sort().join()
];
d.kind = 'pet';
out.push(d.kind, Animal.prototype.kind, delete d.kind, d.kind);
out.join('|');
""",
            """Rex makes a sound: woof|true|true|true|true|false|true|true|animal|name|Dog>Dog>Animal>Object|hi there|true|true|true|constructor,speak|pet|animal|true|animal""",
        ),
        Program(
            "control_flow",
            """var out = [];
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
""",
            """00|01|10|11|20|21|onetwo|two|ab|defthree|three|12|43210|2;10;b;a;|HEY|3|yes|or|and|nullish|0|0|in""",
        ),
        Program(
            "exceptions",
            """var log = [];
function CustomError(message, code) {
  this.name = 'CustomError';
  this.message = message;
  this.code = code;
}
CustomError.prototype = Object.create(Error.prototype);
CustomError.prototype.constructor = CustomError;
function ValidationError(field) {
  var e = Error.call(this, 'bad ' + field);
  this.message = e.message;
  this.name = 'ValidationError';
  this.field = field;
}
ValidationError.prototype = Object.create(Error.prototype);
ValidationError.prototype.constructor = ValidationError;
function order() {
  try { log.push('try'); throw new Error('boom'); }
  catch (e) { log.push('catch:' + e.message); return 'from catch'; }
  finally { log.push('finally'); }
}
function override() { try { return 'try'; } finally { return 'finally'; } }
function nested() {
  try {
    try { throw new CustomError('inner', 42); }
    finally { log.push('inner finally'); }
  } catch (e) { return e.name + ':' + e.code + ':' + (e instanceof Error) + ':' + (e instanceof CustomError); }
}
function rethrow() {
  try { try { throw 'first'; } catch (e) { throw e + '+second'; } } catch (e) { return e; }
}
function loopFinally() {
  var r = '';
  for (var i = 0; i < 3; i++) { try { if (i === 1) continue; if (i === 2) break; r += i; } finally { r += 'f'; } }
  return r;
}
var out = [order(), log.join(), override(), nested(), rethrow(), loopFinally()];
try { throw new ValidationError('email'); } catch (e) { out.push(e.name, e.message, e.field, e instanceof ValidationError, e instanceof Error, '' + e); }
try { null.x; } catch (e) { out.push(e.name, e instanceof TypeError); }
try { undefinedFunction(); } catch (e) { out.push(e.name, e.message); }
try { throw { custom: true }; } catch (e) { out.push(typeof e, e.custom); }
try { throw 42; } catch (e) { out.push(e + 1); }
var caught = 0;
try { try { throw 1; } finally { caught++; } } catch (e) { caught += 10; }
out.push(caught);
out.push((function () { try { throw new RangeError('r'); } catch ({ name, message }) { return name + '/' + message; } })());
var err = new Error('with props', { cause: 'root' });
out.push(err.cause, Object.keys(err).join(), err.propertyIsEnumerable('message'), JSON.stringify(err));
out.join('|');
""",
            """from catch|try,catch:boom,finally|finally|CustomError:42:true:true|first+second|0fff|ValidationError|bad email|email|true|true|ValidationError: bad email|TypeError|true|ReferenceError|"undefinedFunction" is not defined.|object|true|43|11|RangeError/r|root||false|{}""",
        ),
        Program(
            "higher_order_functions",
            """function compose() { var fns = Array.prototype.slice.call(arguments); return function (x) { return fns.reduceRight(function (acc, f) { return f(acc); }, x); }; }
function pipe() { var fns = Array.prototype.slice.call(arguments); return function (x) { return fns.reduce(function (acc, f) { return f(acc); }, x); }; }
function curry(fn) {
  return function curried() {
    var args = Array.prototype.slice.call(arguments);
    if (args.length >= fn.length) return fn.apply(this, args);
    return function () { return curried.apply(this, args.concat(Array.prototype.slice.call(arguments))); };
  };
}
function memoize(fn) { var cache = {}, calls = 0; var m = function (x) { if (!(x in cache)) { calls++; cache[x] = fn(x); } return cache[x]; }; m.calls = function () { return calls; }; return m; }
function partial(fn) { var preset = Array.prototype.slice.call(arguments, 1); return function () { return fn.apply(this, preset.concat(Array.prototype.slice.call(arguments))); }; }
function debounceLike(fn) { var last; return function (x) { if (x === last) return 'skip'; last = x; return fn(x); }; }
var inc = function (x) { return x + 1; }, dbl = function (x) { return x * 2; }, sq = function (x) { return x * x; };
var add3 = curry(function (a, b, c) { return a + b + c; });
var slowSquare = memoize(function (x) { return x * x; });
slowSquare(4); slowSquare(4); slowSquare(5);
var greet = partial(function (greeting, name, punct) { return greeting + ', ' + name + punct; }, 'Hello');
var d = debounceLike(function (x) { return 'ran:' + x; });
[compose(inc, dbl)(5), pipe(inc, dbl)(5), compose(sq, inc, dbl)(2), add3(1)(2)(3), add3(1, 2)(3), add3(1)(2, 3), add3(1, 2, 3), slowSquare(4), slowSquare.calls(), greet('Kite', '!'), d(1), d(1), d(2),
  [1, 2, 3, 4].map(sq).filter(function (x) { return x % 2; }).reduce(function (a, b) { return a + b; }), ['a', 'b'].map(function (c, i) { return c + i; }).join(), typeof compose()].join('|');
""",
            """11|12|25|6|6|6|6|16|2|Hello, Kite!|ran:1|skip|ran:2|10|a0,b1|function""",
        ),
        Program(
            "linked_list",
            """function Node(value, next) { this.value = value; this.next = next || null; }
function LinkedList() { this.head = null; this.size = 0; }
LinkedList.prototype.push = function (v) {
  var node = new Node(v);
  if (!this.head) this.head = node;
  else { var cur = this.head; while (cur.next) cur = cur.next; cur.next = node; }
  this.size++;
  return this;
};
LinkedList.prototype.toArray = function () { var out = []; for (var c = this.head; c; c = c.next) out.push(c.value); return out; };
LinkedList.prototype.reverse = function () { var prev = null, cur = this.head; while (cur) { var next = cur.next; cur.next = prev; prev = cur; cur = next; } this.head = prev; return this; };
LinkedList.prototype.remove = function (v) { if (!this.head) return false; if (this.head.value === v) { this.head = this.head.next; this.size--; return true; } for (var c = this.head; c.next; c = c.next) if (c.next.value === v) { c.next = c.next.next; this.size--; return true; } return false; };
LinkedList.prototype.find = function (pred) { for (var c = this.head; c; c = c.next) if (pred(c.value)) return c.value; return undefined; };
LinkedList.prototype.map = function (f) { var l = new LinkedList(); for (var c = this.head; c; c = c.next) l.push(f(c.value)); return l; };
var l = new LinkedList().push(1).push(2).push(3).push(4);
var out = [l.toArray().join(), l.size, l.reverse().toArray().join(), l.remove(3), l.remove(99), l.toArray().join(), l.size, l.find(function (v) { return v % 2 === 0; }), l.map(function (v) { return v * 10; }).toArray().join()];
var middle = (function (list) { var slow = list.head, fast = list.head; while (fast && fast.next) { slow = slow.next; fast = fast.next.next; } return slow.value; })(new LinkedList().push('a').push('b').push('c').push('d').push('e'));
out.push(middle);
out.join('|');
""",
            """1,2,3,4|4|4,3,2,1|true|false|4,2,1|3|4|40,20,10|c""",
        ),
        Program(
            "binary_tree",
            """function TreeNode(v) { this.v = v; this.left = null; this.right = null; }
function BST() { this.root = null; this.count = 0; }
BST.prototype.insert = function (v) {
  var node = new TreeNode(v);
  this.count++;
  if (!this.root) { this.root = node; return this; }
  var cur = this.root;
  while (true) {
    if (v < cur.v) { if (!cur.left) { cur.left = node; break; } cur = cur.left; }
    else { if (!cur.right) { cur.right = node; break; } cur = cur.right; }
  }
  return this;
};
BST.prototype.inorder = function (node, out) {
  if (arguments.length === 0) { node = this.root; out = []; }
  if (node) { this.inorder(node.left, out); out.push(node.v); this.inorder(node.right, out); }
  return out;
};
BST.prototype.preorder = function (node = this.root, out = []) { if (node) { out.push(node.v); this.preorder(node.left, out); this.preorder(node.right, out); } return out; };
BST.prototype.height = function (node = this.root) { return node ? 1 + Math.max(this.height(node.left), this.height(node.right)) : 0; };
BST.prototype.contains = function (v) { var c = this.root; while (c) { if (v === c.v) return true; c = v < c.v ? c.left : c.right; } return false; };
BST.prototype.min = function () { var c = this.root; while (c.left) c = c.left; return c.v; };
BST.prototype.max = function () { var c = this.root; while (c.right) c = c.right; return c.v; };
BST.prototype.levels = function () {
  var out = [], q = this.root ? [this.root] : [];
  while (q.length) {
    var next = [], vals = [];
    q.forEach(function (n) { vals.push(n.v); if (n.left) next.push(n.left); if (n.right) next.push(n.right); });
    out.push(vals.join(','));
    q = next;
  }
  return out.join(' / ');
};
var t = new BST();
[50, 30, 70, 20, 40, 60, 80, 35, 45, 65].forEach(function (v) { t.insert(v); });
[t.inorder().join(), t.preorder().join(), t.height(), t.contains(45), t.contains(55), t.min(), t.max(), t.count, t.levels()].join('|');
""",
            """20,30,35,40,45,50,60,65,70,80|50,30,20,40,35,45,70,60,65,80|4|true|false|20|80|10|50 / 30,70 / 20,40,60,80 / 35,45,65""",
        ),
        Program(
            "stack_queue",
            """function Stack() { this.items = []; }
Stack.prototype.push = function (x) { this.items.push(x); return this; };
Stack.prototype.pop = function () { return this.items.pop(); };
Stack.prototype.peek = function () { return this.items[this.items.length - 1]; };
Stack.prototype.isEmpty = function () { return this.items.length === 0; };
function Queue() { this.items = []; }
Queue.prototype.enqueue = function (x) { this.items.push(x); return this; };
Queue.prototype.dequeue = function () { return this.items.shift(); };
function balanced(s) {
  var st = new Stack(), pairs = { ')': '(', ']': '[', '}': '{' };
  for (var i = 0; i < s.length; i++) {
    var c = s[i];
    if ('([{'.indexOf(c) >= 0) st.push(c);
    else if (c in pairs) { if (st.pop() !== pairs[c]) return false; }
  }
  return st.isEmpty();
}
function rpn(expr) {
  var st = new Stack();
  expr.split(' ').forEach(function (tok) {
    if (!isNaN(tok)) st.push(Number(tok));
    else { var b = st.pop(), a = st.pop(); st.push(tok === '+' ? a + b : tok === '-' ? a - b : tok === '*' ? a * b : a / b); }
  });
  return st.pop();
}
var q = new Queue().enqueue('a').enqueue('b').enqueue('c');
var order = [q.dequeue(), q.dequeue(), q.enqueue('d').dequeue(), q.dequeue(), q.dequeue()].join();
[balanced('([]{})'), balanced('([)]'), balanced('(('), balanced(''), rpn('3 4 + 2 *'), rpn('5 1 2 + 4 * + 3 -'), order, new Stack().push(1).push(2).peek()].join('|');
""",
            """true|false|false|true|14|14|a,b,c,d,|2""",
        ),
        Program(
            "prime_sieve",
            """function sieve(n) {
  var flags = new Array(n + 1).fill(true), primes = [];
  flags[0] = flags[1] = false;
  for (var i = 2; i <= n; i++) {
    if (!flags[i]) continue;
    primes.push(i);
    for (var j = i * i; j <= n; j += i) flags[j] = false;
  }
  return primes;
}
function fizzbuzz(n) {
  var out = [];
  for (var i = 1; i <= n; i++) out.push(i % 15 === 0 ? 'FizzBuzz' : i % 3 === 0 ? 'Fizz' : i % 5 === 0 ? 'Buzz' : String(i));
  return out;
}
function collatz(n) { var steps = 0; while (n !== 1) { n = n % 2 ? 3 * n + 1 : n / 2; steps++; } return steps; }
function isPerfect(n) { var s = 0; for (var i = 1; i < n; i++) if (n % i === 0) s += i; return s === n; }
function digitsReversed(n) { return Number(String(n).split('').reverse().join('')); }
var perfect = [];
for (var i = 2; i < 10000; i++) if (isPerfect(i)) perfect.push(i);
[sieve(100).join(), sieve(100).length, fizzbuzz(15).join(), collatz(27), collatz(97), perfect.join(), digitsReversed(12345), sieve(2).join(), sieve(1).length].join('|');
""",
            """2,3,5,7,11,13,17,19,23,29,31,37,41,43,47,53,59,61,67,71,73,79,83,89,97|25|1,2,Fizz,4,Buzz,Fizz,7,8,Fizz,Buzz,11,Fizz,13,14,FizzBuzz|111|118|6,28,496,8128|54321|2|0""",
        ),
        Program(
            "deep_equal",
            """function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (typeof a === 'number' && isNaN(a) && isNaN(b)) return true;
  if (a === null || b === null || typeof a !== 'object') return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  var ka = Object.keys(a), kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return ka.every(function (k) { return kb.indexOf(k) >= 0 && deepEqual(a[k], b[k]); });
}
var cases = [
  [1, 1], [1, '1'], [NaN, NaN], [null, undefined], [null, null], [[1, 2], [1, 2]], [[1, 2], [2, 1]], [{ a: 1, b: [1, { c: 2 }] }, { b: [1, { c: 2 }], a: 1 }],
  [{ a: 1 }, { a: 1, b: undefined }], [[], {}], [{ a: [] }, { a: {} }], ['x', 'x'], [function () {}, function () {}], [{ a: { b: { c: 1 } } }, { a: { b: { c: 2 } } }]
];
cases.map(function (c) { return deepEqual(c[0], c[1]) ? 1 : 0; }).join('');
""",
            """10101101000100""",
        ),
        Program(
            "coercion_matrix",
            """var values = [0, 1, -1, '', '0', '1', 'a', ' ', true, false, null, undefined, NaN, [], [0], [1], [1, 2], {}, Infinity];
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
""",
            """0:1001100101000110000
1:0100010010000001000
-1:0010000000000000000
"":1001000001000100000
"0":1000100001000010000
"1":0100010010000001000
"a":0000001000000000000
" ":1000000101000000000
true:0100010010000001000
false:1001100101000110000
null:0000000000110000000
undefined:0000000000110000000
NaN:0000000000000000000
[]:1001000001000100000
[0]:1000100001000010000
[1]:0100010010000001000
[1,2]:0000000000000000100
{}:0000000000000000010
Infinity:0000000000000000001
1,2,0,"1","01","11","a1"," 1",2,1,1,NaN,NaN,"1","01","11","1,21","[object Object]1",Infinity
-1,0,-2,-1,-1,0,NaN,-1,0,-1,-1,NaN,NaN,-1,-1,0,NaN,NaN,Infinity
fttftttttfffftttttt
number,number,number,string,string,string,string,string,boolean,boolean,object,undefined,number,object,object,object,object,object,number
0,1,-1,0,0,1,NaN,0,1,0,0,NaN,NaN,0,0,1,NaN,NaN,Infinity
0|1|-1||0|1|a| |true|false|null|undefined|NaN||0|1|1,2|[object Object]|Infinity
true
false
false
true
true
[object Object]
[object Object]

[object Object]
11
12
2
true
false
true""",
        ),
        Program(
            "array_holes_and_length",
            """var a = [1, , 3];
var out = [a.length, 1 in a, a[1], a.hasOwnProperty(1), a.indexOf(undefined), a.includes(undefined), a.join('-'), String(a)];
var visited = [];
a.forEach(function (v, i) { visited.push(i); });
out.push(visited.join(), a.map(function (x) { return x * 2; }).length, a.filter(function () { return true; }).length, Object.keys(a).join());
var b = [1, 2, 3, 4, 5];
b.length = 2;
out.push(b.join(), b[3]);
b.length = 4;
out.push(b.length, b.join('-'), 3 in b);
b[10] = 'far';
out.push(b.length, Object.keys(b).join());
delete b[0];
out.push(b.length, 0 in b, b.join('|'));
var c = new Array(3);
out.push(c.length, c.join('x'), c.fill(0).join(), Array(3).fill().map(function (_, i) { return i; }).join(), [, ,].length, [1, 2, ,].length);
var d = [];
d[2] = 'c';
d.unshift('a');
out.push(d.length, d.join(), d.shift(), d.length, d.pop(), d.length);
out.push([NaN].indexOf(NaN), [NaN].includes(NaN), [1, 2, 3].indexOf('2'), [1, 2, 3].lastIndexOf(3, -2), [0].includes(-0), [-0].indexOf(0));
var sparse = [];
sparse[4294967294] = 'max';
out.push(sparse.length);
try { sparse.length = 4294967296; } catch (e) { out.push(e.name); }
out.join('|');
""",
            """3|false||false|-1|true|1--3|1,,3|0,2|3|2|0,2|1,2||4|1-2--|false|11|0,1,10|11|false||2|||||||||far|3|xx|0,0,0|0,1,2|2|3|4|a,,,c|a|3|c|2|-1|true|-1|-1|true|0|4294967295|RangeError""",
        ),
        Program(
            "json_roundtrip",
            """var data = {
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
""",
            """{"name":"Kite","version":1.5,"tags":["js","kmp"],"nested":{"deep":{"deeper":[1,{"x":null}]}},"empty":{},"emptyArr":[],"flag":true,"nothing":null,"unicode":"café 😀","ctrl":"a\tb\nc\"d\\e"}
true
{
  "a": [
    1,
    {
      "b": 2
    }
  ],
  "c": "x"
}
{
	"a": [
		1
	]
}
{"name":"Kite","tags":["js","kmp"],"version":1.5}
{"name":"Kite","version":3,"tags":["js","kmp"],"nested":{"deep":{"deeper":[2,{"x":null}]}},"empty":{},"emptyArr":[],"flag":true,"nothing":null,"unicode":"café 😀","ctrl":"a\tb\nc\"d\\e"}
{"n":10,"o":{"n":20},"a":[{"n":30}]}
{"keep":1}
{"when":"json:when","list":[1]}
[null,null,0,1e+21,2,"s",false,null,null]
{"1":"one","2":"two","b":1,"a":2}
SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,SyntaxError,TypeError
object
7
name,version,tags,nested,empty,emptyArr,flag,nothing,unicode,ctrl""",
        ),
        Program(
            "getters_setters_defineproperty",
            """var temp = { _c: 25, get f() { return this._c * 9 / 5 + 32; }, set f(v) { this._c = (v - 32) * 5 / 9; } };
temp.f = 212;
var o = {};
Object.defineProperty(o, 'ro', { value: 1, writable: false, enumerable: true, configurable: false });
Object.defineProperty(o, 'hidden', { value: 2, enumerable: false });
Object.defineProperty(o, 'acc', { get: function () { return 'got'; }, enumerable: true, configurable: true });
Object.defineProperties(o, { p: { value: 'p', enumerable: true }, q: { value: 'q' } });
o.ro = 99;
delete o.ro;
var d = Object.getOwnPropertyDescriptor(o, 'ro');
var out = [temp._c, temp.f, Object.keys(temp).join(), o.ro, o.hidden, o.acc, Object.keys(o).join(), Object.getOwnPropertyNames(o).join(), JSON.stringify(d), JSON.stringify(Object.getOwnPropertyDescriptor(o, 'acc').get === undefined)];
var errors = [];
(function () {
  'use strict';
  try { o.ro = 5; } catch (e) { errors.push(e.name); }
  try { delete o.ro; } catch (e) { errors.push(e.name); }
  try { o.acc = 1; } catch (e) { errors.push(e.name); }
  try { Object.defineProperty(o, 'ro', { value: 3 }); } catch (e) { errors.push(e.name); }
  var frozen = Object.freeze({ a: 1 });
  try { frozen.a = 2; } catch (e) { errors.push(e.name); }
  try { frozen.b = 2; } catch (e) { errors.push(e.name); }
  var sealed = Object.seal({ a: 1 });
  sealed.a = 2;
  try { sealed.b = 1; } catch (e) { errors.push(e.name); }
  errors.push(sealed.a);
})();
out.push(errors.join());
var counter = { count: 0 };
Object.defineProperty(counter, 'next', { get: function () { return ++this.count; } });
out.push(counter.next, counter.next, counter.count, JSON.stringify(counter));
out.join('|');
""",
            """100|212|_c,f|1|2|got|ro,acc,p|ro,hidden,acc,p,q|{"value":1,"writable":false,"enumerable":true,"configurable":false}|false|TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,TypeError,2|1|2|2|{"count":2}""",
        ),
        Program(
            "error_messages",
            """var attempts = [
  function () { return null.prop; },
  function () { return undefined.prop; },
  function () { var o = {}; return o.missing.deeper; },
  function () { var o = {}; return o.notAFunction(); },
  function () { return notDefinedAnywhere; },
  function () { notDefinedAnywhere = 1; },
  function () { 'use strict'; notDefinedStrict = 1; },
  function () { return new Array(-1); },
  function () { return [].length = -1; },
  function () { return (1).toFixed(200); },
  function () { return 'abc'.repeat(-1); },
  function () { return new (function () {})().x.y; },
  function () { return JSON.parse('{bad'); },
  function () { const c = 1; c = 2; },
  function () { return Object.defineProperty(1, 'x', {}); },
  function () { var o = Object.freeze({}); 'use strict'; o.x = 1; return o.x; },
  function () { 'use strict'; var o = Object.freeze({}); o.x = 1; },
  function () { return decodeURIComponent('%'); },
  function () { throw new TypeError('custom type'); },
  function () { return [1, 2].reduce(function () {}, undefined) + [].reduce(function () {}); },
  function () { return new 5.5; },
  function () { return 1 in 1; },
  function () { return {} instanceof 1; },
  function () { return Object.create(5); },
  function () { return 'x'.charAt.call(null); },
  function () { return Array.prototype.join.call(undefined); }
];
attempts.map(function (f, i) {
  try { var r = f(); return i + ':ok:' + r; }
  catch (e) { return i + ':' + (e && e.name) + ':' + (e && e.message); }
}).join('\n');
""",
            """0:TypeError:Cannot read property "prop" from null
1:TypeError:Cannot read property "prop" from undefined
2:TypeError:Cannot read property "deeper" from undefined
3:TypeError:Cannot find function notAFunction.
4:ReferenceError:"notDefinedAnywhere" is not defined.
5:ok:undefined
6:ReferenceError:Assignment to undefined "notDefinedStrict" in strict mode
7:RangeError:Inappropriate array length.
8:RangeError:Inappropriate array length.
9:RangeError:Precision 200 out of range.
10:RangeError:Invalid count value
11:TypeError:Cannot read property "y" from undefined
12:SyntaxError:Unexpected token in object literal
13:ok:undefined
14:TypeError:Expected argument of type object, but instead had type number
15:ok:undefined
16:TypeError:Cannot add properties to this object because extensible is false.
17:URIError:Malformed URI sequence.
18:TypeError:custom type
19:TypeError:Reduce of empty array with no initial value
20:TypeError:5.5 is not a function, it is number.
21:TypeError:Can't use 'in' on a non-object.
22:TypeError:Can't use 'instanceof' on a non-object.
23:TypeError:Expected argument of type object, but instead had type number
24:TypeError:String.prototype.charAt method called on null or undefined
25:TypeError:Cannot convert undefined to an object.""",
        ),
        Program(
            "wrapper_objects",
            """var n = new Number(5), s = new String('str'), b = new Boolean(false);
var prim = 5;
prim.prop = 'lost';
var out = [typeof n, typeof s, typeof b, n + 1, s + '!', b ? 'truthy' : 'falsy', n == 5, n === 5, s == 'str', s === 'str', b == false, b === false, !b, !!b,
  n.valueOf() === 5, s.valueOf() === 'str', b.valueOf() === false, n instanceof Number, (5) instanceof Number, Object(5) instanceof Number, typeof Object('x'), Object(true).valueOf(),
  prim.prop, s.length, s[0], s.charAt(1), Object.keys(s).join(), JSON.stringify([n, s, b]), n.toFixed(1), new Number(1) + new Number(2), new String('a') + new String('b'), new Boolean(false) + 1,
  Number(new Number(3)) === 3, String(new String('x')) === 'x', new Number(NaN) == NaN, [new Number(1)].indexOf(1), [1].indexOf(new Number(1)), new String('ab') == new String('ab'), Object.prototype.toString.call(n), Object.prototype.toString.call(s), Object.prototype.toString.call(b),
  (function () { 'use strict'; return typeof this; }).call(5), (function () { return typeof this; }).call(5), (function () { return this instanceof Number; }).call(5)];
out.join('|');
""",
            """object|object|object|6|str!|truthy|true|false|true|false|true|false|false|true|true|true|true|true|false|true|object|true||3|s|t|0,1,2|[5,"str",false]|5.0|3|ab|1|true|true|false|-1|-1|false|[object Number]|[object String]|[object Boolean]|object|object|true""",
        ),
        Program(
            "optional_chaining_nullish",
            """var data = { user: { name: 'k', tags: ['a'], greet: function () { return 'hi ' + this.name; }, zero: 0, empty: '', f: false }, list: null };
var out = [
  data.user?.name, data.missing?.name, data.list?.[0], data.user?.tags?.[0], data.user?.tags?.[5], data.user.greet?.(), data.user.nope?.(), data?.user?.['name'],
  data.user.zero ?? 'default', data.user.empty ?? 'default', data.user.f ?? 'default', data.user.undef ?? 'default', data.list ?? 'default', null ?? undefined ?? 'last',
  data.user.zero || 'fallback', data.user.empty || 'fallback', (data.user.zero ?? 5) + 1, data.missing?.deep.deeper.deepest, typeof data.missing?.(),
  (null)?.x, (undefined)?.[1], data.user?.tags.length, data.user.greet?.call({ name: 'other' })
];
var count = 0;
function side() { count++; return { v: 1 }; }
var r = null?.[side()];
out.push(count, r, side()?.v, count);
out.push(typeof data.user?.greet, delete data.user?.zero, 'zero' in data.user);
out.join('|');
""",
            """k|||a||hi k||k|0||false|default|default|last|fallback|fallback|1||undefined|||1|hi other|0||1|1|function|true|false""",
        ),
    )
}
