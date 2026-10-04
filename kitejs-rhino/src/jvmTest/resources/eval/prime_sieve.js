function sieve(n) {
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
