var a = [...b];
var c = [1, ...d, 2];
var e = [...f, ...g];
var h = {
  ...i
};
var j = {
  a: 1,
  ...k
};
function rest(...args) {
  return args;
}
function mixed(a, b, ...rest) {
  return rest;
}
