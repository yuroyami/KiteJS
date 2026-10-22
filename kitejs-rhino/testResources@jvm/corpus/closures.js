function outer() {
  var x = 1;
  function inner() {
    return x;
  }
  return inner;
}
var counter = function () {
  var n = 0;
  return function () {
    return n++;
  };
};
function shadow(x) {
  return function (x) {
    return x;
  };
}
