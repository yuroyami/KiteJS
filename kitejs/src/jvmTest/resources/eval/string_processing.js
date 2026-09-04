function reverseWords(s) { return s.split(' ').reverse().join(' '); }
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
