function* gen() {
  yield 1;
}
function* gen2() {
  yield 1;
  yield 2;
  return 3;
}
function* gen3() {
  var x = yield;
  return x;
}
function* gen4() {
  yield* other();
}
var obj = {
  *method() {
    yield 1;
  }
};
