// A tokenizer built from one sticky pattern per token kind.
var SPEC = [
  ['ws', /\s+/y],
  ['number', /\d+(?:\.\d+)?/y],
  ['string', /"(?:[^"\\]|\\.)*"/y],
  ['ident', /[A-Za-z_$][A-Za-z0-9_$]*/y],
  ['op', /[+\-*\/=<>!]=?|&&|\|\||[(){};,]/y]
];
function tokenize(src) {
  var out = [], pos = 0;
  outer: while (pos < src.length) {
    for (var i = 0; i < SPEC.length; i++) {
      var name = SPEC[i][0], re = SPEC[i][1];
      re.lastIndex = pos;
      var m = re.exec(src);
      if (m && m.index === pos) {
        if (name !== 'ws') out.push(name + ':' + m[0]);
        pos = re.lastIndex;
        continue outer;
      }
    }
    out.push('bad:' + src.charAt(pos));
    pos++;
  }
  return out;
}
var tokens = tokenize('var x1 = 3.5 + count(a, "he\\"llo") && b <= 10;');

// The same source split a second way, to cross-check the count.
var byGlobal = 'var x1 = 3.5 + count(a, "he\\"llo") && b <= 10;'.match(/[A-Za-z_$][A-Za-z0-9_$]*/g);

// A tiny expression evaluator over the token stream.
function sumNumbers(src) {
  var total = 0, re = /\d+(?:\.\d+)?/g, m;
  while ((m = re.exec(src)) !== null) total += Number(m[0]);
  return total;
}

// Named groups pulled out of a key=value list.
var pairs = [];
var kv = /(?<key>\w+)=(?<value>[^;]+)/g, hit;
while ((hit = kv.exec('a=1;bb=two;ccc=3 3')) !== null) pairs.push(hit.groups.key + '->' + hit.groups.value);

[tokens.join(' '), tokens.length, byGlobal.join(), sumNumbers('a1 b22 c3.5'), pairs.join(' ')].join('|');
