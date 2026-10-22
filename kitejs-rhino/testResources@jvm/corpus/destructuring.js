var [a, b] = c;
var [d, , e] = f;
var [g, [h, i]] = j;
var {k} = l;
var {m: n} = o;
var {p: {q}} = r;
var [s] = [1];
var {t} = {
  t: 1
};
function fn([a, b]) {
  return a;
}
function fn2({a, b}) {
  return a;
}
