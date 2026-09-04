function fact(n) { return n <= 1 ? 1 : n * fact(n - 1); }
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
