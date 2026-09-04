function noArgs() {
}
function oneArg(a) {
  return a;
}
function manyArgs(a, b, c) {
  return a + b + c;
}
function withDefaults(a, b) {
  return a;
}
function nested() {
  function inner() {
    return 1;
  }
  return inner();
}
var expr = function () {
  return 1;
};
var named = function myName() {
  return 1;
};
(function () {
  return 1;
})();
function withRest(a) {
  return a;
}
