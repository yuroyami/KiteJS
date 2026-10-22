function a() {
  var x = 1;
  function b() {
    var y = 2;
    function c() {
      var z = 3;
      return x + y + z;
    }
    return c();
  }
  return b();
}
var d = function () {
  return function () {
    return function () {
      return 1;
    };
  };
};
